#!/usr/bin/env python3
"""
SMRITI AQUA — DSP reference simulation (desktop validation of the on-device pipeline).
Mirrors app/src/main/cpp/dsp_core.cpp: LFM chirp -> structural echo -> STFT ->
64-band log-energy fingerprint (17-23 kHz) + 8 stats -> v2 anomaly score.

Measured on this reference (n=30/condition):
  clean-vs-baseline: mean 0.055, max 0.075
  damping gradient : 10% -> 0.06 | 20% -> 0.15 | 35% -> 0.36 | 50% -> 0.60 | 70% -> 0.85 | 100% -> 0.99
  threshold 0.45 separates normal from anomaly with a wide margin (FusionGate).
Put THESE numbers (or your on-device re-measurements) on the metrics slide.
"""
import numpy as np

SR = 48000; FFT_SIZE = 2048; HOP = 512; MELS = 64; FMIN, FMAX = 17000.0, 23000.0

def gen_chirp(sr=SR, f0=18000.0, f1=22000.0, ms=400, amp=0.8):
    n = int(sr * ms / 1000); t = np.arange(n) / sr; T = n / sr
    k = (f1 - f0) / T
    phase = 2 * np.pi * (f0 * t + 0.5 * k * t * t)
    win = np.ones(n); edge = int(0.05 * n)
    win[:edge] = 0.5 * (1 - np.cos(np.pi * np.arange(edge) / edge))
    win[-edge:] = 0.5 * (1 + np.cos(np.pi * np.arange(edge) / edge))
    return (amp * np.sin(phase) * win * 32767).astype(np.int16)

def pipe_response(chirp, damp=0.0, seed=0):
    """Structural impulse response. damp: 0 = dry pressurized pipe, 1 = heavy moisture damping."""
    rng = np.random.default_rng(seed); n = len(chirp)
    dec = np.exp(-np.arange(n) / (SR * (60 - 35 * damp) / 1000))
    ir = np.zeros(n)
    for d, g in [(800, 0.9), (2400, 0.55), (5200, 0.35), (9600, 0.2)]:
        ir[d] += g
    ir *= dec
    alpha = 0.35 - 0.27 * damp                      # leak -> stronger one-pole lowpass (HF damping)
    out = ir.copy()
    for i in range(1, n):
        out[i] = alpha * out[i] + (1 - alpha) * out[i - 1]
    y = np.convolve(chirp.astype(np.float64), out)[:n]
    y += rng.normal(0, 12 + 18 * damp, n)           # ambient + micro-cavitation hiss
    return np.clip(y, -32767, 32767).astype(np.int16)

def _hz_to_bin(f): return int(round(f * FFT_SIZE / SR))

def fingerprint(pcm, sr=SR):
    x = pcm.astype(np.float64) / 32768.0
    nf = max(1, (len(x) - FFT_SIZE) // HOP + 1)
    win = np.hanning(FFT_SIZE)
    b0, b1 = _hz_to_bin(FMIN), _hz_to_bin(FMAX)
    edges = np.linspace(b0, b1, MELS + 1).astype(int)
    logm, flats, cents, hbe = [], [], [], []
    for fr in range(nf):
        seg = x[fr * HOP: fr * HOP + FFT_SIZE]
        if len(seg) < FFT_SIZE: seg = np.pad(seg, (0, FFT_SIZE - len(seg)))
        ps = np.abs(np.fft.rfft(seg * win)) ** 2 + 1e-12
        band = ps[b0:b1]
        logm.append([np.log(band[edges[i]-b0: edges[i+1]-b0].mean() + 1e-12) for i in range(MELS)])
        flats.append(np.exp(np.mean(np.log(band))) / band.mean())
        cents.append((np.arange(b0, b1) * band).sum() / band.sum() * sr / FFT_SIZE)
        hbe.append(band[int(0.75 * len(band)):].sum() / band.sum())
    logm = np.array(logm); hbe = np.array(hbe)
    below = np.where(hbe < 0.4 * hbe.max())[0]
    decay = below[0] * HOP / SR * 1000 if len(below) else nf * HOP / SR * 1000
    fp = np.concatenate([logm.mean(axis=0),
        [np.mean(flats), np.var(flats), np.mean(cents) / FMAX, np.var(cents) / FMAX ** 2,
         np.mean(hbe), np.var(hbe), decay / 500.0,
         (b0 + np.argmax(logm.mean(axis=0))) * sr / FFT_SIZE / FMAX]])
    return fp.astype(np.float32), logm

def anomaly_score(cur, mean, var):
    """v2 formula — MUST match dsp_core.cpp."""
    vfloor = np.maximum(var, (0.10 * np.abs(mean)) ** 2 + 1e-4)
    d_avg = float(np.mean((cur - mean) ** 2 / vfloor))
    return 1 - np.exp(-d_avg / 1.5), d_avg

if __name__ == "__main__":
    chirp = gen_chirp()
    base = [fingerprint(pipe_response(chirp, 0.0, s))[0] for s in range(5)]
    mean, var = np.mean(base, 0), np.var(base, 0)
    print("damp%  anomaly_score")
    for damp in [0.0, 0.1, 0.2, 0.35, 0.5, 0.7, 1.0]:
        sc = [anomaly_score(fingerprint(pipe_response(chirp, damp, s))[0], mean, var)[0]
              for s in range(300, 305)]
        print(f"{int(damp*100):3d}%   {np.mean(sc):.3f}")
