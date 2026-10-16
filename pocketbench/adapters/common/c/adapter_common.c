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

#define PB_HEADER 4U
#define PB_RECORD_HEADER 5U
/* Room for one decoded field: F <= 65535 bits is at most 8192 bytes. */
#define PB_DECODE_RESERVE (8192U + 64U)
/* Room per compressed packet, per field byte; POCKET+ stays far below it. */
#define PB_ENCODE_GROWTH 64U
#define PB_OUTPUT_SLACK 4096U

void *pb_xmalloc(size_t n) {
    void *p = malloc(n ? n : 1U);
    if (!p) {
        fprintf(stderr, "adapter: out of memory (%lu bytes)\n", (unsigned long)n);
        exit(1);
    }
    return p;
}

/* --- file IO ---------------------------------------------------------------------------------- */

static unsigned char *pb_read_file(const char *path, size_t *size) {
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
    buf = (unsigned char *)pb_xmalloc((size_t)n);
    got = (size_t)n ? fread(buf, 1, (size_t)n, f) : 0U;
    fclose(f);
    if (got != (size_t)n) { free(buf); return NULL; }
    *size = (size_t)n;
    return buf;
}

static int pb_write_file(const char *path, const void *data, size_t size) {
    FILE *f = fopen(path, "wb");
    size_t put;
    if (!f) return -1;
    put = size ? fwrite(data, 1, size, f) : 0U;
    if (fclose(f) != 0) return -1;
    return put == size ? 0 : -1;
}

static unsigned long pb_be32(const unsigned char *p) {
    return ((unsigned long)p[0] << 24) | ((unsigned long)p[1] << 16) |
           ((unsigned long)p[2] << 8) | (unsigned long)p[3];
}

static void pb_put_be32(unsigned char *p, unsigned long v) {
    p[0] = (unsigned char)(v >> 24);
    p[1] = (unsigned char)(v >> 16);
    p[2] = (unsigned char)(v >> 8);
    p[3] = (unsigned char)v;
}

/* --- keyed flags ------------------------------------------------------------------------------ */

typedef struct {
    const char *in;
    const char *out;
    long warmup;
    long iterations;
    int has_warmup;
    int has_iterations;
} pb_args;

static int pb_key_is(const char *arg, size_t klen, const char *key) {
    return klen == strlen(key) && strncmp(arg, key, klen) == 0;
}

static int pb_set_path(const char *sub, const char *key, const char *val, const char **dst) {
    if (*dst) {
        fprintf(stderr, "%s: flag --%s given more than once\n", sub, key);
        return 2;
    }
    if (!*val) {
        fprintf(stderr, "%s: flag --%s needs a non-empty value\n", sub, key);
        return 2;
    }
    *dst = val;
    return 0;
}

static int pb_set_count(const char *sub, const char *key, const char *val, long *dst, int *has) {
    char *end = NULL;
    long v;
    if (*has) {
        fprintf(stderr, "%s: flag --%s given more than once\n", sub, key);
        return 2;
    }
    errno = 0;
    v = strtol(val, &end, 10);
    if (errno != 0 || end == val || *end || v < 0) {
        fprintf(stderr, "%s: flag --%s must be a non-negative integer, got \"%s\"\n", sub, key, val);
        return 2;
    }
    *dst = v;
    *has = 1;
    return 0;
}

/* --in and --out are required, --warmup and --iterations optional; anything else is a contract
 * violation. Returns 0, or 2 with a message on stderr. */
