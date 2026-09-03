/* reference-c pocketbench adapter: links the ccsds124 C codec and speaks the fixed contract.
 * Reuses the same ccsds124_* entry points as the codec's CLI/bench/conformance harnesses.
 * Compiled together with the codec sources so CCSDS124_MAX_PACKET_LENGTH is consistent. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "ccsds124.h"
#include "adapter_common.h"

/* Stringize the compile-time packet-length cap so `limitations` reports the value this binary was
 * built with: bench builds it to the dataset max, validate/profile/conformance to 65535. */
#define ADAPTER_STR2(x) #x
#define ADAPTER_STR(x) ADAPTER_STR2(x)

/* --- conformance helpers (ported verbatim from crossvalidation_encoder.c) --- */

static uint32_t cv_read_be32(const uint8_t *buf) {
    return ((uint32_t)buf[0] << 24) |
           ((uint32_t)buf[1] << 16) |
           ((uint32_t)buf[2] << 8)  |
           ((uint32_t)buf[3]);
}

static int cv_check_padding_zero(const uint8_t *buf, uint32_t large_f) {
    uint32_t total_bits = ((large_f + 7U) / 8U) * 8U;
    uint32_t padding_bits = total_bits - large_f;

    if (padding_bits == 0U) {
        return 1; /* No padding, always valid */
    }

    uint32_t num_bytes = (large_f + 7U) / 8U;
    uint8_t last_byte = buf[num_bytes - 1U];
    uint8_t mask = (uint8_t)((1U << padding_bits) - 1U);

    return (last_byte & mask) == 0U;
}

/* conformance-compress: port of crossvalidation_encoder.c main (lines 42-252).
 * Reads a .raw+config file, compresses each packet with per-packet flags via
 * ccsds124_compress_packet, and writes the concatenated byte-aligned output. On any
 * validation/codec failure it stops and writes the partial output accumulated so far. */
