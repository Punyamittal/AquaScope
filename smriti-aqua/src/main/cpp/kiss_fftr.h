/*
 * KISS FFT - real-valued FFT optimization (kiss_fftr).
 * Clean, self-contained minimal implementation, API-compatible with
 * KISS FFT by Mark Borgerding (BSD license, https://github.com/mborgerding/kissfft).
 *
 * Original project license: BSD-3-Clause. See ATTRIBUTION.md.
 */
#ifndef KISS_FFTR_H
#define KISS_FFTR_H

#include "kiss_fft.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kiss_fftr_state* kiss_fftr_cfg;

/*
 * Real-optimized FFT. nfft MUST be even.
 * The frequency output uses nfft/2+1 kiss_fft_cpx bins
 * (DC at index 0, Nyquist at index nfft/2).
 */
kiss_fftr_cfg kiss_fftr_alloc(int nfft, int inverse_fft, void* mem, size_t* lenmem);

/* forward: timedata[nfft] real -> freqdata[nfft/2+1] complex */
void kiss_fftr(kiss_fftr_cfg cfg, const kiss_fft_scalar* timedata, kiss_fft_cpx* freqdata);

/* inverse: freqdata[nfft/2+1] complex -> timedata[nfft] real.
 * NOTE: output is scaled by nfft (divide by nfft to get the true inverse). */
void kiss_fftri(kiss_fftr_cfg cfg, const kiss_fft_cpx* freqdata, kiss_fft_scalar* timedata);

/* Frees a cfg allocated by kiss_fftr_alloc (same block for all substate). */
void kiss_fftr_free(kiss_fftr_cfg cfg);

#ifdef __cplusplus
}
#endif

#endif /* KISS_FFTR_H */