static int pb_parse_args(const char *sub, int argc, char **argv, pb_args *a) {
    int i, rc = 0;

    memset(a, 0, sizeof *a);
    for (i = 2; i < argc && rc == 0; i++) {
        const char *arg = argv[i];
        const char *eq;
        size_t klen;

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
        if (pb_key_is(arg + 2, klen, "in")) {
            rc = pb_set_path(sub, "in", eq + 1, &a->in);
        } else if (pb_key_is(arg + 2, klen, "out")) {
            rc = pb_set_path(sub, "out", eq + 1, &a->out);
        } else if (pb_key_is(arg + 2, klen, "warmup")) {
            rc = pb_set_count(sub, "warmup", eq + 1, &a->warmup, &a->has_warmup);
        } else if (pb_key_is(arg + 2, klen, "iterations")) {
            rc = pb_set_count(sub, "iterations", eq + 1, &a->iterations, &a->has_iterations);
        } else {
            fprintf(stderr, "%s: unknown flag --%.*s; this adapter does not implement it, so the "
                            "harness and the adapter disagree about the contract\n",
                    sub, (int)klen, arg + 2);
            return 2;
        }
    }
    if (rc != 0) return rc;
    if (!a->in) {
        fprintf(stderr, "%s: missing required flag --in\n", sub);
        return 2;
    }
    if (!a->out) {
        fprintf(stderr, "%s: missing required flag --out\n", sub);
        return 2;
    }
    return 0;
}

/* --- the record container --------------------------------------------------------------------- */

typedef struct {
    int f_present;
    unsigned long f;
    pb_record *recs;
    size_t n;
} pb_container;

/* Returns 0, or 2 when the container is malformed (a record header cut off), which only a harness
 * bug produces. */
static int pb_parse_container(const char *sub, const unsigned char *buf, size_t size,
                              pb_container *c) {
    size_t pos = PB_HEADER, cap = 0;

    memset(c, 0, sizeof *c);
    if (size < PB_HEADER) return 0;
    c->f_present = 1;
    c->f = pb_be32(buf);
    while (pos < size) {
        pb_record *r;
        size_t want, have;

        if (size - pos < PB_RECORD_HEADER) {
            fprintf(stderr, "%s: malformed record container: record header cut off at byte %lu\n",
                    sub, (unsigned long)pos);
            free(c->recs);
            c->recs = NULL;
            return 2;
        }
        if (c->n == cap) {
            pb_record *grown;
            cap = cap ? cap * 2U : 256U;
            grown = (pb_record *)realloc(c->recs, cap * sizeof *grown);
            if (!grown) {
                fprintf(stderr, "%s: out of memory\n", sub);
                exit(1);
            }
            c->recs = grown;
        }
        r = &c->recs[c->n++];
        r->kind = buf[pos];
        r->bits = pb_be32(buf + pos + 1U);
        pos += PB_RECORD_HEADER;
        want = (size_t)((r->bits + 7UL) / 8UL);
        have = size - pos;
        r->data = buf + pos;
        r->len = want < have ? want : have;
        pos += r->len;
    }
    return 0;
}

/* --- passes ----------------------------------------------------------------------------------- */

typedef struct {
    unsigned char *buf;
    size_t cap;
    size_t size;
} pb_out;

/* The buffer outlives the passes, so a timed pass reuses what the warmup or first pass sized. */
static void pb_room(pb_out *o, size_t n) {
    size_t cap;
    unsigned char *grown;

    if (o->cap - o->size >= n) return;
    cap = o->cap * 2U;
    if (cap < o->size + n) cap = o->size + n;
    grown = (unsigned char *)realloc(o->buf, cap);
    if (!grown) {
        fprintf(stderr, "adapter: out of memory\n");
        exit(1);
    }
    o->buf = grown;
    o->cap = cap;
}

typedef struct {
    size_t records_out;
    long stopped_at; /* -1: every record was processed */
    int error;
} pb_pass;

