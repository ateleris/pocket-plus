/* pocketplus pocketbench adapter: per-packet hooks over the Stainless GenC output of
 * PocketPlus.scala, driven through the codec's own pp_* shim. The codec takes one bool per bit, so
 * the hooks unpack and pack every field and packet at the boundary. */
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "pp_shim.h"
#include "adapter_common.h"

/* Fields are MSB-first: bit i is bit 7-(i%8) of byte i/8. */
static void unpack_bits(const unsigned char *bytes, size_t nbits, bool *bits) {
    for (size_t i = 0; i < nbits; i++) bits[i] = (bytes[i >> 3] >> (7U - (i & 7U))) & 1U;
}

/* Writes ceil(nbits/8) bytes with zero padding bits. */
static void pack_bits(const bool *bits, size_t nbits, unsigned char *bytes) {
    memset(bytes, 0, (nbits + 7U) / 8U);
    for (size_t i = 0; i < nbits; i++) {
        if (bits[i]) bytes[i >> 3] |= (unsigned char)(0x80U >> (i & 7U));
    }
}

static struct {
    PPCompressor *c;
    bool *vec;
    bool *pkt;
    int32_t n;
    int32_t cap;
    size_t stride;
} g_enc;

static struct {
    PPDecompressor *d;
    bool *stream;
    size_t stream_cap;
    bool *vec;
    int32_t n;
} g_dec;

static void pp_enc_destroy(void *state);

/* The codec judges F first (pp_compressor_create rejects n outside 1..65535), then the mask. The
 * plumbing calls enc_destroy only after a successful create, so a failed one frees its own. */
static int pp_enc_create(void *ctx, int f_present, unsigned long f, const pb_record *mask,
                         void **state) {
    (void)ctx;
    if (!f_present || f > INT32_MAX) return PB_NOT_REPRESENTABLE;
    memset(&g_enc, 0, sizeof g_enc);
    g_enc.n = (int32_t)f;
    g_enc.c = pp_compressor_create(g_enc.n);
    if (!g_enc.c) return -1;
    g_enc.stride = (size_t)((f + 7UL) / 8UL);
    if (!mask || mask->len != g_enc.stride) {
        pp_enc_destroy(&g_enc);
        return PB_NOT_REPRESENTABLE;
    }
    g_enc.cap = pp_compress_output_capacity_bits(g_enc.n);
    g_enc.vec = (bool *)pb_xmalloc((size_t)g_enc.n * sizeof(bool));
    g_enc.pkt = (bool *)pb_xmalloc((size_t)g_enc.cap * sizeof(bool));
    unpack_bits(mask->data, (size_t)g_enc.n, g_enc.vec);
    pp_compressor_set_initial_mask(g_enc.c, g_enc.vec, g_enc.n);
    *state = &g_enc;
    return 0;
}

static int pp_enc_packet(void *state, unsigned char flag, const unsigned char *field, size_t len,
                         unsigned char *out, size_t out_cap, unsigned long *out_bits) {
    int32_t bits;
    (void)state;
    if (len != g_enc.stride) return PB_NOT_REPRESENTABLE;
    unpack_bits(field, (size_t)g_enc.n, g_enc.vec);
    bits = pp_compress(g_enc.c, g_enc.vec, g_enc.n, (int32_t)(flag & 0x0FU), (flag >> 5) & 1U,
                       (flag >> 6) & 1U, (flag >> 4) & 1U, g_enc.pkt, g_enc.cap);
    if (bits < 0) return (int)bits;
    if (((size_t)bits + 7U) / 8U > out_cap) return PB_OUTPUT_FULL;
    pack_bits(g_enc.pkt, (size_t)bits, out);
    *out_bits = (unsigned long)bits;
    return 0;
}

static void pp_enc_destroy(void *state) {
    (void)state;
    pp_compressor_free(g_enc.c);
    free(g_enc.vec);
    free(g_enc.pkt);
    memset(&g_enc, 0, sizeof g_enc);
}

static int pp_dec_create(void *ctx, unsigned long f, const pb_record *records, size_t n,
                         void **state) {
    (void)ctx;
    (void)records;
    (void)n;
    memset(&g_dec, 0, sizeof g_dec);
    *state = &g_dec;
    /* The codec cannot start with F unknown; every packet then reports failed. */
    if (f == 0) return 0;
    if (f > INT32_MAX) return PB_NOT_REPRESENTABLE;
    g_dec.n = (int32_t)f;
    g_dec.d = pp_decompressor_create(g_dec.n);
    if (!g_dec.d) return -1;
    g_dec.vec = (bool *)pb_xmalloc((size_t)g_dec.n * sizeof(bool));
    return 0;
}

static int pp_dec_packet(void *state, const unsigned char *data, size_t len,
                         unsigned long declared_bits, unsigned long avail_bits, unsigned char *out,
                         size_t out_cap, unsigned long *out_bits) {
    (void)state;
    (void)len;
    (void)declared_bits;
    (void)out_cap;
    if (!g_dec.d || avail_bits > INT32_MAX) return PB_FAILED;
    if (avail_bits > g_dec.stream_cap) {
        free(g_dec.stream);
        g_dec.stream_cap = avail_bits;
        g_dec.stream = (bool *)pb_xmalloc(g_dec.stream_cap * sizeof(bool));
    }
    unpack_bits(data, (size_t)avail_bits, g_dec.stream);
    if (pp_decompress(g_dec.d, g_dec.stream, (int32_t)avail_bits, 0, g_dec.vec, g_dec.n) < 0) {
        return PB_FAILED;
    }
    pack_bits(g_dec.vec, (size_t)g_dec.n, out);
    *out_bits = (unsigned long)g_dec.n;
    return PB_DECODED;
}

static void pp_dec_lost(void *state) { (void)state; }

static unsigned long pp_dec_trailer_f(void *state) {
    (void)state;
    return g_dec.d ? (unsigned long)g_dec.n : 0UL;
}

static void pp_dec_destroy(void *state) {
    (void)state;
    pp_decompressor_free(g_dec.d);
    free(g_dec.stream);
    free(g_dec.vec);
    memset(&g_dec, 0, sizeof g_dec);
}

int main(int argc, char **argv) {
    pb_adapter a;
    memset(&a, 0, sizeof a);
    a.caps.build_profile = "gcc -std=c99 -O3 -flto";
    a.caps.limitations =
        "bool-per-bit codec API (one bool per bit, as GenC emits it), so every call unpacks and "
        "packs bits around the codec and bench timings include that; packets up to 65535 bits; "
        "no packet-loss notification and no decoding with F unknown (every packet reports failed)";
    a.enc_create = pp_enc_create;
    a.enc_packet = pp_enc_packet;
    a.enc_destroy = pp_enc_destroy;
    a.dec_create = pp_dec_create;
    a.dec_packet = pp_dec_packet;
    a.dec_lost = pp_dec_lost;
    a.dec_trailer_f = pp_dec_trailer_f;
    a.dec_destroy = pp_dec_destroy;
    return pb_main(argc, argv, &a);
}
