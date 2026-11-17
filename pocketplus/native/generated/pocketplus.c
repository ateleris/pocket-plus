/* --------------------------- GenC requirements ----- */

#include <limits.h>
#if (__STDC_VERSION__ < 199901L) || (CHAR_BIT != 8)
#error "Your compiler does not meet the minimum requirements of GenC. Please see"
#error "https://epfl-lara.github.io/stainless/genc.html#requirements for more details."
#endif

/* ---------------------------- include header ------- */

#include "pocketplus.h"

/* ----------------------------------- includes ----- */

#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>







/* ---------------------- function declarations ----- */

static STAINLESS_FUNC_PURE bool apply(BitArray* thiss, int32_t i);
static void clear(BitArray* thiss);
static void clearFrom(BitArray* thiss, int32_t k);
static void copyBits(BitArray* src, BitArray* dst);
static STAINLESS_FUNC_PURE int32_t size(BitArray* thiss);
static void update(BitArray* thiss, int32_t i, bool v);


/* --------------------------- global variables ----- */

static const int32_t MaxF = 65535;
static const int32_t ErrMalformed = -2;
static const int32_t MaxR = 7;
static const uint8_t AllSlotsZero = 127;


/* ----------------------- function definitions ----- */

static STAINLESS_FUNC_PURE bool apply(BitArray* thiss, int32_t i) {
    uint8_t byte = thiss->data.data[(int32_t)(((uint32_t)i) >> 3)];
    return (byte & 128 >> ((uint8_t)((uint32_t)(i & 7)))) != 0;
}

STAINLESS_FUNC_PURE int32_t bytesPerVector(int32_t f) {
    assert((1 <= f && f <= MaxF));
    return (int32_t)(((uint32_t)(f + 7)) >> 3);
}

static void clear(BitArray* thiss) {
    clearFrom(thiss, 0);
}

static void clearFrom(BitArray* thiss, int32_t k) {
    BitArray* thiss_0 = thiss;
    int32_t k_0 = k;
    label_0: ;
        if (k_0 < thiss_0->data.length) {
            thiss_0->data.data[k_0] = 0;
            BitArray* thiss_0_0 = thiss_0;
            int32_t k_0_0 = k_0 + 1;
            thiss_0 = thiss_0_0;
            k_0 = k_0_0;
            goto label_0;
        };
}

static void copyBits(BitArray* src, BitArray* dst) {
    int32_t n = size(dst);
    int32_t i = 0;
    while (i < n) {
        update(dst, i, apply(src, i));
        i = i + 1;
    }
}

STAINLESS_FUNC_PURE int32_t decodeStep(DecoderState* st, BitArray* packet, BitArray* out) {
    assert((size(out) == st->f));
    return ErrMalformed;
}

void decoderInit(DecoderState* st) {
    st->t = 0;
    clear(&st->lastInput);
    clear(&st->mask);
    st->unknownMasks = 0;
    st->unknownInputs = 0;
}

STAINLESS_FUNC_PURE int32_t encodeStep(EncoderState* st, BitArray* input, bool newMaskFlag, bool sendMaskFlag, bool uncompressedFlag, int32_t robustnessT, BitArray* out) {
    assert((size(input) == st->f));
    assert((0 <= robustnessT && robustnessT <= MaxR));
    assert((st->t > robustnessT || sendMaskFlag && uncompressedFlag));
    assert((size(out) <= maxOutputBits(MaxF)));
    return 0;
}

void encoderInit(EncoderState* st, BitArray* initialMask) {
    assert((size(initialMask) == st->f));
    st->t = 0;
    clear(&st->lastInput);
    copyBits(initialMask, &st->mask);
    clear(&st->build);
    clear(&st->changeHistory);
    st->changeHistoryZero = AllSlotsZero;
    st->historyHead = 0;
    st->zeroRunBeyond = 0;
    st->newMaskHistory = 0;
}

STAINLESS_FUNC_PURE int32_t maxOutputBits(int32_t f) {
    assert((1 <= f && f <= MaxF));
    return ((int32_t)(((uint32_t)(19 * f + 1)) >> 1)) + 44;
}

static STAINLESS_FUNC_PURE int32_t size(BitArray* thiss) {
    return thiss->isize;
}

static void update(BitArray* thiss, int32_t i, bool v) {
    int32_t byteIndex = (int32_t)(((uint32_t)i) >> 3);
    uint8_t mask = 128 >> ((uint8_t)((uint32_t)(i & 7)));
    uint8_t byte = thiss->data.data[byteIndex];
    uint8_t newByte;
    if (v) {
        newByte = byte | mask;
    } else {
        newByte = byte & ((uint8_t)(~mask));
    }
    thiss->data.data[byteIndex] = newByte;
}