static void pb_compress_pass(const pb_adapter *a, const pb_container *in, pb_out *o, pb_pass *r) {
    void *state = NULL;
    size_t i;
    int rc;

    memset(r, 0, sizeof *r);
    r->stopped_at = -1;
    o->size = 0;
    pb_room(o, PB_HEADER);
    pb_put_be32(o->buf, in->f_present ? in->f : 0UL);
    o->size = PB_HEADER;

    rc = a->enc_create(a->ctx, in->f_present, in->f, in->n ? &in->recs[0] : NULL, &state);
    if (rc != 0) {
        r->stopped_at = 0;
        r->error = rc;
        return;
    }
    for (i = 1; i < in->n; i++) {
        const pb_record *rec = &in->recs[i];
        size_t hdr;
        unsigned long bits = 0;

        pb_room(o, PB_RECORD_HEADER + PB_ENCODE_GROWTH * (rec->len > 128U ? rec->len : 128U));
        hdr = o->size;
        rc = a->enc_packet(state, rec->kind, rec->data, rec->len, o->buf + hdr + PB_RECORD_HEADER,
                           o->cap - hdr - PB_RECORD_HEADER, &bits);
        if (rc != 0) {
            r->stopped_at = (long)i;
            r->error = rc;
            break;
        }
        o->buf[hdr] = 0;
        pb_put_be32(o->buf + hdr + 1U, bits);
        o->size = hdr + PB_RECORD_HEADER + (size_t)((bits + 7UL) / 8UL);
        r->records_out++;
    }
    a->enc_destroy(state);
}

static void pb_decompress_pass(const pb_adapter *a, const pb_container *in, pb_out *o,
                               pb_pass *r) {
    void *state = NULL;
    size_t i;
    int rc;

    memset(r, 0, sizeof *r);
    r->stopped_at = -1;
    o->size = 0;
    pb_room(o, PB_HEADER);
    pb_put_be32(o->buf, 0UL);
    o->size = PB_HEADER;

    rc = a->dec_create(a->ctx, in->f, in->recs, in->n, &state);
    if (rc != 0) {
        r->stopped_at = 0;
        r->error = rc;
        return;
    }
    for (i = 0; i < in->n; i++) {
        const pb_record *rec = &in->recs[i];
        size_t hdr;
        unsigned long bits = 0;
        int status;

        pb_room(o, PB_RECORD_HEADER + (rec->len > PB_DECODE_RESERVE ? rec->len : PB_DECODE_RESERVE));
        hdr = o->size;
        if (rec->kind & 1U) {
            a->dec_lost(state);
            status = PB_LOST;
        } else {
            unsigned long present = (unsigned long)rec->len * 8UL;
            status = a->dec_packet(state, rec->data, rec->len, rec->bits,
                                   present < rec->bits ? present : rec->bits,
                                   o->buf + hdr + PB_RECORD_HEADER, o->cap - hdr - PB_RECORD_HEADER,
                                   &bits);
            if (status < 0) {
                r->stopped_at = (long)i;
                r->error = status;
                break;
            }
            if (status != PB_DECODED) {
                status = PB_FAILED;
                bits = 0;
            }
        }
        o->buf[hdr] = (unsigned char)status;
        pb_put_be32(o->buf + hdr + 1U, bits);
        o->size = hdr + PB_RECORD_HEADER + (size_t)((bits + 7UL) / 8UL);
        r->records_out++;
    }
    pb_put_be32(o->buf, a->dec_trailer_f(state));
    a->dec_destroy(state);
}

/* --- the verbs -------------------------------------------------------------------------------- */

static long long pb_ns_since(struct timespec a, struct timespec b) {
    return (long long)(b.tv_sec - a.tv_sec) * 1000000000LL + (long long)(b.tv_nsec - a.tv_nsec);
}

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

static void pb_print_summary(size_t records_in, const pb_pass *r, const long long *nanos,
                             long timed, int with_nanos) {
    long i;
    printf("{\"records_in\":%lu,\"records_out\":%lu,\"stopped_at\":", (unsigned long)records_in,
           (unsigned long)r->records_out);
    if (r->stopped_at < 0) {
        printf("null,\"error\":null");
    } else {
        printf("%ld,\"error\":", r->stopped_at);
        if (r->error == PB_NOT_REPRESENTABLE) {
            pb_json_str("input not representable by this codec's API");
        } else if (r->error == PB_OUTPUT_FULL) {
            pb_json_str("output buffer exhausted");
        } else {
            printf("\"codec error %d\"", r->error);
        }
    }
    if (with_nanos) {
        printf(",\"nanos\":[");
        for (i = 0; i < timed; i++) printf("%s%lld", i ? "," : "", nanos[i]);
        printf("]");
    }
    printf("}\n");
}

