import torch


class RMSNorm(torch.nn.Module):
    """Как mamba_ssm.ops.triton.layernorm.RMSNorm (эталонная rms_norm_ref)."""

    def __init__(self, hidden_size, eps=1e-5, device=None, dtype=None):
        super().__init__()
        self.eps = eps
        self.weight = torch.nn.Parameter(torch.ones(hidden_size, device=device, dtype=dtype))
        self.register_parameter("bias", None)

    def forward(self, x, residual=None, prenorm=False, residual_in_fp32=False):
        if residual is not None:
            x = x + residual
        rstd = torch.rsqrt(x.float().pow(2).mean(dim=-1, keepdim=True) + self.eps)
        out = (x.float() * rstd * self.weight.float()).to(x.dtype)
        return (out, x) if prenorm else out


def rms_norm_fn(x, weight, bias, residual=None, eps=1e-6, prenorm=False, residual_in_fp32=False):
    if residual is not None:
        x = x + residual
    rstd = torch.rsqrt(x.float().pow(2).mean(dim=-1, keepdim=True) + eps)
    out = x.float() * rstd * weight.float()
    if bias is not None:
        out = out + bias.float()
    return (out, x) if prenorm else out


def layer_norm_fn(x, weight, bias, residual=None, eps=1e-6, prenorm=False, residual_in_fp32=False):
    if residual is not None:
        x = x + residual
    out = torch.nn.functional.layer_norm(x.float(), (x.shape[-1],), weight, bias, eps)
    return (out, x) if prenorm else out
