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
static STAINLESS_FUNC_PURE int32_t countWidth(int32_t a);
static STAINLESS_FUNC_PURE int32_t log2Floor(int32_t n);
static STAINLESS_FUNC_PURE int32_t size(BitArray* thiss);
static STAINLESS_FUNC_PURE int32_t storedSize(BitArray* thiss);
static void update(BitArray* thiss, int32_t i, bool v);
static STAINLESS_FUNC_PURE bool wellSized(BitArray* thiss);
static void writeBit(BitArray* out, int32_t pos, bool b);
static void writeBits(BitArray* out, int32_t pos, int32_t value, int32_t n);
static void writeBitsFrom(BitArray* out, int32_t pos, int32_t rem, int32_t k, int32_t half);
static int32_t writeCount(BitArray* out, int32_t pos, int32_t a);
static int32_t writeRunLengthFrom(BitArray* x, int32_t n, int32_t run, BitArray* out, int32_t pos);


/* --------------------------- global variables ----- */

static const int32_t MaxF = 65535;
static const int32_t ErrMalformed = -2;
static const int32_t MaxR = 7;
static const uint8_t AllSlotsZero = 127;
static const int32_t ErrCapacity = -1;


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

static STAINLESS_FUNC_PURE int32_t countWidth(int32_t a) {
    return 2 * (log2Floor(a - 2) + 1) - 6;
}

STAINLESS_FUNC_PURE int32_t decodeStep(DecoderState* st, BitArray* packet, BitArray* out) {
    assert(wellSized(packet));
    assert((wellSized(out) && storedSize(out) == st->f));
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
    assert((wellSized(input) && storedSize(input) == st->f));
    assert((0 <= robustnessT && robustnessT <= MaxR));
    assert((st->t > robustnessT || sendMaskFlag && uncompressedFlag));
    assert((wellSized(out) && storedSize(out) <= maxOutputBits(MaxF)));
    return 0;
}

void encoderInit(EncoderState* st, BitArray* initialMask) {
    assert((wellSized(initialMask) && storedSize(initialMask) == st->f));
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

static STAINLESS_FUNC_PURE int32_t log2Floor(int32_t n) {
    if (n <= 63) {
        return 5;
    } else if (n <= 127) {
        return 6;
    } else if (n <= 255) {
        return 7;
    } else if (n <= 511) {
        return 8;
    } else if (n <= 1023) {
        return 9;
    } else if (n <= 2047) {
        return 10;
    } else if (n <= 4095) {
        return 11;
    } else if (n <= 8191) {
        return 12;
    } else if (n <= 16383) {
        return 13;
    } else if (n <= 32767) {
        return 14;
    } else {
        return 15;
    }
}

STAINLESS_FUNC_PURE int32_t maxOutputBits(int32_t f) {
    assert((1 <= f && f <= MaxF));
    return ((int32_t)(((uint32_t)(19 * f + 1)) >> 1)) + 44;
}

static STAINLESS_FUNC_PURE int32_t size(BitArray* thiss) {
    return thiss->_isize;
}

static STAINLESS_FUNC_PURE int32_t storedSize(BitArray* thiss) {
    return thiss->_isize;
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

static STAINLESS_FUNC_PURE bool wellSized(BitArray* thiss) {
    return (thiss->data.length <= ((int32_t)(((uint32_t)2147483647) >> 3)) && 0 <= thiss->_isize) && thiss->_isize <= 8 * thiss->data.length;
}

static void writeBit(BitArray* out, int32_t pos, bool b) {
    update(out, pos, b);
}

static void writeBits(BitArray* out, int32_t pos, int32_t value, int32_t n) {
    writeBitsFrom(out, pos, value, n, (int32_t)(((uint32_t)1) << n - 1));
}

static void writeBitsFrom(BitArray* out, int32_t pos, int32_t rem, int32_t k, int32_t half) {
    BitArray* out_0 = out;
    int32_t pos_0 = pos;
    int32_t k_1 = k;
    int32_t rem_0 = rem;
    int32_t half_0 = half;
    label_1: ;
        bool bit = rem_0 >= half_0;
        if (k_1 > 1) {
            int32_t rest;
            if (bit) {
                rest = rem_0 - half_0;
            } else {
                rest = rem_0;
            }
            writeBit(out_0, pos_0, bit);
            BitArray* out_0_0 = out_0;
            int32_t pos_0_0 = pos_0 + 1;
            int32_t rem_0_0 = rest;
            int32_t k_1_0 = k_1 - 1;
            int32_t half_0_0 = (int32_t)(((uint32_t)half_0) >> 1);
            out_0 = out_0_0;
            pos_0 = pos_0_0;
            rem_0 = rem_0_0;
            k_1 = k_1_0;
            half_0 = half_0_0;
            goto label_1;
        } else {
            writeBit(out_0, pos_0, bit);
        };
}

static int32_t writeCount(BitArray* out, int32_t pos, int32_t a) {
    int32_t room = size(out) - pos;
    if (a == 1) {
        if (room < 1) {
            return ErrCapacity;
        } else {
            writeBit(out, pos, false);
            return pos + 1;
        }
    } else if (a <= 33) {
        if (room < 8) {
            return ErrCapacity;
        } else {
            writeBits(out, pos, 6, 3);
            writeBits(out, pos + 3, a - 2, 5);
            return pos + 8;
        }
    } else {
        int32_t e = countWidth(a);
        if (room < 3 + e) {
            return ErrCapacity;
        } else {
            writeBits(out, pos, 7, 3);
            writeBits(out, pos + 3, a - 2, e);
            return (pos + 3) + e;
        }
    }
}

static int32_t writeRunLengthFrom(BitArray* x, int32_t n, int32_t run, BitArray* out, int32_t pos) {
    BitArray* out_1 = out;
    int32_t pos_1 = pos;
    BitArray* x_0 = x;
    int32_t n_0 = n;
    int32_t run_0 = run;
    label_2: ;
        if (n_0 == 0) {
            if (size(out_1) - pos_1 < 2) {
                return ErrCapacity;
            } else {
                writeBit(out_1, pos_1, true);
                writeBit(out_1, pos_1 + 1, false);
                return pos_1 + 2;
            }
        } else if (apply(x_0, n_0 - 1)) {
            int32_t next = writeCount(out_1, pos_1, run_0 + 1);
            if (next < 0) {
                return next;
            } else {
                BitArray* x_0_0 = x_0;
                int32_t n_0_0 = n_0 - 1;
                int32_t run_0_0 = 0;
                BitArray* out_1_0 = out_1;
                int32_t pos_1_0 = next;
                x_0 = x_0_0;
                n_0 = n_0_0;
                run_0 = run_0_0;
                out_1 = out_1_0;
                pos_1 = pos_1_0;
                goto label_2;
            }
        } else {
            BitArray* x_0_1 = x_0;
            int32_t n_0_1 = n_0 - 1;
            int32_t run_0_1 = run_0 + 1;
            BitArray* out_1_1 = out_1;
            int32_t pos_1_1 = pos_1;
            x_0 = x_0_1;
            n_0 = n_0_1;
            run_0 = run_0_1;
            out_1 = out_1_1;
            pos_1 = pos_1_1;
            goto label_2;
        };
}

int32_t writeRunLengthReversed(BitArray* x, BitArray* out, int32_t pos) {
    assert(((size(x) <= MaxF && 0 <= pos) && pos <= size(out)));
    return writeRunLengthFrom(x, size(x), 0, out, pos);
}
