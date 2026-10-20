package pocket

import stainless.lang.*
import stainless.annotation.*
import stainless.math.Unsigned.*

/**
  * Imperative implementation of the POCKET+ core (CCSDS 124.0-B-1), in the GenC-compatible
  * imperative subset of Stainless. This file sets the state layout and the entry points of the
  * C API; the bodies are stubs until the refinement to [[PocketExecSpec]] is carried out.
  *
  * Conventions:
  *  - A vector of F bits is packed MSB first into `Layout.bytesPerVector(F)` bytes; the unused low
  *    bits of the last byte are zero.
  *  - Bytes are `UByte` from `stainless.math.Unsigned`, generated as `uint8_t`. This file is tested as
  *    generated C, through the C API, while the functional specification keeps its JVM tests.
  *  - Every buffer is allocated by the caller, inside the state or passed to the call. Nothing is
  *    allocated here (R-PERF-04) and no call writes outside the state and its output buffer (R-PERF-07).
  *  - Calls return an `Int`: a non-negative number of bits, or a negative error code from [[Errors]].
  *  - Only integer and bitwise operations are used; in particular no division (R-PERF-03).
  */
object PocketImperative {

  object Layout {
    val MaxF: Int = 65535
    /** Full change vectors kept for X_t: D_{t-1} .. D_{t-7} (green book table 5-1). */
    val HistoryVectors: Int = 7
    /** The definitions only read min(t, 16), so t saturates there. */
    val TSaturation: Int = 16
    /** The decoder's loss counters saturate at 16 (green book 3.3.4.2). */
    val LossSaturation: Int = 16

    /** Bytes needed to pack a vector of f bits. */
    def bytesPerVector(f: Int): Int = {
      require(1 <= f && f <= MaxF)
      (f + 7) >>> 3
    }.ensuring(res => 1 <= res && res <= 8192)

    /** Worst-case output vector length in bits, 9.5 * F + 44 (green book table 5-1). */
    def maxOutputBits(f: Int): Int = {
      require(1 <= f && f <= MaxF)
      ((19 * f + 1) >>> 1) + 44
    }.ensuring(res => 44 < res && res <= 622627)
  }

  object Errors {
    val ErrCapacity: Int = -1  // output buffer too small for this vector
    val ErrMalformed: Int = -2 // packet fails a validity check (green book 3.3.6)
    val ErrArgument: Int = -3  // a size argument is inconsistent with the state
  }

  def encoderInvariant(f: Int, robustnessT: Int, t: Int, lastInput: Array[UByte], mask: Array[UByte], build: Array[UByte], changeHistory: Array[UByte], changeHistoryZero: Array[Boolean], historyHead: Int, zeroRunBeyond: Int, newMaskHistory: Int, xt: Array[UByte], yt: Array[UByte]): Boolean = {
    1 <= f && f <= Layout.MaxF &&
    0 <= robustnessT && robustnessT <= 7 &&
    0 <= t && t <= Layout.TSaturation &&
    lastInput.length == Layout.bytesPerVector(f) &&
    mask.length == Layout.bytesPerVector(f) &&
    build.length == Layout.bytesPerVector(f) &&
    changeHistory.length == Layout.HistoryVectors * Layout.bytesPerVector(f) &&
    changeHistoryZero.length == Layout.HistoryVectors &&
    0 <= historyHead && historyHead < Layout.HistoryVectors &&
    0 <= zeroRunBeyond && zeroRunBeyond <= 8 &&
    0 <= newMaskHistory && newMaskHistory < 32768 &&
    xt.length == Layout.bytesPerVector(f) &&
    yt.length == Layout.bytesPerVector(f)
  }

  /** Encoder state of one stream, laid out as in green book table 5-1; sized by the caller for a fixed f. */
  case class EncoderState(
      f: Int,
      var robustnessT: Int,              // R_t, fixed for the stream as in the functional specification
      var t: Int,                        // saturating at Layout.TSaturation
      lastInput: Array[UByte],            // I_{t-1}
      mask: Array[UByte],                 // M_{t-1}
      build: Array[UByte],                // B_{t-1}
      changeHistory: Array[UByte],        // D_{t-1} .. D_{t-7}: ring buffer of HistoryVectors slots, newest at historyHead
      changeHistoryZero: Array[Boolean], // per slot: the stored vector is all zero (Z_t of green book 5.3.1.2)
      var historyHead: Int,
      var zeroRunBeyond: Int,            // consecutive all-zero D older than the stored ones, saturating at 8
      var newMaskHistory: Int,           // p_{t-1} .. p_{t-15} in the low 15 bits
      xt: Array[UByte],                   // scratch: X_t
      yt: Array[UByte]                    // scratch: y_t and bit extractions
  ) {
    require(encoderInvariant(f, robustnessT, t, lastInput, mask, build, changeHistory, changeHistoryZero, historyHead, zeroRunBeyond, newMaskHistory, xt, yt))

    @ghost
    @inlineOnce
    def lemmaInvariant(): Unit = {}.ensuring(_ => encoderInvariant(f, robustnessT, t, lastInput, mask, build, changeHistory, changeHistoryZero, historyHead, zeroRunBeyond, newMaskHistory, xt, yt))
  }