static int do_conformance_compress(const char *in_path, const char *out_path) {
    size_t file_size = 0;
    uint8_t *file_data = pb_read_file(in_path, &file_size);
    if (!file_data) { fprintf(stderr, "cannot read %s\n", in_path); return 1; }

    size_t output_capacity = file_size * 6U + 1024U;
    uint8_t *output_data = (uint8_t *)malloc(output_capacity);
    if (!output_data) { free(file_data); fprintf(stderr, "oom\n"); return 1; }

    size_t output_size = 0;
    size_t pos = 0;
    int failed = 0; /* set on a realloc/codec-hard error requiring a nonzero exit */

    /* --- Read large_f (32-bit BE) --- */
    if (file_size < 4U) {
        goto write_output;
    }

    uint32_t large_f = cv_read_be32(&file_data[pos]);
    pos += 4U;

    if (large_f == 0U || large_f > CCSDS124_MAX_PACKET_LENGTH) {
        goto write_output;
    }

    uint32_t packet_bytes = (large_f + 7U) / 8U;

    /* --- Read M_0 --- */
    if (pos + packet_bytes > file_size) {
        goto write_output;
    }
    if (!cv_check_padding_zero(&file_data[pos], large_f)) {
        goto write_output;
    }

    ccsds124_compressor_t comp;
    bitvector_t initial_mask;
    bitvector_init(&initial_mask, (size_t)large_f);
    bitvector_from_bytes(&initial_mask, &file_data[pos], (size_t)packet_bytes);
    pos += packet_bytes;

    if (ccsds124_compressor_init(&comp, (size_t)large_f, &initial_mask, 0, 0, 0, 0) != CCSDS124_OK) {
        goto write_output;
    }

    /* --- Process packets --- */
    size_t pkt_idx = 0;
    while (pos < file_size) {
        if (pos + 1U > file_size) {
            break; /* Incomplete packet */
        }

        uint8_t flag_byte = file_data[pos];
        pos += 1U;

        /* bit7=reserved, bit6=f, bit5=p, bit4=r, bits3-0=R */
        uint8_t f_flag = (flag_byte >> 6) & 1U;
        uint8_t p_flag = (flag_byte >> 5) & 1U;
        uint8_t r_flag = (flag_byte >> 4) & 1U;
        uint8_t R_val  = flag_byte & 0x0FU;

        if (R_val > CCSDS124_MAX_ROBUSTNESS) {
            break; /* Stop: invalid parameter */
        }

        /* CCSDS 124.0-B-1 Section 3.3.2: during the init phase (t = 0..R), r_t must be 1,
         * and when R > 0 f_t must also be 1 to ensure mask synchronization. */
        if (pkt_idx <= (size_t)R_val) {
            if (r_flag == 0U) {
                break; /* non-reference packet during init phase */
            }
            if (R_val > 0U && f_flag == 0U) {
                break; /* f=0 during init phase with R > 0 */
            }
        }

        if (pos + packet_bytes > file_size) {
            break; /* Incomplete packet */
        }
        if (!cv_check_padding_zero(&file_data[pos], large_f)) {
            break; /* Stop: invalid padding */
        }

        bitvector_t input_vec;
        bitvector_init(&input_vec, (size_t)large_f);
        bitvector_from_bytes(&input_vec, &file_data[pos], (size_t)packet_bytes);
        pos += packet_bytes;

        comp.robustness = R_val;

        ccsds124_params_t params;
        params.min_robustness = R_val;
        params.send_mask_flag = f_flag;
        params.new_mask_flag = p_flag;
        params.uncompressed_flag = r_flag;

        bitbuffer_t packet_output;
        bitbuffer_init(&packet_output);

        if (ccsds124_compress_packet(&comp, &input_vec, &packet_output, &params) != CCSDS124_OK) {
            break; /* Stop on compression error */
        }

        uint8_t packet_out_bytes[CCSDS124_MAX_OUTPUT_BYTES];
        size_t packet_out_size = bitbuffer_to_bytes(&packet_output, packet_out_bytes, sizeof(packet_out_bytes));

        if (output_size + packet_out_size > output_capacity) {
            output_capacity = (output_size + packet_out_size) * 2U;
            uint8_t *new_buf = (uint8_t *)realloc(output_data, output_capacity);
            if (!new_buf) {
                fprintf(stderr, "realloc failed\n");
                failed = 1;
                break;
            }
            output_data = new_buf;
        }

        memcpy(&output_data[output_size], packet_out_bytes, packet_out_size);
        output_size += packet_out_size;
        pkt_idx++;
    }

write_output:
    {
        int ret = failed;
        if (pb_write_file(out_path, output_data, output_size) != 0) {
            fprintf(stderr, "cannot write %s\n", out_path);
            ret = 1;
        }
        free(file_data);
        free(output_data);
        return ret;
    }
}

/* --- conformance-decompress helpers (ported verbatim from crossvalidation_decoder.c) --- */

#define CV_MAX_COMPRESSED_BITS 622627U

static void cv_write_be32(uint8_t *buf, uint32_t val) {
    buf[0] = (uint8_t)(val >> 24);
    buf[1] = (uint8_t)(val >> 16);
    buf[2] = (uint8_t)(val >> 8);
    buf[3] = (uint8_t)(val);
}

/* Two-pass F discovery: a strictly-validated reference packet wins; otherwise remember the first
 * truncated reference packet's signaled COUNT(F) (weak_F, trailer-only, not reliable to decode). */
