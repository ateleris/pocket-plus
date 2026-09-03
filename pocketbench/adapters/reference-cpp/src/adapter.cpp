/* reference-cpp pocketbench adapter: links the ccsds124 C++ codec and speaks the fixed contract.
 * Reuses the codec's high-level ccsds124::compress<N> / ccsds124::decompress<N>, the entry points
 * its own CLI and bench use. Those are templated on the packet length N in bits, resolved at
 * compile time, so the adapter dispatches the runtime packet_bits (F) to an instantiation listed
 * in SUPPORTED_SIZES; add a line there to support another size. 720 bits (90-byte packets) covers
 * every byte-aligned pocketbench reference dataset, and sub-byte F is unsupported because it is
 * not a listed size.
 *
 * The C++ codec ships no UAB/CNES conformance harness and exposes no file-level per-packet-flag
 * driver, so the conformance hooks are left NULL. */
#include <cstdint>
#include <cstdio>
#include <cstring>

#include <ccsds124/ccsds124.hpp>

#include "adapter_common.h"

using namespace ccsds124;

/* The packet field widths (F, in bits) the adapter can instantiate. X(bits). */
#define SUPPORTED_SIZES(X) \
    X(720)

/* Distinct from any ccsds124::Error value, so the reported code is unambiguous. */
#define UNSUPPORTED_SIZE 99

/* --- compress / decompress, one template instantiation per packet size --- */

template <std::size_t N>
static int compress_n(const unsigned char *in, size_t in_size, const pb_params *p,
                      unsigned char *out, size_t out_cap, size_t *out_size) {
    return (int)compress<N>(in, in_size, out, out_cap, *out_size, (std::uint8_t)p->robustness,
                            (int)p->pt, (int)p->ft, (int)p->rt);
}

template <std::size_t N>
static int decompress_n(const unsigned char *in, size_t in_size, const pb_params *p,
                        unsigned char *out, size_t out_cap, size_t *out_size) {
    return (int)decompress<N>(in, in_size, out, out_cap, *out_size, (std::uint8_t)p->robustness);
}

static int rcpp_compress(void *ctx, const unsigned char *in, size_t in_size, const pb_params *p,
                         unsigned char *out, size_t out_cap, size_t *out_size) {
    (void)ctx;
#define DISPATCH(bits) \
    if (p->packet_bits == (bits)) return compress_n<bits>(in, in_size, p, out, out_cap, out_size);
    SUPPORTED_SIZES(DISPATCH)
#undef DISPATCH
    std::fprintf(stderr, "unsupported packet_bits %lu (compile-time template; see SUPPORTED_SIZES)\n",
                 p->packet_bits);
    return UNSUPPORTED_SIZE;
}

static int rcpp_decompress(void *ctx, const unsigned char *in, size_t in_size, const pb_params *p,
                           unsigned char *out, size_t out_cap, size_t *out_size) {
    (void)ctx;
#define DISPATCH(bits) \
    if (p->packet_bits == (bits)) return decompress_n<bits>(in, in_size, p, out, out_cap, out_size);
    SUPPORTED_SIZES(DISPATCH)
#undef DISPATCH
    std::fprintf(stderr, "unsupported packet_bits %lu (compile-time template; see SUPPORTED_SIZES)\n",
                 p->packet_bits);
    return UNSUPPORTED_SIZE;
}

int main(int argc, char **argv) {
    pb_adapter adapter;
    std::memset(&adapter, 0, sizeof adapter);
    adapter.caps.timing_tier = "in_process";
    adapter.caps.reference_conformant = 1;
    adapter.caps.param_schedule = "pt_ft_rt";
    adapter.caps.build_profile = "g++ -std=c++17 -O3 -flto";
    adapter.caps.limitations =
        "compiled for 720-bit (90-byte) packets only; other packet sizes, including sub-byte F, "
        "are rejected (packet length is a compile-time template parameter, see SUPPORTED_SIZES); "
        "no conformance support";
    adapter.compress = rcpp_compress;
    adapter.decompress = rcpp_decompress;
    return pb_main(argc, argv, &adapter);
}
