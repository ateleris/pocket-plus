/* Shared pocketbench adapter plumbing for the C and C++ adapters: subcommand dispatch, keyed-flag
 * parsing, the capabilities JSON, file IO, output sizing, the warmup+timed loop and the raw-nanos
 * payload. An adapter supplies codec hooks plus its declarative capability facts. */
#ifndef PB_ADAPTER_COMMON_H
#define PB_ADAPTER_COMMON_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* A codec ignores any field it does not use. */
typedef struct {
    unsigned long packet_bits; /* F (large_f) in bits, 1..65535; stride is ceil(F/8) bytes */
    long pt;
    long ft;
    long rt;
    long robustness;
} pb_params;

/* The `ops` list and the two conformance flags are not here: pb_main derives them from which hooks
 * are set, so a claim of support cannot drift from the code that implements it. */
typedef struct {
    const char *timing_tier;    /* "in_process" | "subprocess" */
    int reference_conformant;   /* compressed output is byte-identical to the ESA reference */
    const char *param_schedule; /* free label, e.g. "pt_ft_rt" */
    const char *build_profile;  /* NULL for "" */
    const char *limitations;    /* NULL for "" */
} pb_caps;

/* Run one op into `out`, which pb_main sizes and owns so a timed iteration allocates nothing. Any
 * per-call codec setup belongs inside the hook: a real caller pays it, so it is timed.
 * Returns 0, or a codec error code. */
typedef int (*pb_run_fn)(void *ctx, const unsigned char *in, size_t in_size, const pb_params *p,
                         unsigned char *out, size_t out_cap, size_t *out_size);

/* Run one UAB/CNES vector. The suite expects an empty output file rather than an error for a
 * malformed vector, which is codec-specific, so the hook does its own IO. Returns 0 on success. */
typedef int (*pb_conformance_fn)(void *ctx, const char *in_path, const char *out_path);

/* A NULL hook means the impl does not support that op: its capability flag is reported false and
 * the subcommand exits 2. */
typedef struct {
    pb_caps caps;
    void *ctx; /* opaque, handed back to every hook */
    pb_run_fn compress;
    pb_run_fn decompress;
    pb_conformance_fn conformance_compress;
    pb_conformance_fn conformance_decompress;
} pb_adapter;

/* Returns the process exit code: 0 ok, 1 runtime error, 2 usage error. */
int pb_main(int argc, char **argv, const pb_adapter *a);

unsigned char *pb_read_file(const char *path, size_t *size); /* malloc'd; NULL on failure */
int pb_write_file(const char *path, const void *data, size_t size); /* 0 on success */

#ifdef __cplusplus
}
#endif

#endif /* PB_ADAPTER_COMMON_H */
