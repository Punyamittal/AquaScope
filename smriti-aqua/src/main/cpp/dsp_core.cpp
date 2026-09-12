/*
 * SMRITI AQUA - DSP core implementation (module A).
 * Pure C++17, no Android headers. FFT via vendored KissFFT.
 *
 * Design notes:
 * - All features are computed on normalized float samples (pcm/32768.f).
 * - The "mel-style" filterbank uses 64 triangular filters LINEARLY spaced
 *   between FMIN..FMAX. Over the narrow 17-23 kHz band the mel scale is
 *   nearly linear (mel(23k)-mel(17k) maps almost 1:1), so linear spacing is
 *   equivalent within one bin width and keeps the mapping transparent.
 * - "High-band energy" = linear energy in bins >= 21 kHz (upper third of the
 *   analysis band), summarized as per-frame log-energy mean/var.
 * - Decay time = time from the in-band energy envelope peak until it falls
 *   below peak/e (end of signal if it never does), in seconds.
 * - Peak bin freq = frequency of the analysis-band bin with max mean power.
 */
#include "dsp_core.h"
#include "kiss_fftr.h"

#include <cfloat>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>

namespace smriti {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr double kPiD = 3.14159265358979323846;
constexpr double kEpsPow = 1e-20;     // guards log() of band energies
constexpr float kHighBandFmin = 21000.f; // upper third of 17-23 kHz
constexpr float kEnvCutoffHz = 10.f;     // envelope one-pole lowpass cutoff

// ---------------------------------------------------------------- STFT ----

struct Filterbank {
    int start[MELS];                 // first bin per band
    int count[MELS];                 // number of bins per band
    std::vector<std::vector<float>> w; // per-band triangular weights
};

// 64 triangular filters, linearly spaced edges across [FMIN, FMAX].
Filterbank makeFilterbank(int sampleRate) {
    Filterbank fb;
    fb.w.resize(MELS);
    const float binHz = (float)sampleRate / (float)FFT_SIZE;
    for (int b = 0; b < MELS; ++b) {
        const float eL = FMIN + (FMAX - FMIN) * (float)b / (float)(MELS + 1);
        const float eC = FMIN + (FMAX - FMIN) * (float)(b + 1) / (float)(MELS + 1);
        const float eR = FMIN + (FMAX - FMIN) * (float)(b + 2) / (float)(MELS + 1);
        int kL = (int)std::ceil(eL / binHz);
        int kR = (int)std::floor(eR / binHz);
        if (kL < 1) kL = 1;
        if (kR > FFT_SIZE / 2) kR = FFT_SIZE / 2;
        if (kR < kL) kR = kL; // very narrow band: keep at least one bin
        fb.start[b] = kL;
        fb.count[b] = kR - kL + 1;
        fb.w[b].resize((size_t)fb.count[b]);
        for (int k = kL; k <= kR; ++k) {
            const float f = (float)k * binHz;
            float wgt = (f <= eC) ? (f - eL) / (eC - eL) : (eR - f) / (eR - eC);
            if (wgt < 0.f) wgt = 0.f;
            fb.w[b][(size_t)(k - kL)] = wgt;
        }
    }
    return fb;
}

struct Analysis {
    int frames = 0;
    int kLo = 0, kHi = 0;                        // analysis-band bin range
    std::vector<std::vector<float>> logMel;      // [frames][MELS]
    std::vector<float> sfm;                      // spectral flatness per frame
    std::vector<float> centroid;                 // spectral centroid (Hz) per frame
    std::vector<float> hbLogE;                   // high-band log-energy per frame
    std::vector<float> bandE;                    // in-band linear energy per frame (decay)
    std::vector<double> avgPow;                  // mean power per bin (peak freq)
};

Analysis analyze(const int16_t* pcm, size_t n, int sampleRate) {
    Analysis a;
    const int frames = (n >= (size_t)FFT_SIZE) ? (int)((n - (size_t)FFT_SIZE) / (size_t)HOP) + 1 : 1;
    a.frames = frames;

    // Hann window
    float win[FFT_SIZE];
    for (int i = 0; i < FFT_SIZE; ++i)
        win[i] = 0.5f * (1.f - std::cos(2.f * kPi * (float)i / (float)(FFT_SIZE - 1)));

    kiss_fftr_cfg cfg = kiss_fftr_alloc(FFT_SIZE, 0, nullptr, nullptr);
    std::vector<float> timeBuf(FFT_SIZE, 0.f);
    std::vector<kiss_fft_cpx> freqBuf(FFT_SIZE / 2 + 1);
    std::vector<float> power(FFT_SIZE / 2 + 1);

    const Filterbank fb = makeFilterbank(sampleRate);
    const float binHz = (float)sampleRate / (float)FFT_SIZE;
    int kLo = (int)std::ceil(FMIN / binHz);
    int kHi = (int)std::floor(FMAX / binHz);
    if (kHi > FFT_SIZE / 2) kHi = FFT_SIZE / 2;
    if (kLo < 1) kLo = 1;
    a.kLo = kLo;
    a.kHi = kHi;
    const int kHb = (int)std::ceil(kHighBandFmin / binHz);

    a.logMel.assign((size_t)frames, std::vector<float>(MELS, 0.f));
    a.sfm.assign((size_t)frames, 0.f);
    a.centroid.assign((size_t)frames, 0.f);
    a.hbLogE.assign((size_t)frames, 0.f);
    a.bandE.assign((size_t)frames, 0.f);
    a.avgPow.assign(FFT_SIZE / 2 + 1, 0.0);

    for (int t = 0; t < frames; ++t) {
        const size_t base = (size_t)t * (size_t)HOP;
        for (int i = 0; i < FFT_SIZE; ++i) {
            const size_t idx = base + (size_t)i;
            const float x = (idx < n) ? (float)pcm[idx] * (1.f / 32768.f) : 0.f;
            timeBuf[(size_t)i] = x * win[i];
        }
        kiss_fftr(cfg, timeBuf.data(), freqBuf.data());
        for (int k = 0; k <= FFT_SIZE / 2; ++k) {
            const float p = freqBuf[(size_t)k].r * freqBuf[(size_t)k].r +
                            freqBuf[(size_t)k].i * freqBuf[(size_t)k].i;
            power[(size_t)k] = p;
            a.avgPow[(size_t)k] += (double)p;
        }

        // Per-frame RELATIVE floors (-60 dB of the frame's own peak, i.e.
        // top_db=60 semantics): without them, frames/bands far below the
        // frame peak sit at the absolute eps floor (~1e-9 leakage) and the
        // mean log-energies become hypersensitive to the recording noise
        // floor, breaking repeatability of the fingerprint.
        double bandRaw[MELS];
        double maxBandE = 0.0, maxBinP = 0.0, tot = 0.0, hb = 0.0;
        for (int b = 0; b < MELS; ++b) {
            double e = 0.0;
            const int s = fb.start[b];
            for (int j = 0; j < fb.count[b]; ++j)
                e += (double)power[(size_t)(s + j)] * (double)fb.w[b][(size_t)j];
            bandRaw[b] = e;
            if (e > maxBandE) maxBandE = e;
        }
        for (int k = kLo; k <= kHi; ++k) {
            const double p = (double)power[(size_t)k];
            if (p > maxBinP) maxBinP = p;
            tot += p;
            if (k >= kHb) hb += p;
        }
        const double bandFloor = 1e-6 * maxBandE;
        const double binFloor = 1e-6 * maxBinP;
        const double totFloor = 1e-6 * tot;

        // log band energies (floored)
        for (int b = 0; b < MELS; ++b) {
            const double e = bandRaw[b] > bandFloor ? bandRaw[b] : bandFloor;
            a.logMel[(size_t)t][(size_t)b] = (float)std::log(e + kEpsPow);
        }

        // per-frame stats over the analysis band (bins floored for flatness)
        double logSum = 0.0, linSum = 0.0, cw = 0.0, cwf = 0.0;
        for (int k = kLo; k <= kHi; ++k) {
            double p = (double)power[(size_t)k];
            cw += p;
            cwf += p * (double)k * (double)binHz;
            if (p < binFloor) p = binFloor;
            logSum += std::log(p + 1e-30);
            linSum += p;
        }
        const int cnt = kHi - kLo + 1;
        const double gm = std::exp(logSum / (double)cnt);
        const double am = linSum / (double)cnt;
        a.sfm[(size_t)t] = (float)(gm / (am + 1e-30));
        a.centroid[(size_t)t] = (float)(cwf / (cw + 1e-30));
        const double hbF = hb > totFloor ? hb : totFloor;
        a.hbLogE[(size_t)t] = (float)std::log(hbF + kEpsPow);
        a.bandE[(size_t)t] = (float)tot;
    }

    for (double& p : a.avgPow) p /= (double)frames;
    kiss_fftr_free(cfg);
    return a;
}

void meanVar(const std::vector<float>& x, float& m, float& v) {
    double s = 0.0, s2 = 0.0;
    for (float f : x) {
        s += (double)f;
        s2 += (double)f * (double)f;
    }
    const double nInv = 1.0 / (double)x.size();
    m = (float)(s * nInv);
    v = (float)(s2 * nInv - (s * nInv) * (s * nInv));
    if (v < 0.f) v = 0.f; // roundoff guard
}

} // namespace

