/* Shared pocketbench adapter plumbing: see adapter_common.h.
 *
 * Defines its own POSIX level for clock_gettime so an adapter's Makefile does not have to. */
#if !defined(_POSIX_C_SOURCE) || (_POSIX_C_SOURCE - 0) < 199309L
#  undef _POSIX_C_SOURCE
#  define _POSIX_C_SOURCE 199309L
#endif

#include "adapter_common.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* CCSDS 124.0-B-1 allows F in 1..=65535. */
#define PB_MAX_PACKET_BITS 65535UL

#define PB_COMPRESS_GROWTH   12U
#define PB_DECOMPRESS_GROWTH 20U
#define PB_OUTPUT_SLACK      4096U

/* --- file IO ---------------------------------------------------------------------------------- */

unsigned char *pb_read_file(const char *path, size_t *size) {
    FILE *f;
    long n;
    unsigned char *buf;
    size_t got;

    *size = 0;
    f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    n = ftell(f);
    if (n < 0 || fseek(f, 0, SEEK_SET) != 0) { fclose(f); return NULL; }
    buf = (unsigned char *)malloc((size_t)n ? (size_t)n : 1U);
    if (!buf) { fclose(f); return NULL; }
    got = (size_t)n ? fread(buf, 1, (size_t)n, f) : 0U;
    fclose(f);
    if (got != (size_t)n) { free(buf); return NULL; }
    *size = (size_t)n;
    return buf;
}

int pb_write_file(const char *path, const void *data, size_t size) {
    FILE *f = fopen(path, "wb");
    size_t put;
    if (!f) return -1;
    put = size ? fwrite(data, 1, size, f) : 0U;
    if (fclose(f) != 0) return -1;
    return put == size ? 0 : -1;
}

/* --- keyed-flag parsing ----------------------------------------------------------------------- */

typedef enum { PB_STR, PB_LONG, PB_ULONG } pb_kind;

typedef struct {
    const char *key;
    pb_kind kind;
    void *dst;
    int seen;
} pb_flag;

#define PB_FLAG(idx, k, kd, d) \
    do { flags[idx].key = (k); flags[idx].kind = (kd); flags[idx].dst = (d); flags[idx].seen = 0; } \
    while (0)

/* Parse `--key=value` flags from argv[first..). Every flag in the table is required, unknown keys
 * are rejected, and a value must parse completely. Returns 0, or 2 with a message on stderr. */
static int pb_parse_flags(const char *sub, int argc, char **argv, int first, pb_flag *flags,
                          size_t n) {
    int i;
    size_t k;

    for (i = first; i < argc; i++) {
        const char *arg = argv[i];
        const char *eq;
        const char *val;
        size_t klen;
        int matched = 0;

        if (arg[0] != '-' || arg[1] != '-') {
            fprintf(stderr, "%s: unexpected positional argument \"%s\"; the contract passes keyed "
                            "flags (--key=value)\n", sub, arg);
            return 2;
        }
        eq = strchr(arg + 2, '=');
        if (!eq) {
            fprintf(stderr, "%s: flag \"%s\" needs a value, written --key=value\n", sub, arg);
            return 2;
        }
        klen = (size_t)(eq - (arg + 2));
        val = eq + 1;
        for (k = 0; k < n; k++) {
            if (strlen(flags[k].key) == klen && strncmp(flags[k].key, arg + 2, klen) == 0) {
                matched = 1;
                break;
            }
        }
        if (!matched) {
            fprintf(stderr, "%s: unknown flag --%.*s; this adapter does not implement it, so the "
                            "harness and the adapter disagree about the contract\n",
                    sub, (int)klen, arg + 2);
            return 2;
        }
        if (flags[k].seen) {
            fprintf(stderr, "%s: flag --%s given more than once\n", sub, flags[k].key);
            return 2;
        }
        flags[k].seen = 1;

        if (flags[k].kind == PB_STR) {
            if (!*val) {
                fprintf(stderr, "%s: flag --%s needs a non-empty value\n", sub, flags[k].key);
                return 2;
            }
            *(const char **)flags[k].dst = val;
        } else {
            char *end = NULL;
            errno = 0;
            if (flags[k].kind == PB_ULONG) {
                unsigned long v;
                if (*val == '-') {
                    fprintf(stderr, "%s: flag --%s must be a non-negative integer, got \"%s\"\n",
                            sub, flags[k].key, val);
                    return 2;
                }
                v = strtoul(val, &end, 10);
                if (errno != 0 || end == val || *end) {
                    fprintf(stderr, "%s: flag --%s must be a non-negative integer, got \"%s\"\n",
                            sub, flags[k].key, val);
                    return 2;
                }
                *(unsigned long *)flags[k].dst = v;
            } else {
                long v = strtol(val, &end, 10);
                if (errno != 0 || end == val || *end) {
                    fprintf(stderr, "%s: flag --%s must be an integer, got \"%s\"\n",
                            sub, flags[k].key, val);
                    return 2;
                }
                *(long *)flags[k].dst = v;
            }
        }
    }

    for (k = 0; k < n; k++) {
        if (!flags[k].seen) {
            fprintf(stderr, "%s: missing required flag --%s\n", sub, flags[k].key);
            return 2;
        }
    }
    return 0;
}

