/* reference-c pocketbench adapter: per-packet hooks over the ccsds124 C codec, compiled together
 * with the codec sources so CCSDS124_MAX_PACKET_LENGTH is consistent. */
#include <stdint.h>
#include <string.h>

#include "ccsds124.h"
#include "adapter_common.h"

/* Stringize the compile-time packet-length cap so `limitations` reports the value this binary was
 * built with: bench builds it per dataset size, validate/profile/conformance at 65535. */
#define ADAPTER_STR2(x) #x
#define ADAPTER_STR(x) ADAPTER_STR2(x)

/* Static: the codec state is sized by CCSDS124_MAX_PACKET_LENGTH and too large for the stack at
 * the 65535-bit build. */
static struct {
    ccsds124_compressor_t comp;
    bitvector_t vec;
    bitbuffer_t packet;
    size_t f;
    size_t field_bytes;
} g_enc;

static struct {
    ccsds124_decompressor_t decomp;
    bitvector_t vec;
    int f_known;
    uint32_t f;
    uint32_t weak_f;
} g_dec;

static int rc_enc_create(void *ctx, int f_present, unsigned long f, const pb_record *mask,
                         void **state) {
    int rc;
    (void)ctx;
    if (!f_present) return PB_NOT_REPRESENTABLE;
    rc = bitvector_init(&g_enc.vec, (size_t)f);
    if (rc != CCSDS124_OK) return rc;
    g_enc.f = (size_t)f;
    g_enc.field_bytes = (size_t)((f + 7UL) / 8UL);
    if (!mask || mask->len != g_enc.field_bytes) return PB_NOT_REPRESENTABLE;
    rc = bitvector_from_bytes(&g_enc.vec, mask->data, mask->len);
    if (rc != CCSDS124_OK) return rc;
    rc = ccsds124_compressor_init(&g_enc.comp, g_enc.f, &g_enc.vec, 0, 0, 0, 0);
    if (rc != CCSDS124_OK) return rc;
    *state = &g_enc;
    return 0;
}

/* Mirrors the per-packet body of the codec's own ccsds124_compress, with explicit flags. */
static int rc_enc_packet(void *state, unsigned char flag, const unsigned char *field, size_t len,
                         unsigned char *out, size_t out_cap, unsigned long *out_bits) {
    ccsds124_params_t params;
    size_t bytes;
    int rc;
    (void)state;
    if (len != g_enc.field_bytes) return PB_NOT_REPRESENTABLE;
    (void)bitvector_init(&g_enc.vec, g_enc.f);
    bitbuffer_init(&g_enc.packet);
    rc = bitvector_from_bytes(&g_enc.vec, field, len);
    if (rc != CCSDS124_OK) return rc;
    params.min_robustness = (uint8_t)(flag & 0x0FU);
    params.send_mask_flag = (uint8_t)((flag >> 6) & 1U);
    params.new_mask_flag = (uint8_t)((flag >> 5) & 1U);
    params.uncompressed_flag = (uint8_t)((flag >> 4) & 1U);
    g_enc.comp.robustness = params.min_robustness;
    rc = ccsds124_compress_packet(&g_enc.comp, &g_enc.vec, &g_enc.packet, &params);
    if (rc != CCSDS124_OK) return rc;
    bytes = (g_enc.packet.num_bits + 7U) / 8U;
    if (bytes > out_cap) return PB_OUTPUT_FULL;
    (void)bitbuffer_to_bytes(&g_enc.packet, out, bytes);
    *out_bits = (unsigned long)g_enc.packet.num_bits;
    return 0;
}

static void rc_destroy(void *state) { (void)state; }

/* With F unknown, discover it the way the codec's own crossvalidation decoder does: the first
 * strictly-discovered F wins; otherwise remember the first truncated reference's signaled F. */
static int rc_dec_create(void *ctx, unsigned long f, const pb_record *records, size_t n,
                         void **state) {
    size_t i;
    int rc;
    (void)ctx;
    g_dec.f_known = 0;
    g_dec.f = 0;
    g_dec.weak_f = 0;
    *state = &g_dec;
    if (f == 0) {
        for (i = 0; i < n && f == 0; i++) {
            const pb_record *r = &records[i];
            unsigned long present = (unsigned long)r->len * 8UL;
            uint32_t found = 0;
            if (r->kind & 1U) continue;
            rc = ccsds124_discover_packet_length(r->data, present < r->bits ? present : r->bits,
                                                 &found);
            if (rc == CCSDS124_OK && found > 0U) {
                f = found;
            } else if (rc == CCSDS124_STATUS_TRUNCATED_LENGTH && found > 0U && g_dec.weak_f == 0U) {
                g_dec.weak_f = found;
            }
        }
        if (f == 0) return 0;
    }
    rc = ccsds124_decompressor_init(&g_dec.decomp, (size_t)f, NULL, 0);
    if (rc != CCSDS124_OK) return rc;
    g_dec.f_known = 1;
    g_dec.f = (uint32_t)f;
    return 0;
}

static int rc_dec_packet(void *state, const unsigned char *data, size_t len,
                         unsigned long declared_bits, unsigned long avail_bits, unsigned char *out,
                         size_t out_cap, unsigned long *out_bits) {
    (void)state;
    (void)len;
    (void)declared_bits;
    (void)out_cap;
    if (!g_dec.f_known) return PB_FAILED;
    if (ccsds124_decompress_packet_checked(&g_dec.decomp, data, (size_t)avail_bits, &g_dec.vec,
                                           NULL) != CCSDS124_OK) {
        return PB_FAILED;
    }
    (void)bitvector_to_bytes(&g_dec.vec, out, (g_dec.f + 7U) / 8U);
    *out_bits = g_dec.f;
    return PB_DECODED;
}

static void rc_dec_lost(void *state) {
    (void)state;
    if (g_dec.f_known) (void)ccsds124_decompressor_notify_packet_loss(&g_dec.decomp, 1);
}

static unsigned long rc_dec_trailer_f(void *state) {
    (void)state;
    return g_dec.f_known ? g_dec.f : g_dec.weak_f;
}

int main(int argc, char **argv) {
    pb_adapter a;
    memset(&a, 0, sizeof a);
    a.caps.build_profile = "gcc -std=c99 -O3 -flto";
    a.caps.limitations =
        "packets up to CCSDS124_MAX_PACKET_LENGTH=" ADAPTER_STR(CCSDS124_MAX_PACKET_LENGTH)
        " bits (compile-time struct sizing); the codec rejects a larger F";
    a.enc_create = rc_enc_create;
    a.enc_packet = rc_enc_packet;
    a.enc_destroy = rc_destroy;
    a.dec_create = rc_dec_create;
    a.dec_packet = rc_dec_packet;
    a.dec_lost = rc_dec_lost;
    a.dec_trailer_f = rc_dec_trailer_f;
    a.dec_destroy = rc_destroy;
    return pb_main(argc, argv, &a);
}
