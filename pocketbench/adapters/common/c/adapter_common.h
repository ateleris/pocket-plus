/* Shared pocketbench adapter plumbing for the C and C++ adapters: subcommand dispatch, strict
 * keyed-flag parsing, the record container, the warmup + timed loop and the summary JSON. An
 * adapter supplies per-packet codec hooks plus two report strings. */
#ifndef PB_ADAPTER_COMMON_H
#define PB_ADAPTER_COMMON_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* A hook returns this when the codec's API cannot take the input at all (a value its types cannot
 * hold, a field of the wrong length), without calling the codec. */
#define PB_NOT_REPRESENTABLE (-100000)
/* A compress hook returns this when `out_cap` is too small: a harness limit, not a codec result. */
#define PB_OUTPUT_FULL (-100001)

/* Decompress output kinds. */
#define PB_DECODED 0x00
#define PB_FAILED  0x01
#define PB_LOST    0x02

typedef struct {
    unsigned char kind;
    unsigned long bits;        /* declared length */
    const unsigned char *data; /* the bytes physically present */
    size_t len;
} pb_record;

typedef struct {
    const char *build_profile; /* NULL for "" */
    const char *limitations;   /* NULL for "" */
} pb_caps;

/* Every hook is required. A hook returns 0 or an error code; the plumbing stops a compress pass at
 * the first error and reports it as "codec error <code>". */
typedef struct {
    pb_caps caps;
    void *ctx; /* opaque, handed back to the create hooks */

    /* `f_present` is 0 when the input is shorter than its 4-byte header; `mask` is NULL when the
     * input holds no mask record. */
    int (*enc_create)(void *ctx, int f_present, unsigned long f, const pb_record *mask,
                      void **state);
    /* Writes the packet to out[0..out_cap) and its length in bits to *out_bits. */
    int (*enc_packet)(void *state, unsigned char flag, const unsigned char *field, size_t len,
                      unsigned char *out, size_t out_cap, unsigned long *out_bits);
    void (*enc_destroy)(void *state);

    /* `f` is 0 when unknown; `records` is the whole input, for codecs that pre-scan it. */
    int (*dec_create)(void *ctx, unsigned long f, const pb_record *records, size_t n,
                      void **state);
    /* Returns PB_DECODED (field in out, its length in *out_bits), PB_FAILED, or a negative error
     * code, which stops the pass at this record. `avail_bits` is min(declared_bits, 8 * len), the
     * bits actually present; out_cap is at least max(8256, len). */
    int (*dec_packet)(void *state, const unsigned char *data, size_t len,
                      unsigned long declared_bits, unsigned long avail_bits, unsigned char *out,
                      size_t out_cap, unsigned long *out_bits);
    void (*dec_lost)(void *state);
    unsigned long (*dec_trailer_f)(void *state);
    void (*dec_destroy)(void *state);
} pb_adapter;

/* Returns the process exit code: 0 ok, 1 runtime error, 2 contract violation. */
int pb_main(int argc, char **argv, const pb_adapter *a);

/* malloc that exits 1 with a message instead of returning NULL. */
void *pb_xmalloc(size_t n);

#ifdef __cplusplus
}
#endif

#endif /* PB_ADAPTER_COMMON_H */
