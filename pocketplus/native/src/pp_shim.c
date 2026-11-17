/* pp_shim — see pp_shim.h. malloc-backed lifecycle around the generated codec. */
#include <stdlib.h>
#include "pp_shim.h"
#include "../generated/pocketplus.h"

/* Values from PocketImperative.scala that GenC does not export; keep them in sync. */
#define MAX_F           65535      /* Layout.MaxF */
#define MAX_R           7          /* Layout.MaxR */
#define HISTORY_VECTORS 7          /* Layout.HistoryVectors */
#define MAX_PACKET_BITS 2147483640 /* largest BitArray: 8 * (INT32_MAX / 8) bits */

struct PPCompressor   { EncoderState st; };
struct PPDecompressor { DecoderState st; };

/* The generated code takes every bit vector as a BitArray: a byte buffer and a size in bits, MSB
   first. The Scala side requires each one to be valid, a ghost condition GenC cannot turn into an
   assert, so the two constructors below are what establishes it: 0 <= bits <= 8 * length, and
   length <= INT32_MAX / 8 (which bits <= MAX_PACKET_BITS gives). */

/* A zeroed bit array of `bits` bits in (bits + 7) / 8 >= 1 bytes; data.data is NULL if the
   allocation failed. */
static BitArray alloc_bits(int32_t bits) {
    BitArray a;
    a.data.length = (bits + 7) >> 3;
    a.data.data = (uint8_t*)calloc((size_t)a.data.length, 1);
    a._isize = bits;
    return a;
}

/* View of the first `bits` bits of a caller buffer of (bits + 7) / 8 bytes. Input views are never
   written by the generated code. */
static BitArray view(const uint8_t* p, int32_t bits) {
    BitArray a;
    a.data.data = (uint8_t*)p;
    a.data.length = (bits + 7) >> 3;
    a._isize = bits;
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
    free(s->lastInput.data.data);
    free(s->mask.data.data);
    free(s->build.data.data);
    free(s->changeHistory.data.data);
    free(s->xt.data.data);
    free(s->yt.data.data);
    free(c);
}

PP_API PPCompressor* pp_compressor_create(int32_t f, const uint8_t* initial_mask) {
    if (f < 1 || f > MAX_F) return NULL;
    int32_t nb = bytesPerVector(f);
    PPCompressor* c = (PPCompressor*)calloc(1, sizeof(*c));
    if (!c) return NULL;
    EncoderState* s = &c->st;
    s->f = f;
    s->lastInput         = alloc_bits(f);
    s->mask              = alloc_bits(f);
    s->build             = alloc_bits(f);
    /* HISTORY_VECTORS slots of Layout.bitsPerSlot(f) = 8 * nb bits, so each starts on a byte. */
    s->changeHistory     = alloc_bits(HISTORY_VECTORS * 8 * nb);
    s->xt                = alloc_bits(f);
    s->yt                = alloc_bits(f);
    /* encoderInit copies M_0 into the state, so the all-zero default only lives for the call. */
    BitArray m0 = initial_mask ? view(initial_mask, f) : alloc_bits(f);
    if (!s->lastInput.data.data || !s->mask.data.data || !s->build.data.data ||
        !s->changeHistory.data.data || !s->xt.data.data || !s->yt.data.data ||
        !m0.data.data) {
        if (!initial_mask) free(m0.data.data);
        pp_compressor_free(c);
        return NULL;
    }
    encoderInit(s, &m0);
    if (!initial_mask) free(m0.data.data);
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
    BitArray in_bits = view(input, c->st.f);
    BitArray out_bits = view(out, cap);
    return encodeStep(&c->st, &in_bits, new_mask_flag, send_mask_flag, uncompressed_flag,
                      robustness, &out_bits);
}

/* ---- decompressor ---- */

PP_API void pp_decompressor_free(PPDecompressor* d) {
    if (!d) return;
    DecoderState* s = &d->st;
    free(s->lastInput.data.data);
    free(s->mask.data.data);
    free(s->xt.data.data);
    free(s->sentMask.data.data);
    free(d);
}

PP_API PPDecompressor* pp_decompressor_create(int32_t f) {
    if (f < 1 || f > MAX_F) return NULL;
    PPDecompressor* d = (PPDecompressor*)calloc(1, sizeof(*d));
    if (!d) return NULL;
    DecoderState* s = &d->st;
    s->f = f;
    s->lastInput = alloc_bits(f);
    s->mask      = alloc_bits(f);
    s->xt        = alloc_bits(f);
    s->sentMask  = alloc_bits(f);
    if (!s->lastInput.data.data || !s->mask.data.data || !s->xt.data.data ||
        !s->sentMask.data.data) {
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
    BitArray packet_view = view(packet, packet_bits);
    BitArray out_bits = view(out, d->st.f);
    return decodeStep(&d->st, &packet_view, &out_bits);
}