/* Fills flags[base..base+5). */
static void pb_param_flags(pb_flag *flags, size_t base, pb_params *p) {
    flags += base;
    PB_FLAG(0, "packet-bits", PB_ULONG, &p->packet_bits);
    PB_FLAG(1, "pt", PB_LONG, &p->pt);
    PB_FLAG(2, "ft", PB_LONG, &p->ft);
    PB_FLAG(3, "rt", PB_LONG, &p->rt);
    PB_FLAG(4, "robustness", PB_LONG, &p->robustness);
}

static int pb_check_packet_bits(const char *sub, const pb_params *p) {
    if (p->packet_bits < 1UL || p->packet_bits > PB_MAX_PACKET_BITS) {
        fprintf(stderr, "%s: --packet-bits must be 1..%lu (F, the packet field width in bits)\n",
                sub, PB_MAX_PACKET_BITS);
        return 2;
    }
    return 0;
}

/* --- capabilities ----------------------------------------------------------------------------- */

static void pb_json_str(const char *s) {
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    putchar('"');
    for (; *p; p++) {
        if (*p == '"' || *p == '\\') {
            putchar('\\');
            putchar((int)*p);
        } else if (*p < 0x20U) {
            printf("\\u%04x", (unsigned int)*p);
        } else {
            putchar((int)*p);
        }
    }
    putchar('"');
}

static void pb_print_capabilities(const pb_adapter *a) {
    printf("{\"ops\":[");
    if (a->compress) printf("\"compress\"");
    if (a->decompress) printf("%s\"decompress\"", a->compress ? "," : "");
    printf("],\"timing_tier\":");
    pb_json_str(a->caps.timing_tier ? a->caps.timing_tier : "in_process");
    printf(",\"reference_conformant\":%s", a->caps.reference_conformant ? "true" : "false");
    printf(",\"conformance_compress\":%s", a->conformance_compress ? "true" : "false");
    printf(",\"conformance_decompress\":%s", a->conformance_decompress ? "true" : "false");
    printf(",\"param_schedule\":");
    pb_json_str(a->caps.param_schedule);
    printf(",\"build_profile\":");
    pb_json_str(a->caps.build_profile);
    printf(",\"limitations\":");
    pb_json_str(a->caps.limitations);
    printf("}\n");
}

/* --- compress / decompress -------------------------------------------------------------------- */

/* `fed` is the buffer handed to the codec; `raw` is the uncompressed size when known (decompress
 * output cannot be smaller than it), else 0. */
static size_t pb_out_cap(int is_compress, size_t fed, size_t raw) {
    size_t cap = (is_compress ? PB_COMPRESS_GROWTH : PB_DECOMPRESS_GROWTH) * fed + PB_OUTPUT_SLACK;
    if (!is_compress && cap < raw + PB_OUTPUT_SLACK) cap = raw + PB_OUTPUT_SLACK;
    return cap;
}