// ------------------------------------------------------------- chirp ----

std::vector<int16_t> generateChirp(int sampleRate, float f0, float f1, int durationMs) {
    const int n = (int)((long long)sampleRate * (long long)durationMs / 1000LL);
    if (n <= 0 || sampleRate <= 0) return {};
    std::vector<int16_t> out((size_t)n);
    const double T = (double)n / (double)sampleRate;
    const double sweep = ((double)f1 - (double)f0) / T; // Hz/s
    const int edge = (int)(0.05 * (double)n);           // raised-cosine 5% edges
    for (int i = 0; i < n; ++i) {
        const double t = (double)i / (double)sampleRate;
        const double phase = 2.0 * kPiD * ((double)f0 * t + 0.5 * sweep * t * t);
        double amp = 0.8;
        if (edge > 0) {
            if (i < edge)
                amp *= 0.5 * (1.0 - std::cos(kPiD * (double)i / (double)edge));
            else if (i >= n - edge)
                amp *= 0.5 * (1.0 + std::cos(kPiD * (double)(i - (n - edge)) / (double)edge));
        }
        double s = amp * std::sin(phase) * 32767.0;
        if (s > 32767.0) s = 32767.0;
        if (s < -32768.0) s = -32768.0;
        out[(size_t)i] = (int16_t)std::llround(s);
    }
    return out;
}

