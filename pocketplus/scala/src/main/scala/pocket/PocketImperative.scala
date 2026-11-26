package pocket

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.math.Unsigned.*
import stainless.lang.StaticChecks
import stainless.lang.StaticChecks.{Ensuring, WhileDecorations}
import datastructure.arrays.BitArray

/**
  * Imperative implementation of the POCKET+ core (CCSDS 124.0-B-1), in the GenC-compatible
  * imperative subset of Stainless. This file sets the state layout and the entry points of the
  * C API; the bodies are stubs until the refinement to [[PocketExecSpec]] is carried out.
  *
  * Conventions:
  *  - Bit vectors and bit streams are [[datastructure.arrays.BitArray]]s: bits packed MSB first into
  *    unsigned bytes (`uint8_t` in the generated C), with the list of booleans they stand for as ghost
  *    state, which is what relates them to the vectors of the functional specification. A vector of F
  *    bits takes `Layout.bytesPerVector(F)` bytes; the unused low bits of the last byte are not part
  *    of the vector and are never read.
  *  - A precondition written with `require` is also emitted by GenC as an `assert` in the C. One that
  *    mentions ghost state, such as the validity of a `BitArray`, is written with
  *    `StaticChecks.require` and only exists for the prover; so do the class invariants. `ensuring`
  *    and the loop `invariant` are extension methods, which cannot be qualified at the call: theirs
  *    are the `StaticChecks` ones throughout, through the import of its two implicit classes.
  *  - This file is tested as generated C, through the C API, while the functional specification keeps
  *    its JVM tests.
  *  - Every buffer is allocated by the caller, inside the state or passed to the call. Nothing is
  *    allocated here (R-PERF-04) and no call writes outside the state and its output buffer (R-PERF-07).
  *  - Steps return an `Int`: a non-negative number of bits, or a negative error code from [[Errors]].
  *    Init calls cannot fail and return nothing.
  *  - Only integer and bitwise operations are used; in particular no division (R-PERF-03).
  */
object PocketImperative {

  object Layout {
    val MaxF: Int = 65535
    /** Maximum robustness level R_t (blue book 3.3.2). */
    val MaxR: Int = 7
    /** Full change vectors kept for X_t: D_{t-1} .. D_{t-7} (green book table 5-1). */
    val HistoryVectors: Int = 7
    /** The zero flags of the HistoryVectors slots, all set. */
    val AllSlotsZero: UByte = 127.toUByte
    /** The definitions only read min(t, 16), so t saturates there. */
    val TSaturation: Int = 16
    /** The decoder's loss counters saturate at 16 (green book 3.3.4.2). */
    val LossSaturation: Int = 16

    /** Bytes needed to pack a vector of f bits. */
    @cCode.`export`
    def bytesPerVector(f: Int): Int = {
      require(1 <= f && f <= MaxF)
      (f + 7) >>> 3
    }.ensuring(res => 1 <= res && res <= 8192)

    /** Bits of one slot of the change history: a vector of f bits padded to whole bytes, so that every
      * slot starts on a byte boundary. Slot k starts at bit `k * bitsPerSlot(f)`. */
    def bitsPerSlot(f: Int): Int = {
      require(1 <= f && f <= MaxF)
      bytesPerVector(f) << 3
    }.ensuring(res => f <= res && res <= 65536)

    /** Worst-case output vector length in bits, 9.5 * F + 44 (green book table 5-1). */
    @cCode.`export`
    def maxOutputBits(f: Int): Int = {
      require(1 <= f && f <= MaxF)
      ((19 * f + 1) >>> 1) + 44
    }.ensuring(res => 44 < res && res <= 622627)
  }

  object Errors {
    val ErrCapacity: Int = -1  // output buffer too small for this vector
    val ErrMalformed: Int = -2 // packet fails a validity check (green book 3.3.6)
    val ErrArgument: Int = -3  // an argument is out of range or inconsistent with the state
  }

