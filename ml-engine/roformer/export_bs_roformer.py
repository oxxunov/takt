"""
Экспорт BS-Roformer (viperx, вокал, SDR vocals 10.87 на Multisong) в ONNX для телефона.

Внутрь ONNX встраиваются STFT и обратное STFT (через свёртки с ядрами ДПФ), поэтому модель
принимает обычный звук и отдаёт обычный звук:
    вход  "mix"    — float32 [1, 2, 352800]  (стерео, 44.1 кГц, ровно 8 с)
    выход "vocals" — float32 [1, 2, 352800]
После экспорта модель сверяется с оригиналом PyTorch (torch.stft/istft) — при расхождении сборка падает.

Запуск (в CI): python export_bs_roformer.py <MSST_DIR> <config.yaml> <ckpt> <out.onnx>
"""
import math
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F
import yaml
from einops import rearrange, pack, unpack

MSST, CONFIG, CKPT, OUT = sys.argv[1:5]
sys.path.insert(0, MSST)
from models.bs_roformer.bs_roformer import BSRoformer  # noqa: E402
from models.bs_roformer.attend import Attend  # noqa: E402

torch.manual_seed(0)
torch.set_grad_enabled(False)

with open(CONFIG) as f:
    cfg = yaml.unsafe_load(f)
mcfg = dict(cfg["model"])
L = int(cfg["audio"]["chunk_size"])  # 352800
model = BSRoformer(**mcfg)
state = torch.load(CKPT, map_location="cpu", weights_only=False)
if isinstance(state, dict) and "state_dict" in state:
    state = state["state_dict"]
missing, unexpected = model.load_state_dict(state, strict=False)
# буферы rotary (freqs) могут отличаться по составу между версиями — всё остальное должно совпасть
missing = [k for k in missing if "rotary" not in k]
unexpected = [k for k in unexpected if "rotary" not in k]
assert not missing and not unexpected, f"missing={missing[:5]} unexpected={unexpected[:5]}"
model.eval()
for m in model.modules():
    if isinstance(m, Attend):
        m.flash = False  # обычное внимание через einsum — то же самое математически, экспортируется везде

N = mcfg.get("stft_n_fft", 2048)
HOP = mcfg.get("stft_hop_length", 441)
WIN = mcfg.get("stft_win_length", 2048)
assert N == WIN
BINS = N // 2 + 1
PAD = N // 2
T = L // HOP + 1  # кадров STFT с center=True


def dft_kernels():
    w = torch.hann_window(WIN, dtype=torch.float64)  # как в модели: periodic Hann
    n = torch.arange(N, dtype=torch.float64)
    k = torch.arange(BINS, dtype=torch.float64)
    ang = 2 * math.pi * k[:, None] * n[None, :] / N
    # прямое: X_k = Σ x[n] w[n] e^{-j·ang}
    fwd = torch.cat([torch.cos(ang) * w, -torch.sin(ang) * w], 0)[:, None, :]  # [2·BINS, 1, N]
    # обратное (irfft с окном синтеза): y[n] = w[n]/N · Σ a_k (Re·cos − Im·sin)
    a = torch.full((BINS,), 2.0, dtype=torch.float64)
    a[0] = 1.0
    a[-1] = 1.0
    inv = torch.cat([a[:, None] * torch.cos(ang), -a[:, None] * torch.sin(ang)], 0) * w / N
    inv = inv[:, None, :]  # [2·BINS, 1, N]
    # огибающая Σ w²(m − t·hop) для нормировки overlap-add (как в torch.istft)
    total = (T - 1) * HOP + N
    env = torch.zeros(total, dtype=torch.float64)
    for t in range(T):
        env[t * HOP:t * HOP + N] += w ** 2
    env = env[PAD:PAD + L]
    return fwd.float(), inv.float(), env.float()


