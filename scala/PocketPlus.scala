package asn1scala

import stainless.lang._
import stainless.annotation._

/**
 * POCKET+ / CCSDS 124.0-B-1 — Stainless implementation, written in the GenC-compatible
 * subset so it can be transpiled to C.
 *
 * GenC constraints that shape the whole design:
 *   - No `BigInt` and no bit-wise operators on user data: everything is `Int`, and COUNT /
 *     big-endian encoding use plain arithmetic (`/`, `%`, `*`) via [[BitOps.pow2]].
 *   - All bit vectors are stored as packed [[BitStream]] (one bit per stored bit, eight per
 *     byte in `buf`). Two access patterns live side by side:
 *       - random access for the persistent state and per-call scratch (read/write at an
 *         arbitrary bit index via [[BitOps.bitAtBs]] / [[BitOps.setBitAtBs]] — cursor untouched);
 *       - sequential append/read for the encoded packet (the `out`/`input` BitStream's cursor
 *         is mutated by `appendBit`/`readBit`/`moveBitIndex`).
 *   - No `stainless.collection.List`: case-class state is mutated in place; nothing array-typed
 *     is returned. The deque histories (mask-change vectors, mask flags) live in flat slabs
 *     with explicit counts; row 0 is the newest entry.
 *
 * The API therefore diverges from the C# (which returned growing `List<bool>`):
 *   - `compress` writes the packet into the cursor of `out`. After the call,
 *     `out.bitIndex` is the bit length of the packet.
 *   - `decompress` reads from `input` starting at its current cursor and writes the
 *     reconstructed n-bit vector into bits 0..n-1 of `out.buf`. After the call,
 *     `input.bitIndex` points at the first bit of the next packet (already byte-aligned).
 *
 * Generated C: the per-call working buffers become C99/C11 variable-length arrays, so the
 * GenC output compiles cleanly with gcc and clang (`-std=c11 -Wall -Wextra`).
 */
object BitOps {
  val BitsPerByte: Int = 8

  /** 2^i, by repeated multiplication (GenC has no shifts). */
  def pow2(i: Int): Int = {
    require(i >= 0 && i <= 30)
    var r = 1
    var k = 0
    (while (k < i) {
      decreases(i - k)
      r = r * 2
      k = k + 1
    }).invariant(k >= 0 && k <= i && r >= 1)
    r
  }

  /** Number of bits needed to represent `value` (0 for value 0). */
  def bitLength(value: Int): Int = {
    require(value >= 0)
    var v = value
    var n = 0
    (while (v > 0) {
      decreases(v)
      n = n + 1
      v = v / 2
    }).invariant(n >= 0 && v >= 0)
    n
  }

  /** Random read of bit at absolute index `at` in `bs.buf`. Cursor untouched. */
  def bitAtBs(bs: BitStream, at: Int): Boolean = {
    require(0 <= at && at < bs.buf.length * BitsPerByte)
    val byteIx = at / BitsPerByte
    val bitIx = at % BitsPerByte
    (bs.buf(byteIx) & BitAccessMasks(bitIx)) != 0
  }

  /** Random write of bit at absolute index `at` in `bs.buf`. Cursor untouched. */
  def setBitAtBs(bs: BitStream, at: Int, v: Boolean): Unit = {
    require(0 <= at && at < bs.buf.length * BitsPerByte)
    val byteIx = at / BitsPerByte
    val bitIx = at % BitsPerByte
    val mask = BitAccessMasks(bitIx)
    bs.buf(byteIx) = stainless.math.wrapping {
      ((bs.buf(byteIx) & ~mask) | (if (v) mask else 0)).toByte
    }
  }