static uint32_t cv_discover_F_from_file(const uint8_t *file_data, size_t file_size,
                                        uint32_t *weak_F_out) {
    size_t pos = 0;
    uint32_t weak_F = 0;

    while (pos < file_size) {
        uint8_t reception_byte = file_data[pos];
        pos += 1U;

        if ((reception_byte & 1U) == 1U) {
            continue; /* Type 2: lost packet */
        }

        if (pos + 4U > file_size) {
            break; /* Truncated */
        }

        uint32_t length_bits = cv_read_be32(&file_data[pos]);
        pos += 4U;

        if (length_bits == 0U || length_bits > CV_MAX_COMPRESSED_BITS) {
            break; /* Invalid length */
        }

        uint32_t length_bytes = (length_bits + 7U) / 8U;
        if (pos + length_bytes > file_size) {
            break; /* Truncated */
        }

        uint32_t F = 0;
        int rc = ccsds124_discover_packet_length(&file_data[pos], (size_t)length_bits, &F);
        if (rc == CCSDS124_OK && F > 0U) {
            return F; /* Strict discovery */
        }
        if (rc == CCSDS124_STATUS_TRUNCATED_LENGTH && F > 0U && weak_F == 0U) {
            weak_F = F; /* first signaled length; keep scanning */
        }

        pos += length_bytes;
    }

    if (weak_F_out != NULL) {
        *weak_F_out = weak_F;
    }
    return 0;
}

/* Returns -1 on realloc failure. */
static int cv_append_output(uint8_t **buf, size_t *size, size_t *cap,
                            const uint8_t *data, size_t len) {
    if (*size + len > *cap) {
        size_t new_cap = (*cap == 0U) ? 65536U : *cap * 2U;
        while (new_cap < *size + len) {
            new_cap *= 2U;
        }
        uint8_t *new_buf = (uint8_t *)realloc(*buf, new_cap);
        if (new_buf == NULL) {
            return -1;
        }
        *buf = new_buf;
        *cap = new_cap;
    }
    memcpy(*buf + *size, data, len);
    *size += len;
    return 0;
}

/* conformance-decompress: port of crossvalidation_decoder.c main (lines 123-289).
 * Discovers F via a pre-scan, then decodes each element to .raw+large_f format
 * (status byte + decoded packet, plus a trailing 32-bit BE F). */
static int do_conformance_decompress(const char *in_path, const char *out_path) {
    size_t file_size = 0;
    uint8_t *file_data = pb_read_file(in_path, &file_size);
    if (!file_data) { fprintf(stderr, "cannot read %s\n", in_path); return 1; }

    uint8_t *output_data = NULL;
    size_t output_size = 0;
    size_t output_cap = 0;

    uint32_t discovered_F = 0;
    uint32_t weak_F = 0;
    int F_known = 0;
    ccsds124_decompressor_t decomp;

    if (file_size > 0) {
        discovered_F = cv_discover_F_from_file(file_data, file_size, &weak_F);
        if (discovered_F > 0U && discovered_F <= CCSDS124_MAX_PACKET_LENGTH) {
            F_known = 1;
            ccsds124_decompressor_init(&decomp, (size_t)discovered_F, NULL, 0);
        }
    }

    uint32_t packet_bytes_F = F_known ? (discovered_F + 7U) / 8U : 0U;

    size_t pos = 0;
    int stop_processing = 0;

    while (pos < file_size && !stop_processing) {
        uint8_t reception_byte = file_data[pos];
        pos += 1U;

        if ((reception_byte & 1U) == 1U) {
            /* Type 2: lost packet */
            uint8_t status = 0x02;
            cv_append_output(&output_data, &output_size, &output_cap, &status, 1);
            if (F_known) {
                ccsds124_decompressor_notify_packet_loss(&decomp, 1);
            }
            continue;
        }

        /* Type 1: received packet */
        if (pos + 4U > file_size) {
            break; /* Truncated */
        }

        uint32_t length_bits = cv_read_be32(&file_data[pos]);
        pos += 4U;

        if (length_bits == 0U || length_bits > CV_MAX_COMPRESSED_BITS) {
            stop_processing = 1;
            continue;
        }

        uint32_t length_bytes = (length_bits + 7U) / 8U;
        if (pos + length_bytes > file_size) {
            break; /* Truncated */
        }

        const uint8_t *packet_data = &file_data[pos];
        pos += length_bytes;

        if (!F_known) {
            uint8_t status = 0x01;
            cv_append_output(&output_data, &output_size, &output_cap, &status, 1);
            continue;
        }

        bitvector_t output_vec;
        int rc = ccsds124_decompress_packet_checked(
            &decomp, packet_data, (size_t)length_bits, &output_vec, NULL);

        if (rc == CCSDS124_OK) {
            uint8_t status = 0x00;
            cv_append_output(&output_data, &output_size, &output_cap, &status, 1);

            uint8_t pkt_bytes[CCSDS124_MAX_PACKET_BYTES];
            memset(pkt_bytes, 0, sizeof(pkt_bytes));
            bitvector_to_bytes(&output_vec, pkt_bytes, (size_t)packet_bytes_F);
            cv_append_output(&output_data, &output_size, &output_cap,
                             pkt_bytes, (size_t)packet_bytes_F);
        } else {
            uint8_t status = 0x01;
            cv_append_output(&output_data, &output_size, &output_cap, &status, 1);
        }
    }

    /* Trailer: final 32-bit BE F (discovered, else weak, else 0). */
    {
        uint8_t f_bytes[4];
        cv_write_be32(f_bytes, F_known ? discovered_F : weak_F);
        cv_append_output(&output_data, &output_size, &output_cap, f_bytes, 4);
    }

    int ret = 0;
    if (pb_write_file(out_path, output_data, output_size) != 0) {
        fprintf(stderr, "cannot write %s\n", out_path);
        ret = 1;
    }
    free(file_data);
    free(output_data);
    return ret;
}