  def decoderInvariant(f: Int, t: Int, lastInput: Array[UByte], mask: Array[UByte], unknownMasks: Int, unknownInputs: Int, xt: Array[UByte], sentMask: Array[UByte]): Boolean = {
    1 <= f && f <= Layout.MaxF &&
    0 <= t && t <= Layout.TSaturation &&
    lastInput.length == Layout.bytesPerVector(f) &&
    mask.length == Layout.bytesPerVector(f) &&
    0 <= unknownMasks && unknownMasks <= Layout.LossSaturation &&
    0 <= unknownInputs && unknownInputs <= Layout.LossSaturation &&
    xt.length == Layout.bytesPerVector(f) &&
    sentMask.length == Layout.bytesPerVector(f)
  }

  /** Decoder state of one stream (green book 3.3.2). F is fixed at creation, see docs/questions.md. */
  case class DecoderState(
      f: Int,
      var t: Int,                        // saturating at Layout.TSaturation
      lastInput: Array[UByte],            // I_{t-1}
      mask: Array[UByte],                 // M_{t-1}
      var unknownMasks: Int,             // L^M_t, saturating at Layout.LossSaturation
      var unknownInputs: Int,            // L^I_t, saturating at Layout.LossSaturation
      xt: Array[UByte],                   // scratch: X_t
      sentMask: Array[UByte]              // scratch: mask from q_t, kept apart until the checks pass (green book 3.3.3.2)
  ) {
    require(decoderInvariant(f, t, lastInput, mask, unknownMasks, unknownInputs, xt, sentMask))

    @ghost
    @inlineOnce
    def lemmaInvariant(): Unit = {}.ensuring(_ => decoderInvariant(f, t, lastInput, mask, unknownMasks, unknownInputs, xt, sentMask))
  }

  object EncoderMut {
    /** One compression step (blue book sections 4 and 5.3): writes o_t into `out` and returns its length
      * in bits, or [[Errors.ErrCapacity]] if `outCapacityBits` is too small, in which case the state is
      * unchanged.
      *
      * @param st encoder state at time step t, updated in place to the state at t+1
      * @param input input vector I_t, packed in `Layout.bytesPerVector(st.f)` bytes
      * @param newMaskFlag p_t, new-mask flag (blue book 3.3.2)
      * @param sendMaskFlag f_t, send-mask flag (blue book 3.3.2); must be set while t <= R_t
      * @param uncompressedFlag r_t, uncompressed flag (blue book 3.3.2); must be set while t <= R_t
      * @param out output buffer receiving o_t, MSB first from bit 0
      * @param outCapacityBits number of bits of `out` the call may write
      */
    @cCode.`export`
    def encodeStep(
        st: EncoderState,
        input: Array[UByte],
        newMaskFlag: Boolean,
        sendMaskFlag: Boolean,
        uncompressedFlag: Boolean,
        out: Array[UByte],
        outCapacityBits: Int
    ): Int = {
      require(input.length == Layout.bytesPerVector(st.f))
      require(0 <= outCapacityBits && outCapacityBits <= Layout.maxOutputBits(Layout.MaxF))
      require(((outCapacityBits + 7) >>> 3) <= out.length)
      0
    }.ensuring(res => res <= outCapacityBits)
  }

  object DecoderMut {
    /** One decompression step: consumes exactly one output vector from the front of `packet`, writes I_t
      * into `out` and returns the number of bits consumed, at most `packetBits`. Returns
      * [[Errors.ErrMalformed]] and leaves the state unchanged if the packet fails a validity check
      * (green book 3.3.6). The caller decides what the remaining bits mean: padding on the packet path,
      * the next vector on the file path.
      *
      * @param st decoder state at time step t, updated in place to the state at t+1
      * @param packet received bits, MSB first from bit 0, starting with o_t
      * @param packetBits number of valid bits in `packet`
      * @param out output buffer receiving I_t, packed in `Layout.bytesPerVector(st.f)` bytes
      */
    @cCode.`export`
    def decodeStep(st: DecoderState, packet: Array[UByte], packetBits: Int, out: Array[UByte]): Int = {
      require(0 <= packetBits && packetBits <= 2147483640 && ((packetBits + 7) >>> 3) <= packet.length)
      require(out.length == Layout.bytesPerVector(st.f))
      Errors.ErrMalformed
    }.ensuring(res => res <= packetBits)
  }
}
