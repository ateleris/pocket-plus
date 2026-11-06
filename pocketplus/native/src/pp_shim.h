/*
 * pp_shim — hand-written, malloc-backed lifecycle + ABI over the GenC-generated POCKET+ codec
 * (scala/src/main/scala/pocket/PocketImperative.scala).
 *
 * The generated entry points take a state struct full of (uint8_t*, int32_t) array views that the
 * caller must allocate, and assert their preconditions. This shim hides the struct behind opaque
 * handles, allocates every buffer once at creation (R-PERF-04), and checks the arguments before each
 * call so a bad argument returns PP_ERR_ARGUMENT instead of tripping an assert (R-PERF-07).
 * Generated code is unchanged.
 *
 * Vectors of f bits are packed MSB first into pp_bytes_per_vector(f) bytes; the unused low bits of
 * the last byte must be zero. Every vector buffer passed to or from a call has exactly that size.
 */
#ifndef PP_SHIM_H
#define PP_SHIM_H

#include <stdint.h>
#include <stdbool.h>

#if defined(_WIN32)
#  if defined(PP_BUILD_DLL)
#    define PP_API __declspec(dllexport)
#  else
#    define PP_API __declspec(dllimport)
#  endif
#else
#  define PP_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Error codes, the values of Errors in PocketImperative.scala. */
#define PP_ERR_CAPACITY  (-1) /* output buffer too small for this vector */
#define PP_ERR_MALFORMED (-2) /* packet fails a validity check (green book 3.3.6) */
#define PP_ERR_ARGUMENT  (-3) /* argument out of range or inconsistent with the state */

typedef struct PPCompressor   PPCompressor;
typedef struct PPDecompressor PPDecompressor;

/* Bytes holding a vector of f bits, or PP_ERR_ARGUMENT unless 1 <= f <= 65535. */
PP_API int32_t pp_bytes_per_vector(int32_t f);
/* Worst-case output vector length in bits, 9.5 f + 44 (green book table 5-1), or PP_ERR_ARGUMENT. */
PP_API int32_t pp_compress_output_capacity_bits(int32_t f);

/* ---- compressor ---- */
/* Starts a stream of f-bit vectors with initial mask M_0 = initial_mask, or all zero if NULL.
   Returns NULL if f is out of range or allocation fails. */
PP_API PPCompressor* pp_compressor_create(int32_t f, const uint8_t* initial_mask);
PP_API void          pp_compressor_free(PPCompressor* c);
/* Compresses input I_t with the parameters of this step (blue book 3.3.2): robustness level R_t in
   0..7, p_t, f_t and r_t; f_t and r_t must be set while t <= R_t. Writes o_t into out, of
   (out_capacity_bits + 7) / 8 bytes, and returns its length in bits, or a negative error code. */
PP_API int32_t       pp_compress(PPCompressor* c, const uint8_t* input, int32_t robustness,
                                 bool new_mask_flag, bool send_mask_flag, bool uncompressed_flag,
                                 uint8_t* out, int32_t out_capacity_bits);

/* ---- decompressor ---- */
/* Starts decoding a stream of f-bit vectors. Returns NULL if f is out of range or allocation fails. */
PP_API PPDecompressor* pp_decompressor_create(int32_t f);
PP_API void            pp_decompressor_free(PPDecompressor* d);
/* Decodes the output vector at the front of packet, of (packet_bits + 7) / 8 bytes, and writes I_t
   into out. Returns the number of bits consumed, or a negative error code with the state unchanged.
   The caller checks the bits left over: padding on the packet path, the next vector in a file. */
PP_API int32_t         pp_decompress(PPDecompressor* d, const uint8_t* packet, int32_t packet_bits,
                                     uint8_t* out);

#ifdef __cplusplus
}
#endif

#endif /* PP_SHIM_H */