// -------------------------------------------------------- fingerprint ----

Fingerprint computeFingerprint(const int16_t* pcm, size_t n, int sampleRate) {
    Fingerprint fp{};
    if (!pcm || n == 0 || sampleRate <= 0) return fp;
    const Analysis a = analyze(pcm, n, sampleRate);

    // dims 0..63: per-band mean log-energy
    for (int b = 0; b < MELS; ++b) {
        double s = 0.0;
        for (int t = 0; t < a.frames; ++t) s += (double)a.logMel[(size_t)t][(size_t)b];
        fp.v[b] = (float)(s / (double)a.frames);
    }

    // dims 64..69: flatness / centroid / high-band energy mean+var
    float m, v;
    meanVar(a.sfm, m, v);
    fp.v[64] = m;
    fp.v[65] = v;
    meanVar(a.centroid, m, v);
    fp.v[66] = m;
    fp.v[67] = v;
    meanVar(a.hbLogE, m, v);
    fp.v[68] = m;
    fp.v[69] = v;

    // dim 70: decay time (peak of in-band energy envelope -> below peak/e)
    int tPeak = 0;
    float ePeak = 0.f;
    for (int t = 0; t < a.frames; ++t) {
        if (a.bandE[(size_t)t] > ePeak) {
            ePeak = a.bandE[(size_t)t];
            tPeak = t;
        }
    }
    const float thresh = ePeak * (1.f / 2.71828182845904523536f);
    int tEnd = a.frames - 1;
    for (int t = tPeak + 1; t < a.frames; ++t) {
        if (a.bandE[(size_t)t] <= thresh) {
            tEnd = t;
            break;
        }
    }
    fp.v[70] = (float)(tEnd - tPeak) * (float)HOP / (float)sampleRate;

    // dim 71: peak bin frequency (Hz) within the analysis band
    int kBest = a.kLo;
    double pBest = 0.0;
    for (int k = a.kLo; k <= a.kHi; ++k) {
        if (a.avgPow[(size_t)k] > pBest) {
            pBest = a.avgPow[(size_t)k];
            kBest = k;
        }
    }
    fp.v[71] = (float)kBest * ((float)sampleRate / (float)FFT_SIZE);
    return fp;
}

