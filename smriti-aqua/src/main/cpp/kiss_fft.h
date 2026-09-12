/*
 * KISS FFT - A mixed-radix Fast Fourier Transform.
 * Clean, self-contained minimal implementation, API-compatible with
 * KISS FFT by Mark Borgerding (BSD license, https://github.com/mborgerding/kissfft).
 * Vendored for SMRITI AQUA with kiss_fft_scalar = float.
 *
 * Original project license: BSD-3-Clause. See ATTRIBUTION.md.
 */
#ifndef KISS_FFT_H
#define KISS_FFT_H

#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef kiss_fft_scalar
#define kiss_fft_scalar float
#endif

typedef struct {
    kiss_fft_scalar r;
    kiss_fft_scalar i;
} kiss_fft_cpx;

typedef struct kiss_fft_state* kiss_fft_cfg;

/*
 * nfft must be > 0. Factorization is done at alloc time.
 * If mem is NULL, the state is malloc'd. Otherwise *lenmem must carry the
 * buffer size on input and receives the required size on output.
 */
kiss_fft_cfg kiss_fft_alloc(int nfft, int inverse_fft, void* mem, size_t* lenmem);

/* fin and fout may point to the same buffer (a temp buffer is used). */
void kiss_fft(kiss_fft_cfg cfg, const kiss_fft_cpx* fin, kiss_fft_cpx* fout);

void kiss_fft_free(kiss_fft_cfg cfg);

/* Kept for API compatibility with upstream KISS FFT (no-op here). */
void kiss_fft_cleanup(void);

/* Internal accessors (used by kiss_fftr; the state struct stays private). */
int kiss_fft_state_nfft(kiss_fft_cfg cfg);
int kiss_fft_state_inverse(kiss_fft_cfg cfg);

#ifdef __cplusplus
}
#endif

#endif /* KISS_FFT_H */
