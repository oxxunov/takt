"""
Экспорт NVIDIA RE-USE (NemotronLabs, OpenMDW-1.1) в ONNX для телефона.

Внутри ONNX: STFT (свёртки с ядрами ДПФ) → амплитуда/фаза → сжатие амплитуды → сеть SEMamba →
обратное сжатие → обратное STFT. На вход — моно-звук фиксированной длины [1, L] на частоте модели,
на выход — очищенный звук той же длины. Нормализацию громкости делает приложение (как в inference.py).

Mamba считается через прослойку ../mamba_shim (чистый PyTorch вместо CUDA). Проверки:
  1) ONNX Runtime совпадает с PyTorch;
  2) на зашумлённой речи модель реально улучшает звук (SI-SDR растёт).
Если что-то не сходится — скрипт падает, и модель в приложение не попадает.

Запуск: python export_reuse.py <папка_модели> <чистая_речь.wav> <out.onnx> <out_meta.json>
"""
import importlib
import inspect
import json
import math
import os
import sys
import time
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "mamba_shim"))  # раньше настоящего mamba_ssm

import torch  # noqa: E402
import torch.nn.functional as F  # noqa: E402

REPO, CLEAN_WAV, OUT, META = sys.argv[1:5]
sys.path.insert(0, REPO)
torch.manual_seed(0)
torch.set_grad_enabled(False)

# ---------------- конфиг и модель ----------------
cfg = json.load(open(os.path.join(REPO, "config.json")))
print("config.json:", json.dumps(cfg)[:1500])


def find(o, key):
    if isinstance(o, dict):
        if key in o:
            return o[key]
        for v in o.values():
            r = find(v, key)
            if r is not None:
                return r
    return None


SR = int(find(cfg, "sampling_rate") or 48000)
N_FFT = int(find(cfg, "n_fft"))
HOP = int(find(cfg, "hop_size") or find(cfg, "hop_length"))
WIN = int(find(cfg, "win_size") or find(cfg, "win_length") or N_FFT)
COMP = float(find(cfg, "compress_factor") or 1.0)
print(f"STFT: sr={SR} n_fft={N_FFT} hop={HOP} win={WIN} compress={COMP}")

gen_mod = None
for f in sorted(os.listdir(os.path.join(REPO, "models"))):
    if f.startswith("generator") and f.endswith(".py"):
        gen_mod = importlib.import_module("models." + f[:-3])
        break
assert gen_mod is not None, "нет models/generator*.py"
classes = [c for n, c in inspect.getmembers(gen_mod, inspect.isclass)
           if issubclass(c, torch.nn.Module) and c.__module__ == gen_mod.__name__ and "mamba" in n.lower()]
assert classes, "в generator нет класса модели"
Model = classes[0]
print("Модель:", Model.__name__)
model = Model(cfg).eval()

from safetensors.torch import load_file  # noqa: E402

sd = load_file(os.path.join(REPO, "model.safetensors"))
for pref in ("generator.", "model.", "module."):
    if all(k.startswith(pref) for k in sd):
        sd = {k[len(pref):]: v for k, v in sd.items()}
missing, unexpected = model.load_state_dict(sd, strict=False)
assert not missing and not unexpected, f"missing={missing[:8]} unexpected={unexpected[:8]}"
print("Параметров:", sum(p.numel() for p in model.parameters()))

# ---------------- STFT / iSTFT как в models/stfts.py, но свёртками ----------------
CHUNK_S = 4
L = CHUNK_S * SR
PAD = N_FFT // 2
BINS = N_FFT // 2 + 1
T = L // HOP + 1


def kernels():
    w = torch.hann_window(WIN, dtype=torch.float64)
    if WIN < N_FFT:  # torch.stft центрирует окно внутри n_fft
        lp = (N_FFT - WIN) // 2
        w = F.pad(w, (lp, N_FFT - WIN - lp))
    n = torch.arange(N_FFT, dtype=torch.float64)
    k = torch.arange(BINS, dtype=torch.float64)
    ang = 2 * math.pi * k[:, None] * n[None, :] / N_FFT
    fwd = torch.cat([torch.cos(ang) * w, -torch.sin(ang) * w], 0)[:, None, :]
    a = torch.full((BINS,), 2.0, dtype=torch.float64)
    a[0] = 1.0
    if N_FFT % 2 == 0:
        a[-1] = 1.0
    inv = (torch.cat([a[:, None] * torch.cos(ang), -a[:, None] * torch.sin(ang)], 0) * w / N_FFT)[:, None, :]
    total = (T - 1) * HOP + N_FFT
    env = torch.zeros(total, dtype=torch.float64)
    for t in range(T):
        env[t * HOP:t * HOP + N_FFT] += w ** 2
    env = env[PAD:PAD + L].clamp(min=1e-11)
    return fwd.float(), inv.float(), env.float()


