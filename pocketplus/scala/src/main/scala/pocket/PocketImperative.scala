package pocket

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
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
  *  - The buffers of a call are built by the C caller, so an entry point assumes nothing of their
  *    ghost state: it requires `wellSized`, which is checked at run time, and establishes their
  *    validity itself. The bit arrays of a state are covered by the class invariant instead.
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
      require(initialMask.wellSized && initialMask.storedSize == st.f)
      ghostExpr(st.lemmaInvariant())
      ghostExpr(initialMask.establishValid())
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
      require(input.wellSized && input.storedSize == st.f)
      require(0 <= robustnessT && robustnessT <= Layout.MaxR)
      require(st.t > robustnessT || (sendMaskFlag && uncompressedFlag)) // blue book 3.3.2 c) and d); exact, as t saturates above MaxR
      require(out.wellSized && out.storedSize <= Layout.maxOutputBits(Layout.MaxF))
      ghostExpr({
        input.establishValid()
        out.establishValid()
      })
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
      require(packet.wellSized)
      require(out.wellSized && out.storedSize == st.f)
      ghostExpr({
        packet.establishValid()
        out.establishValid()
      })
      Errors.ErrMalformed
    }.ensuring(res => packet.valid && res <= packet.size)
  }
}