static int pb_cmd_oneshot(const pb_adapter *a, int argc, char **argv, int is_compress) {
    const char *sub = is_compress ? "compress" : "decompress";
    pb_run_fn run = is_compress ? a->compress : a->decompress;
    const char *in_path = NULL;
    const char *out_path = NULL;
    pb_params p;
    pb_flag flags[7];
    unsigned char *input = NULL;
    unsigned char *output = NULL;
    size_t in_size = 0, out_cap, out_size = 0;
    int rc;

    memset(&p, 0, sizeof p);
    PB_FLAG(0, "in", PB_STR, &in_path);
    PB_FLAG(1, "out", PB_STR, &out_path);
    pb_param_flags(flags, 2, &p);
    rc = pb_parse_flags(sub, argc, argv, 2, flags, sizeof flags / sizeof flags[0]);
    if (rc != 0) return rc;
    rc = pb_check_packet_bits(sub, &p);
    if (rc != 0) return rc;
    if (!run) {
        fprintf(stderr, "%s: not supported by this implementation\n", sub);
        return 2;
    }

    input = pb_read_file(in_path, &in_size);
    if (!input) {
        fprintf(stderr, "%s: cannot read %s\n", sub, in_path);
        return 1;
    }
    out_cap = pb_out_cap(is_compress, in_size, 0);
    output = (unsigned char *)malloc(out_cap);
    if (!output) {
        free(input);
        fprintf(stderr, "%s: out of memory\n", sub);
        return 1;
    }

    rc = run(a->ctx, input, in_size, &p, output, out_cap, &out_size);
    if (rc != 0) {
        fprintf(stderr, "%s: codec error %d\n", sub, rc);
        rc = 1;
    } else if (pb_write_file(out_path, output, out_size) != 0) {
        fprintf(stderr, "%s: cannot write %s\n", sub, out_path);
        rc = 1;
    }
    free(output);
    free(input);
    return rc;
}

/* --- bench ------------------------------------------------------------------------------------ */

static long long pb_ns_since(struct timespec a, struct timespec b) {
    return (long long)(b.tv_sec - a.tv_sec) * 1000000000LL + (long long)(b.tv_nsec - a.tv_nsec);
}

static int pb_cmd_bench(const pb_adapter *a, int argc, char **argv) {
    const char *op = NULL;
    const char *in_path = NULL;
    long warmup = 0, iterations = 0;
    pb_params p;
    pb_flag flags[9];
    pb_run_fn run;
    unsigned char *input = NULL;
    unsigned char *compressed = NULL;
    unsigned char *output = NULL;
    long long *nanos = NULL;
    const unsigned char *bench_in;
    size_t in_size = 0, bench_in_size, out_cap, out_size = 0, stride, packets_per_iter;
    int is_compress, rc;
    long i;

    memset(&p, 0, sizeof p);
    PB_FLAG(0, "op", PB_STR, &op);
    PB_FLAG(1, "in", PB_STR, &in_path);
    PB_FLAG(2, "warmup", PB_LONG, &warmup);
    PB_FLAG(3, "iterations", PB_LONG, &iterations);
    pb_param_flags(flags, 4, &p);
    rc = pb_parse_flags("bench", argc, argv, 2, flags, sizeof flags / sizeof flags[0]);
    if (rc != 0) return rc;
    rc = pb_check_packet_bits("bench", &p);
    if (rc != 0) return rc;

    is_compress = strcmp(op, "compress") == 0;
    if (!is_compress && strcmp(op, "decompress") != 0) {
        fprintf(stderr, "bench: --op must be compress or decompress, got \"%s\"\n", op);
        return 2;
    }
    if (warmup < 0 || iterations < 0) {
        fprintf(stderr, "bench: --warmup and --iterations must be non-negative\n");
        return 2;
    }
    run = is_compress ? a->compress : a->decompress;
    if (!run) {
        fprintf(stderr, "bench: %s is not supported by this implementation\n", op);
        return 2;
    }
    /* The input is compressed once outside the timer, so a decompress bench needs both ops. */
    if (!is_compress && !a->compress) {
        fprintf(stderr, "bench: decompress needs compress to prepare its input\n");
        return 2;
    }

    input = pb_read_file(in_path, &in_size);
    if (!input) {
        fprintf(stderr, "bench: cannot read %s\n", in_path);
        return 1;
    }
    stride = (size_t)((p.packet_bits + 7UL) / 8UL);
    packets_per_iter = in_size / stride;

    bench_in = input;
    bench_in_size = in_size;
    if (!is_compress) {
        size_t cap = pb_out_cap(1, in_size, 0);
        size_t csize = 0;
        compressed = (unsigned char *)malloc(cap);
        if (!compressed) {
            free(input);
            fprintf(stderr, "bench: out of memory\n");
            return 1;
        }
        rc = a->compress(a->ctx, input, in_size, &p, compressed, cap, &csize);
        if (rc != 0) {
            fprintf(stderr, "bench: pre-compress failed with codec error %d\n", rc);
            free(compressed);
            free(input);
            return 1;
        }
        bench_in = compressed;
        bench_in_size = csize;
    }

    out_cap = pb_out_cap(is_compress, bench_in_size, is_compress ? 0 : in_size);
    output = (unsigned char *)malloc(out_cap);
    nanos = (long long *)malloc(sizeof(long long) * (size_t)(iterations > 0 ? iterations : 1));
    if (!output || !nanos) {
        free(nanos);
        free(output);
        free(compressed);
        free(input);
        fprintf(stderr, "bench: out of memory\n");
        return 1;
    }

    rc = 0;
    for (i = 0; i < warmup && rc == 0; i++)
        rc = run(a->ctx, bench_in, bench_in_size, &p, output, out_cap, &out_size);
    for (i = 0; i < iterations && rc == 0; i++) {
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        rc = run(a->ctx, bench_in, bench_in_size, &p, output, out_cap, &out_size);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        nanos[i] = pb_ns_since(t0, t1);
    }

    if (rc != 0) {
        fprintf(stderr, "bench: codec error %d\n", rc);
        rc = 1;
    } else {
        printf("{\"op\":\"%s\",\"iterations\":%ld,\"packets_per_iter\":%lu,\"nanos\":[",
               op, iterations, (unsigned long)packets_per_iter);
        for (i = 0; i < iterations; i++) printf("%s%lld", i ? "," : "", nanos[i]);
        printf("]}\n");
    }

    free(nanos);
    free(output);
    free(compressed);
    free(input);
    return rc;
}

