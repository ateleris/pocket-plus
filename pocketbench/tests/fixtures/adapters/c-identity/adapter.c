/* The shared C plumbing's test double: an identity "codec" with the same behaviour as
 * adapters/mock/adapter.py, so tests/test_plumbing.py holds every plumbing copy to one contract. */
#include <string.h>

#include "adapter_common.h"

static unsigned long g_f;

static int id_enc_create(void *ctx, int f_present, unsigned long f, const pb_record *mask,
                         void **state) {
    (void)ctx;
    (void)f;
    (void)mask;
    if (!f_present) return PB_NOT_REPRESENTABLE;
    *state = &g_f;
    return 0;
}

static int id_enc_packet(void *state, unsigned char flag, const unsigned char *field, size_t len,
                         unsigned char *out, size_t out_cap, unsigned long *out_bits) {
    (void)state;
    if (flag & 0x80U) return 7;
    if (len > out_cap) return PB_OUTPUT_FULL;
    memcpy(out, field, len);
    *out_bits = 8UL * len;
    return 0;
}

static void id_destroy(void *state) { (void)state; }

static int id_dec_create(void *ctx, unsigned long f, const pb_record *records, size_t n,
                         void **state) {
    (void)ctx;
    (void)records;
    (void)n;
    g_f = f;
    *state = &g_f;
    return 0;
}

static int id_dec_packet(void *state, const unsigned char *data, size_t len,
                         unsigned long declared_bits, unsigned long avail_bits, unsigned char *out,
                         size_t out_cap, unsigned long *out_bits) {
    (void)state;
    (void)avail_bits;
    if (declared_bits == 13UL) return -9;
    (void)out_cap;
    if (g_f == 0) return PB_FAILED;
    memcpy(out, data, len);
    *out_bits = 8UL * len;
    return PB_DECODED;
}

static void id_dec_lost(void *state) { (void)state; }

static unsigned long id_dec_trailer_f(void *state) {
    (void)state;
    return g_f;
}

int main(int argc, char **argv) {
    pb_adapter a;
    memset(&a, 0, sizeof a);
    a.caps.build_profile = "";
    a.caps.limitations = "identity test double: every packet is its own field";
    a.enc_create = id_enc_create;
    a.enc_packet = id_enc_packet;
    a.enc_destroy = id_destroy;
    a.dec_create = id_dec_create;
    a.dec_packet = id_dec_packet;
    a.dec_lost = id_dec_lost;
    a.dec_trailer_f = id_dec_trailer_f;
    a.dec_destroy = id_destroy;
    return pb_main(argc, argv, &a);
}
