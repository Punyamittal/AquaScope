/*
 * SMRITI AQUA - native DSP test (SPEC section 7).
 * Desktop only: g++ -std=c++17, no Android deps, no JNI.
 *
 * Synthetic rig:
 *   clean "pipe" response  = chirp (*) exponentially-decaying echo kernel
 *                            (tau=100 ms + reflection)
 *   damped "leak" response = same, but echo kernel lowpassed (one-pole, small
 *                            alpha) with faster decay (tau=50 ms) + slight noise
 * Asserts:
 *   1. chirp length == sr*ms/1000, max|x| <= 0.81*32767
 *   2. anomalyScore(mean=clean, var=small): clean < 0.2, damped > 0.6
 *   3. coherence: correlated envelopes > 0.5, uncorrelated noise < 0.2
 * Prints "ALL DSP TESTS PASSED" on success.
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <algorithm>
#include <vector>

#include "dsp_core.h"

using namespace smriti;

namespace {

int g_failures = 0;

void check(bool cond, const char* msg) {
    if (cond) {
        std::printf("ok   : %s\n", msg);
    } else {
        std::printf("FAIL : %s\n", msg);
        ++g_failures;
    }
}

// Deterministic xorshift32 PRNG, uniform in [-1, 1).
struct Rng {
    uint32_t s;
    explicit Rng(uint32_t seed) : s(seed ? seed : 1u) {}
    float uni() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return ((float)(s & 0xFFFFFFu) / (float)0x1000000u) * 2.f - 1.f;
    }
    uint32_t next() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return s;
    }
};

std::vector<int16_t> convolve(const std::vector<int16_t>& x, const std::vector<float>& h) {
    std::vector<double> y(x.size() + h.size(), 0.0);
    for (size_t i = 0; i < x.size(); ++i)
        for (size_t j = 0; j < h.size(); ++j) y[i + j] += (double)x[i] * (double)h[j];
    std::vector<int16_t> out(y.size());
    for (size_t i = 0; i < y.size(); ++i) {
        double v = y[i];
        if (v > 32767.0) v = 32767.0;
        if (v < -32768.0) v = -32768.0;
        out[i] = (int16_t)std::llround(v);
    }
    return out;
}

void addNoise(std::vector<int16_t>& x, double ampCounts, uint32_t seed) {
    Rng rng(seed);
    for (size_t i = 0; i < x.size(); ++i) {
        double v = (double)x[i] + ampCounts * (double)rng.uni();
        if (v > 32767.0) v = 32767.0;
        if (v < -32768.0) v = -32768.0;
        x[i] = (int16_t)std::llround(v);
    }
}

// Same envelope extraction as dsp_core (rectify + one-pole lowpass, 10 Hz,
// state init = first rectified sample).
std::vector<float> micEnvelope(const std::vector<int16_t>& x, int rate) {
    const float alpha = 1.f - std::exp(-2.f * 3.14159265358979f * 10.f / (float)rate);
    std::vector<float> env(x.size());
    float y = std::fabs((float)x[0] * (1.f / 32768.f));
    for (size_t i = 0; i < x.size(); ++i) {
        const float r = std::fabs((float)x[i] * (1.f / 32768.f));
        y += alpha * (r - y);
        env[i] = y;
    }
    return env;
}

} // namespace

int main() {
    const int sr = 48000;

    // ---------------------------------------------------------- test 1 ----
    std::vector<int16_t> chirp = generateChirp(sr, FMIN, FMAX, 400);
    check(chirp.size() == (size_t)(sr * 400 / 1000), "chirp length == sr*ms/1000");
    int maxAbs = 0;
    for (int16_t s : chirp) maxAbs = std::max(maxAbs, std::abs((int)s));
    std::printf("info : chirp max|x| = %d (limit %.0f)\n", maxAbs, 0.81 * 32767.0);
    check(maxAbs <= (int)(0.81 * 32767.0), "chirp max|x| <= 0.81*32767");

    // ---------------------------------------------------------- test 2 ----
    // Clean pipe: exponentially-decaying echo kernel, tau=100 ms, plus a
    // 30 ms reflection. NOT sum-normalized: natural in-band gain (~0.5-0.9
    // across 17-23 kHz) keeps the response loud. The long decay keeps most
    // STFT frames above the leakage floor.
    const int kLen = 9600; // 200 ms
    std::vector<float> hClean((size_t)kLen, 0.f);
    for (int n = 0; n < kLen; ++n) {
        hClean[(size_t)n] = std::exp(-(float)n / 4800.f);
        if (n >= 1440) hClean[(size_t)n] += 0.6f * std::exp(-(float)(n - 1440) / 4800.f);
    }

    // Dampened/leak: faster decay (tau=50 ms) + one-pole lowpass (alpha
    // small). The one-pole pulls the whole 17-23 kHz band down by ~12-16 dB
    // and the shorter tau halves the decay time -> clear fingerprint deviation.
    std::vector<float> hDamp((size_t)kLen, 0.f);
    for (int n = 0; n < kLen; ++n) hDamp[(size_t)n] = std::exp(-(float)n / 2400.f);
    {
        const float alpha = 0.25f; // one-pole lowpass, small alpha
        float y = 0.f;
        for (int n = 0; n < kLen; ++n) {
            y += alpha * (hDamp[(size_t)n] - y);
            hDamp[(size_t)n] = y;
        }
    }

    std::vector<int16_t> respClean = convolve(chirp, hClean);
    std::vector<int16_t> respDamp = convolve(chirp, hDamp);

    // slight measurement noise on the damped (leak) response
    addNoise(respDamp, 120.0, 777u);
    // clean repeat with quantization-level noise (baseline stability check)
    std::vector<int16_t> respClean2 = respClean;
    addNoise(respClean2, 2.0, 4242u);

    Fingerprint fpClean = computeFingerprint(respClean.data(), respClean.size(), sr);
    Fingerprint fpClean2 = computeFingerprint(respClean2.data(), respClean2.size(), sr);
    Fingerprint fpDamp = computeFingerprint(respDamp.data(), respDamp.size(), sr);

    float baseVar[Fingerprint::DIM];
    for (int i = 0; i < Fingerprint::DIM; ++i) baseVar[i] = 1e-6f; // "var = small"

    const float sClean = anomalyScore(fpClean, fpClean.v, baseVar);
    const float sClean2 = anomalyScore(fpClean2, fpClean.v, baseVar);
    const float sDamp = anomalyScore(fpDamp, fpClean.v, baseVar);
    std::printf("info : anomaly clean(exact)=%.4f  clean(noisy repeat)=%.4f  damped=%.4f\n",
                sClean, sClean2, sDamp);
    check(sClean < 0.2f, "anomalyScore(clean vs clean baseline) < 0.2");
    check(sClean2 < 0.2f, "anomalyScore(noisy clean repeat) < 0.2");
    check(sDamp > 0.6f, "anomalyScore(damped leak vs clean baseline) > 0.6");

    // ---------------------------------------------------------- test 3 ----
    // 1.2 s mic signal: clean response burst starting at 100 ms.
    const size_t micN = (size_t)(sr * 12 / 10);
    std::vector<int16_t> mic(micN, 0);
    const size_t off = 4800;
    for (size_t i = 0; i < respClean.size() && off + i < micN; ++i) mic[off + i] = respClean[i];

    // correlated accel: the true mic envelope sampled at 100 Hz
    const int accRate = 100;
    const size_t accN = micN * (size_t)accRate / (size_t)sr; // 120
    std::vector<float> env = micEnvelope(mic, sr);
    std::vector<float> accCorr(accN);
    for (size_t i = 0; i < accN; ++i) accCorr[i] = env[i * (size_t)(sr / accRate)];

    // uncorrelated accel: zero-mean noise, no relation to the mic burst
    std::vector<float> accNoise(accN);
    {
        Rng rng(90210u);
        for (size_t i = 0; i < accN; ++i) accNoise[i] = rng.uni();
    }
    // second uncorrelated variant: balanced random-sign noise (exact zero
    // mean -> constant |x| -> flat envelope, exercises the zero-norm guard)
    std::vector<float> accSign(accN, 0.5f);
    {
        for (size_t i = accN / 2; i < accN; ++i) accSign[i] = -0.5f;
        Rng rng(1357u); // Fisher-Yates shuffle
        for (size_t i = accN - 1; i > 0; --i) {
            const size_t j = (size_t)(rng.next() % (uint32_t)(i + 1));
            std::swap(accSign[i], accSign[j]);
        }
    }

    const float cCorr = coherenceScore(mic.data(), micN, accCorr.data(), accN, sr, accRate);
    const float cNoise = coherenceScore(mic.data(), micN, accNoise.data(), accN, sr, accRate);
    const float cSign = coherenceScore(mic.data(), micN, accSign.data(), accN, sr, accRate);
    std::printf("info : coherence correlated=%.4f  uncorrelated(noise)=%.4f  uncorrelated(sign)=%.4f\n",
                cCorr, cNoise, cSign);
    check(cCorr > 0.5f, "coherence(correlated envelopes) > 0.5");
    check(cNoise < 0.2f, "coherence(uncorrelated noise) < 0.2");
    check(cSign < 0.2f, "coherence(random-sign noise) < 0.2");

    // ------------------------------------------------------------- done ----
    if (g_failures == 0) {
        std::printf("ALL DSP TESTS PASSED\n");
        return 0;
    }
    std::printf("%d TEST(S) FAILED\n", g_failures);
    return 1;
}