class Wrapped(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m
        fwd, inv, env = kernels()
        self.register_buffer("fwd", fwd)
        self.register_buffer("inv", inv)
        self.register_buffer("env", env)

    def forward(self, wav):  # [1, L]
        x = F.pad(wav.reshape(1, 1, L), (PAD, PAD), mode="reflect")
        spec = F.conv1d(x, self.fwd, stride=HOP)          # [1, 2·BINS, T]
        re, im = spec[:, :BINS], spec[:, BINS:]
        mag = torch.sqrt(re * re + im * im)
        pha = torch.atan2(im, re)
        silent = ((mag == 0).float().mean(dim=1, keepdim=True) > 0.5).float()  # «кадры-пустышки»
        mag_c = torch.pow(mag + 1e-12, COMP) * (mag > 0).float()
        mag_g, pha_g, _ = self.m(mag_c, pha)
        mag_g = torch.pow(mag_g.clamp(min=0) + 1e-12, 1.0 / COMP) * (1 - silent)
        yr = mag_g * torch.cos(pha_g)
        yi = mag_g * torch.sin(pha_g)
        y = F.conv_transpose1d(torch.cat([yr, yi], 1), self.inv, stride=HOP)
        return (y[:, 0, PAD:PAD + L] / self.env).reshape(1, L)


def reference(m, wav):
    """Ровно как inference.py: torch.stft → модель → torch.istft."""
    w = torch.hann_window(WIN)
    s = torch.stft(wav, N_FFT, HOP, WIN, w, center=True, pad_mode="reflect", normalized=False, return_complex=True)
    mag, pha = torch.abs(s), torch.angle(s)
    mag = torch.pow(mag, COMP)
    mg, pg, _ = m(mag, pha)
    mg = torch.pow(mg, 1.0 / COMP)
    return torch.istft(torch.complex(mg * torch.cos(pg), mg * torch.sin(pg)), N_FFT, HOP, WIN, w, center=True, length=L)


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
    ref = ref - ref.mean()
    est = est - est.mean()
    a = (est @ ref) / (ref @ ref + 1e-12)
    t = a * ref
    return 10 * np.log10((t @ t) / ((est - t) @ (est - t) + 1e-12))


clean = load_wav(CLEAN_WAV)
if len(clean) < L:
    clean = np.pad(clean, (0, L - len(clean)))
clean = clean[:L]
rng = np.random.default_rng(1)
noise = np.cumsum(rng.standard_normal(L)).astype(np.float32)          # «гул»
noise = noise - np.convolve(noise, np.ones(64) / 64, mode="same")
noise += 0.5 * rng.standard_normal(L).astype(np.float32)               # «шипение»
noise *= np.sqrt((clean @ clean) / (noise @ noise)) * 10 ** (-5 / 20)  # SNR 5 дБ
noisy = clean + noise
norm = math.sqrt(L / float(noisy @ noisy))                             # как в inference.py

w = Wrapped(model).eval()
x = torch.from_numpy(noisy * norm)[None]

t0 = time.time()
ref = reference(model, x)[0].numpy() / norm
print(f"PyTorch (эталон torch.stft): {time.time() - t0:.1f} с на {CHUNK_S} с звука")
mine = w(x)[0].numpy() / norm
d = 10 * np.log10((ref @ ref) / (((mine - ref) @ (mine - ref)) + 1e-20))
print(f"Обёртка vs эталон: совпадение {d:.1f} дБ")
assert d > 40, "обёртка STFT не совпала с эталоном"

before, after = si_sdr(noisy, clean), si_sdr(ref, clean)
print(f"Качество: SI-SDR {before:.1f} дБ → {after:.1f} дБ")
assert after > before + 3, "модель не улучшает зашумлённую речь — что-то собрано неправильно"

t0 = time.time()
torch.onnx.export(w, (x,), OUT, input_names=["wav"], output_names=["out"], opset_version=17,
                  do_constant_folding=True, dynamo=False)
print(f"ONNX сохранён за {time.time() - t0:.0f} с, {os.path.getsize(OUT) / 1e6:.1f} МБ")

import onnxruntime as ort  # noqa: E402

s = ort.InferenceSession(OUT, providers=["CPUExecutionProvider"])
t0 = time.time()
out = s.run(None, {"wav": x.numpy()})[0][0] / norm
dt = time.time() - t0
d2 = 10 * np.log10((mine @ mine) / (((out - mine) @ (out - mine)) + 1e-20))
print(f"ONNX Runtime vs PyTorch: {d2:.1f} дБ; {CHUNK_S} с звука за {dt:.1f} с на CPU CI")
assert d2 > 40, "ONNX не совпал с PyTorch"
json.dump({"sample_rate": SR, "chunk": L, "n_fft": N_FFT, "hop": HOP, "win": WIN, "compress": COMP,
           "si_sdr_before": round(before, 2), "si_sdr_after": round(after, 2)}, open(META, "w"))
print("OK")