  /** Sets `a[0 until len]` to false (bit indices 0..len-1 of `a.buf`). */
  def zeroFill(a: BitStream, len: Int): Unit = {
    require(len >= 0 && len <= a.buf.length * BitsPerByte)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      setBitAtBs(a, i, false)
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** Copies bits 0..len-1 from `src.buf` to `dst.buf`. */
  def copyRange(src: BitStream, len: Int, dst: BitStream): Unit = {
    require(len >= 0 && len <= src.buf.length * BitsPerByte && len <= dst.buf.length * BitsPerByte)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      setBitAtBs(dst, i, bitAtBs(src, i))
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** dst[i] = src[len-1-i] for i in 0..len-1. Denoted <A> in the standard. dst must differ from src. */
  def reverseInto(src: BitStream, len: Int, dst: BitStream): Unit = {
    require(len >= 0 && len <= src.buf.length * BitsPerByte && len <= dst.buf.length * BitsPerByte)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      setBitAtBs(dst, i, bitAtBs(src, len - 1 - i))
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** Reverses bits 0..len-1 of `a.buf` in place. */
  def reverseInPlace(a: BitStream, len: Int): Unit = {
    require(len >= 0 && len <= a.buf.length * BitsPerByte)
    var i = 0
    var k = len - 1
    (while (i < k) {
      decreases(k - i)
      val tmp = bitAtBs(a, i)
      setBitAtBs(a, i, bitAtBs(a, k))
      setBitAtBs(a, k, tmp)
      i = i + 1
      k = k - 1
    }).invariant(i >= 0 && k < len)
  }

  /** dst[i] = !src[i]. Denoted ~A in the standard. */
  def inverseInto(src: BitStream, len: Int, dst: BitStream): Unit = {
    require(len >= 0 && len <= src.buf.length * BitsPerByte && len <= dst.buf.length * BitsPerByte)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      setBitAtBs(dst, i, !bitAtBs(src, i))
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** Number of set bits in `a[0 until len]`. */
  def hammingWeight(a: BitStream, len: Int): Int = {
    require(len >= 0 && len <= a.buf.length * BitsPerByte)
    var i = 0
    var w = 0
    (while (i < len) {
      decreases(len - i)
      if (bitAtBs(a, i)) w = w + 1
      i = i + 1
    }).invariant(i >= 0 && i <= len && w >= 0)
    w
  }

  /** True iff bits [offset, offset+len) of `a.buf` are all zero. */
  def allZero(a: BitStream, offset: Int, len: Int): Boolean = {
    require(offset >= 0 && len >= 0 && offset + len <= a.buf.length * BitsPerByte)
    var i = 0
    var result = true
    (while (i < len) {
      decreases(len - i)
      if (bitAtBs(a, offset + i)) result = false
      i = i + 1
    }).invariant(i >= 0 && i <= len)
    result
  }

  /** Bits of `b` at the positions where `a` is set (5.3.1.3); writes into `dst`, returns the count. */
  def bitExtractionInto(b: BitStream, a: BitStream, len: Int, dst: BitStream): Int = {
    require(
      len >= 0 && len <= a.buf.length * BitsPerByte &&
      len <= b.buf.length * BitsPerByte && len <= dst.buf.length * BitsPerByte
    )
    var i = 0
    var k = 0
    (while (i < len) {
      decreases(len - i)
      if (bitAtBs(a, i)) {
        setBitAtBs(dst, k, bitAtBs(b, i))
        k = k + 1
      }
      i = i + 1
    }).invariant(i >= 0 && i <= len && k >= 0 && k <= i)
    k
  }

  /** Appends `width` bits of `value`, MSB first, to `dst` at its cursor. */
  def appendBigEndian(dst: BitStream, value: Int, width: Int): Unit = {
    require(value >= 0 && width >= 0 && width <= 30)
    require(BitStream.validate_offset_bits(dst.buf.length.toLong, dst.currentByte.toLong, dst.currentBit.toLong, width.toLong))
    var i = width - 1
    (while (i >= 0) {
      decreases(i + 1)
      dst.appendBit((value / pow2(i)) % 2 == 1)
      i = i - 1
    }).invariant(i >= -1 && i < width)
  }

  /** Appends bits 0..srcLen-1 of `src.buf` to `dst` at its cursor. */
  def appendBits(dst: BitStream, src: BitStream, srcLen: Int): Unit = {
    require(srcLen >= 0 && srcLen <= src.buf.length * BitsPerByte)
    require(BitStream.validate_offset_bits(dst.buf.length.toLong, dst.currentByte.toLong, dst.currentBit.toLong, srcLen.toLong))
    var i = 0
    (while (i < srcLen) {
      decreases(srcLen - i)
      dst.appendBit(bitAtBs(src, i))
      i = i + 1
    }).invariant(i >= 0 && i <= srcLen)
  }

  /** Appends reverse(bits 0..srcLen-1 of `src.buf`) to `dst` at its cursor. */
  def appendReversed(dst: BitStream, src: BitStream, srcLen: Int): Unit = {
    require(srcLen >= 0 && srcLen <= src.buf.length * BitsPerByte)
    require(BitStream.validate_offset_bits(dst.buf.length.toLong, dst.currentByte.toLong, dst.currentBit.toLong, srcLen.toLong))
    var i = 0
    (while (i < srcLen) {
      decreases(srcLen - i)
      dst.appendBit(bitAtBs(src, srcLen - 1 - i))
      i = i + 1
    }).invariant(i >= 0 && i <= srcLen)
  }

  /** In-place bitwise OR over bits 0..len-1: dst[i] = dst[i] || src[i]. */
  def orInto(dst: BitStream, src: BitStream, len: Int): Unit = {
    require(len >= 0 && len <= dst.buf.length * BitsPerByte && len <= src.buf.length * BitsPerByte)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      setBitAtBs(dst, i, bitAtBs(dst, i) || bitAtBs(src, i))
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }
}

/**
 * COUNT(a): self-delimiting encoding of a positive integer (5.3.1.1), appended to `dst` at its
 * current cursor. Caller reads the new bit position from `dst.bitIndex`.
 */
object Count {
  val MaxInputVectorLength: Int = 65535
  val CountFiveBitMin: Int = 2
  val CountFiveBitMax: Int = 33
  val CountFiveBitWidth: Int = 5
  val CountExtendedBaseWidth: Int = 6

  def encode(dst: BitStream, a: Int): Unit = {
    require(a >= 1 && a <= MaxInputVectorLength)
    require(BitStream.validate_offset_bits(dst.buf.length.toLong, dst.currentByte.toLong, dst.currentBit.toLong, 64L))
    if (a == 1) {
      dst.appendBit(false)
    } else if (a >= CountFiveBitMin && a <= CountFiveBitMax) {
      dst.appendBit(true)
      dst.appendBit(true)
      dst.appendBit(false)
      BitOps.appendBigEndian(dst, a - CountFiveBitMin, CountFiveBitWidth)
    } else {
      val value = a - CountFiveBitMin
      val bl = BitOps.bitLength(value)
      dst.appendBit(true)
      dst.appendBit(true)
      dst.appendBit(true)
      BitOps.appendBigEndian(dst, value, (2 * bl) - CountExtendedBaseWidth)
    }
  }

  /** Run-length encoding of zero runs: emit COUNT(distance) at every set bit (5.3.1.2). */
  def runLengthEncoding(dst: BitStream, src: BitStream, srcLen: Int): Unit = {
    require(srcLen >= 0 && srcLen <= src.buf.length * BitOps.BitsPerByte)
    var zeroCounter = 0
    var i = 0
    (while (i < srcLen) {
      decreases(srcLen - i)
      zeroCounter = zeroCounter + 1
      val hasRoom = BitStream.validate_offset_bits(dst.buf.length.toLong, dst.currentByte.toLong, dst.currentBit.toLong, 64L)
      if (BitOps.bitAtBs(src, i) && hasRoom && zeroCounter >= 1 && zeroCounter <= MaxInputVectorLength) {
        encode(dst, zeroCounter)
        zeroCounter = 0
      }
      i = i + 1
    }).invariant(i >= 0 && i <= srcLen && zeroCounter >= 0)
  }
}

/**
 * Stateful POCKET+ compressor. All persistent buffers are pre-allocated and sized for a fixed
 * input vector length `n`. The deque histories (mask-change vectors, mask flags) are stored in
 * flat slabs with explicit counts; row 0 is the newest entry.
 *
 * Each BitStream field holds its bits in `buf` at fixed positions (bit 0 of the logical vector
 * lives in MSB of `buf(0)` per [[BitAccessMasks]]). Cursors are unused for these fields.
 */
case class CompressorState(
  n: Int,
  maskNew: BitStream,                // [n bits]   current mask M_t
  maskOld: BitStream,                // [n bits]   previous mask
  maskBuildNew: BitStream,           // [n bits]   accumulated input changes this period
  maskBuildOld: BitStream,           // [n bits]
  inputOld: BitStream,               // [n bits]   previous input vector
  inputVectorLengthCount: BitStream, // [>=64 bits] precomputed COUNT(n)
  var inputVectorLengthCountLen: Int,
  maskChangeVector: BitStream,       // [16*n bits] history of mask change vectors D, row 0 newest
  var maskChangeCount: Int,          // rows in use (<= 16)
  maskFlag: BitStream,               // [16 bits] history of the new-mask flag p_t, [count-1] newest
  var maskFlagCount: Int,            // entries in use (<= 16)
  var t: Int
) {
  require(
    n >= 1 && n <= 65535 &&
    maskNew.buf.length == (n + 7) / 8 && maskOld.buf.length == (n + 7) / 8 &&
    maskBuildNew.buf.length == (n + 7) / 8 && maskBuildOld.buf.length == (n + 7) / 8 &&
    inputOld.buf.length == (n + 7) / 8 &&
    inputVectorLengthCount.buf.length >= 8 &&
    maskChangeVector.buf.length == (16 * n + 7) / 8 && maskFlag.buf.length == 2 &&
    inputVectorLengthCountLen >= 0 && maskChangeCount >= 0 && maskChangeCount <= 16 &&
    maskFlagCount >= 0 && maskFlagCount <= 16 && t >= 0
  )
}

/**
 * Stateful POCKET+ compressor. Every loop is its own named function so that the orchestrating
 * `compress` contains no `while` loops: this keeps the lowered AST shallow (avoiding a
 * StackOverflow in Stainless' TypeEncoding phase) and avoids two lifted loops colliding in one
 * function. State is mutated in place; nothing array-typed is returned.
 */
object Compressor {
  val MaxRobustnessLevel: Int = 7
  val MaxEffectiveRobustness: Int = 15
  val EffectiveRobustnessBitWidth: Int = 4
  val MaskChangeHistoryLimit: Int = 15
  val MaskFlagHistoryLimit: Int = 16

  // ---- per-block helpers, each with at most one while loop ----

  /** Mask-flag history: append newest at the end, dropping the oldest once full. */
  def pushMaskFlag(st: CompressorState, newMaskFlag: Boolean): Unit = {
    if (st.maskFlagCount < MaskFlagHistoryLimit) {
      BitOps.setBitAtBs(st.maskFlag, st.maskFlagCount, newMaskFlag)
      st.maskFlagCount = st.maskFlagCount + 1
    } else {
      var i = 0
      (while (i < MaskFlagHistoryLimit - 1) {
        decreases(MaskFlagHistoryLimit - 1 - i)
        BitOps.setBitAtBs(st.maskFlag, i, BitOps.bitAtBs(st.maskFlag, i + 1))
        i = i + 1
      }).invariant(i >= 0 && i <= MaskFlagHistoryLimit - 1)
      BitOps.setBitAtBs(st.maskFlag, MaskFlagHistoryLimit - 1, newMaskFlag)
    }
  }

  /** mask_build_new: accumulate input changes across the tracking period (5.2). */
  def computeMaskBuildNew(st: CompressorState, input: BitStream, newMaskFlag: Boolean): Unit = {
    require(input.buf.length >= (st.n + 7) / 8)
    if (st.t != 0 && !newMaskFlag) {
      var i = 0
      (while (i < st.n) {
        decreases(st.n - i)
        val inBit = BitOps.bitAtBs(input, i)
        val oldBit = BitOps.bitAtBs(st.inputOld, i)
        val accBit = BitOps.bitAtBs(st.maskBuildOld, i)
        BitOps.setBitAtBs(st.maskBuildNew, i, (inBit != oldBit) || accBit)
        i = i + 1
      }).invariant(i >= 0 && i <= st.n)
    } else {
      BitOps.zeroFill(st.maskBuildNew, st.n)
    }
  }

  /** Copies row `srcRow` of the flat mask-change slab onto row `dstRow`. */
  def copyRow(st: CompressorState, srcRow: Int, dstRow: Int): Unit = {
    require(srcRow >= 0 && dstRow >= 0 && srcRow < 16 && dstRow < 16)
    var j = 0
    (while (j < st.n) {
      decreases(st.n - j)
      BitOps.setBitAtBs(st.maskChangeVector, dstRow * st.n + j, BitOps.bitAtBs(st.maskChangeVector, srcRow * st.n + j))
      j = j + 1
    }).invariant(j >= 0 && j <= st.n)
  }

  /** Prepend a fresh zero row to the mask-change history (row 0 newest), keeping at most 16. */
  def pushMaskChangeRow(st: CompressorState): Unit = {
    val keep = if (st.maskChangeCount > MaskChangeHistoryLimit) MaskChangeHistoryLimit else st.maskChangeCount
    var r = keep
    (while (r >= 1) {
      decreases(r)
      copyRow(st, r - 1, r)
      r = r - 1
    }).invariant(r >= 0 && r <= keep)
    // zero row 0
    var j = 0
    (while (j < st.n) {
      decreases(st.n - j)
      BitOps.setBitAtBs(st.maskChangeVector, j, false)
      j = j + 1
    }).invariant(j >= 0 && j <= st.n)
    st.maskChangeCount = keep + 1
  }

  /** M_t = (input changed) OR accumulated, for t != 0. */
  def computeMaskNew(st: CompressorState, input: BitStream, newMaskFlag: Boolean): Unit = {
    require(input.buf.length >= (st.n + 7) / 8)
    var i = 0
    (while (i < st.n) {
      decreases(st.n - i)
      val accumulated = if (newMaskFlag) BitOps.bitAtBs(st.maskBuildOld, i) else BitOps.bitAtBs(st.maskOld, i)
      val inBit = BitOps.bitAtBs(input, i)
      val oldBit = BitOps.bitAtBs(st.inputOld, i)
      BitOps.setBitAtBs(st.maskNew, i, (inBit != oldBit) || accumulated)
      i = i + 1
    }).invariant(i >= 0 && i <= st.n)
  }

  /** Row 0 of the mask-change history = (M_t differs from M_{t-1}). */
  def computeMaskChangeRow0(st: CompressorState): Unit = {
    var i = 0
    (while (i < st.n) {
      decreases(st.n - i)
      BitOps.setBitAtBs(st.maskChangeVector, i, BitOps.bitAtBs(st.maskNew, i) != BitOps.bitAtBs(st.maskOld, i))
      i = i + 1
    }).invariant(i >= 0 && i <= st.n)
  }

  /** Extend requested robustness over iterations whose mask did not change (5.2); returns V_t. */
  def effectiveRobustness(st: CompressorState, robustnessLevel: Int): Int = {
    require(robustnessLevel >= 0)
    var cT = 0
    var vT = robustnessLevel
    var cont = true
    (while (cont && vT <= st.t && vT <= MaxEffectiveRobustness && st.maskChangeCount > vT + 1) {
      decreases(MaxEffectiveRobustness - vT + (if (cont) 1 else 0))
      if (BitOps.allZero(st.maskChangeVector, (vT + 1) * st.n, st.n)) {
        cT = cT + 1
        vT = cT + robustnessLevel
      } else {
        cont = false
      }
    }).invariant(cT >= 0 && vT >= robustnessLevel)
    vT
  }

  /** dst[j] |= maskChangeVector[row][j] for j in 0..n-1. */
  def orRowInto(st: CompressorState, row: Int, dst: BitStream): Unit = {
    require(row >= 0 && row < 16 && dst.buf.length * BitOps.BitsPerByte >= st.n)
    var j = 0
    (while (j < st.n) {
      decreases(st.n - j)
      BitOps.setBitAtBs(dst, j, BitOps.bitAtBs(st.maskChangeVector, row * st.n + j) || BitOps.bitAtBs(dst, j))
      j = j + 1
    }).invariant(j >= 0 && j <= st.n)
  }

  /** X_t: reversed union of relevant mask change vectors in the robustness window (5.3.2). */
  def computeXt(st: CompressorState, robustnessLevel: Int, vT: Int, xT: BitStream): Unit = {
    require(robustnessLevel >= 0 && xT.buf.length * BitOps.BitsPerByte >= st.n)
    if (vT == 0) {
      // reverse(row 0)
      var i = 0
      (while (i < st.n) {
        decreases(st.n - i)
        BitOps.setBitAtBs(xT, i, BitOps.bitAtBs(st.maskChangeVector, st.n - 1 - i))
        i = i + 1
      }).invariant(i >= 0 && i <= st.n)
    } else {
      BitOps.zeroFill(xT, st.n)
      val window = if (st.t - vT <= 0) st.maskChangeCount else robustnessLevel + 1
      var i = 0
      (while (i < window && i < 16) {
        decreases(16 - i)
        orRowInto(st, i, xT)
        i = i + 1
      }).invariant(i >= 0 && i <= 16)
      BitOps.reverseInPlace(xT, st.n)
    }
  }

  /** Mask-flag weight over the last V_t+1 iterations governs c_t. */
  def maskFlagWeight(st: CompressorState, vT: Int): Int = {
    var w = 0
    var fi = st.maskFlagCount - 1
    var counter = 0
    (while (fi >= 0 && counter <= vT) {
      decreases(fi + 1)
      if (BitOps.bitAtBs(st.maskFlag, fi)) w = w + 1
      fi = fi - 1
      counter = counter + 1
    }).invariant(fi >= -1 && counter >= 0 && w >= 0)
    w
  }

  /** maskDiff[i] = M_t[i] XOR M_t[i+1] (the run-length-encoded mask difference, 5.3.3.2). */
  def computeMaskDiff(st: CompressorState, maskDiff: BitStream): Unit = {
    require(maskDiff.buf.length * BitOps.BitsPerByte >= st.n)
    var i = 0
    (while (i < st.n) {
      decreases(st.n - i)
      val shifted = (i + 1 < st.n) && BitOps.bitAtBs(st.maskNew, i + 1)
      BitOps.setBitAtBs(maskDiff, i, BitOps.bitAtBs(st.maskNew, i) != shifted)
      i = i + 1
    }).invariant(i >= 0 && i <= st.n)
  }

  /**
   * Appends reverse(bitExtraction(input, selector)) to `out`, where selector is M_t, or
   * (M_t OR reverse(X_t)) when `useXt`. `selector` and `tmp` are scratch (length n, distinct).
   * Contains no while loops of its own.
   */
  def extractByMask(
    out: BitStream, input: BitStream, mask: BitStream,
    xT: BitStream, useXt: Boolean, n: Int, selector: BitStream, tmp: BitStream
  ): Unit = {
    require(
      n >= 0 && n <= input.buf.length * BitOps.BitsPerByte && n <= mask.buf.length * BitOps.BitsPerByte &&
      n <= xT.buf.length * BitOps.BitsPerByte && n <= selector.buf.length * BitOps.BitsPerByte &&
      n <= tmp.buf.length * BitOps.BitsPerByte
    )
    val exLen =
      if (!useXt) {
        BitOps.bitExtractionInto(input, mask, n, tmp)
      } else {
        BitOps.reverseInto(xT, n, selector) // selector = reverse(X_t)
        BitOps.orInto(selector, mask, n)    // selector |= M_t
        BitOps.bitExtractionInto(input, selector, n, tmp)
      }
    BitOps.appendReversed(out, tmp, exLen)
  }

  // ---- public API ----

  /** Initialises the state: zeroes every vector, resets counters, precomputes COUNT(n). */
  @cCode.`export`
  def compressorInit(st: CompressorState): Unit = {
    BitOps.zeroFill(st.maskNew, st.n)
    BitOps.zeroFill(st.maskOld, st.n)
    BitOps.zeroFill(st.maskBuildNew, st.n)
    BitOps.zeroFill(st.maskBuildOld, st.n)
    BitOps.zeroFill(st.inputOld, st.n)
    st.t = 0
    st.maskChangeCount = 0
    st.maskFlagCount = 0
    // Reset the COUNT(n) buffer to a clean slate, then encode at cursor 0.
    BitOps.zeroFill(st.inputVectorLengthCount, st.inputVectorLengthCount.buf.length * BitOps.BitsPerByte)
    st.inputVectorLengthCount.resetBitIndex()
    Count.encode(st.inputVectorLengthCount, st.n)
    st.inputVectorLengthCountLen = st.inputVectorLengthCount.bitIndex.toInt
  }

  /** Sets the initial mask M_0 (length n) before the first compress. */
  @cCode.`export`
  def setInitialMask(st: CompressorState, initialMask: BitStream): Unit = {
    require(initialMask.buf.length * BitOps.BitsPerByte >= st.n)
    BitOps.copyRange(initialMask, st.n, st.maskNew)
  }

  /**
   * Compresses one input vector into `out`, appending at `out`'s current cursor. After the
   * call, `out.bitIndex` is the position past the packet. `input` is a BitStream whose
   * `buf` holds n bits at positions 0..n-1 (cursor unused). At t <= robustnessLevel both
   * `sendMaskFlag` and `uncompressedFlag` must be true so the decompressor can initialise.
   *
   * Orchestrator only: contains no `while` loops; every loop lives in a helper above.
   */
  @cCode.`export`
  def compress(
    st: CompressorState, input: BitStream, robustnessLevel: Int,
    newMaskFlag: Boolean, sendMaskFlag: Boolean, uncompressedFlag: Boolean, out: BitStream
  ): Unit = {
    require(
      input.buf.length >= (st.n + 7) / 8 && robustnessLevel >= 0 && robustnessLevel <= MaxRobustnessLevel
    )
    val n = st.n
    val nBytes = (n + 7) / 8
    val xT = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val yT = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val kT = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val inv = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val invRev = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val tmp = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val selector = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val maskDiff = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)

    pushMaskFlag(st, newMaskFlag)
    computeMaskBuildNew(st, input, newMaskFlag)
    pushMaskChangeRow(st)

    var vT = robustnessLevel
    if (st.t != 0) {
      computeMaskNew(st, input, newMaskFlag)
      computeMaskChangeRow0(st)
      vT = effectiveRobustness(st, robustnessLevel)
    }

    val dT = !sendMaskFlag && !uncompressedFlag
    computeXt(st, robustnessLevel, vT, xT)

    // y_t = reverse( bitExtraction( reverse(~M_t), X_t ) )
    BitOps.inverseInto(st.maskNew, n, inv)
    BitOps.reverseInto(inv, n, invRev)
    val exLen = BitOps.bitExtractionInto(invRev, xT, n, tmp)
    BitOps.reverseInto(tmp, exLen, yT)
    val yTLen = exLen
    val xTWeight = BitOps.hammingWeight(xT, n)
    val yTWeight = BitOps.hammingWeight(yT, yTLen)

    val eTLen = if (vT == 0 || xTWeight == 0) 0 else 1
    val eTVal = yTWeight != 0
    val kTLen = if (vT == 0 || xTWeight == 0 || yTWeight == 0) 0 else yTLen
    if (kTLen != 0) BitOps.copyRange(yT, kTLen, kT)
    val cBitsLen = if (kTLen != 0) 1 else 0
    val cBitsVal = kTLen != 0 && maskFlagWeight(st, vT) > 1

    // ---- First binary vector h_t (5.3.3.1) ----
    Count.runLengthEncoding(out, xT, n)
    out.appendBit(true)
    out.appendBit(false)
    BitOps.appendBigEndian(out, vT, EffectiveRobustnessBitWidth)
    if (eTLen == 1) out.appendBit(eTVal)
    BitOps.appendBits(out, kT, kTLen)
    if (cBitsLen == 1) out.appendBit(cBitsVal)
    out.appendBit(dT)

    // ---- Second binary vector q_t (5.3.3.2) ----
    if (dT) {
      // empty
    } else if (sendMaskFlag) {
      computeMaskDiff(st, maskDiff)
      out.appendBit(true)
      BitOps.reverseInPlace(maskDiff, n)
      Count.runLengthEncoding(out, maskDiff, n)
      out.appendBit(true)
      out.appendBit(false)
    } else {
      out.appendBit(false)
    }

    // ---- Third binary vector u_t (5.3.3.3) ----
    val cTset = cBitsLen != 0 && cBitsVal
    if (dT) {
      extractByMask(out, input, st.maskNew, xT, cTset, n, selector, tmp)
    } else if (uncompressedFlag) {
      out.appendBit(true)
      BitOps.appendBits(out, st.inputVectorLengthCount, st.inputVectorLengthCountLen)
      BitOps.appendBits(out, input, n)
    } else {
      out.appendBit(false)
      extractByMask(out, input, st.maskNew, xT, sendMaskFlag && cTset, n, selector, tmp)
    }

    // ---- advance state ----
    st.t = st.t + 1
    BitOps.copyRange(input, n, st.inputOld)
    BitOps.copyRange(st.maskNew, n, st.maskOld)
    BitOps.copyRange(st.maskBuildNew, n, st.maskBuildOld)
  }
}

/**
 * Stateful POCKET+ decompressor. The C# reference keeps full histories of masks and decoded
 * vectors but only ever reads the most recent of each, so the state collapses to a single
 * current `mask` and the previous decoded vector `lastInput`. As with the compressor, every
 * loop is its own named function so the orchestrator has no `while` loops.
 *
 * The reconstructed n-bit vector is written into bits 0..n-1 of `out.buf` (cursor unused);
 * the `input` BitStream's cursor advances from the start of the packet to the start of the
 * next packet (already byte-aligned).
 */
case class DecompressorState(n: Int, mask: BitStream, lastInput: BitStream, var t: Int) {
  require(
    n >= 1 && n <= 65535 && mask.buf.length == (n + 7) / 8 &&
    lastInput.buf.length == (n + 7) / 8 && t >= 0
  )
}

object Decompressor {
  val MinimumPacketBits: Int = 7
  val EffectiveRobustnessBitWidth: Int = 4
  val CountFiveBitWidth: Int = 5
  val CountExtendedBaseWidth: Int = 5 // matches the reference COUNT-revert seed
  val BitsPerByte: Int = 8

  // ---- bit-stream readers (advance the input cursor in place) ----

  /** Reads `width` bits big-endian, advancing the cursor. */
  def readBigEndian(input: BitStream, width: Int): Int = {
    require(width >= 0 && width <= 30)
    require(BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, width.toLong))
    var value = 0
    var i = 0
    (while (i < width) {
      decreases(width - i)
      value = value * 2 + (if (input.readBit()) 1 else 0)
      i = i + 1
    }).invariant(i >= 0 && i <= width && value >= 0)
    value
  }

  /** Counts the leading zero bits of an extended COUNT field, advancing the cursor past them.
   *  Returns `startSize` plus the number of zeros consumed. */
  def scanZeros(input: BitStream, startSize: Int): Int = {
    require(startSize >= 0)
    var countSize = startSize
    var cont = true
    (while (cont) {
      decreases(input.buf.length.toLong * BitsPerByte.toLong - input.bitIndex)
      val hasBit = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 1L)
      if (hasBit && !input.peekBit()) {
        countSize = countSize + 1
        input.moveBitIndex(1L)
      } else {
        cont = false
      }
    }).invariant(countSize >= startSize)
    countSize
  }

  /** Appends `run` zeros then a one to `output` at bit-index `outLen`; returns the new length. */
  def emitRun(output: BitStream, outLen: Int, run: Int): Int = {
    require(outLen >= 0 && run >= 0 && outLen + run + 1 <= output.buf.length * BitsPerByte)
    var k = outLen
    var i = 0
    (while (i < run) {
      decreases(run - i)
      BitOps.setBitAtBs(output, k, false)
      k = k + 1
      i = i + 1
    }).invariant(i >= 0 && i <= run && k == outLen + i)
    BitOps.setBitAtBs(output, k, true)
    k + 1
  }

  /** Reverses run-length encoding into `output` until the '10' terminator (5.3.1.2). */
  def undoRunLengthEncoding(input: BitStream, output: BitStream): Unit = {
    val totalBits = input.buf.length * BitsPerByte
    val outBits = output.buf.length * BitsPerByte
    var outLen = 0
    var cont = true
    (while (cont) {
      decreases(totalBits.toLong * 2L - input.bitIndex - outLen.toLong)
      val pos = input.bitIndex.toInt
      if (pos + 1 < totalBits && outLen < outBits) {
        val b0 = BitOps.bitAtBs(input, pos)
        val b1 = BitOps.bitAtBs(input, pos + 1)
        if (b0 && !b1) {
          // '10' terminator reached
          cont = false
        } else if (!b0) {
          BitOps.setBitAtBs(output, outLen, true)
          outLen = outLen + 1
          input.moveBitIndex(1L)
        } else if (pos + 2 < totalBits) {
          val b2 = BitOps.bitAtBs(input, pos + 2)
          if (b0 && b1 && !b2) {
            input.moveBitIndex(3L)
            val run = readBigEndian(input, CountFiveBitWidth) + 1
            if (outLen + run + 1 <= outBits) outLen = emitRun(output, outLen, run)
            else {
              // give up: malformed / over-long
              input.moveBitIndex(input.buf.length.toLong * BitsPerByte.toLong - input.bitIndex)
              cont = false
            }
          } else if (b0 && b1 && b2) {
            input.moveBitIndex(3L)
            val width = scanZeros(input, CountExtendedBaseWidth + 1)
            val canRead = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, width.toLong)
            val run = if (canRead) readBigEndian(input, width) + 1 else 1
            if (outLen + run + 1 <= outBits) outLen = emitRun(output, outLen, run)
            else {
              input.moveBitIndex(input.buf.length.toLong * BitsPerByte.toLong - input.bitIndex)
              cont = false
            }
          } else {
            // unreachable for well-formed input
            input.moveBitIndex(1L)
          }
        } else {
          cont = false
        }
      } else {
        cont = false
      }
    }).invariant(outLen >= 0)
  }