  /** Copies `src` into `dst`, of the same size. */
  def copyBits(src: BitArray, dst: BitArray): Unit = {
    StaticChecks.require(src.valid && dst.valid && src.size == dst.size)
    val n = dst.size
    var i = 0
    (while (i < n) {
      decreases(n - i)
      dst(i) = src(i)
      i += 1
    }).invariant(0 <= i && i <= n && src.valid && src.size == n && dst.valid && dst.size == n)
  }.ensuring(_ => dst.valid && dst.size == old(dst).size)

  /** The basic encoding functions (blue book 5.2), written into a bit array. Each function writes from a position of
    * the output and is specified by the bits it appends to those before that position, so that the calls compose
    * by concatenation, like the lists of the functional specification. */
  object BasicEncodingMut {
    import BasicEncodingFunctions.*
    import PocketExecSpecLemmas.*

    /** The first n bits of a, as a list. It is opaque: the functions below are specified by equations between
      * prefixes, and only `writeBit` needs to know what a prefix is made of. */
    @ghost @pure @opaque
    def prefix(a: BitArray, n: Int): List[Boolean] = {
      require(a.valid && 0 <= n && n <= a.size)
      datastructure.Utils.compareIntPreservedByToBigInt(n, a.size)
      datastructure.Utils.compareIntPreservedByToBigInt(n, 0)
      a.toList.take(BigInt(n))
    }.ensuring(res => res.size == BigInt(n))

    /** Writes bit b at position pos. */
    def writeBit(out: BitArray, pos: Int, b: Boolean): Unit = {
      StaticChecks.require(out.valid)
      require(0 <= pos && pos < out.size)
      ghostExpr({
        val n = out.size
        assert(BigInt(n) == out.toList.size)
        datastructure.Utils.compareIntPreservedByToBigInt(pos, n)
        assert(BigInt(pos) < BigInt(n))
        datastructure.Utils.compareIntPreservedByToBigInt(pos, 0)
        assert(BigInt(0) <= BigInt(pos))
        assert(BigInt(pos) < out.toList.size)
        datastructure.Utils.additionIntPreservedByToBigInt(pos, 1)
        ListLemmas.lemmaUpdatedTakeSnoc(out.toList, BigInt(pos), b)
        unfold(prefix(out, pos))
      })
      out(pos) = b
      ghostExpr(unfold(prefix(out, pos + 1)))
    }.ensuring(_ => out.valid && out.size == old(out).size && prefix(out, pos + 1) == prefix(old(out), pos) ++ List(b))

    /** Writes the k low bits of rem from position pos, most significant first, where half is 2^(k-1). This is
      * encodeNBits of the specification: compare with the weight of the bit, subtract it, halve it. */
    def writeBitsFrom(out: BitArray, pos: Int, rem: Int, k: Int, half: Int): Unit = {
      StaticChecks.require(out.valid)
      require(1 <= k && k <= 30 && 0 <= pos && pos <= out.size - k && 0 <= rem && 0 <= half)
      StaticChecks.require(BigInt(half) == pow2(BigInt(k) - 1) && BigInt(rem) < pow2(BigInt(k)))
      decreases(k)
      @ghost val before = prefix(out, pos)
      val bit = rem >= half
      if (k > 1) {
        val rest = if (bit) rem - half else rem
        ghostExpr({
          lemmaEncodeNBitsStep(rem, k, half)
          ListLemmas.lemmaAppendCons(before, bit, encodeNBits(BigInt(rest), BigInt(k - 1), pow2(BigInt(k - 1))))
        })
        writeBit(out, pos, bit)
        writeBitsFrom(out, pos + 1, rest, k - 1, half >>> 1)
      } else {
        ghostExpr(lemmaEncodeNBitsLast(rem, k, half))
        writeBit(out, pos, bit)
      }
    }.ensuring(_ => out.valid && out.size == old(out).size &&
      prefix(out, pos + k) == prefix(old(out), pos) ++ encodeNBits(BigInt(rem), BigInt(k), pow2(BigInt(k))))