static int pb_cmd_verb(const pb_adapter *a, int argc, char **argv, int is_compress) {
    const char *sub = is_compress ? "compress" : "decompress";
    void (*one_pass)(const pb_adapter *, const pb_container *, pb_out *, pb_pass *) =
        is_compress ? pb_compress_pass : pb_decompress_pass;
    pb_args args;
    pb_container in;
    pb_out o;
    pb_pass r;
    unsigned char *input;
    size_t in_size = 0, i, payload = 0;
    long long *nanos;
    long k, timed = 0;
    int rc;

    rc = pb_parse_args(sub, argc, argv, &args);
    if (rc != 0) return rc;
    input = pb_read_file(args.in, &in_size);
    if (!input) {
        fprintf(stderr, "%s: cannot read %s\n", sub, args.in);
        return 1;
    }
    rc = pb_parse_container(sub, input, in_size, &in);
    if (rc != 0) {
        free(input);
        return rc;
    }

    for (i = 0; i < in.n; i++) payload += in.recs[i].len;
    o.size = 0;
    o.cap = PB_HEADER + in.n * PB_RECORD_HEADER + PB_OUTPUT_SLACK +
            (is_compress ? 12U * payload + PB_ENCODE_GROWTH * 8192U
                         : in.n * (size_t)((in.f + 7UL) / 8UL) + PB_DECODE_RESERVE);
    o.buf = (unsigned char *)pb_xmalloc(o.cap);
    nanos = (long long *)pb_xmalloc(sizeof(long long) *
                                    (size_t)(args.iterations > 0 ? args.iterations : 1));

    for (k = 0; k < args.warmup; k++) one_pass(a, &in, &o, &r);
    if (args.iterations > 0) {
        for (k = 0; k < args.iterations; k++) {
            struct timespec t0, t1;
            clock_gettime(CLOCK_MONOTONIC, &t0);
            one_pass(a, &in, &o, &r);
            clock_gettime(CLOCK_MONOTONIC, &t1);
            nanos[timed++] = pb_ns_since(t0, t1);
            if (r.stopped_at >= 0) break;
        }
    } else {
        one_pass(a, &in, &o, &r);
    }

    rc = 0;
    if (pb_write_file(args.out, o.buf, o.size) != 0) {
        fprintf(stderr, "%s: cannot write %s\n", sub, args.out);
        rc = 1;
    }
    pb_print_summary(in.n, &r, nanos, timed, args.has_iterations);
    if (r.stopped_at >= 0 && r.error == PB_OUTPUT_FULL) rc = 1;
    free(nanos);
    free(o.buf);
    free(in.recs);
    free(input);
    return rc;
}

int pb_main(int argc, char **argv, const pb_adapter *a) {
    const char *cmd;

    if (argc < 2) {
        fprintf(stderr, "usage: adapter <capabilities|compress|decompress> [--key=value ...]\n");
        return 2;
    }
    cmd = argv[1];
    if (strcmp(cmd, "capabilities") == 0) {
        printf("{\"build_profile\":");
        pb_json_str(a->caps.build_profile);
        printf(",\"limitations\":");
        pb_json_str(a->caps.limitations);
        printf("}\n");
        return 0;
    }
    if (strcmp(cmd, "compress") == 0) return pb_cmd_verb(a, argc, argv, 1);
    if (strcmp(cmd, "decompress") == 0) return pb_cmd_verb(a, argc, argv, 0);
    fprintf(stderr, "unknown subcommand: %s\n", cmd);
    return 2;
}