/* --- the contract ------------------------------------------------------------------------------ */

/* init + whole-buffer op, mirroring the codec's own test_bench.c: the stack compressor is
 * initialized per call, so a bench iteration times the setup a real caller pays. */
static int rc_compress(void *ctx, const unsigned char *in, size_t in_size, const pb_params *p,
                       unsigned char *out, size_t out_cap, size_t *out_size) {
    ccsds124_compressor_t comp;
    int rc;
    (void)ctx;
    rc = ccsds124_compressor_init(&comp, (size_t)p->packet_bits, NULL, (uint8_t)p->robustness,
                                  (int)p->pt, (int)p->ft, (int)p->rt);
    if (rc != CCSDS124_OK) return rc;
    return ccsds124_compress(&comp, in, in_size, out, out_cap, out_size);
}

static int rc_decompress(void *ctx, const unsigned char *in, size_t in_size, const pb_params *p,
                         unsigned char *out, size_t out_cap, size_t *out_size) {
    ccsds124_decompressor_t decomp;
    int rc;
    (void)ctx;
    rc = ccsds124_decompressor_init(&decomp, (size_t)p->packet_bits, NULL, (uint8_t)p->robustness);
    if (rc != CCSDS124_OK) return rc;
    return ccsds124_decompress(&decomp, in, in_size, out, out_cap, out_size);
}

static int rc_conformance_compress(void *ctx, const char *in_path, const char *out_path) {
    (void)ctx;
    return do_conformance_compress(in_path, out_path);
}

static int rc_conformance_decompress(void *ctx, const char *in_path, const char *out_path) {
    (void)ctx;
    return do_conformance_decompress(in_path, out_path);
}

int main(int argc, char **argv) {
    pb_adapter adapter;
    memset(&adapter, 0, sizeof adapter);
    adapter.caps.timing_tier = "in_process";
    adapter.caps.reference_conformant = 1;
    adapter.caps.param_schedule = "pt_ft_rt";
    adapter.caps.build_profile = "gcc -std=c99 -O3 -flto";
    adapter.caps.limitations =
        "packets up to CCSDS124_MAX_PACKET_LENGTH=" ADAPTER_STR(CCSDS124_MAX_PACKET_LENGTH)
        " bits; larger packets are not supported by this build (compile-time struct sizing)";
    adapter.compress = rc_compress;
    adapter.decompress = rc_decompress;
    adapter.conformance_compress = rc_conformance_compress;
    adapter.conformance_decompress = rc_conformance_decompress;
    return pb_main(argc, argv, &adapter);
}
