"""
Экспорт Roformer-модели разделения (по умолчанию becruily Mel-Roformer «Deux», CC BY-NC 4.0) в ONNX.
Модель в телефоне получает смесь и отдаёт ФОН без голоса (музыка + звуки):
    вход  "mix" — float32 [1, 2, L] (стерео, частота модели, ровно L сэмплов)
    выход "bg"  — float32 [1, 2, L]
STFT/iSTFT встроены свёртками, усреднение масок перекрывающихся полос — ScatterElements(add).
Если у модели есть стем «instrumental/other» — берётся он, иначе фон = смесь − голос.

Проверки (иначе скрипт падает и модель в APK не попадает):
  1) обёртка совпадает с оригинальной моделью (torch.stft/istft);
  2) ONNX Runtime совпадает с PyTorch;
  3) на смеси «речь + музыка» голос реально убирается.

Запуск: python export_roformer_bg.py <MSST_DIR> <config.yaml> <ckpt> <speech.wav> <out.onnx> <out.json>
"""
import json
import math
import os
import sys
import time
import wave

import numpy as np
import torch
import torch.nn.functional as F
import yaml
from einops import rearrange, pack, unpack

MSST, CONFIG, CKPT, SPEECH, OUT, META = sys.argv[1:7]
sys.path.insert(0, MSST)
from models.bs_roformer.attend import Attend  # noqa: E402

torch.manual_seed(0)
torch.set_grad_enabled(False)

with open(CONFIG) as f:
    cfg = yaml.unsafe_load(f)
mcfg = dict(cfg["model"])
print("model cfg:", {k: v for k, v in mcfg.items() if not isinstance(v, (list, tuple))})
instruments = list(cfg.get("training", {}).get("instruments", []))
target = cfg.get("training", {}).get("target_instrument")
print("instruments:", instruments, "target:", target)

if "num_bands" in mcfg:
    from models.bs_roformer.mel_band_roformer import MelBandRoformer as Net  # noqa: E402
else:
    from models.bs_roformer.bs_roformer import BSRoformer as Net  # noqa: E402
model = Net(**mcfg).eval()
if CKPT.endswith(".safetensors"):
    from safetensors.torch import load_file
    state = load_file(CKPT)
else:
    state = torch.load(CKPT, map_location="cpu", weights_only=False)
if isinstance(state, dict) and "state_dict" in state:
    state = state["state_dict"]
missing, unexpected = model.load_state_dict(state, strict=False)
missing = [k for k in missing if "rotary" not in k]
unexpected = [k for k in unexpected if "rotary" not in k]
assert not missing and not unexpected, f"missing={missing[:5]} unexpected={unexpected[:5]}"
for m in model.modules():
    if isinstance(m, Attend):
        m.flash = False
assert model.stereo, "нужна стерео-модель"

SR = int(cfg.get("audio", {}).get("sample_rate", mcfg.get("sample_rate", 44100)))
L = int(cfg["audio"]["chunk_size"])
N = mcfg.get("stft_n_fft", 2048)
HOP = mcfg.get("stft_hop_length", 512)
WIN = mcfg.get("stft_win_length", 2048)
assert N == WIN
BINS = N // 2 + 1
PAD = N // 2
T = L // HOP + 1
NST = model.num_stems
names = instruments[:NST] if NST > 1 else [target or (instruments[0] if instruments else "vocals")]
bg_idx = next((i for i, n in enumerate(names) if n.lower() in ("instrumental", "other", "accompaniment", "inst")), None)
voc_idx = next((i for i, n in enumerate(names) if "vocal" in n.lower()), 0)
print(f"sr={SR} chunk={L} n_fft={N} hop={HOP}; стемы {names}: фон={'стем ' + names[bg_idx] if bg_idx is not None else 'смесь − ' + names[voc_idx]}")


