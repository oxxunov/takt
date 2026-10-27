import math
from typing import List, Optional

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch import Tensor


@torch.jit.script
def _scan(u: Tensor, delta: Tensor, A: Tensor, B: Tensor, C: Tensor) -> Tensor:
    """
    Последовательный проход состояния — ровно selective_scan_ref (mamba_ssm 1.2.0), но всё,
    что зависит от шага, считается внутри цикла: память O(batch·d·n) вместо O(batch·d·n·L).
    u, delta: (b, d, l); A: (d, n); B, C: (b, n, l). Возвращает y: (b, d, l) — без D и z.
    В ONNX цикл становится оператором Loop (длина последовательности не разворачивается).
    """
    b = u.size(0)
    d = u.size(1)
    n = A.size(1)
    L = u.size(2)
    x = torch.zeros(b, d, n, dtype=u.dtype, device=u.device)
    ys: List[Tensor] = []
    for i in range(L):
        dl = delta[:, :, i]                                   # (b, d)
        dA = torch.exp(dl.unsqueeze(-1) * A.unsqueeze(0))     # (b, d, n)
        dBu = (dl * u[:, :, i]).unsqueeze(-1) * B[:, :, i].unsqueeze(1)  # (b, d, n)
        x = dA * x + dBu
        ys.append((x * C[:, :, i].unsqueeze(1)).sum(-1))      # (b, d)
    return torch.stack(ys, dim=2)


def selective_scan(u, delta, A, B, C, D, z, delta_bias):
    delta = F.softplus(delta + delta_bias[..., None])
    y = _scan(u, delta, A, B, C)
    out = y + u * D[:, None]
    return out * F.silu(z)


class Mamba(nn.Module):
    """Mamba v1 (mamba_ssm 1.2.0): те же слои и имена параметров, расчёт — эталонная ветка без CUDA."""

    def __init__(self, d_model, d_state=16, d_conv=4, expand=2, dt_rank="auto", dt_min=0.001, dt_max=0.1,
                 dt_init="random", dt_scale=1.0, dt_init_floor=1e-4, conv_bias=True, bias=False,
                 use_fast_path=True, layer_idx=None, device=None, dtype=None):
        super().__init__()
        self.d_model = d_model
        self.d_state = d_state
        self.d_conv = d_conv
        self.expand = expand
        self.d_inner = int(expand * d_model)
        self.dt_rank = math.ceil(d_model / 16) if dt_rank == "auto" else dt_rank
        self.layer_idx = layer_idx
        self.in_proj = nn.Linear(d_model, self.d_inner * 2, bias=bias)
        self.conv1d = nn.Conv1d(self.d_inner, self.d_inner, bias=conv_bias, kernel_size=d_conv,
                                groups=self.d_inner, padding=d_conv - 1)
        self.act = nn.SiLU()
        self.x_proj = nn.Linear(self.d_inner, self.dt_rank + d_state * 2, bias=False)
        self.dt_proj = nn.Linear(self.dt_rank, self.d_inner, bias=True)
        self.A_log = nn.Parameter(torch.zeros(self.d_inner, d_state))
        self.D = nn.Parameter(torch.ones(self.d_inner))
        self.out_proj = nn.Linear(self.d_inner, d_model, bias=bias)

    def forward(self, hidden_states, inference_params=None):
        bsz, seqlen, _ = hidden_states.shape
        xz = self.in_proj(hidden_states).transpose(1, 2)          # (b, 2d, l)
        A = -torch.exp(self.A_log.float())
        x, z = xz.chunk(2, dim=1)
        x = self.act(self.conv1d(x)[..., :seqlen])
        x_dbl = self.x_proj(x.transpose(1, 2))                    # (b, l, r + 2n)
        dt, B, C = torch.split(x_dbl, [self.dt_rank, self.d_state, self.d_state], dim=-1)
        dt = (dt @ self.dt_proj.weight.t()).transpose(1, 2)      # (b, d, l) — без смещения, как в оригинале
        B = B.transpose(1, 2)                                     # (b, n, l)
        C = C.transpose(1, 2)
        y = selective_scan(x, dt, A, B, C, self.D.float(), z, self.dt_proj.bias.float())
        return self.out_proj(y.transpose(1, 2))


class Block(nn.Module):
    """Block из mamba_ssm 1.2.0: Add -> Norm -> Mixer, возвращает (выход, residual)."""

    def __init__(self, dim, mixer_cls, norm_cls=nn.LayerNorm, fused_add_norm=False, residual_in_fp32=False):
        super().__init__()
        self.residual_in_fp32 = residual_in_fp32
        self.fused_add_norm = fused_add_norm
        self.mixer = mixer_cls(dim)
        self.norm = norm_cls(dim)

    def forward(self, hidden_states: Tensor, residual: Optional[Tensor] = None, inference_params=None):
        residual = (hidden_states + residual) if residual is not None else hidden_states
        hidden_states = self.norm(residual.to(dtype=self.norm.weight.dtype))
        if self.residual_in_fp32:
            residual = residual.to(torch.float32)
        hidden_states = self.mixer(hidden_states, inference_params=inference_params)
        return hidden_states, residual
