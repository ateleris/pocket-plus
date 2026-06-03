/* pp_shim — see pp_shim.h. malloc-backed lifecycle around the generated codec. */
#include <stdlib.h>
#include "pp_shim.h"
#include "../generated/pocketplus.h"

struct PPCompressor   { CompressorState_0 cs; };
struct PPDecompressor { DecompressorState_0 ds; };

/* Allocate a zeroed bit buffer of length len (>=1 so calloc never gets 0). */
static array_bool mk(int32_t len) {
    array_bool a;
    a.length = len;
    a.data = (bool*)calloc((size_t)(len > 0 ? len : 1), sizeof(bool));
    return a;
}

PP_API int32_t pp_compress_output_capacity_bits(int32_t n) {
    return 4 + 64 * (n + 1);
}

/* ---- compressor ---- */

PP_API PPCompressor* pp_compressor_create(int32_t n) {
    if (n < 1 || n > 65535) return NULL;
    PPCompressor* c = (PPCompressor*)calloc(1, sizeof(*c));
    if (!c) return NULL;
    CompressorState_0* s = &c->cs;
    s->n_0 = n;
    s->maskNew_0      = mk(n);
    s->maskOld_0      = mk(n);
    s->maskBuildNew_0 = mk(n);
    s->maskBuildOld_0 = mk(n);
    s->inputOld_0     = mk(n);
    s->inputVectorLengthCount_0 = mk(64);     /* COUNT(n) fits in <= 64 bits */
    s->inputVectorLengthCountLen_0 = 0;
    s->maskChangeVector_0 = mk(16 * n);        /* 16-deep history of length-n change vectors */
    s->maskChangeCount_0 = 0;
    s->maskFlagCount_0 = 0;                    /* maskFlag_0[16] is inline in the struct */
    s->t_0 = 0;
    compressorInit(s);
    return c;
}

PP_API void pp_compressor_free(PPCompressor* c) {
    if (!c) return;
    CompressorState_0* s = &c->cs;
    free(s->maskNew_0.data);
    free(s->maskOld_0.data);
    free(s->maskBuildNew_0.data);
    free(s->maskBuildOld_0.data);
    free(s->inputOld_0.data);
    free(s->inputVectorLengthCount_0.data);
    free(s->maskChangeVector_0.data);
    free(c);
}

PP_API void pp_compressor_set_initial_mask(PPCompressor* c, const bool* mask, int32_t n) {
    if (!c || !mask) return;
    array_bool m;
    m.data = (bool*)mask;   /* setInitialMask copies; it does not retain or mutate the input */
    m.length = n;
    setInitialMask(&c->cs, m);
}

PP_API int32_t pp_compress(PPCompressor* c, const bool* input, int32_t n,
                           int32_t robustnessLevel, bool newMaskFlag,
                           bool sendMaskFlag, bool uncompressedFlag,
                           bool* out, int32_t out_cap) {
    if (!c || !input || !out) return -1;
    if (n != c->cs.n_0) return -1;
    if (out_cap < pp_compress_output_capacity_bits(n)) return -1;
    array_bool in_;  in_.data = (bool*)input; in_.length = n;
    array_bool out_; out_.data = out;          out_.length = out_cap;
    return compress(&c->cs, in_, robustnessLevel, newMaskFlag, sendMaskFlag, uncompressedFlag, out_);
}

/* ---- decompressor ---- */

PP_API PPDecompressor* pp_decompressor_create(int32_t n) {
    if (n < 1 || n > 65535) return NULL;
    PPDecompressor* d = (PPDecompressor*)calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->ds.n_3 = n;
    d->ds.mask_0      = mk(n);
    d->ds.lastInput_0 = mk(n);
    d->ds.t_3 = 0;
    decompressorInit(&d->ds);
    return d;
}

PP_API void pp_decompressor_free(PPDecompressor* d) {
    if (!d) return;
    free(d->ds.mask_0.data);
    free(d->ds.lastInput_0.data);
    free(d);
}

PP_API int32_t pp_decompress(PPDecompressor* d, const bool* stream, int32_t stream_len,
                             int32_t bitOffset, bool* out, int32_t n) {
    if (!d || !stream || !out) return -1;
    if (n != d->ds.n_3) return -1;
    array_bool s; s.data = (bool*)stream; s.length = stream_len;
    array_bool o; o.data = out;           o.length = n;
    return decompress(&d->ds, s, bitOffset, o);
}