  /** Reverts COUNT(a) for the input-vector-length field (5.3.1.1); distinct from RLE: no -1 bias. */
  def revertCount(input: BitStream): Int = {
    require(BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 3L))
    val pos = input.bitIndex.toInt
    val b0 = BitOps.bitAtBs(input, pos)
    if (!b0) {
      input.moveBitIndex(1L)
      1
    } else {
      val b1 = BitOps.bitAtBs(input, pos + 1)
      val b2 = BitOps.bitAtBs(input, pos + 2)
      if (b0 && b1 && !b2) {
        input.moveBitIndex(3L)
        readBigEndian(input, CountFiveBitWidth) + 2
      } else {
        input.moveBitIndex(3L)
        val width = scanZeros(input, CountExtendedBaseWidth) + 1
        val canRead = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, width.toLong)
        if (canRead) readBigEndian(input, width) + 2 else 2
      }
    }
  }

  // ---- mask reconstruction ----

  /** Cumulative reconstruction of M_t from the run-length-decoded (M_t XOR M_t<<1) difference. */
  def reconstructMaskFromDiff(maskDiff: BitStream, n: Int, mask: BitStream): Unit = {
    require(
      n >= 0 && n <= maskDiff.buf.length * BitsPerByte && n <= mask.buf.length * BitsPerByte
    )
    var cum = false
    var j = 0
    (while (j < n) {
      decreases(n - j)
      cum = cum != BitOps.bitAtBs(maskDiff, j)
      BitOps.setBitAtBs(mask, n - 1 - j, cum)
      j = j + 1
    }).invariant(j >= 0 && j <= n)
  }

  /** mask[i] = !y_t[..] at the change positions, consuming y_t from its end. */
  def applyMaskFromY(mask: BitStream, dChange: BitStream, yT: BitStream, yTLen: Int, n: Int): Unit = {
    require(
      n >= 0 && n <= mask.buf.length * BitsPerByte && n <= dChange.buf.length * BitsPerByte &&
      yTLen >= 0 && yTLen <= yT.buf.length * BitsPerByte
    )
    var i = 0
    var yj = yTLen - 1
    (while (i < n) {
      decreases(n - i)
      if (BitOps.bitAtBs(dChange, i) && yj >= 0 && yj < yTLen) {
        BitOps.setBitAtBs(mask, i, !BitOps.bitAtBs(yT, yj))
        yj = yj - 1
      }
      i = i + 1
    }).invariant(i >= 0 && i <= n && yj < yTLen)
  }

  /** mask[i] = !k_t[..] at the change positions, consuming k_t from its end. */
  def applyMaskFromK(mask: BitStream, dChange: BitStream, kT: BitStream, kTLen: Int, n: Int): Unit = {
    require(
      n >= 0 && n <= mask.buf.length * BitsPerByte && n <= dChange.buf.length * BitsPerByte &&
      kTLen >= 0 && kTLen <= kT.buf.length * BitsPerByte
    )
    var i = 0
    var kj = kTLen - 1
    (while (i < n) {
      decreases(n - i)
      if (BitOps.bitAtBs(dChange, i) && kj >= 0 && kj < kTLen) {
        BitOps.setBitAtBs(mask, i, !BitOps.bitAtBs(kT, kj))
        kj = kj - 1
      }
      i = i + 1
    }).invariant(i >= 0 && i <= n && kj < kTLen)
  }

  /** mask[i] ^= dChange[i] (fallback when neither y_t nor k_t is present). */
  def applyMaskXor(mask: BitStream, dChange: BitStream, n: Int): Unit = {
    require(n >= 0 && n <= mask.buf.length * BitsPerByte && n <= dChange.buf.length * BitsPerByte)
    var i = 0
    (while (i < n) {
      decreases(n - i)
      BitOps.setBitAtBs(mask, i, BitOps.bitAtBs(mask, i) != BitOps.bitAtBs(dChange, i))
      i = i + 1
    }).invariant(i >= 0 && i <= n)
  }

  /** Dispatches the in-place mask update (no loops of its own). */
  def applyMaskChanges(
    mask: BitStream, dChange: BitStream, yT: BitStream, yTLen: Int,
    kT: BitStream, kTLen: Int, useXorFallback: Boolean, n: Int
  ): Unit = {
    require(
      n >= 0 && n <= mask.buf.length * BitsPerByte && n <= dChange.buf.length * BitsPerByte &&
      yTLen >= 0 && yTLen <= yT.buf.length * BitsPerByte &&
      kTLen >= 0 && kTLen <= kT.buf.length * BitsPerByte
    )
    if (yTLen > 0) applyMaskFromY(mask, dChange, yT, yTLen, n)
    else if (kTLen > 0) applyMaskFromK(mask, dChange, kT, kTLen, n)
    else if (useXorFallback) applyMaskXor(mask, dChange, n)
  }

  /** Uncompressed-mask branch: mask[i] ^= !y_t[..] at change positions, scanning from the high end. */
  def applyMaskUncompressed(mask: BitStream, dChange: BitStream, yT: BitStream, yTLen: Int, n: Int): Unit = {
    require(
      n >= 0 && n <= mask.buf.length * BitsPerByte && n <= dChange.buf.length * BitsPerByte &&
      yTLen >= 0 && yTLen <= yT.buf.length * BitsPerByte
    )
    var i = n - 1
    var yj = 0
    (while (i >= 0) {
      decreases(i + 1)
      if (BitOps.bitAtBs(dChange, i) && yj >= 0 && yj < yTLen) {
        BitOps.setBitAtBs(mask, i, BitOps.bitAtBs(mask, i) != (!BitOps.bitAtBs(yT, yj)))
        yj = yj + 1
      }
      i = i - 1
    }).invariant(i >= -1 && i < n && yj >= 0)
  }

  // ---- third-vector helpers ----

  /** Reads k_t into `kT` (built end-first, matching the reference emplace_front), advancing cursor. */
  def fillKt(input: BitStream, kT: BitStream, count: Int): Unit = {
    require(count >= 0 && count <= kT.buf.length * BitsPerByte)
    require(BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, count.toLong))
    var m = 0
    (while (m < count) {
      decreases(count - m)
      BitOps.setBitAtBs(kT, count - 1 - m, input.readBit())
      m = m + 1
    }).invariant(m >= 0 && m <= count)
  }

  /** Reconstructs the vector: take bits from the stream where selected, else copy the previous vector.
   *  Selector is M_t, or (M_t OR X_t) when useXt. Writes into bits 0..n-1 of `out.buf`. */
  def reconstruct(input: BitStream, xT: BitStream, useXt: Boolean, st: DecompressorState, out: BitStream): Unit = {
    require(
      st.n <= xT.buf.length * BitsPerByte && st.n <= out.buf.length * BitsPerByte
    )
    val n = st.n
    val totalBits = input.buf.length * BitsPerByte
    var j = 0
    (while (j < n) {
      decreases(n - j)
      val p = n - 1 - j
      val selected = BitOps.bitAtBs(st.mask, p) || (useXt && BitOps.bitAtBs(xT, j))
      val canRead = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 1L)
      if (selected && canRead) {
        BitOps.setBitAtBs(out, p, input.readBit())
      } else {
        BitOps.setBitAtBs(out, p, BitOps.bitAtBs(st.lastInput, p))
      }
      j = j + 1
    }).invariant(j >= 0 && j <= n)
  }

  /** Copies the next n raw bits from the stream into bits 0..n-1 of `out.buf` (uncompressed input vector). */
  def copyInputBits(input: BitStream, out: BitStream, n: Int): Unit = {
    require(n >= 0 && n <= out.buf.length * BitsPerByte)
    require(BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, n.toLong))
    var i = 0
    (while (i < n) {
      decreases(n - i)
      BitOps.setBitAtBs(out, i, input.readBit())
      i = i + 1
    }).invariant(i >= 0 && i <= n)
  }

  def roundUpToByte(bits: Int): Int = {
    require(bits >= 0)
    (bits + BitsPerByte - 1) / BitsPerByte * BitsPerByte
  }

  // ---- public API ----

  /** Initialises the state: M_0 all zeros, no previous input, t = 0. */
  @cCode.`export`
  def decompressorInit(st: DecompressorState): Unit = {
    BitOps.zeroFill(st.mask, st.n)
    BitOps.zeroFill(st.lastInput, st.n)
    st.t = 0
  }

  /**
   * Decompresses one packet starting at `input`'s current cursor, writing the reconstructed
   * n-bit vector into bits 0..n-1 of `out.buf` (cursor unused). After the call,
   * `input.bitIndex` is the bit offset of the next packet (past byte alignment padding).
   *
   * Orchestrator only: contains no `while` loops; every loop lives in a helper above.
   */
  @cCode.`export`
  def decompress(st: DecompressorState, input: BitStream, out: BitStream): Unit = {
    require(
      input.bitIndex >= 0 && out.buf.length * BitsPerByte >= st.n &&
      BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, MinimumPacketBits.toLong)
    )
    val n = st.n
    val nBytes = (n + 7) / 8
    val start = input.bitIndex.toInt
    val totalBits = input.buf.length * BitsPerByte

    val xT = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val dChange = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val yT = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val kT = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)
    val maskDiff = BitStream(Array.fill(nBytes)(0: Byte), 0, 0)

    // ---- First binary vector h_t (5.3.3.1) ----
    val pos0 = input.bitIndex.toInt
    if (pos0 + 1 < totalBits && !(BitOps.bitAtBs(input, pos0) && !BitOps.bitAtBs(input, pos0 + 1))) {
      undoRunLengthEncoding(input, xT)
    }
    BitOps.reverseInto(xT, n, dChange)
    val xTWeight = BitOps.hammingWeight(xT, n)
    input.moveBitIndex(2L) // consume the '10' terminator

    val robustnessLevel = readBigEndian(input, EffectiveRobustnessBitWidth)

    var yTLen = 0
    var eTpresent = false
    var eTval = false
    val canReadETag = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 1L)
    if (!(robustnessLevel == 0 || xTWeight == 0) && canReadETag) {
      val bit = input.peekBit()
      input.moveBitIndex(1L)
      if (!bit) {
        eTpresent = true
        eTval = false
        yTLen = xTWeight // yT stays all-false
      } else {
        eTpresent = true
        eTval = true
      }
    }

    var kTLen = 0
    var cTpresent = false
    var cTval = false
    val kEmpty = (robustnessLevel == 0 || xTWeight == 0 || BitOps.hammingWeight(yT, yTLen) == 0) && (!eTpresent || !eTval)
    val canFillK = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, (xTWeight + 1).toLong)
    if (!kEmpty && canFillK && xTWeight <= n) {
      fillKt(input, kT, xTWeight)
      kTLen = xTWeight
      cTpresent = true
      cTval = input.peekBit()
      input.moveBitIndex(1L)
    }

    val canReadDT = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 1L)
    val dT = if (canReadDT) input.peekBit() else false
    input.moveBitIndex(1L)
    var sendMaskFlag = false

    // ---- Second binary vector q_t (5.3.3.2) ----
    if (dT) {
      applyMaskChanges(st.mask, dChange, yT, yTLen, kT, kTLen, true, n)
    } else {
      val canReadQ = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 1L)
      val qBit = if (canReadQ) input.peekBit() else false
      if (canReadQ && qBit) {
        sendMaskFlag = true
        input.moveBitIndex(1L)
        val posQ = input.bitIndex.toInt
        if (posQ + 1 < totalBits && !(BitOps.bitAtBs(input, posQ) && !BitOps.bitAtBs(input, posQ + 1))) {
          undoRunLengthEncoding(input, maskDiff)
          reconstructMaskFromDiff(maskDiff, n, st.mask)
          applyMaskChanges(st.mask, dChange, yT, yTLen, kT, kTLen, false, n)
        } else {
          BitOps.zeroFill(st.mask, n)
        }
        input.moveBitIndex(2L) // consume the '10' terminator
      } else {
        input.moveBitIndex(1L)
        applyMaskUncompressed(st.mask, dChange, yT, yTLen, n)
      }
    }

    // ---- Third binary vector u_t (5.3.3.3) ----
    if (dT) {
      reconstruct(input, xT, cTpresent && cTval, st, out)
    } else {
      val canReadU = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, 1L)
      val uBit = if (canReadU) input.peekBit() else false
      if (canReadU && uBit) {
        input.moveBitIndex(1L)
        val declaredLength = revertCount(input)
        val canCopy = BitStream.validate_offset_bits(input.buf.length.toLong, input.currentByte.toLong, input.currentBit.toLong, n.toLong)
        if (declaredLength == n && canCopy) copyInputBits(input, out, n)
      } else {
        input.moveBitIndex(1L)
        reconstruct(input, xT, sendMaskFlag && cTpresent && cTval, st, out)
      }
    }
    BitOps.copyRange(out, n, st.lastInput)

    st.t = st.t + 1
    // Align to next byte boundary relative to the packet start.
    val consumed = input.bitIndex.toInt - start
    val padded = roundUpToByte(consumed)
    val padBits = padded - consumed
    if (padBits > 0) input.moveBitIndex(padBits.toLong)
  }
}
