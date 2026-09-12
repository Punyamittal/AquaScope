/*
 * SMRITI AQUA - DSP core (module A).
 * Pure C++17, NO Android headers (desktop-testable with g++).
 * Contract: SPEC.md section 2. Signatures are fixed; do not change.
 */
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace smriti {

// ---- SPEC section 2 constants ----
constexpr int SAMPLE_RATE = 48000;
constexpr int FFT_SIZE = 2048;
constexpr int HOP = 512;
constexpr int MELS = 64;
constexpr float FMIN = 17000.f;
constexpr float FMAX = 23000.f;
constexpr int FINGERPRINT_DIM = 72; // 64 log-mel means + 8 stats

struct Fingerprint {
    static constexpr int DIM = 72;
    float v[72];
};

// Linear-FM chirp, raised-cosine 5% edges, amplitude 0.8.
std::vector<int16_t> generateChirp(int sampleRate, float f0, float f1, int durationMs);

// STFT(Hann, FFT_SIZE=2048, HOP=512) -> 64-band filterbank (17-23 kHz)
// -> per-band mean log-energy + 8 stats -> 72-dim fingerprint.
// All features are computed on normalized float samples (pcm/32768.f).
Fingerprint computeFingerprint(const int16_t* pcm, size_t n, int sampleRate);

// Row-major [frames][64] log-mel spectrogram, min-max normalized to 0..1.
std::vector<float> computeSpectrogram(const int16_t* pcm, size_t n, int sampleRate,
                                      int& outFrames, int& outMels);

// Diagonal Mahalanobis anomaly score in [0,1].
// v2 calibration (see dsp_core.cpp): per-dim relative variance floor,
// mean over 72 dims, squash 1-exp(-d_avg/1.5).
float anomalyScore(const Fingerprint& cur, const float* baseMean, const float* baseVar);

// Envelope (rectify + one-pole lowpass) normalized cross-correlation peak,
// clamped to [0,1]. The accel envelope is resampled (linear interp) onto the
// mic envelope grid to handle differing sample rates. The peak search is
// limited to |lag| <= micRate/4 (the physical mic<->vibration coupling
// delay window), which rejects spurious full-length noise peaks.
float coherenceScore(const int16_t* mic, size_t micN, const float* accelZ, size_t accN,
                     int micRate, int accRate);

} // namespace smriti
