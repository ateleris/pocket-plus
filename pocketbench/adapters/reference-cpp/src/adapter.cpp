/* reference-cpp pocketbench adapter: per-packet hooks over the ccsds124 C++ codec. Its packet
 * length N is a compile-time template parameter, so only the sizes in SUPPORTED_SIZES are
 * representable; add a line there to support another. */
#include <cstdint>
#include <cstring>

#include <ccsds124/ccsds124.hpp>

#include "adapter_common.h"

using namespace ccsds124;

/* The packet field widths (F, in bits) the adapter can instantiate. X(bits). */
#define SUPPORTED_SIZES(X) \
    X(720)                 \
    X(32912)

struct Enc {
    virtual int packet(unsigned char flag, const unsigned char *field, std::size_t len,
                       unsigned char *out, std::size_t out_cap, unsigned long *out_bits) = 0;
};

struct Dec {
    virtual int packet(const unsigned char *data, unsigned long avail_bits, unsigned char *out,
                       unsigned long *out_bits) = 0;
    virtual unsigned long trailer() = 0;
};

/* Mirrors the per-packet body of the codec's own compress<N>, with explicit flags. */
template <std::size_t N> struct EncN final : Enc {
    static constexpr std::size_t STRIDE = (N + 7) / 8;
    Compressor<N> comp;
    BitVector<N> mask;
    bool started = false;
    std::uint8_t robustness = 0;

    int packet(unsigned char flag, const unsigned char *field, std::size_t len, unsigned char *out,
               std::size_t out_cap, unsigned long *out_bits) override {
        if (len != STRIDE) return PB_NOT_REPRESENTABLE;
        std::uint8_t r = flag & 0x0FU;
        /* The codec fixes its base robustness at construction, so it is built at the first packet. */
        if (!started) {
            comp = Compressor<N>(r);
            comp.set_initial_mask(mask);
            robustness = r;
            started = true;
        } else if (r != robustness) {
            return PB_NOT_REPRESENTABLE;
        }
        BitVector<N> input_vec;
        input_vec.from_bytes(field, len);
        CompressParams params;
        params.min_robustness = r;
        params.send_mask_flag = (flag >> 6) & 1U;
        params.new_mask_flag = (flag >> 5) & 1U;
        params.uncompressed_flag = (flag >> 4) & 1U;
        BitBuffer<N * 6> packet_output;
        Error e = comp.compress_packet(input_vec, packet_output, &params);
        if (e != Error::Ok) return static_cast<int>(e);
        std::size_t bytes = (packet_output.size() + 7) / 8;
        if (bytes > out_cap) return PB_OUTPUT_FULL;
        packet_output.to_bytes(out, bytes);
        *out_bits = static_cast<unsigned long>(packet_output.size());
        return 0;
    }
};

template <std::size_t N> struct DecN final : Dec {
    Decompressor<N> decomp;

    int packet(const unsigned char *data, unsigned long avail_bits, unsigned char *out,
               unsigned long *out_bits) override {
        BitReader reader(data, avail_bits);
        BitVector<N> output;
        if (decomp.decompress_packet(reader, output) != Error::Ok) return PB_FAILED;
        output.to_bytes(out, (N + 7) / 8);
        *out_bits = N;
        return PB_DECODED;
    }
    unsigned long trailer() override { return N; }
};

/* The codec has no F discovery, so with F unknown every packet reports failed. */
struct DecUnknown final : Dec {
    int packet(const unsigned char *, unsigned long, unsigned char *, unsigned long *) override {
        return PB_FAILED;
    }
    unsigned long trailer() override { return 0; }
};

template <std::size_t N> static EncN<N> &enc_slot() {
    static EncN<N> slot;
    return slot;
}

template <std::size_t N> static DecN<N> &dec_slot() {
    static DecN<N> slot;
    return slot;
}

static int rcpp_enc_create(void *, int f_present, unsigned long f, const pb_record *mask,
                           void **state) {
    if (!f_present || !mask) return PB_NOT_REPRESENTABLE;
#define CREATE(bits)                                                                   \
    if (f == (bits)) {                                                                 \
        EncN<bits> &e = enc_slot<bits>();                                              \
        if (mask->len != EncN<bits>::STRIDE) return PB_NOT_REPRESENTABLE;              \
        e.mask.from_bytes(mask->data, mask->len);                                      \
        e.started = false;                                                             \
        *state = static_cast<Enc *>(&e);                                               \
        return 0;                                                                      \
    }
    SUPPORTED_SIZES(CREATE)
#undef CREATE
    return PB_NOT_REPRESENTABLE;
}

static int rcpp_enc_packet(void *state, unsigned char flag, const unsigned char *field,
                           std::size_t len, unsigned char *out, std::size_t out_cap,
                           unsigned long *out_bits) {
    return static_cast<Enc *>(state)->packet(flag, field, len, out, out_cap, out_bits);
}

static void rcpp_destroy(void *) {}

static int rcpp_dec_create(void *, unsigned long f, const pb_record *, std::size_t, void **state) {
    static DecUnknown unknown;
    if (f == 0) {
        *state = static_cast<Dec *>(&unknown);
        return 0;
    }
#define CREATE(bits)                                \
    if (f == (bits)) {                              \
        DecN<bits> &d = dec_slot<bits>();           \
        d.decomp = Decompressor<bits>();            \
        *state = static_cast<Dec *>(&d);            \
        return 0;                                   \
    }
    SUPPORTED_SIZES(CREATE)
#undef CREATE
    return PB_NOT_REPRESENTABLE;
}

static int rcpp_dec_packet(void *state, const unsigned char *data, std::size_t, unsigned long,
                           unsigned long avail_bits, unsigned char *out, std::size_t,
                           unsigned long *out_bits) {
    return static_cast<Dec *>(state)->packet(data, avail_bits, out, out_bits);
}

/* The codec has no packet-loss notification. */
static void rcpp_dec_lost(void *) {}

static unsigned long rcpp_dec_trailer_f(void *state) {
    return static_cast<Dec *>(state)->trailer();
}

int main(int argc, char **argv) {
    pb_adapter a;
    std::memset(&a, 0, sizeof a);
    a.caps.build_profile = "g++ -std=c++17 -O3 -flto";
    a.caps.limitations =
        "compiled for 720-bit (90-byte) and 32912-bit (4114-byte) packets only (packet length is "
        "a compile-time template parameter, see SUPPORTED_SIZES), so any other F is not "
        "representable; the base robustness is fixed at the first packet, so a later packet with "
        "a different R is not representable; no F discovery and no packet-loss notification";
    a.enc_create = rcpp_enc_create;
    a.enc_packet = rcpp_enc_packet;
    a.enc_destroy = rcpp_destroy;
    a.dec_create = rcpp_dec_create;
    a.dec_packet = rcpp_dec_packet;
    a.dec_lost = rcpp_dec_lost;
    a.dec_trailer_f = rcpp_dec_trailer_f;
    a.dec_destroy = rcpp_destroy;
    return pb_main(argc, argv, &a);
}
