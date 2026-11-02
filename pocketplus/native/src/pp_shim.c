/* pp_shim — see pp_shim.h. malloc-backed lifecycle around the generated codec. */
#include <stdlib.h>
#include "pp_shim.h"
#include "../generated/pocketplus.h"

/* Values from PocketImperative.scala that GenC does not export; keep them in sync. */
#define MAX_F           65535      /* Layout.MaxF */
#define MAX_R           7          /* Layout.MaxR */
#define HISTORY_VECTORS 7          /* Layout.HistoryVectors */
#define MAX_PACKET_BITS 2147483640 /* bound on decodeStep's packetBits */

struct PPCompressor   { EncoderState st; };
struct PPDecompressor { DecoderState st; };

/* A zeroed buffer of len >= 1 bytes; data is NULL if the allocation failed. */
static array_uint8 alloc_bytes(int32_t len) {
    array_uint8 a;
    a.data = (uint8_t*)calloc((size_t)len, 1);
    a.length = len;
    return a;
}

static array_bool alloc_bools(int32_t len) {
    array_bool a;
    a.data = (bool*)calloc((size_t)len, sizeof(bool));
    a.length = len;
    return a;
}

/* View of a caller buffer of len bytes. Input views are never written by the generated code. */
static array_uint8 view(const uint8_t* p, int32_t len) {
    array_uint8 a;
    a.data = (uint8_t*)p;
    a.length = len;
    return a;
}

PP_API int32_t pp_bytes_per_vector(int32_t f) {
    if (f < 1 || f > MAX_F) return PP_ERR_ARGUMENT;
    return bytesPerVector(f);
}

PP_API int32_t pp_compress_output_capacity_bits(int32_t f) {
    if (f < 1 || f > MAX_F) return PP_ERR_ARGUMENT;
    return maxOutputBits(f);
}

/* ---- compressor ---- */

PP_API void pp_compressor_free(PPCompressor* c) {
    if (!c) return;
    EncoderState* s = &c->st;
    free(s->lastInput.data);
    free(s->mask.data);
    free(s->build.data);
    free(s->changeHistory.data);
    free(s->changeHistoryZero.data);
    free(s->xt.data);
    free(s->yt.data);
    free(c);
}

PP_API PPCompressor* pp_compressor_create(int32_t f, const uint8_t* initial_mask) {
    if (f < 1 || f > MAX_F) return NULL;
    int32_t nb = bytesPerVector(f);
    PPCompressor* c = (PPCompressor*)calloc(1, sizeof(*c));
    if (!c) return NULL;
    EncoderState* s = &c->st;
    s->f = f;
    s->lastInput         = alloc_bytes(nb);
    s->mask              = alloc_bytes(nb);
    s->build             = alloc_bytes(nb);
    s->changeHistory     = alloc_bytes(HISTORY_VECTORS * nb);
    s->changeHistoryZero = alloc_bools(HISTORY_VECTORS);
    s->xt                = alloc_bytes(nb);
    s->yt                = alloc_bytes(nb);
    /* encoderInit copies M_0 into the state, so the all-zero default only lives for the call. */
    array_uint8 m0 = initial_mask ? view(initial_mask, nb) : alloc_bytes(nb);
    if (!s->lastInput.data || !s->mask.data || !s->build.data || !s->changeHistory.data ||
        !s->changeHistoryZero.data || !s->xt.data || !s->yt.data || !m0.data) {
        if (!initial_mask) free(m0.data);
        pp_compressor_free(c);
        return NULL;
    }
    encoderInit(s, m0);
    if (!initial_mask) free(m0.data);
    return c;
}

PP_API int32_t pp_compress(PPCompressor* c, const uint8_t* input, int32_t robustness,
                           bool new_mask_flag, bool send_mask_flag, bool uncompressed_flag,
                           uint8_t* out, int32_t out_capacity_bits) {
    if (!c || !input || !out) return PP_ERR_ARGUMENT;
    if (robustness < 0 || robustness > MAX_R) return PP_ERR_ARGUMENT;
    /* blue book 3.3.2 c) and d) */
    if (c->st.t <= robustness && !(send_mask_flag && uncompressed_flag)) return PP_ERR_ARGUMENT;
    if (out_capacity_bits < 0) return PP_ERR_ARGUMENT;
    /* No vector is longer than the worst case, and encodeStep requires a capacity within it. */
    int32_t max_bits = maxOutputBits(MAX_F);
    int32_t cap = out_capacity_bits < max_bits ? out_capacity_bits : max_bits;
    return encodeStep(&c->st, view(input, bytesPerVector(c->st.f)), new_mask_flag, send_mask_flag,
                      uncompressed_flag, robustness, view(out, (cap + 7) >> 3), cap);
}

/* ---- decompressor ---- */

PP_API void pp_decompressor_free(PPDecompressor* d) {
    if (!d) return;
    DecoderState* s = &d->st;
    free(s->lastInput.data);
    free(s->mask.data);
    free(s->xt.data);
    free(s->sentMask.data);
    free(d);
}

PP_API PPDecompressor* pp_decompressor_create(int32_t f) {
    if (f < 1 || f > MAX_F) return NULL;
    int32_t nb = bytesPerVector(f);
    PPDecompressor* d = (PPDecompressor*)calloc(1, sizeof(*d));
    if (!d) return NULL;
    DecoderState* s = &d->st;
    s->f = f;
    s->lastInput = alloc_bytes(nb);
    s->mask      = alloc_bytes(nb);
    s->xt        = alloc_bytes(nb);
    s->sentMask  = alloc_bytes(nb);
    if (!s->lastInput.data || !s->mask.data || !s->xt.data || !s->sentMask.data) {
        pp_decompressor_free(d);
        return NULL;
    }
    decoderInit(s);
    return d;
}

PP_API int32_t pp_decompress(PPDecompressor* d, const uint8_t* packet, int32_t packet_bits,
                             uint8_t* out) {
    if (!d || !packet || !out) return PP_ERR_ARGUMENT;
    if (packet_bits < 0 || packet_bits > MAX_PACKET_BITS) return PP_ERR_ARGUMENT;
    return decodeStep(&d->st, view(packet, (packet_bits + 7) >> 3), packet_bits,
                      view(out, bytesPerVector(d->st.f)));
}