class Wrapped(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m
        fwd, inv, env = dft_kernels()
        self.register_buffer("fwd", fwd)
        self.register_buffer("inv", inv)
        self.register_buffer("env", env)
        dc = torch.ones(BINS)
        if getattr(m, "zero_dc", True):
            dc[0] = 0.0  # как в модели: постоянная составляющая обнуляется
        self.register_buffer("dc", dc)

    def forward(self, mix):  # [1, 2, L]
        m = self.m
        x = mix.reshape(2, 1, L)
        xp = F.pad(x, (PAD, PAD), mode="reflect")
        spec = F.conv1d(xp, self.fwd, stride=HOP)  # [2, 2·BINS, T]
        re, im = spec[:, :BINS], spec[:, BINS:]
        stft = torch.stack([re, im], -1)  # [s, f, t, c]
        stft = rearrange(stft, "s f t c -> 1 (f s) t c")
        h = rearrange(stft, "b f t c -> b t (f c)")
        h = m.band_split(h)
        for block in m.layers:
            time_t, freq_t = block[-2], block[-1]
            h = rearrange(h, "b t f d -> b f t d")
            h, ps = pack([h], "* t d")
            h = time_t(h)
            h, = unpack(h, ps, "* t d")
            h = rearrange(h, "b f t d -> b t f d")
            h, ps = pack([h], "* f d")
            h = freq_t(h)
            h, = unpack(h, ps, "* f d")
        h = m.final_norm(h)
        mask = m.mask_estimators[0](h)  # [1, t, (f c)]
        mask = rearrange(mask, "b t (f c) -> b f t c", c=2)
        sr_, si_ = stft[..., 0], stft[..., 1]
        mr, mi = mask[..., 0], mask[..., 1]
        yr = sr_ * mr - si_ * mi
        yi = sr_ * mi + si_ * mr
        yr = rearrange(yr, "1 (f s) t -> s f t", s=2) * self.dc[None, :, None]
        yi = rearrange(yi, "1 (f s) t -> s f t", s=2) * self.dc[None, :, None]
        y = F.conv_transpose1d(torch.cat([yr, yi], 1), self.inv, stride=HOP)  # [2, 1, (T−1)·hop + N]
        y = y[:, 0, PAD:PAD + L] / self.env
        return y.reshape(1, 2, L)


w = Wrapped(model).eval()

# тестовый сигнал: «голос» (гармоники с вибрато) + «музыка» (аккорд) + шум
sr = 44100
tt = torch.arange(L, dtype=torch.float32) / sr
f0 = 180 + 25 * torch.sin(2 * math.pi * 5 * tt)
voice = sum(torch.sin(2 * math.pi * k * torch.cumsum(f0, 0) / sr) / k for k in range(1, 8)) * 0.15
music = sum(torch.sin(2 * math.pi * f * tt) for f in (220.0, 277.2, 329.6)) * 0.08
mix = torch.stack([voice + music, voice * 0.9 + music]) + 0.01 * torch.randn(2, L)
mix = mix[None]

ref = model(mix)[:, 0]  # оригинал: torch.stft/istft, [1, 2, L]
mine = w(mix)
err = (mine - ref).abs().max().item()
print(f"PyTorch: обёртка vs оригинал, max|Δ| = {err:.2e}")
assert err < 1e-3, "обёртка не совпала с оригиналом"

t0 = time.time()
torch.onnx.export(
    w, (mix,), OUT, input_names=["mix"], output_names=["vocals"],
    opset_version=17, do_constant_folding=True, dynamo=False,
)
print(f"ONNX сохранён за {time.time() - t0:.0f} с")

import onnxruntime as ort  # noqa: E402

s = ort.InferenceSession(OUT, providers=["CPUExecutionProvider"])
t0 = time.time()
out = s.run(None, {"mix": mix.numpy()})[0]
dt = time.time() - t0
d = np.abs(out - ref.numpy()).max()
snr = 10 * np.log10((ref.numpy() ** 2).sum() / max(((out - ref.numpy()) ** 2).sum(), 1e-20))
print(f"ONNX Runtime vs PyTorch: max|Δ| = {d:.2e}, совпадение {snr:.1f} дБ; 8 с звука за {dt:.1f} с на CPU CI")
assert snr > 50, "ONNX не совпал с оригиналом"
print("OK")
