/*
 * KISS FFT - real-valued FFT optimization (kiss_fftr).
 * Clean, self-contained minimal implementation, API-compatible with
 * KISS FFT by Mark Borgerding (BSD license, https://github.com/mborgerding/kissfft).
 *
 * Original project license: BSD-3-Clause. See ATTRIBUTION.md.
 */
#include "kiss_fftr.h"
#include <stdio.h>

struct kiss_fftr_state {
    kiss_fft_cfg substate;
    kiss_fft_cpx* tmpbuf;
    kiss_fft_cpx* super_twiddles;
};

#define KISS_FFT_MALLOC malloc
#define KISS_FFT_FREE free
#define HALF_OF(x) ((x) * 0.5f)
#define C_FIXDIV(c, div) \
    do {                 \
        (void)(c);       \
        (void)(div);     \
    } while (0)
#define C_ADD(res, a, b)                  \
    do {                                  \
        (res).r = (a).r + (b).r;          \
        (res).i = (a).i + (b).i;          \
    } while (0)
#define C_SUB(res, a, b)                  \
    do {                                  \
        (res).r = (a).r - (b).r;          \
        (res).i = (a).i - (b).i;          \
    } while (0)
#define C_MUL(m, a, b)                       \
    do {                                     \
        (m).r = (a).r * (b).r - (a).i * (b).i; \
        (m).i = (a).r * (b).i + (a).i * (b).r; \
    } while (0)

static void kf_cexp2(kiss_fft_cpx* x, double phase) {
    x->r = (kiss_fft_scalar)cos(phase);
    x->i = (kiss_fft_scalar)sin(phase);
}

kiss_fftr_cfg kiss_fftr_alloc(int nfft, int inverse_fft, void* mem, size_t* lenmem) {
    int i;
    kiss_fftr_cfg st = NULL;
    size_t subsize = 0, memneeded;

    if (nfft <= 0 || (nfft & 1)) {
        fprintf(stderr, "kiss_fftr_alloc: nfft must be even and > 0 (got %d)\n", nfft);
        return NULL;
    }
    nfft >>= 1;

    kiss_fft_alloc(nfft, inverse_fft, NULL, &subsize);
    memneeded = sizeof(struct kiss_fftr_state) + subsize + sizeof(kiss_fft_cpx) * (size_t)(nfft * 3 / 2);

    if (lenmem == NULL) {
        st = (kiss_fftr_cfg)KISS_FFT_MALLOC(memneeded);
    } else {
        if (mem != NULL && *lenmem >= memneeded) st = (kiss_fftr_cfg)mem;
        *lenmem = memneeded;
    }
    if (!st) return NULL;

    st->substate = (kiss_fft_cfg)(st + 1); /* just beyond the kiss_fftr_state struct */
    st->tmpbuf = (kiss_fft_cpx*)(((char*)st->substate) + subsize);
    st->super_twiddles = st->tmpbuf + nfft;
    kiss_fft_alloc(nfft, inverse_fft, st->substate, &subsize);

    for (i = 0; i < nfft / 2; ++i) {
        double phase = -3.14159265358979323846264338327950288 * ((double)(i + 1) / nfft + 0.5);
        if (inverse_fft) phase *= -1;
        kf_cexp2(st->super_twiddles + i, phase);
    }
    return st;
}

void kiss_fftr(kiss_fftr_cfg st, const kiss_fft_scalar* timedata, kiss_fft_cpx* freqdata) {
    /* input buffer timedata is stored row-wise */
    int k, ncfft;
    kiss_fft_cpx fpnk, fpk, f1k, f2k, tw, tdc;

    if (!st || kiss_fft_state_inverse(st->substate)) {
        fprintf(stderr, "kiss_fftr: bad cfg (must be allocated with inverse_fft=0)\n");
        return;
    }

    ncfft = kiss_fft_state_nfft(st->substate);

    /* perform the parallel FFT of two real signals packed in real,imag */
    kiss_fft(st->substate, (const kiss_fft_cpx*)timedata, st->tmpbuf);

    /* tmpbuf[0] holds DC(r) + Nyquist(i) of the full real sequence */
    tdc.r = st->tmpbuf[0].r;
    tdc.i = st->tmpbuf[0].i;
    C_FIXDIV(tdc, 2);
    freqdata[0].r = tdc.r + tdc.i;
    freqdata[ncfft].r = tdc.r - tdc.i;
    freqdata[ncfft].i = freqdata[0].i = 0;

    for (k = 1; k <= ncfft / 2; ++k) {
        fpk = st->tmpbuf[k];
        fpnk.r = st->tmpbuf[ncfft - k].r;
        fpnk.i = -st->tmpbuf[ncfft - k].i;
        C_FIXDIV(fpk, 2);
        C_FIXDIV(fpnk, 2);

        C_ADD(f1k, fpk, fpnk);
        C_SUB(f2k, fpk, fpnk);
        C_MUL(tw, f2k, st->super_twiddles[k - 1]);

        freqdata[k].r = HALF_OF(f1k.r + tw.r);
        freqdata[k].i = HALF_OF(f1k.i + tw.i);
        freqdata[ncfft - k].r = HALF_OF(f1k.r - tw.r);
        freqdata[ncfft - k].i = HALF_OF(tw.i - f1k.i);
    }
}

void kiss_fftri(kiss_fftr_cfg st, const kiss_fft_cpx* freqdata, kiss_fft_scalar* timedata) {
    /* input buffer freqdata is stored row-wise */
    int k, ncfft;

    if (!st || kiss_fft_state_inverse(st->substate) == 0) {
        fprintf(stderr, "kiss_fftri: bad cfg (must be allocated with inverse_fft=1)\n");
        return;
    }

    ncfft = kiss_fft_state_nfft(st->substate);

    st->tmpbuf[0].r = freqdata[0].r + freqdata[ncfft].r;
    st->tmpbuf[0].i = freqdata[0].r - freqdata[ncfft].r;
    C_FIXDIV(st->tmpbuf[0], 2);

    for (k = 1; k <= ncfft / 2; ++k) {
        kiss_fft_cpx fk, fnkc, fek, fok, tmp;
        fk = freqdata[k];
        fnkc.r = freqdata[ncfft - k].r;
        fnkc.i = -freqdata[ncfft - k].i;
        C_FIXDIV(fk, 2);
        C_FIXDIV(fnkc, 2);

        C_ADD(fek, fk, fnkc);
        C_SUB(tmp, fk, fnkc);
        C_MUL(fok, tmp, st->super_twiddles[k - 1]);
        C_ADD(st->tmpbuf[k], fek, fok);
        C_SUB(st->tmpbuf[ncfft - k], fek, fok);
        st->tmpbuf[ncfft - k].i *= -1;
    }
    kiss_fft(st->substate, st->tmpbuf, (kiss_fft_cpx*)timedata);
}

void kiss_fftr_free(kiss_fftr_cfg cfg) {
    /* substate/tmpbuf/super_twiddles live inside the same allocation */
    if (cfg) KISS_FFT_FREE(cfg);
}