    /** Writes the n low bits of value from position pos, most significant first. */
    def writeBits(out: BitArray, pos: Int, value: Int, n: Int): Unit = {
      StaticChecks.require(out.valid)
      require(1 <= n && n <= 30 && 0 <= pos && pos <= out.size - n && 0 <= value)
      StaticChecks.require(BigInt(value) < pow2(BigInt(n)))
      ghostExpr({
        lemmaShiftIsPow2(n - 1)
        datastructure.Utils.subtractionIntPreservedByToBigInt(n, 1)
      })
      writeBitsFrom(out, pos, value, n, 1 << (n - 1))
    }.ensuring(_ => out.valid && out.size == old(out).size &&
      prefix(out, pos + n) == prefix(old(out), pos) ++ encodeNBits(BigInt(value), BigInt(n), pow2(BigInt(n))))

    /** floor(log2(n)) for 32 <= n <= MaxF, by comparisons as in the specification: no division and no loop. */
    def log2Floor(n: Int): Int = {
      require(32 <= n && n <= Layout.MaxF)
      ghostExpr({
        datastructure.Utils.compareIntPreservedByToBigInt(n, 32)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 63)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 127)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 255)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 511)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 1023)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 2047)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 4095)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 8191)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 16383)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 32767)
        datastructure.Utils.compareIntPreservedByToBigInt(n, 65535)
      })
      if (n <= 63) 5
      else if (n <= 127) 6
      else if (n <= 255) 7
      else if (n <= 511) 8
      else if (n <= 1023) 9
      else if (n <= 2047) 10
      else if (n <= 4095) 11
      else if (n <= 8191) 12
      else if (n <= 16383) 13
      else if (n <= 32767) 14
      else 15
    }.ensuring(res => 5 <= res && res <= 15 && BigInt(res) == floorLog2(BigInt(n)))

    /** The number of bits E on which COUNT writes a - 2 when a >= 34 (blue book 5.2.2, countE in the specification). */
    def countWidth(a: Int): Int = {
      require(34 <= a && a <= Layout.MaxF)
      2 * (log2Floor(a - 2) + 1) - 6
    }.ensuring(res => 6 <= res && res <= 26)

    /** COUNT(a) for a >= 34 is 111 followed by a - 2 on countWidth(a) bits. */
    @ghost @opaque @inlineOnce
    def lemmaCountEncodeLarge(a: Int): Unit = {
      require(34 <= a && a <= Layout.MaxF)
      val v = a - 2
      val l = log2Floor(v)
      val e = countWidth(a)
      datastructure.Utils.compareIntPreservedByToBigInt(a, 33)
      datastructure.Utils.compareIntPreservedByToBigInt(a, 65535)
      datastructure.Utils.subtractionIntPreservedByToBigInt(a, 2)
      assert(BigInt(v) == BigInt(a) - 2)
      datastructure.Utils.additionIntPreservedByToBigInt(l, l)
      datastructure.Utils.subtractionIntPreservedByToBigInt(l + l, 4)
      assert(e == l + l - 4)
      assert(BigInt(e) == 2 * BigInt(l) - 4)
      assert(BigInt(e) == countE(BigInt(v)))
      assert(pow2Fast(BigInt(e)) == pow2(BigInt(e)))
      assert(BigInt(v) < pow2Fast(countE(BigInt(v))))
      lemmaCountPrefix()
    }.ensuring(_ => BigInt(7) < pow2(BigInt(3)) && BigInt(a - 2) < pow2(BigInt(countWidth(a))) &&
      countEncode(BigInt(a)) == encodeNBits(BigInt(7), BigInt(3), pow2(BigInt(3))) ++ encodeNBits(BigInt(a - 2), BigInt(countWidth(a)), pow2(BigInt(countWidth(a)))))

    /** Writes COUNT(a) (blue book 5.2.2) from position pos. Returns the position after it, or Errors.ErrCapacity if
      * it does not fit in out, which may then have been written anywhere from pos on. */
    def writeCount(out: BitArray, pos: Int, a: Int): Int = {
      StaticChecks.require(out.valid)
      require(1 <= a && a <= Layout.MaxF && 0 <= pos && pos <= out.size)
      val room = out.size - pos
      @ghost val before = prefix(out, pos)
      if (a == 1) {
        if (room < 1) Errors.ErrCapacity
        else {
          ghostExpr(lemmaCountEncodeOne(a))
          writeBit(out, pos, false)
          pos + 1
        }
      } else if (a <= 33) {
        if (room < 8) Errors.ErrCapacity
        else {
          ghostExpr({
            lemmaCountEncodeSmall(a)
            ListSpecs.appendAssoc(before, encodeNBits(BigInt(6), BigInt(3), pow2(BigInt(3))), encodeNBits(BigInt(a - 2), BigInt(5), pow2(BigInt(5))))
          })
          writeBits(out, pos, 6, 3)
          writeBits(out, pos + 3, a - 2, 5)
          pos + 8
        }
      } else {
        val e = countWidth(a)
        if (room < 3 + e) Errors.ErrCapacity
        else {
          ghostExpr({
            lemmaCountEncodeLarge(a)
            ListSpecs.appendAssoc(before, encodeNBits(BigInt(7), BigInt(3), pow2(BigInt(3))), encodeNBits(BigInt(a - 2), BigInt(e), pow2(BigInt(e))))
          })
          writeBits(out, pos, 7, 3)
          writeBits(out, pos + 3, a - 2, e)
          pos + 3 + e
        }
      }
    }.ensuring(res => out.valid && out.size == old(out).size && (res == Errors.ErrCapacity || (
      pos < res && res <= out.size && prefix(out, res) == prefix(old(out), pos) ++ countEncode(BigInt(a)))))

    /** The sizes met while scanning a vector of at most MaxF bits, as the specification states them. */
    @ghost @opaque @inlineOnce
    def lemmaScanBounds(n: Int, run: Int, size: Int): Unit = {
      require(0 <= n && n <= size && size <= Layout.MaxF && 0 <= run && run <= size - n)
      datastructure.Utils.compareIntPreservedByToBigInt(run, 0)
      datastructure.Utils.compareIntPreservedByToBigInt(n, 0)
      datastructure.Utils.compareIntPreservedByToBigInt(run, 65535)
      datastructure.Utils.compareIntPreservedByToBigInt(n, 65535)
      datastructure.Utils.additionIntPreservedByToBigInt(run, n)
      datastructure.Utils.compareIntPreservedByToBigInt(run + n, 65535)
    }.ensuring(_ => BigInt(0) <= BigInt(run) && BigInt(0) <= BigInt(n) && BigInt(run) + BigInt(n) <= MAX_F)

    /** What is left to write of a run-length encoding when the bits n - 1 down to 0 of x are still to be scanned
      * and the last `run` bits scanned were zeros: the encoding of these zeros followed by those bits, in scanning
      * order. The scan is specified by how this list shrinks. */
    @ghost @pure @opaque
    def pendingRunLength(x: BitArray, n: Int, run: Int): List[Boolean] = {
      require(x.valid && 0 <= n && n <= x.size && x.size <= Layout.MaxF && 0 <= run && run <= x.size - n)
      lemmaScanBounds(n, run, x.size)
      runLengthEncode(List.fill(BigInt(run))(ZERO) ++ prefix(x, n).reverse)
    }

    /** The first i + 1 bits in reverse order are bit i followed by the first i bits in reverse order. */
    @ghost @opaque @inlineOnce
    def lemmaPrefixReverseCons(x: BitArray, i: Int): Unit = {
      require(x.valid && 0 <= i && i < x.size)
      val size = x.size
      assert(BigInt(size) == x.toList.size)
      datastructure.Utils.compareIntPreservedByToBigInt(i, size)
      assert(BigInt(i) < BigInt(size))
      datastructure.Utils.compareIntPreservedByToBigInt(i, 0)
      assert(BigInt(0) <= BigInt(i))
      datastructure.Utils.additionIntPreservedByToBigInt(i, 1)
      ListLemmas.lemmaTakeReverseCons(x.toList, BigInt(i))
      unfold(prefix(x, i))
      unfold(prefix(x, i + 1))
    }.ensuring(_ => prefix(x, i + 1).reverse == Cons(x(i), prefix(x, i).reverse))

    /** Scanning a zero adds it to the run. */
    @ghost @opaque @inlineOnce
    def lemmaPendingZero(x: BitArray, n: Int, run: Int): Unit = {
      require(x.valid && 1 <= n && n <= x.size && x.size <= Layout.MaxF && 0 <= run && run <= x.size - n)
      require(!x(n - 1))
      lemmaScanBounds(n, run, x.size)
      lemmaPrefixReverseCons(x, n - 1)
      datastructure.Utils.additionIntPreservedByToBigInt(run, 1)
      lemmaZerosThenZero(BigInt(run), prefix(x, n - 1).reverse)
      unfold(pendingRunLength(x, n, run))
      unfold(pendingRunLength(x, n - 1, run + 1))
    }.ensuring(_ => pendingRunLength(x, n, run) == pendingRunLength(x, n - 1, run + 1))

    /** Scanning a one ends the run: COUNT(run + 1) is due, then what follows with an empty run. */
    @ghost @opaque @inlineOnce
    def lemmaPendingOne(x: BitArray, n: Int, run: Int): Unit = {
      require(x.valid && 1 <= n && n <= x.size && x.size <= Layout.MaxF && 0 <= run && run <= x.size - n)
      require(x(n - 1))
      lemmaScanBounds(n, run, x.size)
      lemmaScanBounds(n - 1, 0, x.size)
      lemmaPrefixReverseCons(x, n - 1)
      datastructure.Utils.additionIntPreservedByToBigInt(run, 1)
      datastructure.Utils.subtractionIntPreservedByToBigInt(n, 1)
      val rest = prefix(x, n - 1).reverse
      assert(rest.size == BigInt(n) - 1)
      lemmaRunLengthEncodeZerosThenOne(BigInt(run), rest)
      assert(List.fill(BigInt(0))(ZERO) ++ rest == rest)
      unfold(pendingRunLength(x, n, run))
      unfold(pendingRunLength(x, n - 1, 0))
    }.ensuring(_ => pendingRunLength(x, n, run) == countEncode(BigInt(run + 1)) ++ pendingRunLength(x, n - 1, 0))

    /** When no bit is left, the run of trailing zeros is encoded as the terminator 10, stated as the two bits that
      * are written one after the other. */
    @ghost @opaque @inlineOnce
    def lemmaPendingEnd(x: BitArray, run: Int): Unit = {
      require(x.valid && x.size <= Layout.MaxF && 0 <= run && run <= x.size)
      lemmaScanBounds(0, run, x.size)
      unfold(prefix(x, 0))
      assert(prefix(x, 0).reverse == Nil[Boolean]())
      ListSpecs.rightUnitAppend(List.fill(BigInt(run))(ZERO))
      lemmaRunLengthEncodeZeros(BigInt(run))
      unfold(pendingRunLength(x, 0, run))
      assert(List(ONE, ZERO) == List(ONE) ++ List(ZERO))
    }.ensuring(_ => pendingRunLength(x, 0, run) == List(ONE) ++ List(ZERO))

    /** Before the scan, everything is pending: the encoding of x read from its last bit to its first. */
    @ghost @opaque @inlineOnce
    def lemmaPendingAll(x: BitArray): Unit = {
      require(x.valid && x.size <= Layout.MaxF)
      val size = x.size
      assert(BigInt(size) == x.toList.size)
      ListSpecs.appendTakeDrop(x.toList, Nil[Boolean](), BigInt(size))
      ListSpecs.rightUnitAppend(x.toList)
      unfold(prefix(x, size))
      assert(prefix(x, size) == x.toList)
      assert(List.fill(BigInt(0))(ZERO) ++ x.toList.reverse == x.toList.reverse)
      unfold(pendingRunLength(x, size, 0))
    }.ensuring(_ => pendingRunLength(x, x.size, 0) == runLengthEncode(x.toList.reverse))

    /** Scans the bits n - 1 down to 0 of x, after a run of `run` zeros, and writes from position pos what is left of
      * the run-length encoding: COUNT(run + 1) at each one, and the terminator at the end. */
    def writeRunLengthFrom(x: BitArray, n: Int, run: Int, out: BitArray, pos: Int): Int = {
      StaticChecks.require(x.valid && out.valid)
      require(0 <= n && n <= x.size && x.size <= Layout.MaxF && 0 <= run && run <= x.size - n && 0 <= pos && pos <= out.size)
      decreases(n)
      @ghost val before = prefix(out, pos)
      if (n == 0) {
        if (out.size - pos < 2) Errors.ErrCapacity
        else {
          ghostExpr({
            lemmaPendingEnd(x, run)
            ListSpecs.appendAssoc(before, List(true), List(false))
          })
          writeBit(out, pos, true)
          writeBit(out, pos + 1, false)
          pos + 2
        }
      } else if (x(n - 1)) {
        ghostExpr({
          lemmaPendingOne(x, n, run)
          ListSpecs.appendAssoc(before, countEncode(BigInt(run + 1)), pendingRunLength(x, n - 1, 0))
        })
        val next = writeCount(out, pos, run + 1)
        if (next < 0) next
        else writeRunLengthFrom(x, n - 1, 0, out, next)
      } else {
        ghostExpr(lemmaPendingZero(x, n, run))
        writeRunLengthFrom(x, n - 1, run + 1, out, pos)
      }
    }.ensuring(res => out.valid && out.size == old(out).size && (res == Errors.ErrCapacity || (
      pos < res && res <= out.size && prefix(out, res) == prefix(old(out), pos) ++ pendingRunLength(x, n, run))))

    /** Writes from position pos the run-length encoding (blue book 5.2.3) of x read from its last bit to its first,
      * which is the order in which the encoder applies it to X_t and to the mask changes (5.3.2). Returns the
      * position after it, or Errors.ErrCapacity if it does not fit in out, which may then have been written anywhere
      * from pos on.
      *
      * Exported for now only so that the generated C of the basic encoding functions shows in the diffs: nothing
      * calls it from C, and the export goes away once encodeStep uses it. */
    @cCode.`export`
    def writeRunLengthReversed(x: BitArray, out: BitArray, pos: Int): Int = {
      StaticChecks.require(x.valid && out.valid)
      require(x.size <= Layout.MaxF && 0 <= pos && pos <= out.size)
      ghostExpr(lemmaPendingAll(x))
      writeRunLengthFrom(x, x.size, 0, out, pos)
    }.ensuring(res => out.valid && out.size == old(out).size && (res == Errors.ErrCapacity || (
      pos < res && res <= out.size && prefix(out, res) == prefix(old(out), pos) ++ runLengthEncode(x.toList.reverse))))
  }

  @ghost
  def encoderInvariant(f: Int, t: Int, lastInput: BitArray, mask: BitArray, build: BitArray, changeHistory: BitArray, changeHistoryZero: UByte, historyHead: Int, zeroRunBeyond: Int, newMaskHistory: UShort, xt: BitArray, yt: BitArray): Boolean = {
    1 <= f && f <= Layout.MaxF &&
    0 <= t && t <= Layout.TSaturation &&
    lastInput.valid && lastInput.size == f &&
    mask.valid && mask.size == f &&
    build.valid && build.size == f &&
    changeHistory.valid && changeHistory.size == Layout.HistoryVectors * Layout.bitsPerSlot(f) &&
    changeHistoryZero <= Layout.AllSlotsZero &&
    0 <= historyHead && historyHead < Layout.HistoryVectors &&
    0 <= zeroRunBeyond && zeroRunBeyond <= 8 &&
    xt.valid && xt.size == f &&
    yt.valid && yt.size == f
  }

  /** Encoder state of one stream, laid out as in green book table 5-1; sized by the caller for a fixed f.
    * R_t is not stored: it is an input of every step, as in the functional specification. */
  case class EncoderState(
      f: Int,
      var t: Int,                        // saturating at Layout.TSaturation
      lastInput: BitArray,               // I_{t-1}
      mask: BitArray,                    // M_{t-1}
      build: BitArray,                   // B_{t-1}
      changeHistory: BitArray,           // D_{t-1} .. D_{t-7}: ring buffer of HistoryVectors slots of Layout.bitsPerSlot(f) bits, newest at historyHead
      var changeHistoryZero: UByte,      // bit k is set when the vector stored in slot k is all zero (Z_t of green book 5.3.1.2); bit 7 stays clear
      var historyHead: Int,
      var zeroRunBeyond: Int,            // consecutive all-zero D older than the stored ones, saturating at 8
      var newMaskHistory: UShort,        // p_{t-1} .. p_{t-16}, newest in bit 0; the definitions read at most the 15 newest
      xt: BitArray,                      // scratch: X_t
      yt: BitArray                       // scratch: y_t and bit extractions
  ) {
    StaticChecks.require(encoderInvariant(f, t, lastInput, mask, build, changeHistory, changeHistoryZero, historyHead, zeroRunBeyond, newMaskHistory, xt, yt))

    @ghost
    @inlineOnce
    def lemmaInvariant(): Unit = {}.ensuring(_ => encoderInvariant(f, t, lastInput, mask, build, changeHistory, changeHistoryZero, historyHead, zeroRunBeyond, newMaskHistory, xt, yt))
  }

  @ghost
  def decoderInvariant(f: Int, t: Int, lastInput: BitArray, mask: BitArray, unknownMasks: Int, unknownInputs: Int, xt: BitArray, sentMask: BitArray): Boolean = {
    1 <= f && f <= Layout.MaxF &&
    0 <= t && t <= Layout.TSaturation &&
    lastInput.valid && lastInput.size == f &&
    mask.valid && mask.size == f &&
    0 <= unknownMasks && unknownMasks <= Layout.LossSaturation &&
    0 <= unknownInputs && unknownInputs <= Layout.LossSaturation &&
    xt.valid && xt.size == f &&
    sentMask.valid && sentMask.size == f
  }

  /** Decoder state of one stream (green book 3.3.2). F is fixed at creation, see docs/questions.md. */
  case class DecoderState(
      f: Int,
      var t: Int,                        // saturating at Layout.TSaturation
      lastInput: BitArray,               // I_{t-1}
      mask: BitArray,                    // M_{t-1}
      var unknownMasks: Int,             // L^M_t, saturating at Layout.LossSaturation
      var unknownInputs: Int,            // L^I_t, saturating at Layout.LossSaturation
      xt: BitArray,                      // scratch: X_t
      sentMask: BitArray                 // scratch: mask from q_t, kept apart until the checks pass (green book 3.3.3.2)
  ) {
    StaticChecks.require(decoderInvariant(f, t, lastInput, mask, unknownMasks, unknownInputs, xt, sentMask))

    @ghost
    @inlineOnce
    def lemmaInvariant(): Unit = {}.ensuring(_ => decoderInvariant(f, t, lastInput, mask, unknownMasks, unknownInputs, xt, sentMask))
  }

  object EncoderMut {
    /** Starts a stream: resets `st` to time step 0 with initial mask M_0 (blue book 4.1). Calling it again
      * restarts the stream.
      *
      * @param st encoder state, sized by the caller for its f; every non-scratch field is overwritten
      * @param initialMask initial mask M_0, of `st.f` bits
      */
    @cCode.`export`
    def encoderInit(st: EncoderState, initialMask: BitArray): Unit = {
      StaticChecks.require(initialMask.valid)
      require(initialMask.size == st.f)
      ghostExpr(st.lemmaInvariant())
      st.t = 0
      st.lastInput.clear()
      copyBits(initialMask, st.mask)
      st.build.clear()
      st.changeHistory.clear()
      st.changeHistoryZero = Layout.AllSlotsZero
      st.historyHead = 0
      st.zeroRunBeyond = 0
      st.newMaskHistory = 0.toUShort
    }.ensuring(_ => st.t == 0)

    /** One compression step (blue book sections 4 and 5.3): writes o_t into `out` and returns its length
      * in bits, or [[Errors.ErrCapacity]] if `out` is too small, in which case the state is unchanged.
      *
      * @param st encoder state at time step t, updated in place to the state at t+1
      * @param input input vector I_t, of `st.f` bits
      * @param newMaskFlag p_t, new-mask flag (blue book 3.3.2)
      * @param sendMaskFlag f_t, send-mask flag (blue book 3.3.2); must be set while t <= R_t
      * @param uncompressedFlag r_t, uncompressed flag (blue book 3.3.2); must be set while t <= R_t
      * @param robustnessT R_t, robustness level of this step (blue book 3.3.2), free to change at every step
      * @param out output buffer receiving o_t from bit 0; its size is the number of bits the call may write
      */
    @cCode.`export`
    def encodeStep(
        st: EncoderState,
        input: BitArray,
        newMaskFlag: Boolean,
        sendMaskFlag: Boolean,
        uncompressedFlag: Boolean,
        robustnessT: Int,
        out: BitArray
    ): Int = {
      StaticChecks.require(input.valid && out.valid)
      require(input.size == st.f)
      require(0 <= robustnessT && robustnessT <= Layout.MaxR)
      require(st.t > robustnessT || (sendMaskFlag && uncompressedFlag)) // blue book 3.3.2 c) and d); exact, as t saturates above MaxR
      require(out.size <= Layout.maxOutputBits(Layout.MaxF))
      0
    }.ensuring(res => out.valid && res <= out.size)
  }

  object DecoderMut {
    /** Starts a stream: resets `st` to time step 0, synchronized (green book 3.3.2). The first packet of a
      * stream carries the full mask and the uncompressed input, so the initial vectors are irrelevant.
      *
      * @param st decoder state, sized by the caller for its f; every non-scratch field is overwritten
      */
    @cCode.`export`
    def decoderInit(st: DecoderState): Unit = {
      ghostExpr(st.lemmaInvariant())
      st.t = 0
      st.lastInput.clear()
      st.mask.clear()
      st.unknownMasks = 0
      st.unknownInputs = 0
    }.ensuring(_ => st.t == 0)

    /** One decompression step: consumes exactly one output vector from the front of `packet`, writes I_t
      * into `out` and returns the number of bits consumed, at most the size of `packet`. Returns
      * [[Errors.ErrMalformed]] and leaves the state unchanged if the packet fails a validity check
      * (green book 3.3.6). The caller decides what the remaining bits mean: padding on the packet path,
      * the next vector on the file path.
      *
      * @param st decoder state at time step t, updated in place to the state at t+1
      * @param packet received bits, starting with o_t at bit 0
      * @param out output buffer receiving I_t, of `st.f` bits
      */
    @cCode.`export`
    def decodeStep(st: DecoderState, packet: BitArray, out: BitArray): Int = {
      StaticChecks.require(packet.valid && out.valid)
      require(out.size == st.f)
      Errors.ErrMalformed
    }.ensuring(res => packet.valid && res <= packet.size)
  }
}