def kernels():
    w = torch.hann_window(WIN, dtype=torch.float64)
    n = torch.arange(N, dtype=torch.float64)
    k = torch.arange(BINS, dtype=torch.float64)
    ang = 2 * math.pi * k[:, None] * n[None, :] / N
    fwd = torch.cat([torch.cos(ang) * w, -torch.sin(ang) * w], 0)[:, None, :]
    a = torch.full((BINS,), 2.0, dtype=torch.float64)
    a[0] = 1.0
    a[-1] = 1.0
    inv = (torch.cat([a[:, None] * torch.cos(ang), -a[:, None] * torch.sin(ang)], 0) * w / N)[:, None, :]
    env = torch.zeros((T - 1) * HOP + N, dtype=torch.float64)
    for t in range(T):
        env[t * HOP:t * HOP + N] += w ** 2
    return fwd.float(), inv.float(), env[PAD:PAD + L].float()


class Wrapped(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m
        fwd, inv, env = kernels()
        self.register_buffer("fwd", fwd)
        self.register_buffer("inv", inv)
        self.register_buffer("env", env)
        dc = torch.ones(BINS)
        if getattr(m, "zero_dc", True):
            dc[0] = 0.0
        self.register_buffer("dc", dc)
        self.mel = hasattr(m, "freq_indices")
        if self.mel:
            fi = m.freq_indices.long()
            self.register_buffer("fi", fi)
            denom = m.num_bands_per_freq.float().repeat_interleave(2).clamp(min=1e-8)  # (f s)
            self.register_buffer("denom", denom)

    def forward(self, mix):  # [1, 2, L]
        m = self.m
        x = F.pad(mix.reshape(2, 1, L), (PAD, PAD), mode="reflect")
        spec = F.conv1d(x, self.fwd, stride=HOP)               # [2, 2·BINS, T]
        re, im = spec[:, :BINS], spec[:, BINS:]
        stft = torch.stack([re, im], -1)                        # [s, f, t, c]
        stft = rearrange(stft, "s f t c -> 1 (f s) t c")
        h = stft.index_select(1, self.fi) if self.mel else stft
        h = rearrange(h, "b f t c -> b t (f c)")
        h = m.band_split(h)
        store = [None] * len(m.layers)
        for i, block in enumerate(m.layers):
            if len(block) == 3:
                lin, time_t, freq_t = block
                h, ps0 = pack([h], "b * d")
                h = lin(h)
                h, = unpack(h, ps0, "b * d")
            else:
                time_t, freq_t = block
            if getattr(m, "skip_connection", False):
                for j in range(i):
                    h = h + store[j]
            h = rearrange(h, "b t f d -> b f t d")
            h, ps = pack([h], "* t d")
            h = time_t(h)
            h, = unpack(h, ps, "* t d")
            h = rearrange(h, "b f t d -> b t f d")
            h, ps = pack([h], "* f d")
            h = freq_t(h)
            h, = unpack(h, ps, "* f d")
            if getattr(m, "skip_connection", False):
                store[i] = h
        if hasattr(m, "final_norm"):
            h = m.final_norm(h)
        idx = bg_idx if bg_idx is not None else voc_idx
        mask = m.mask_estimators[idx](h)                        # [1, t, (fsel c)]
        mask = rearrange(mask, "b t (f c) -> b f t c", c=2)
        if self.mel:
            # усреднение масок перекрывающихся полос: сумма по индексам / число полос на частоту
            fs = stft.shape[1]
            index = self.fi.view(1, -1, 1, 1).expand(1, self.fi.shape[0], T, 2)
            summed = torch.zeros(1, fs, T, 2, dtype=mask.dtype).scatter_add(1, index, mask)
            mask = summed / self.denom.view(1, -1, 1, 1)
        sr_, si_ = stft[..., 0], stft[..., 1]
        mr, mi = mask[..., 0], mask[..., 1]
        yr = rearrange(sr_ * mr - si_ * mi, "1 (f s) t -> s f t", s=2) * self.dc[None, :, None]
        yi = rearrange(sr_ * mi + si_ * mr, "1 (f s) t -> s f t", s=2) * self.dc[None, :, None]
        y = F.conv_transpose1d(torch.cat([yr, yi], 1), self.inv, stride=HOP)
        y = (y[:, 0, PAD:PAD + L] / self.env).reshape(1, 2, L)
        return y if bg_idx is not None else mix - y


def load_wav(path):
    with wave.open(path, "rb") as f:
        sr, ch, n = f.getframerate(), f.getnchannels(), f.getnframes()
        x = np.frombuffer(f.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
    x = x.reshape(-1, ch).mean(1)
    if sr != SR:
        from scipy.signal import resample_poly
        g = math.gcd(sr, SR)
        x = resample_poly(x, SR // g, sr // g).astype(np.float32)
    return x


def si_sdr(est, ref):
    est = est - est.mean()
    ref = ref - ref.mean()
    a = (est @ ref) / (ref @ ref + 1e-12)
    t = a * ref
    return 10 * np.log10((t @ t) / ((est - t) @ (est - t) + 1e-12))


speech = load_wav(SPEECH)
speech = np.pad(speech, (0, max(0, L - len(speech))))[:L]
tt = np.arange(L) / SR
music = sum(np.sin(2 * np.pi * f * tt) for f in (110.0, 220.0, 277.18, 329.63)) * 0.06
music *= (0.6 + 0.4 * np.sin(2 * np.pi * 0.5 * tt))           # «живая» громкость
music = music.astype(np.float32)
mix = np.stack([speech + music, 0.9 * speech + music]).astype(np.float32)
x = torch.from_numpy(mix)[None]

w = Wrapped(model).eval()
t0 = time.time()
orig = model(x)                                            # оригинал: torch.stft/istft
orig = orig if orig.ndim == 4 else orig[:, None]
ln = min(orig.shape[-1], L)  # без match_input_audio_length iSTFT может вернуть чуть короче
ref = orig[:, bg_idx, :, :ln] if bg_idx is not None else x[..., :ln] - orig[:, voc_idx, :, :ln]
print(f"PyTorch оригинал: {time.time() - t0:.1f} с на {L / SR:.1f} с звука")
mine = w(x)
# хвост последнего кадра оригинал не восстанавливает — сравниваем без краёв
c0, c1 = N, ln - N
d = 10 * torch.log10((ref[..., c0:c1] ** 2).sum() / ((mine[..., c0:c1] - ref[..., c0:c1]) ** 2).sum().clamp(min=1e-20)).item()
print(f"Обёртка vs оригинал: {d:.1f} дБ")
assert d > 40, "обёртка не совпала с оригиналом"

bg = mine[0].numpy().mean(0)
mono_mix = mix.mean(0)
before, after = si_sdr(mono_mix, music), si_sdr(bg, music)
print(f"Голос убирается: SI-SDR фона {before:.1f} дБ → {after:.1f} дБ")
assert after > before + 6, "модель не убирает голос — что-то собрано неправильно"

t0 = time.time()
torch.onnx.export(w, (x,), OUT, input_names=["mix"], output_names=["bg"], opset_version=17,
                  do_constant_folding=True, dynamo=False)
print(f"ONNX сохранён за {time.time() - t0:.0f} с, {os.path.getsize(OUT) / 1e6:.0f} МБ")

import onnxruntime as ort  # noqa: E402

s = ort.InferenceSession(OUT, providers=["CPUExecutionProvider"])
t0 = time.time()
out = s.run(None, {"mix": mix[None]})[0]
dt = time.time() - t0
m_np = mine.numpy()
d2 = 10 * np.log10((m_np ** 2).sum() / max(((out - m_np) ** 2).sum(), 1e-20))
print(f"ONNX Runtime vs PyTorch: {d2:.1f} дБ; {L / SR:.1f} с звука за {dt:.1f} с на CPU CI")
assert d2 > 40, "ONNX не совпал с PyTorch"
json.dump({"sample_rate": SR, "chunk": L, "stems": names, "bg": names[bg_idx] if bg_idx is not None else "mix-" + names[voc_idx],
           "si_sdr_before": round(float(before), 2), "si_sdr_after": round(float(after), 2)}, open(META, "w"))
print("OK")