/* --- conformance ------------------------------------------------------------------------------ */

static int pb_cmd_conformance(const pb_adapter *a, int argc, char **argv, int is_compress) {
    const char *sub = is_compress ? "conformance-compress" : "conformance-decompress";
    pb_conformance_fn fn = is_compress ? a->conformance_compress : a->conformance_decompress;
    const char *in_path = NULL;
    const char *out_path = NULL;
    pb_flag flags[2];
    int rc;

    PB_FLAG(0, "in", PB_STR, &in_path);
    PB_FLAG(1, "out", PB_STR, &out_path);
    rc = pb_parse_flags(sub, argc, argv, 2, flags, sizeof flags / sizeof flags[0]);
    if (rc != 0) return rc;
    if (!fn) {
        fprintf(stderr, "%s: not supported by this implementation\n", sub);
        return 2;
    }
    return fn(a->ctx, in_path, out_path);
}

/* --- dispatch --------------------------------------------------------------------------------- */

int pb_main(int argc, char **argv, const pb_adapter *a) {
    const char *cmd;

    if (argc < 2) {
        fprintf(stderr, "usage: adapter <capabilities|compress|decompress|bench|"
                        "conformance-compress|conformance-decompress> [--key=value ...]\n");
        return 2;
    }
    cmd = argv[1];
    if (strcmp(cmd, "capabilities") == 0) {
        pb_print_capabilities(a);
        return 0;
    }
    if (strcmp(cmd, "compress") == 0) return pb_cmd_oneshot(a, argc, argv, 1);
    if (strcmp(cmd, "decompress") == 0) return pb_cmd_oneshot(a, argc, argv, 0);
    if (strcmp(cmd, "bench") == 0) return pb_cmd_bench(a, argc, argv);
    if (strcmp(cmd, "conformance-compress") == 0) return pb_cmd_conformance(a, argc, argv, 1);
    if (strcmp(cmd, "conformance-decompress") == 0) return pb_cmd_conformance(a, argc, argv, 0);
    fprintf(stderr, "unknown subcommand: %s\n", cmd);
    return 2;
}
