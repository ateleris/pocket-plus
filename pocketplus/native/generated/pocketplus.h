#ifndef __POCKETPLUS_H__
#define __POCKETPLUS_H__

/* --------------------------- preprocessor macros ----- */

#define STAINLESS_FUNC_PURE
#if defined(__cplusplus)
#undef STAINLESS_FUNC_PURE
#define STAINLESS_FUNC_PURE _Pragma("FUNC_IS_PURE;")
#elif __GNUC__>=3
#undef STAINLESS_FUNC_PURE
#define STAINLESS_FUNC_PURE __attribute__((__pure__))
#elif defined(__has_attribute)
#if __has_attribute(pure)
#undef STAINLESS_FUNC_PURE
#define STAINLESS_FUNC_PURE __attribute__((__pure__))
#endif
#endif


/* ----------------------------------- includes ----- */

#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>




/* ---------------------- data type definitions ----- */

typedef struct {
  uint8_t* data;
  int32_t length;
} array_uint8;

typedef struct {
  array_uint8 data;
  int32_t isize;
} BitArray;

typedef struct {
  int32_t f;
  int32_t t;
  BitArray lastInput;
  BitArray mask;
  int32_t unknownMasks;
  int32_t unknownInputs;
  BitArray xt;
  BitArray sentMask;
} DecoderState;

typedef struct {
  int32_t f;
  int32_t t;
  BitArray lastInput;
  BitArray mask;
  BitArray build;
  BitArray changeHistory;
  uint8_t changeHistoryZero;
  int32_t historyHead;
  int32_t zeroRunBeyond;
  uint16_t newMaskHistory;
  BitArray xt;
  BitArray yt;
} EncoderState;



/* ---------------------- function declarations ----- */

STAINLESS_FUNC_PURE int32_t bytesPerVector(int32_t f);
STAINLESS_FUNC_PURE int32_t decodeStep(DecoderState* st, BitArray* packet, BitArray* out);
void decoderInit(DecoderState* st);
STAINLESS_FUNC_PURE int32_t encodeStep(EncoderState* st, BitArray* input, bool newMaskFlag, bool sendMaskFlag, bool uncompressedFlag, int32_t robustnessT, BitArray* out);
void encoderInit(EncoderState* st, BitArray* initialMask);
STAINLESS_FUNC_PURE int32_t maxOutputBits(int32_t f);
int32_t writeRunLengthReversed(BitArray* x, BitArray* out, int32_t pos);

#endif
