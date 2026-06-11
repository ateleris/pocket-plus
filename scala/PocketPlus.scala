import stainless.lang.*
import stainless.annotation.*

/**
 * POCKET+ / CCSDS 124.0-B-1 — Stainless implementation, written in the GenC-compatible
 * subset so it can be transpiled to C.
 *
 * GenC constraints that shape the whole design:
 *   - No `BigInt` and no bit-wise operators: everything is `Int`, and COUNT / big-endian encoding
 *     use plain arithmetic (`/`, `%`, `*`) via [[BitOps.pow2]].
 *   - No `stainless.collection.List`: a bit vector is an `Array[Boolean]` (MSB / first-transmitted
 *     bit at index 0) paired with an `Int` length.
 *   - Arrays cannot be returned and there is no heap: every function writes into a caller-provided
 *     buffer and returns the number of bits written (an `Int`). Persistent state lives in a struct
 *     of pre-allocated arrays that is passed by reference and mutated in place — never reassigned —
 *     so the anti-aliasing phase stays happy.
 *
 * The API therefore necessarily diverges from the C# (which returned growing `List<bool>`):
 * `compress` writes the packet into `out` and returns its bit length.
 *
 * Generated C: the per-call working buffers (`Array.fill(n)(...)`) become C99/C11 variable-length
 * arrays, so the GenC output compiles cleanly with gcc and clang (`-std=c11 -Wall -Wextra`). MSVC
 * does not implement VLAs and rejects it; if MSVC support is ever required, move those buffers into
 * the (caller-allocated) state struct so they become plain pointer fields.
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

  /** Sets `a[0 until len]` to false. */
  def zeroFill(a: Array[Boolean], len: Int): Unit = {
    require(len >= 0 && len <= a.length)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      a(i) = false
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** Copies `src[0 until len]` to `dst[0 until len]`. */
  def copyRange(src: Array[Boolean], len: Int, dst: Array[Boolean]): Unit = {
    require(len >= 0 && len <= src.length && len <= dst.length)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      dst(i) = src(i)
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** dst[i] = src[len-1-i] for i in 0 until len. Denoted <A> in the standard. dst must differ from src. */
  def reverseInto(src: Array[Boolean], len: Int, dst: Array[Boolean]): Unit = {
    require(len >= 0 && len <= src.length && len <= dst.length)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      dst(i) = src(len - 1 - i)
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** Reverses `a[0 until len]` in place. */
  def reverseInPlace(a: Array[Boolean], len: Int): Unit = {
    require(len >= 0 && len <= a.length)
    var i = 0
    var k = len - 1
    (while (i < k) {
      decreases(k - i)
      val tmp = a(i)
      a(i) = a(k)
      a(k) = tmp
      i = i + 1
      k = k - 1
    }).invariant(i >= 0 && k < len)
  }

  /** dst[i] = !src[i]. Denoted ~A in the standard. */
  def inverseInto(src: Array[Boolean], len: Int, dst: Array[Boolean]): Unit = {
    require(len >= 0 && len <= src.length && len <= dst.length)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      dst(i) = !src(i)
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }

  /** Number of set bits in `a[0 until len]`. */
  def hammingWeight(a: Array[Boolean], len: Int): Int = {
    require(len >= 0 && len <= a.length)
    var i = 0
    var w = 0
    (while (i < len) {
      decreases(len - i)
      if (a(i)) w = w + 1
      i = i + 1
    }).invariant(i >= 0 && i <= len && w >= 0)
    w
  }

  /** True iff `a[0 until len]` is all zero. */
  def allZero(a: Array[Boolean], offset: Int, len: Int): Boolean = {
    require(offset >= 0 && len >= 0 && offset + len <= a.length)
    var i = 0
    var result = true
    (while (i < len) {
      decreases(len - i)
      if (a(offset + i)) result = false
      i = i + 1
    }).invariant(i >= 0 && i <= len)
    result
  }

  /** Bits of `b` at the positions where `a` is set (5.3.1.3); writes into `dst`, returns the count. */
  def bitExtractionInto(b: Array[Boolean], a: Array[Boolean], len: Int, dst: Array[Boolean]): Int = {
    require(len >= 0 && len <= a.length && len <= b.length && len <= dst.length)
    var i = 0
    var k = 0
    (while (i < len) {
      decreases(len - i)
      if (a(i)) {
        dst(k) = b(i)
        k = k + 1
      }
      i = i + 1
    }).invariant(i >= 0 && i <= len && k >= 0 && k <= i)
    k
  }

  /** Appends `width` bits of `value`, MSB first, to `dst` at `off`. Returns off + width. */
  def appendBigEndian(dst: Array[Boolean], off: Int, value: Int, width: Int): Int = {
    require(off >= 0 && width >= 0 && off + width <= dst.length && value >= 0)
    var i = width - 1
    var o = off
    (while (i >= 0) {
      decreases(i + 1)
      dst(o) = (value / pow2(i)) % 2 == 1
      o = o + 1
      i = i - 1
    }).invariant(o >= off && o <= off + width && i >= -1 && i < width)
    o
  }

  /** Appends `src[0 until srcLen]` to `dst` at `off`. Returns off + srcLen. */
  def appendBits(dst: Array[Boolean], off: Int, src: Array[Boolean], srcLen: Int): Int = {
    require(off >= 0 && srcLen >= 0 && srcLen <= src.length && off + srcLen <= dst.length)
    var i = 0
    var o = off
    (while (i < srcLen) {
      decreases(srcLen - i)
      dst(o) = src(i)
      o = o + 1
      i = i + 1
    }).invariant(i >= 0 && i <= srcLen && o == off + i)
    o
  }

  /** Appends reverse(`src[0 until srcLen]`) to `dst` at `off`. Returns off + srcLen. */
  def appendReversed(dst: Array[Boolean], off: Int, src: Array[Boolean], srcLen: Int): Int = {
    require(off >= 0 && srcLen >= 0 && srcLen <= src.length && off + srcLen <= dst.length)
    var i = 0
    var o = off
    (while (i < srcLen) {
      decreases(srcLen - i)
      dst(o) = src(srcLen - 1 - i)
      o = o + 1
      i = i + 1
    }).invariant(i >= 0 && i <= srcLen && o == off + i)
    o
  }

  /** In-place bitwise OR: dst[i] = dst[i] || src[i] for i in 0 until len. */
  def orInto(dst: Array[Boolean], src: Array[Boolean], len: Int): Unit = {
    require(len >= 0 && len <= dst.length && len <= src.length)
    var i = 0
    (while (i < len) {
      decreases(len - i)
      dst(i) = dst(i) || src(i)
      i = i + 1
    }).invariant(i >= 0 && i <= len)
  }
}

/**
 * COUNT(a): self-delimiting encoding of a positive integer (5.3.1.1), appended to `dst` at `off`.
 * Returns the new offset.
 */
object Count {
  val MaxInputVectorLength: Int = 65535
  val CountFiveBitMin: Int = 2
  val CountFiveBitMax: Int = 33
  val CountFiveBitWidth: Int = 5
  val CountExtendedBaseWidth: Int = 6

  def encode(dst: Array[Boolean], off: Int, a: Int): Int = {
    require(off >= 0 && off + 64 <= dst.length && a >= 1 && a <= MaxInputVectorLength)
    if (a == 1) {
      dst(off) = false
      off + 1
    } else if (a >= CountFiveBitMin && a <= CountFiveBitMax) {
      dst(off) = true
      dst(off + 1) = true
      dst(off + 2) = false
      BitOps.appendBigEndian(dst, off + 3, a - CountFiveBitMin, CountFiveBitWidth)
    } else {
      val value = a - CountFiveBitMin
      val bl = BitOps.bitLength(value)
      dst(off) = true
      dst(off + 1) = true
      dst(off + 2) = true
      BitOps.appendBigEndian(dst, off + 3, value, (2 * bl) - CountExtendedBaseWidth)
    }
  }

  /** Run-length encoding of zero runs: emit COUNT(distance) at every set bit (5.3.1.2). */
  def runLengthEncoding(dst: Array[Boolean], off: Int, src: Array[Boolean], srcLen: Int): Int = {
    require(off >= 0 && srcLen >= 0 && srcLen <= src.length)
    var zeroCounter = 0
    var o = off
    var i = 0
    (while (i < srcLen) {
      decreases(srcLen - i)
      zeroCounter = zeroCounter + 1
      if (src(i) && o + 64 <= dst.length && zeroCounter >= 1 && zeroCounter <= MaxInputVectorLength) {
        o = encode(dst, o, zeroCounter)
        zeroCounter = 0
      }
      i = i + 1
    }).invariant(i >= 0 && i <= srcLen && o >= off && zeroCounter >= 0)
    o
  }
}

/**
 * Stateful POCKET+ compressor. All persistent buffers are pre-allocated and sized for a fixed
 * input vector length `n`. The deque histories (mask-change vectors, mask flags) are stored in
 * flat slabs with explicit counts; row 0 is the newest entry.
 */
case class CompressorState(
  n: Int,
  maskNew: Array[Boolean],                // [n]   current mask M_t
  maskOld: Array[Boolean],                // [n]   previous mask
  maskBuildNew: Array[Boolean],           // [n]   accumulated input changes this period
  maskBuildOld: Array[Boolean],           // [n]
  inputOld: Array[Boolean],               // [n]   previous input vector
  inputVectorLengthCount: Array[Boolean], // [>=64] precomputed COUNT(n)
  var inputVectorLengthCountLen: Int,
  maskChangeVector: Array[Boolean],       // [16*n] history of mask change vectors D, row 0 newest
  var maskChangeCount: Int,               // rows in use (<= 16)
  maskFlag: Array[Boolean],               // [16] history of the new-mask flag p_t, [count-1] newest
  var maskFlagCount: Int,                 // entries in use (<= 16)
  var t: Int
) {
  require(
    n >= 1 && n <= 65535 &&
    maskNew.length == n && maskOld.length == n &&
    maskBuildNew.length == n && maskBuildOld.length == n && inputOld.length == n &&
    inputVectorLengthCount.length >= 64 &&
    maskChangeVector.length == 16 * n && maskFlag.length == 16 &&
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
      st.maskFlag(st.maskFlagCount) = newMaskFlag
      st.maskFlagCount = st.maskFlagCount + 1
    } else {
      var i = 0
      (while (i < MaskFlagHistoryLimit - 1) {
        decreases(MaskFlagHistoryLimit - 1 - i)
        st.maskFlag(i) = st.maskFlag(i + 1)
        i = i + 1
      }).invariant(i >= 0 && i <= MaskFlagHistoryLimit - 1)
      st.maskFlag(MaskFlagHistoryLimit - 1) = newMaskFlag
    }
  }

  /** mask_build_new: accumulate input changes across the tracking period (5.2). */
  def computeMaskBuildNew(st: CompressorState, input: Array[Boolean], newMaskFlag: Boolean): Unit = {
    require(input.length >= st.n)
    if (st.t != 0 && !newMaskFlag) {
      var i = 0
      (while (i < st.n) {
        decreases(st.n - i)
        st.maskBuildNew(i) = (input(i) != st.inputOld(i)) || st.maskBuildOld(i)
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
      st.maskChangeVector(dstRow * st.n + j) = st.maskChangeVector(srcRow * st.n + j)
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
    BitOps.zeroFill(st.maskChangeVector, st.n) // zero row 0
    st.maskChangeCount = keep + 1
  }

  /** M_t = (input changed) OR accumulated, for t != 0. */
  def computeMaskNew(st: CompressorState, input: Array[Boolean], newMaskFlag: Boolean): Unit = {
    require(input.length >= st.n)
    var i = 0
    (while (i < st.n) {
      decreases(st.n - i)
      val accumulated = if (newMaskFlag) st.maskBuildOld(i) else st.maskOld(i)
      st.maskNew(i) = (input(i) != st.inputOld(i)) || accumulated
      i = i + 1
    }).invariant(i >= 0 && i <= st.n)
  }

  /** Row 0 of the mask-change history = (M_t differs from M_{t-1}). */
  def computeMaskChangeRow0(st: CompressorState): Unit = {
    var i = 0
    (while (i < st.n) {
      decreases(st.n - i)
      st.maskChangeVector(i) = st.maskNew(i) != st.maskOld(i)
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

  /** dst[j] |= maskChangeVector[row][j] for j in 0 until n. */
  def orRowInto(st: CompressorState, row: Int, dst: Array[Boolean]): Unit = {
    require(row >= 0 && row < 16 && dst.length >= st.n)
    var j = 0
    (while (j < st.n) {
      decreases(st.n - j)
      dst(j) = st.maskChangeVector(row * st.n + j) || dst(j)
      j = j + 1
    }).invariant(j >= 0 && j <= st.n)
  }

  /** X_t: reversed union of relevant mask change vectors in the robustness window (5.3.2). */
  def computeXt(st: CompressorState, robustnessLevel: Int, vT: Int, xT: Array[Boolean]): Unit = {
    require(robustnessLevel >= 0 && xT.length >= st.n)
    if (vT == 0) {
      BitOps.reverseInto(st.maskChangeVector, st.n, xT) // reverse(row 0)
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
      if (st.maskFlag(fi)) w = w + 1
      fi = fi - 1
      counter = counter + 1
    }).invariant(fi >= -1 && counter >= 0 && w >= 0)
    w
  }

  /** maskDiff[i] = M_t[i] XOR M_t[i+1] (the run-length-encoded mask difference, 5.3.3.2). */
  def computeMaskDiff(st: CompressorState, maskDiff: Array[Boolean]): Unit = {
    require(maskDiff.length >= st.n)
    var i = 0
    (while (i < st.n) {
      decreases(st.n - i)
      val shifted = (i + 1 < st.n) && st.maskNew(i + 1)
      maskDiff(i) = st.maskNew(i) != shifted
      i = i + 1
    }).invariant(i >= 0 && i <= st.n)
  }

  /**
   * Appends reverse(bitExtraction(input, selector)) to `out`, where selector is M_t, or
   * (M_t OR reverse(X_t)) when `useXt`. `selector` and `tmp` are scratch (length n, distinct).
   * Contains no while loops of its own.
   */
  def extractByMask(
    out: Array[Boolean], off: Int, input: Array[Boolean], mask: Array[Boolean],
    xT: Array[Boolean], useXt: Boolean, n: Int, selector: Array[Boolean], tmp: Array[Boolean]
  ): Int = {
    require(
      off >= 0 && n >= 0 && n <= input.length && n <= mask.length && n <= xT.length &&
      n <= selector.length && n <= tmp.length && off + n <= out.length
    )
    val exLen =
      if (!useXt) {
        BitOps.bitExtractionInto(input, mask, n, tmp)
      } else {
        BitOps.reverseInto(xT, n, selector) // selector = reverse(X_t)
        BitOps.orInto(selector, mask, n)    // selector |= M_t
        BitOps.bitExtractionInto(input, selector, n, tmp)
      }
    BitOps.appendReversed(out, off, tmp, exLen)
  }

  // ---- public API ----

  /** Initialises the state: zeroes every vector, resets counters, precomputes COUNT(n). */
  @cCode.`export` @cCode.noMangling
  def compressorInit(st: CompressorState): Unit = {
    BitOps.zeroFill(st.maskNew, st.n)
    BitOps.zeroFill(st.maskOld, st.n)
    BitOps.zeroFill(st.maskBuildNew, st.n)
    BitOps.zeroFill(st.maskBuildOld, st.n)
    BitOps.zeroFill(st.inputOld, st.n)
    st.t = 0
    st.maskChangeCount = 0
    st.maskFlagCount = 0
    st.inputVectorLengthCountLen = Count.encode(st.inputVectorLengthCount, 0, st.n)
  }

  /** Sets the initial mask M_0 (length n) before the first compress. */
  @cCode.`export`
  def setInitialMask(st: CompressorState, initialMask: Array[Boolean]): Unit = {
    require(initialMask.length >= st.n)
    BitOps.copyRange(initialMask, st.n, st.maskNew)
  }

  /**
   * Compresses one input vector into `out`, returning the packet bit length. `input` has length n;
   * `out` must be large enough for the worst-case packet. At t <= robustnessLevel both
   * `sendMaskFlag` and `uncompressedFlag` must be true so the decompressor can initialise.
   *
   * Orchestrator only: contains no `while` loops; every loop lives in a helper above.
   */
  @cCode.`export`
  def compress(
    st: CompressorState, input: Array[Boolean], robustnessLevel: Int,
    newMaskFlag: Boolean, sendMaskFlag: Boolean, uncompressedFlag: Boolean, out: Array[Boolean]
  ): Int = {
    require(
      input.length >= st.n && robustnessLevel >= 0 && robustnessLevel <= MaxRobustnessLevel &&
      out.length >= 4 + 64 * (st.n + 1)
    )
    val n = st.n
    val xT = Array.fill(n)(false)
    val yT = Array.fill(n)(false)
    val kT = Array.fill(n)(false)
    val inv = Array.fill(n)(false)
    val invRev = Array.fill(n)(false)
    val tmp = Array.fill(n)(false)
    val selector = Array.fill(n)(false)
    val maskDiff = Array.fill(n)(false)

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
    var o = Count.runLengthEncoding(out, 0, xT, n)
    out(o) = true;  o = o + 1
    out(o) = false; o = o + 1
    o = BitOps.appendBigEndian(out, o, vT, EffectiveRobustnessBitWidth)
    if (eTLen == 1) { out(o) = eTVal; o = o + 1 }
    o = BitOps.appendBits(out, o, kT, kTLen)
    if (cBitsLen == 1) { out(o) = cBitsVal; o = o + 1 }
    out(o) = dT; o = o + 1

    // ---- Second binary vector q_t (5.3.3.2) ----
    if (dT) {
      // empty
    } else if (sendMaskFlag) {
      computeMaskDiff(st, maskDiff)
      out(o) = true; o = o + 1
      BitOps.reverseInPlace(maskDiff, n)
      o = Count.runLengthEncoding(out, o, maskDiff, n)
      out(o) = true; o = o + 1
      out(o) = false; o = o + 1
    } else {
      out(o) = false; o = o + 1
    }

    // ---- Third binary vector u_t (5.3.3.3) ----
    val cTset = cBitsLen != 0 && cBitsVal
    if (dT) {
      o = extractByMask(out, o, input, st.maskNew, xT, cTset, n, selector, tmp)
    } else if (uncompressedFlag) {
      out(o) = true; o = o + 1
      o = BitOps.appendBits(out, o, st.inputVectorLengthCount, st.inputVectorLengthCountLen)
      o = BitOps.appendBits(out, o, input, n)
    } else {
      out(o) = false; o = o + 1
      o = extractByMask(out, o, input, st.maskNew, xT, sendMaskFlag && cTset, n, selector, tmp)
    }

    // ---- advance state ----
    st.t = st.t + 1
    BitOps.copyRange(input, n, st.inputOld)
    BitOps.copyRange(st.maskNew, n, st.maskOld)
    BitOps.copyRange(st.maskBuildNew, n, st.maskBuildOld)
    o
  }
}

/** A moving read position over the packed bit stream; passed by reference so helpers can advance it
 *  without returning tuples (GenC-friendly). */
case class Cursor(var pos: Int) {
  require(pos >= 0)
}

/**
 * Stateful POCKET+ decompressor. The C# reference keeps full histories of masks and decoded vectors
 * but only ever reads the most recent of each, so the state collapses to a single current `mask`
 * and the previous decoded vector `lastInput`. As with the compressor, every loop is its own named
 * function so the orchestrator has no `while` loops; nothing array-typed is returned.
 *
 * The `ref int bitOffset` of the C# API becomes a return value: `decompress` writes the
 * reconstructed vector (exactly n bits) into `out` and returns the bit offset of the next packet.
 */
case class DecompressorState(n: Int, mask: Array[Boolean], lastInput: Array[Boolean], var t: Int) {
  require(n >= 1 && n <= 65535 && mask.length == n && lastInput.length == n && t >= 0)
}

object Decompressor {
  val MinimumPacketBits: Int = 7
  val EffectiveRobustnessBitWidth: Int = 4
  val CountFiveBitWidth: Int = 5
  val CountExtendedBaseWidth: Int = 5 // matches the reference COUNT-revert seed
  val BitsPerByte: Int = 8

  // ---- bit-stream readers (advance the cursor in place) ----

  /** Reads `width` bits big-endian, advancing the cursor. */
  def readBigEndian(input: Array[Boolean], cursor: Cursor, width: Int): Int = {
    require(width >= 0 && cursor.pos >= 0 && cursor.pos + width <= input.length)
    var value = 0
    var i = 0
    (while (i < width) {
      decreases(width - i)
      value = value * 2 + (if (input(cursor.pos + i)) 1 else 0)
      i = i + 1
    }).invariant(i >= 0 && i <= width && value >= 0)
    cursor.pos = cursor.pos + width
    value
  }

  /** Counts the leading zero bits of an extended COUNT field, advancing the cursor past them.
   *  Returns `startSize` plus the number of zeros consumed. */
  def scanZeros(input: Array[Boolean], cursor: Cursor, startSize: Int): Int = {
    require(startSize >= 0 && cursor.pos >= 0 && cursor.pos < input.length)
    var countSize = startSize
    (while (cursor.pos < input.length && !input(cursor.pos)) {
      countSize = countSize + 1
      cursor.pos = cursor.pos + 1
    }).invariant(countSize >= startSize && cursor.pos >= 0 && cursor.pos <= input.length)
    countSize
  }

  /** Appends `run` zeros then a one to `output` at `outLen`; returns the new length. */
  def emitRun(output: Array[Boolean], outLen: Int, run: Int): Int = {
    require(outLen >= 0 && run >= 0 && outLen + run + 1 <= output.length)
    var k = outLen
    var i = 0
    (while (i < run) {
      decreases(run - i)
      output(k) = false
      k = k + 1
      i = i + 1
    }).invariant(i >= 0 && i <= run && k == outLen + i)
    output(k) = true
    k + 1
  }

  /** Reverses run-length encoding into `output` until the '10' terminator (5.3.1.2); returns length. */
  def undoRunLengthEncoding(input: Array[Boolean], cursor: Cursor, output: Array[Boolean]): Unit = {
    var outLen = 0
    (while (cursor.pos + 1 < input.length && !(input(cursor.pos) && !input(cursor.pos + 1)) &&
            outLen < output.length) {
      if (!input(cursor.pos)) {
        output(outLen) = true
        outLen = outLen + 1
        cursor.pos = cursor.pos + 1
      } else if (cursor.pos + 2 < input.length && input(cursor.pos) && input(cursor.pos + 1) && !input(cursor.pos + 2)) {
        cursor.pos = cursor.pos + 3
        val run = readBigEndian(input, cursor, CountFiveBitWidth) + 1
        if (outLen + run + 1 <= output.length) outLen = emitRun(output, outLen, run)
        else cursor.pos = input.length // give up: malformed / over-long
      } else if (cursor.pos + 2 < input.length && input(cursor.pos) && input(cursor.pos + 1) && input(cursor.pos + 2)) {
        cursor.pos = cursor.pos + 3
        val width = scanZeros(input, cursor, CountExtendedBaseWidth + 1)
        val run = if (cursor.pos + width <= input.length) readBigEndian(input, cursor, width) + 1 else 1
        if (outLen + run + 1 <= output.length) outLen = emitRun(output, outLen, run)
        else cursor.pos = input.length
      } else {
        cursor.pos = cursor.pos + 1 // unreachable for well-formed input
      }
    }).invariant(outLen >= 0 && cursor.pos >= 0)
  }

  /** Reverts COUNT(a) for the input-vector-length field (5.3.1.1); distinct from RLE: no -1 bias. */
  def revertCount(input: Array[Boolean], cursor: Cursor): Int = {
    require(cursor.pos >= 0 && cursor.pos + 3 <= input.length)
    if (!input(cursor.pos)) {
      cursor.pos = cursor.pos + 1
      1
    } else if (input(cursor.pos) && input(cursor.pos + 1) && !input(cursor.pos + 2)) {
      cursor.pos = cursor.pos + 3
      readBigEndian(input, cursor, CountFiveBitWidth) + 2
    } else {
      cursor.pos = cursor.pos + 3
      val width = scanZeros(input, cursor, CountExtendedBaseWidth) + 1
      if (cursor.pos + width <= input.length) readBigEndian(input, cursor, width) + 2 else 2
    }
  }

  // ---- mask reconstruction ----

  /** Cumulative reconstruction of M_t from the run-length-decoded (M_t XOR M_t<<1) difference. */
  def reconstructMaskFromDiff(maskDiff: Array[Boolean], n: Int, mask: Array[Boolean]): Unit = {
    require(n >= 0 && n <= maskDiff.length && n <= mask.length)
    var cum = false
    var j = 0
    (while (j < n) {
      decreases(n - j)
      cum = cum != maskDiff(j)
      mask(n - 1 - j) = cum
      j = j + 1
    }).invariant(j >= 0 && j <= n)
  }

  /** mask[i] = !y_t[..] at the change positions, consuming y_t from its end. */
  def applyMaskFromY(mask: Array[Boolean], dChange: Array[Boolean], yT: Array[Boolean], yTLen: Int, n: Int): Unit = {
    require(n >= 0 && n <= mask.length && n <= dChange.length && yTLen >= 0 && yTLen <= yT.length)
    var i = 0
    var yj = yTLen - 1
    (while (i < n) {
      decreases(n - i)
      if (dChange(i) && yj >= 0 && yj < yTLen) {
        mask(i) = !yT(yj)
        yj = yj - 1
      }
      i = i + 1
    }).invariant(i >= 0 && i <= n && yj < yTLen)
  }

  /** mask[i] = !k_t[..] at the change positions, consuming k_t from its end. */
  def applyMaskFromK(mask: Array[Boolean], dChange: Array[Boolean], kT: Array[Boolean], kTLen: Int, n: Int): Unit = {
    require(n >= 0 && n <= mask.length && n <= dChange.length && kTLen >= 0 && kTLen <= kT.length)
    var i = 0
    var kj = kTLen - 1
    (while (i < n) {
      decreases(n - i)
      if (dChange(i) && kj >= 0 && kj < kTLen) {
        mask(i) = !kT(kj)
        kj = kj - 1
      }
      i = i + 1
    }).invariant(i >= 0 && i <= n && kj < kTLen)
  }

  /** mask[i] ^= dChange[i] (fallback when neither y_t nor k_t is present). */
  def applyMaskXor(mask: Array[Boolean], dChange: Array[Boolean], n: Int): Unit = {
    require(n >= 0 && n <= mask.length && n <= dChange.length)
    var i = 0
    (while (i < n) {
      decreases(n - i)
      mask(i) = mask(i) != dChange(i)
      i = i + 1
    }).invariant(i >= 0 && i <= n)
  }

  /** Dispatches the in-place mask update (no loops of its own). */
  def applyMaskChanges(
    mask: Array[Boolean], dChange: Array[Boolean], yT: Array[Boolean], yTLen: Int,
    kT: Array[Boolean], kTLen: Int, useXorFallback: Boolean, n: Int
  ): Unit = {
    require(
      n >= 0 && n <= mask.length && n <= dChange.length &&
      yTLen >= 0 && yTLen <= yT.length && kTLen >= 0 && kTLen <= kT.length
    )
    if (yTLen > 0) applyMaskFromY(mask, dChange, yT, yTLen, n)
    else if (kTLen > 0) applyMaskFromK(mask, dChange, kT, kTLen, n)
    else if (useXorFallback) applyMaskXor(mask, dChange, n)
  }

  /** Uncompressed-mask branch: mask[i] ^= !y_t[..] at change positions, scanning from the high end. */
  def applyMaskUncompressed(mask: Array[Boolean], dChange: Array[Boolean], yT: Array[Boolean], yTLen: Int, n: Int): Unit = {
    require(n >= 0 && n <= mask.length && n <= dChange.length && yTLen >= 0 && yTLen <= yT.length)
    var i = n - 1
    var yj = 0
    (while (i >= 0) {
      decreases(i + 1)
      if (dChange(i) && yj >= 0 && yj < yTLen) {
        mask(i) = mask(i) != (!yT(yj))
        yj = yj + 1
      }
      i = i - 1
    }).invariant(i >= -1 && i < n && yj >= 0)
  }

  // ---- third-vector helpers ----

  /** Reads k_t into `kT` (built end-first, matching the reference emplace_front), advancing cursor. */
  def fillKt(input: Array[Boolean], cursor: Cursor, kT: Array[Boolean], count: Int): Unit = {
    require(count >= 0 && count <= kT.length && cursor.pos >= 0 && cursor.pos + count <= input.length)
    var m = 0
    (while (m < count) {
      decreases(count - m)
      kT(count - 1 - m) = input(cursor.pos + m)
      m = m + 1
    }).invariant(m >= 0 && m <= count)
    cursor.pos = cursor.pos + count
  }

  /** Reconstructs the vector: take bits from the stream where selected, else copy the previous vector.
   *  Selector is M_t, or (M_t OR X_t) when useXt. Writes into `out`. */
  def reconstruct(input: Array[Boolean], cursor: Cursor, xT: Array[Boolean], useXt: Boolean, st: DecompressorState, out: Array[Boolean]): Unit = {
    require(st.n <= xT.length && st.n <= out.length && cursor.pos >= 0)
    val n = st.n
    var j = 0
    (while (j < n) {
      decreases(n - j)
      val p = n - 1 - j
      val selected = st.mask(p) || (useXt && xT(j))
      if (selected && cursor.pos < input.length) {
        out(p) = input(cursor.pos)
        cursor.pos = cursor.pos + 1
      } else {
        out(p) = st.lastInput(p)
      }
      j = j + 1
    }).invariant(j >= 0 && j <= n && cursor.pos >= 0)
  }

  /** Copies the next n raw bits from the stream into `out` (uncompressed input vector). */
  def copyInputBits(input: Array[Boolean], cursor: Cursor, out: Array[Boolean], n: Int): Unit = {
    require(n >= 0 && n <= out.length && cursor.pos >= 0 && cursor.pos + n <= input.length)
    var i = 0
    (while (i < n) {
      decreases(n - i)
      out(i) = input(cursor.pos)
      cursor.pos = cursor.pos + 1
      i = i + 1
    }).invariant(i >= 0 && i <= n && cursor.pos >= 0)
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
   * Decompresses one packet beginning at `bitOffset` in `input`, writing the reconstructed n-bit
   * vector into `out` and returning the bit offset of the next packet (past byte alignment padding).
   *
   * Orchestrator only: contains no `while` loops; every loop lives in a helper above.
   */
  @cCode.`export`
  def decompress(st: DecompressorState, input: Array[Boolean], bitOffset: Int, out: Array[Boolean]): Int = {
    require(
      bitOffset >= 0 && bitOffset <= input.length &&
      input.length - bitOffset >= MinimumPacketBits && out.length >= st.n
    )
    val n = st.n
    val start = bitOffset
    val cursor = Cursor(start)

    val xT = Array.fill(n)(false)
    val dChange = Array.fill(n)(false)
    val yT = Array.fill(n)(false)
    val kT = Array.fill(n)(false)
    val maskDiff = Array.fill(n)(false)

    // ---- First binary vector h_t (5.3.3.1) ----
    if (cursor.pos + 1 < input.length && !(input(cursor.pos) && !input(cursor.pos + 1))) {
      undoRunLengthEncoding(input, cursor, xT)
    }
    BitOps.reverseInto(xT, n, dChange)
    val xTWeight = BitOps.hammingWeight(xT, n)
    cursor.pos = cursor.pos + 2 // consume the '10' terminator

    val robustnessLevel = readBigEndian(input, cursor, EffectiveRobustnessBitWidth)

    var yTLen = 0
    var eTpresent = false
    var eTval = false
    if (!(robustnessLevel == 0 || xTWeight == 0) && cursor.pos < input.length) {
      if (!input(cursor.pos)) {
        eTpresent = true
        eTval = false
        cursor.pos = cursor.pos + 1
        yTLen = xTWeight // yT stays all-false
      } else {
        eTpresent = true
        eTval = true
        cursor.pos = cursor.pos + 1
      }
    }

    var kTLen = 0
    var cTpresent = false
    var cTval = false
    val kEmpty = (robustnessLevel == 0 || xTWeight == 0 || BitOps.hammingWeight(yT, yTLen) == 0) && (!eTpresent || !eTval)
    if (!kEmpty && cursor.pos + xTWeight < input.length && xTWeight <= n) {
      fillKt(input, cursor, kT, xTWeight)
      kTLen = xTWeight
      cTpresent = true
      cTval = input(cursor.pos)
      cursor.pos = cursor.pos + 1
    }

    val dT = if (cursor.pos < input.length) input(cursor.pos) else false
    cursor.pos = cursor.pos + 1
    var sendMaskFlag = false

    // ---- Second binary vector q_t (5.3.3.2) ----
    if (dT) {
      applyMaskChanges(st.mask, dChange, yT, yTLen, kT, kTLen, true, n)
    } else if (cursor.pos < input.length && input(cursor.pos)) {
      sendMaskFlag = true
      cursor.pos = cursor.pos + 1
      if (cursor.pos + 1 < input.length && !(input(cursor.pos) && !input(cursor.pos + 1))) {
        undoRunLengthEncoding(input, cursor, maskDiff)
        reconstructMaskFromDiff(maskDiff, n, st.mask)
        applyMaskChanges(st.mask, dChange, yT, yTLen, kT, kTLen, false, n)
      } else {
        BitOps.zeroFill(st.mask, n)
      }
      cursor.pos = cursor.pos + 2 // consume the '10' terminator
    } else {
      cursor.pos = cursor.pos + 1
      applyMaskUncompressed(st.mask, dChange, yT, yTLen, n)
    }

    // ---- Third binary vector u_t (5.3.3.3) ----
    if (dT) {
      reconstruct(input, cursor, xT, cTpresent && cTval, st, out)
    } else if (cursor.pos < input.length && input(cursor.pos)) {
      cursor.pos = cursor.pos + 1
      val declaredLength = revertCount(input, cursor)
      if (declaredLength == n && cursor.pos + n <= input.length) copyInputBits(input, cursor, out, n)
    } else {
      cursor.pos = cursor.pos + 1
      reconstruct(input, cursor, xT, sendMaskFlag && cTpresent && cTval, st, out)
    }
    BitOps.copyRange(out, n, st.lastInput)

    st.t = st.t + 1
    start + roundUpToByte(cursor.pos - start)
  }
}

import stainless.io.*
// Crude diagnostic that an executable on a machine ran 
@cCode.`export`
def main(): Int = {
  given s : State = newState
  StdOut.println("Main method of PocketPlus genc output executed successfully.")
  0
}

