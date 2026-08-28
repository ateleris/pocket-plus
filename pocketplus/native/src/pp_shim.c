/* pp_shim — see pp_shim.h. malloc-backed lifecycle around the generated codec. */
#include <stdlib.h>
#include "pp_shim.h"
#include "../generated/pocketplus.h"

struct PPCompressor   { CompressorState cs; };
struct PPDecompressor { DecompressorState ds; };

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
    CompressorState* s = &c->cs;
    s->n = n;
    s->maskNew      = mk(n);
    s->maskOld      = mk(n);
    s->maskBuildNew = mk(n);
    s->maskBuildOld = mk(n);
    s->inputOld     = mk(n);
    s->inputVectorLengthCount = mk(64);     /* COUNT(n) fits in <= 64 bits */
    s->inputVectorLengthCountLen = 0;
    s->maskChangeVector = mk(16 * n);        /* 16-deep history of length-n change vectors */
    s->maskChangeCount = 0;
    s->maskFlagCount = 0;                    /* maskFlag[16] is inline in the struct */
    s->t = 0;
    compressorInit(s);
    return c;
}

PP_API void pp_compressor_free(PPCompressor* c) {
    if (!c) return;
    CompressorState* s = &c->cs;
    free(s->maskNew.data);
    free(s->maskOld.data);
    free(s->maskBuildNew.data);
    free(s->maskBuildOld.data);
    free(s->inputOld.data);
    free(s->inputVectorLengthCount.data);
    free(s->maskChangeVector.data);
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
    if (n != c->cs.n) return -1;
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
    d->ds.n = n;
    d->ds.mask      = mk(n);
    d->ds.lastInput = mk(n);
    d->ds.t = 0;
    decompressorInit(&d->ds);
    return d;
}

PP_API void pp_decompressor_free(PPDecompressor* d) {
    if (!d) return;
    free(d->ds.mask.data);
    free(d->ds.lastInput.data);
    free(d);
}

PP_API int32_t pp_decompress(PPDecompressor* d, const bool* stream, int32_t stream_len,
                             int32_t bitOffset, bool* out, int32_t n) {
    if (!d || !stream || !out) return -1;
    if (n != d->ds.n) return -1;
    array_bool s; s.data = (bool*)stream; s.length = stream_len;
    array_bool o; o.data = out;           o.length = n;
    return decompress(&d->ds, s, bitOffset, o);
}
