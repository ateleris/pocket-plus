/*
 * pp_shim — hand-written, malloc-backed lifecycle + ABI over the GenC-generated POCKET+ codec.
 *
 * The generated functions (compress/decompress/...) take structs full of (bool*, int32_t) array
 * views and require the caller to pre-allocate every buffer. This shim hides that behind opaque
 * handles so Python (ctypes) only ever passes flat (bool*, length) pairs. Generated code is unchanged.
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

typedef struct PPCompressor   PPCompressor;
typedef struct PPDecompressor PPDecompressor;

/* Worst-case compressed packet size, in bits, for a vector of n bits: 4 + 64*(n+1). */
PP_API int32_t pp_compress_output_capacity_bits(int32_t n);

/* ---- compressor ---- */
PP_API PPCompressor* pp_compressor_create(int32_t n);
PP_API void          pp_compressor_free(PPCompressor* c);
PP_API void          pp_compressor_set_initial_mask(PPCompressor* c, const bool* mask, int32_t n);
/* Writes the packet into out[0..out_cap); returns its length in bits (or <0 on a bad argument). */
PP_API int32_t       pp_compress(PPCompressor* c, const bool* input, int32_t n,
                                 int32_t robustnessLevel, bool newMaskFlag,
                                 bool sendMaskFlag, bool uncompressedFlag,
                                 bool* out, int32_t out_cap);

/* ---- decompressor ---- */
PP_API PPDecompressor* pp_decompressor_create(int32_t n);
PP_API void            pp_decompressor_free(PPDecompressor* d);
/* Reads one packet from stream[bitOffset..); writes n reconstructed bits to out; returns next offset. */
PP_API int32_t         pp_decompress(PPDecompressor* d, const bool* stream, int32_t stream_len,
                                     int32_t bitOffset, bool* out, int32_t n);

#ifdef __cplusplus
}
#endif

#endif /* PP_SHIM_H */