// -------------------------------------------------------- spectrogram ----

std::vector<float> computeSpectrogram(const int16_t* pcm, size_t n, int sampleRate,
                                      int& outFrames, int& outMels) {
    outMels = MELS;
    if (!pcm || n == 0 || sampleRate <= 0) {
        outFrames = 1;
        return std::vector<float>(MELS, 0.f);
    }
    const Analysis a = analyze(pcm, n, sampleRate);
    outFrames = a.frames;
    std::vector<float> out((size_t)a.frames * (size_t)MELS);
    float mn = FLT_MAX, mx = -FLT_MAX;
    for (int t = 0; t < a.frames; ++t)
        for (int b = 0; b < MELS; ++b) {
            const float v = a.logMel[(size_t)t][(size_t)b];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
    const float range = (mx - mn > 1e-9f) ? (mx - mn) : 1.f;
    for (int t = 0; t < a.frames; ++t)
        for (int b = 0; b < MELS; ++b)
            out[(size_t)t * (size_t)MELS + (size_t)b] = (a.logMel[(size_t)t][(size_t)b] - mn) / range;
    return out;
}

// ------------------------------------------------------ anomaly score ----

float anomalyScore(const Fingerprint& cur, const float* baseMean, const float* baseVar) {
    if (!baseMean || !baseVar) return 0.f;
    // v2 calibration: raw diagonal-Mahalanobis SUM over 72 dims saturates the
    // squash, so use a per-dim relative variance floor and the MEAN over dims.
    //   vfloor_i = max(var_i, (0.10*|mean_i|)^2 + 1e-4)
    //   d_avg    = mean_i (cur_i - mean_i)^2 / vfloor_i
    //   score    = 1 - exp(-d_avg / 1.5)
    double d = 0.0;
    for (int i = 0; i < Fingerprint::DIM; ++i) {
        const double diff = (double)cur.v[i] - (double)baseMean[i];
        const double relFloor = 0.10 * std::fabs((double)baseMean[i]);
        double vfloor = (double)baseVar[i];
        const double floor2 = relFloor * relFloor + 1e-4;
        if (vfloor < floor2) vfloor = floor2;
        d += diff * diff / vfloor;
    }
    d /= (double)Fingerprint::DIM;
    return 1.f - std::exp((float)(-d / 1.5));
}

// ---------------------------------------------------- coherence score ----

float coherenceScore(const int16_t* mic, size_t micN, const float* accelZ, size_t accN,
                     int micRate, int accRate) {
    if (!mic || !accelZ || micN < 8 || accN < 2 || micRate <= 0 || accRate <= 0) return 0.f;

    // mic envelope: rectify + one-pole lowpass (state init = first sample,
    // avoids a startup ramp that would otherwise correlate spuriously)
    const float aM = 1.f - std::exp(-2.f * kPi * kEnvCutoffHz / (float)micRate);
    const float aA = 1.f - std::exp(-2.f * kPi * kEnvCutoffHz / (float)accRate);
    std::vector<float> em(micN);
    {
        float y = std::fabs((float)mic[0] * (1.f / 32768.f));
        for (size_t i = 0; i < micN; ++i) {
            const float x = std::fabs((float)mic[i] * (1.f / 32768.f));
            y += aM * (x - y);
            em[i] = y;
        }
    }

    // accel envelope: remove DC (gravity), rectify, one-pole lowpass
    std::vector<float> ea(accN);
    {
        double mean = 0.0;
        for (size_t i = 0; i < accN; ++i) mean += (double)accelZ[i];
        mean /= (double)accN;
        float y = std::fabs((float)((double)accelZ[0] - mean));
        for (size_t i = 0; i < accN; ++i) {
            const float x = std::fabs((float)((double)accelZ[i] - mean));
            y += aA * (x - y);
            ea[i] = y;
        }
    }

    // resample the accel envelope onto the mic envelope grid (linear interp)
    std::vector<float> ra(micN);
    const double step = (double)accRate / (double)micRate;
    for (size_t j = 0; j < micN; ++j) {
        const double pos = (double)j * step;
        double ip;
        double fr = std::modf(pos, &ip);
        size_t i0 = (size_t)ip;
        size_t i1 = i0 + 1;
        if (i0 >= accN - 1) {
            i0 = accN - 1;
            i1 = accN - 1;
            fr = 0.0;
        }
        ra[j] = (float)((double)ea[i0] * (1.0 - fr) + (double)ea[i1] * fr);
    }

    // mean-subtract both envelopes, compute norms
    double mA = 0.0, mB = 0.0;
    for (size_t j = 0; j < micN; ++j) {
        mA += (double)ra[j];
        mB += (double)em[j];
    }
    mA /= (double)micN;
    mB /= (double)micN;
    double nA = 0.0, nB = 0.0;
    for (size_t j = 0; j < micN; ++j) {
        const double da = (double)ra[j] - mA;
        const double db = (double)em[j] - mB;
        ra[j] = (float)da;
        em[j] = (float)db;
        nA += da * da;
        nB += db * db;
    }
    nA = std::sqrt(nA);
    nB = std::sqrt(nB);
    if (nA * nB < 1e-12) return 0.f;

    // full normalized cross-correlation via FFT (peak over all lags)
    size_t L = 1;
    while (L < 2 * micN) L <<= 1;
    kiss_fftr_cfg fwd = kiss_fftr_alloc((int)L, 0, nullptr, nullptr);
    kiss_fftr_cfg inv = kiss_fftr_alloc((int)L, 1, nullptr, nullptr);
    if (!fwd || !inv) {
        kiss_fftr_free(fwd);
        kiss_fftr_free(inv);
        return 0.f;
    }
    std::vector<float> ta(L, 0.f), tb(L, 0.f), tc(L, 0.f);
    std::memcpy(ta.data(), ra.data(), micN * sizeof(float));
    std::memcpy(tb.data(), em.data(), micN * sizeof(float));
    std::vector<kiss_fft_cpx> FA(L / 2 + 1), FB(L / 2 + 1), FC(L / 2 + 1);
    kiss_fftr(fwd, ta.data(), FA.data());
    kiss_fftr(fwd, tb.data(), FB.data());
    for (size_t k = 0; k <= L / 2; ++k) {
        // conj(FA) * FB  ->  circular cross-correlation
        FC[k].r = FA[k].r * FB[k].r + FA[k].i * FB[k].i;
        FC[k].i = FA[k].r * FB[k].i - FA[k].i * FB[k].r;
    }
    kiss_fftri(inv, FC.data(), tc.data()); // scaled by L
    // Peak search restricted to |lag| <= micRate/4 (+-250 ms at 48 kHz): the
    // physically meaningful mic<->vibration coupling delay window. Searching
    // all L lags would inflate false peaks from uncorrelated noise.
    const size_t maxLag = std::min((size_t)(micRate / 4), micN - 1);
    double peak = 0.0;
    for (size_t j = 0; j <= maxLag; ++j) // lags 0..+maxLag
        if ((double)tc[j] > peak) peak = (double)tc[j];
    for (size_t j = L - maxLag; j < L; ++j) // lags -maxLag..-1 (circular)
        if ((double)tc[j] > peak) peak = (double)tc[j];
    kiss_fftr_free(fwd);
    kiss_fftr_free(inv);

    double ncc = peak / ((double)L * nA * nB);
    if (ncc < 0.0) ncc = 0.0;
    if (ncc > 1.0) ncc = 1.0;
    return (float)ncc;
}

} // namespace smriti
