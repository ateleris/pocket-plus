package pocket

import stainless.lang.{ghost => ghostExpr, Option, None, Some, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*
import Utils.*

object PocketExecSpec {

  /** Whether a D_t vector represents "no change" (all bits zero). Named (rather than an inline
    * lambda) so every `takeWhile(isAllZero)` call site refers to the exact same function value for easier 
    * verification. */
  def isAllZero(dv: List[Boolean]): Boolean = dv.forall(b => b == false)

  /** Maximum robustness level R_t (bluebook 3.3.2). */
  val MAX_R: BigInt = 7

  def paramsInvariant(t: BigInt, f: BigInt, m0: List[Boolean], pts: List[Boolean], itMinusOne: List[Boolean], btMinusOne: List[Boolean], mtMinusOne: List[Boolean], dts: List[List[Boolean]]): Boolean = {
      (t >= 0)
      && (1 <= f && f <= BasicEncodingFunctions.MAX_F)
      && (m0.size == f)
      && (pts.size == min(t, 15)) // pts is the history of the previous new-mask flags p_{t-1}, p_{t-2}, ..., newest first (the current p_t is an input of the step): V_t <= 15, so the last min(t,15) are enough for eq. 20 for any R_t.
      && (itMinusOne.size == f)
      && (btMinusOne.size == f && (if t == 0 then btMinusOne == build0(f) else true))
      && (mtMinusOne.size == f && (if t == 0 then mtMinusOne == m0 else true))
      && (dts.forall(dv => dv.size == f) && dts.size == min(t, 15)) // last min(t,15) D_t's, newest first: enough history to compute both X_t (first R_t of them) and C_t (5.3.2.2, eq. 14) for any R_t

    }
  /**
    * Compression parameters as defined in the blue book (Section 3.3.2, page 3-4).
    * 
    * Note from the bluebook:
    *  The values of user-specified parameters Mo, Rt, ft, Pt, and it need not be known
    *  in advance, or communicated via means external to the compressor, for successful decompression.
    *
    * @param t current time step, starting from 0
    * @param f size of the vectors, in bits
    * @param m0 initial mask, a bit sequence of length f
    * @param pts history of the previous "new mask" flags p_{t-1}, p_{t-2}, ... (the last min(t, 15) of them), needed to compute c_t in the encoding step (eq 20 in bluebook section 5.3.3.1). Head is p_{t-1}; the current p_t is an input of the step.
    *           The unpredictable bits in the mask cannot be classified as predictable unless this flag is set by the user to true (bluebook section 2.1)
    * @param itMinusOne input vector at time step t-1
    * @param btMinusOne build vector at time step t-1
    * @param mtMinusOne mask vector at time step t-1
    * @param dts last min(t, 15) D_t's (used to compute X_t and C_t during encoding). The head is them most recent element, and at most D_{t-1}. This is NOT a parameter but some state.
    */
  case class CompressionState(t: BigInt, f: BigInt, m0: List[Boolean], pts: List[Boolean], itMinusOne: List[Boolean], btMinusOne: List[Boolean], mtMinusOne: List[Boolean], dts: List[List[Boolean]]) {
    require(paramsInvariant(t, f, m0, pts, itMinusOne, btMinusOne, mtMinusOne, dts))

    /** Whether the new-mask flag was set to one more than once over the current step (flag `pt`) and the
      * previous historyLength - 1 steps (bluebook eq. 20). If the window is longer than elapsed time, pts must
      * hold the whole history (pts.size == t). */
    def ptSetMoreThanOnceInHistory(pt: Boolean, historyLength: BigInt): Boolean = {
      require(historyLength >= 1 && (historyLength <= pts.size + 1 || pts.size == t))
      Cons(pt, pts).take(historyLength).count(b => b == true) > 1
    }

    @ghost
    @inlineOnce
    def lemmaInvariant(): Unit = {}.ensuring(_ => paramsInvariant(t, f, m0, pts, itMinusOne, btMinusOne, mtMinusOne, dts))
  }

  /**
  * Decompressor state at time step t. Unlike CompressionState it carries no user-specified
  * parameter (M_0, R_t, p_t, f_t, r_t): the blue book guarantees they are all recoverable from the
  * output vectors (section 3.1 and note of 3.3.2). Given F, the decompressor only needs the last
  * reconstructed vector and a mask synchronized with the compressor's (section 2.1, a and b).
  *
  * @param t current time step, starting from 0
  * @param f size of the vectors, in bits
  * @param itMinusOne last reconstructed input vector, I_{t-1}
  * @param mtMinusOne mask vector at time step t-1, M_{t-1}
  */
  case class DecompressionState(t: BigInt, f: BigInt, itMinusOne: List[Boolean], mtMinusOne: List[Boolean]) {
    require(t >= 0 && 1 <= f && f <= BasicEncodingFunctions.MAX_F && itMinusOne.size == f && mtMinusOne.size == f)
  }

  /**
    * Initial Build (B_0) vector, as defined in bluebook section 4.1 page 4-1.
    * 
    * f is the length of the vector, which is the length of compressed vectors.
    *
    * @param f
    */
  def build0(f: BigInt): List[Boolean] = {
    require(1 <= f && f <= BasicEncodingFunctions.MAX_F)
    List.fill(f)(false)
  }


  /**
    * The part of the compressor's state that the decompressor can track from the output vectors alone.
    * The remaining fields cannot be recovered: p_t and B_{t-1} never reach the output (different
    * histories can yield identical output vectors), and f_{t+1}, r_{t+1} are only known once o_{t+1}
    * arrives.
    */
  def decoderView(params: CompressionState): DecompressionState = {
    ghostExpr(params.lemmaInvariant())
    DecompressionState(params.t, params.f, params.itMinusOne, params.mtMinusOne)
  }

  /**
    * The decompressor is synchronized with the compressor when it holds the compressor's decoder view.
    * At t = 0 only t and f must match: f_0 = r_0 = 1 (section 3.3.2), so the first output vector
    * carries both the full mask and the uncompressed input.
    */
  def inSync(params: CompressionState, st: DecompressionState): Boolean = {
    params.t == st.t && params.f == st.f && (params.t == 0 || decoderView(params) == st)
  }
}

/**
  * Compressor: mask update (bluebook section 4) and encoding (bluebook section 5.3).
  */
object Encoder {
  import PocketExecSpec.{CompressionState, build0, isAllZero, MAX_R}

  /**
    * Compute the new mask and the new build vector for the given time step t, based on the previous build and mask vectors, and the current input vector.
    * The procedure is defined in the blue book (Section 4.2, page 4-1).
    *
    * @param t
    * @param params
    * @param itMinusOne
    * @param it
    * @param btMinusOne
    * @param mtMinusOne
    * @return
    */
  def updateMaskAndBuild(params: CompressionState, it: List[Boolean], pt: Boolean): (List[Boolean], List[Boolean]) = {
    require(params.f == it.size)
    if (pt == false) {
      val newBuild: List[Boolean] with newBuild.size == params.f = if params.t > 0 then or(xor(it, params.itMinusOne), params.btMinusOne) else build0(params.f)
      val newMask: List[Boolean] with newMask.size == params.f = if params.t > 0 then or(xor(it, params.itMinusOne), params.mtMinusOne) else params.m0
      (newBuild, newMask)
    } else {
      val newBuild: List[Boolean] with newBuild.size == params.f = build0(params.f)
      val newMask: List[Boolean] with newMask.size == params.f = if params.t > 0 then or(xor(it, params.itMinusOne), params.btMinusOne) else params.m0
      (newBuild, newMask)
    }: (List[Boolean], List[Boolean])
  }.ensuring(res => res._1.size == params.f && res._2.size == params.f)

  /**
    * Change vector update computation, defined in bluebook section 4.2.3.
    * 
    * This is the D_t vector.
    *
    * @param params
    * @param mt
    */
  def updateChangeVector(params: CompressionState, mt: List[Boolean]): List[Boolean] = {
    require(params.f == mt.size)
    if params.t > 0 then xor(mt, params.mtMinusOne) else build0(params.f)
  }.ensuring(res => res.size == params.f)

  /**
    * Advances one step: encodes `it` under `params` with the user-specified parameters of this step
    * (robustness level `robustnessT`, new-mask flag `pt`, send-mask flag `ft`, uncompressed flag `rt`,
    * bluebook section 3.3.2), and derives the CompressionState for step t+1 from the resulting
    * mask/build/change state. For t <= R_t the blue book requires ft = rt = 1. The state keeps the
    * last min(t, 15) D_t's and p_t's, which is enough for any R_t (C_t is computed from `dts`, see compVt).
    * 
    * @param params CompressionState at time step t
    * @param it input vector to compress at time step t
    * @param pt new-mask flag for this step (bluebook section 3.3.2) indicating whether the mask should be updated according to new predictable bits
    * @param ft send-mask flag for this step (bluebook section 3.3.2) indicating whether the mask should be sent in the output vector (must be true if t <= R_t)
    * @param rt uncompressed flag for this step (bluebook section 3.3.2) indicating whether the input vector should be sent uncompressed in the output vector (must be true if t <= R_t)
    * @param robustnessT robustness level for this step (also called R_t) (bluebook section 3.3.2), which is the number of previous steps to consider when computing the X_t vector
    */
  def step(params: CompressionState, it: List[Boolean], pt: Boolean, ft: Boolean, rt: Boolean, robustnessT: BigInt): (List[Boolean], CompressionState) = {
    require(params.f == it.size)
    require(0 <= robustnessT && robustnessT <= MAX_R)
    require(params.t > robustnessT || (ft && rt)) // bluebook 3.3.2 c) and d)
    val (packet, bt, mt, dvect) = outputVector(params, it, pt, ft, rt, robustnessT)
    val tNext = params.t + 1
    val consDts = Cons(dvect, params.dts)
    val newDts = consDts.take(min(tNext, 15))
    assert(newDts.size <= min(tNext, 15))
    assert(newDts.size <= 15)
    val newPts = Cons(pt, params.pts).take(min(tNext, 15))

    ghostExpr({
      params.lemmaInvariant()
      assert(tNext >= 0)
      assert(1 <= params.f && params.f <= BasicEncodingFunctions.MAX_F)
      assert(params.m0.size == params.f)
      assert(newPts.size == min(tNext, 15))
      assert(it.size == params.f)
      assert(bt.size == params.f && (if tNext == 0 then bt == build0(params.f) else true))
      assert(mt.size == params.f && (if tNext == 0 then mt == params.m0 else true))
      assert(dvect.size == params.f)
      assert(consDts.forall(dv => dv.size == params.f))
      ListSpecs.contentSubsetPreservesForall(consDts, newDts, (dv: List[Boolean]) => dv.size == params.f)
      assert(newDts.forall(dv => dv.size == params.f) && newDts.size == min(tNext, 15))
    })
    val nextParams = CompressionState(
      t = tNext, f = params.f, m0 = params.m0,
      pts = newPts,
      itMinusOne = it, btMinusOne = bt, mtMinusOne = mt, dts = newDts
    )
    (packet, nextParams)
  }.ensuring(res => 
    res._2.t == params.t + 1 && 
    res._2.itMinusOne == it
  )

  /**
    * Computes the output vector for the given time step, along with the resulting build (B_t),
    * mask (M_t) and change (D_t) vectors, so the caller can advance to time step t+1 without
    * recomputing them.
    *
    * @param params
    * @param it
    * @return (output vector, bt, mt, dvect)
    */
  def outputVector(params: CompressionState, it: List[Boolean], pt: Boolean, ft: Boolean, rt: Boolean, robustnessT: BigInt): (List[Boolean], List[Boolean], List[Boolean], List[Boolean]) = {
    require(params.f == it.size)
    require(0 <= robustnessT && robustnessT <= MAX_R)
    val (bt, mt) = updateMaskAndBuild(params, it, pt)
    val dvect = updateChangeVector(params, mt)
    // ht computation
    // X_T represents all the bit positions that became "unpredictable" during the last R_t + 1 cycles, i.e., if R_t = 0 then X_t = D_t so the changes between t-1 and t, and otherwise, betwee t-R_t and t.
    ghostExpr({
      params.lemmaInvariant()
      assert(params.dts.forall(dv => dv.size == params.f))
      ListSpecs.contentSubsetPreservesForall(params.dts, params.dts.take(robustnessT), (dv: List[Boolean]) => dv.size == params.f)
      assert(params.dts.take(robustnessT).forall(dv => dv.size == params.f))
      assert(dvect.size == params.f)
      ListSpecs.listAppendValidProp(List(dvect), params.dts.take(robustnessT), (dv: List[Boolean]) => dv.size == params.f)
      assert((params.dts.take(robustnessT) ++ List(dvect)).forall(dv => dv.size == params.f))
    })
    val xt: List[Boolean] with xt.size == params.f = or(params.f, (params.dts.take(robustnessT) ++ List(dvect))).reverse.asInstanceOf[{r: List[Boolean] with r.size == params.f }] // X_t = OR of the last robustnessT D_t's plus the current one; dts may hold more (up to 15) for C_t's benefit, so take only what X_t needs.
    val yt: List[Boolean] = BasicEncodingFunctions.bitExtractionFunction(not(mt).reverse, xt)
    val vt: BigInt = compVt(params, robustnessT)
    assert(0 <= vt && vt < 16)
    ghostExpr(params.lemmaInvariant())
    assert(vt + 1 <= params.pts.size + 1 || params.pts.size == params.t)
    val et: List[Boolean] with et.isEmpty || et.size == 1 = if vt == 0 || isAllZero(xt) then List[Boolean]() else if isAllZero(yt) && vt > 0 && !isAllZero(xt) then List(false) else List(true)
    val kt: List[Boolean] with kt.isEmpty || kt == yt = if vt == 0 || isAllZero(xt) || isAllZero(yt) then List[Boolean]() else yt
    val ct: List[Boolean] with ct.isEmpty || ct.size == 1 = if kt.isEmpty then List[Boolean]() else if !kt.isEmpty && !params.ptSetMoreThanOnceInHistory(pt, vt + 1) then List(false) else List(true)
    val dt: Boolean = compDt(ft, rt)
    val ht: List[Boolean] = BasicEncodingFunctions.runLengthEncode(xt) ++ BasicEncodingFunctions.encodeNBits(vt, 4, BasicEncodingFunctions.pow2Fast(4)) ++ et ++ kt ++ ct ++ List(dt)

    // qt computation
    val qt: List[Boolean] = 
      if dt then 
        List[Boolean]() 
      else if ft then
        List(true) ++ BasicEncodingFunctions.runLengthEncode(xor(mt, leftShift(mt)).reverse)
      else 
        List(false) 

    // ut computation
    val ut: List[Boolean] = 
      if dt && ct == List(true) then
        BasicEncodingFunctions.bitExtractionFunction(it, or(xt.reverse, mt))
      else if dt && ct != List(true) then
        BasicEncodingFunctions.bitExtractionFunction(it, mt)
      else if rt then
        List(true) ++ BasicEncodingFunctions.countEncode(params.f) ++ it
      else if !rt && ft && ct == List(true) then
        List(false) ++ BasicEncodingFunctions.bitExtractionFunction(it, or(xt.reverse, mt))
      else
        List(false) ++ BasicEncodingFunctions.bitExtractionFunction(it, mt)

    // final output vector
    (ht ++ qt ++ ut, bt, mt, dvect)
  }
  // --------------------------------------------------------------- INTERMEDIATE CALCULATIONS SECTION 5.3.2 ---------------------------------------------------------------

  /**
    * For t >= 0, d_t is defined in bluebook section 5.3.2.1, page 5-3 as
    * if ft == false and rt == false, then d_t = 1, else d_t = 0.
    *
    * @param params
    */
  def compDt(ft: Boolean, rt: Boolean): Boolean = !ft && !rt

  /**
    * Bluebook section 5.3.2.2, eq. 14: V_t = R_t if t - R_t <= 0, R_t + C_t otherwise, where C_t is the
    * maximum integer <= min(t, 15) - R_t such that D_t' = 0 for t' in {t - R_t - 1, ..., t - R_t - C_t}.
    * dts holds D_{t-1}, D_{t-2}, ... (min(t, 15) of them), so dts.drop(R_t) starts at D_{t-R_t-1} and has
    * min(t, 15) - R_t elements: C_t is the length of its leading all-zero run.
    *
    * @param params
    * @param robustnessT R_t
    */
  def compVt(params: CompressionState, robustnessT: BigInt): BigInt = {
    require(0 <= robustnessT && robustnessT <= MAX_R)
    ghostExpr(params.lemmaInvariant())
    if params.t - robustnessT <= 0 then robustnessT else robustnessT + params.dts.drop(robustnessT).takeWhile(isAllZero).size
  }.ensuring(res => 0 <= res && res <= 15)
}

/**
  * Decompressor: inverts Encoder.step using only the output vectors (bluebook section 2.1).
  */
object Decoder {
  import PocketExecSpec.{DecompressionState, isAllZero}
  /**
    * Mirror of Encoder.step: consumes one output vector o_t of the given length, and returns
    * the reconstructed input vector I_t, the state for step t+1. If the vector o_t is not a well formed packet
    * for the given state, returns None and leaves the state unchanged.
    * IMPORTANT: /!\ This function assumes that the received o_t is the whole packet to decode.
    */
  def step(st: DecompressionState, ot: List[Boolean]): (Option[List[Boolean]], DecompressionState) = {
    decodeOutputVector(st, ot) match {
      case Some((it, mt)) => (Some(it), DecompressionState(st.t + 1, st.f, it, mt))
      case None() => (None(), st)
    }: (Option[List[Boolean]], DecompressionState)
  }.ensuring(res => res match {
    case (Some(it), nextSt) => nextSt.t == st.t + 1 && nextSt.f == st.f && nextSt.itMinusOne == it && it.size == st.f
    case (None(), nextSt) => nextSt == st
  })

  /**
    * Mirror of Encoder.outputVector: parses o_t = h_t || q_t || u_t (bluebook section 5.3.3) and
    * returns the reconstructed input vector I_t along with the mask M_t, or None if o_t is not a well
    * formed packet for the given state (truncated field, invalid encoding, wrong COUNT(F) or trailing bits).
    *
    * M_t is read from q_t when f_t = 1. Otherwise it is recovered from h_t (bluebook section 2.1):
    * outside X_t, D_t = 0 so M_t = M_{t-1}; at the positions of X_t, M_t = M_{t-1} XOR X_t if V_t = 0
    * (then X_t = D_t), M_t is all ones if e_t = 0 (Y_t = 0), and M_t = NOT k_t if e_t = 1 (k_t = Y_t).
    *
    * @param st
    * @param ot
    * @return (it, mt)
    */
  def decodeOutputVector(st: DecompressionState, ot: List[Boolean]): Option[(List[Boolean], List[Boolean])] = {
    // ht decoding
    val xtSize = BasicEncodingFunctions.runLengthEncodingSize(ot, st.f)
    if xtSize == -1 then None()
    else {
      val (xtEncoded, afterXt) = ot.splitAtIndex(xtSize)
      val xt: List[Boolean] = BasicEncodingFunctions.runLengthDecode(xtEncoded, st.f)
      if afterXt.size < 4 then None()
      else {
        val (vtBits, afterVt) = afterXt.splitAtIndex(4)
        val vt: BigInt = BasicEncodingFunctions.decodeNBits(vtBits)
        val etSize: BigInt = if vt == 0 || isAllZero(xt) then 0 else 1
        if afterVt.size < etSize then None()
        else {
          val (et, afterEt) = afterVt.splitAtIndex(etSize)
          val ktSize: BigInt = if et == List(true) then hammingWeight(xt) else 0
          if afterEt.size < ktSize then None()
          else {
            val (kt, afterKt) = afterEt.splitAtIndex(ktSize)
            val ctSize: BigInt = if kt.isEmpty then 0 else 1
            if afterKt.size < ctSize + 1 then None()
            else {
              val (ct, afterCt) = afterKt.splitAtIndex(ctSize)
              val dt: Boolean = afterCt.head
              val afterHt = afterCt.tail

              // mask recovered from ht, used when ft = 0
              val mtFromHt: List[Boolean] =
                if vt == 0 || isAllZero(xt) then
                  xor(st.mtMinusOne, xt.reverse)
                else {
                  val yt: List[Boolean] = if et == List(true) then kt else List.fill(hammingWeight(xt))(false)
                  // Y_t = BE(NOT(M_t).reverse, X_t) (see Encoder.outputVector), so insert it back into NOT(M_{t-1}).reverse
                  not(BasicEncodingFunctions.bitInsertionFunction(not(st.mtMinusOne).reverse, xt, yt)).reverse
                }

              // qt decoding: (ft, mt, rest of the packet)
              val qtDecoded: Option[(Boolean, List[Boolean], List[Boolean])] =
                if dt then
                  Some((false, mtFromHt, afterHt))
                else if afterHt.isEmpty then
                  None()
                else if afterHt.head == false then
                  Some((false, mtFromHt, afterHt.tail))
                else {
                  val mtDiffSize = BasicEncodingFunctions.runLengthEncodingSize(afterHt.tail, st.f)
                  if mtDiffSize == -1 then None()
                  else {
                    val (mtDiffEncoded, afterQt) = afterHt.tail.splitAtIndex(mtDiffSize)
                    Some((true, maskFromDiff(BasicEncodingFunctions.runLengthDecode(mtDiffEncoded, st.f).reverse), afterQt))
                  }
                }

              // ut decoding
              qtDecoded match {
                case None() => None()
                case Some((ft, mt, afterQt)) =>
                  if !dt && afterQt.isEmpty then None()
                  else {
                    val rt: Boolean = !dt && afterQt.head
                    val ut: List[Boolean] = if dt then afterQt else afterQt.tail
                    if rt then {
                      if ut.isEmpty then None()
                      else {
                        val (count, it) = BasicEncodingFunctions.countDecodePrefix(ut)
                        if count == st.f && it.size == st.f then Some((it, mt)) else None()
                      }
                    } else {
                      val selector: List[Boolean] = if (dt || ft) && ct == List(true) then or(xt.reverse, mt) else mt
                      if ut.size == hammingWeight(selector) then
                        Some((BasicEncodingFunctions.bitInsertionFunction(st.itMinusOne, selector, ut), mt))
                      else
                        None()
                    }
                  }
              }
            }
          }
        }
      }
    }
  }
}


object EncodeDecodeRoundTripProof {
  import PocketExecSpec.{CompressionState, DecompressionState, decoderView, isAllZero, MAX_R}

  // --------------------------------------------------------------- BIT VECTOR LEMMAS ---------------------------------------------------------------

  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectIsZeroXor(a: List[Boolean], b: List[Boolean]): Unit = {
    require(a.size == b.size)
    decreases(a)
    a match {
      case Nil() => ()
      case Cons(h, t) => lemmaEqualWhereSelectIsZeroXor(t, b.tail)
    }
  }.ensuring(_ => equalWhereSelectIsZero(a, b, xor(a, b)))

  /** Widening the selector preserves equalWhereSelectIsZero. */
  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectIsZeroOr(a: List[Boolean], b: List[Boolean], x: List[Boolean], selector: List[Boolean]): Unit = {
    require(a.size == selector.size && b.size == selector.size && x.size == selector.size)
    require(equalWhereSelectIsZero(a, b, selector))
    decreases(selector)
    selector match {
      case Nil() => ()
      case Cons(sh, stl) => lemmaEqualWhereSelectIsZeroOr(a.tail, b.tail, x.tail, stl)
    }
  }.ensuring(_ => equalWhereSelectIsZero(a, b, or(x, selector)) && equalWhereSelectIsZero(a, b, or(selector, x)))

  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectIsZeroOrList(a: List[Boolean], b: List[Boolean], f: BigInt, ds: List[List[Boolean]], selector: List[Boolean]): Unit = {
    require(f >= 1 && a.size == f && b.size == f && selector.size == f)
    require((ds ++ List(selector)).forall(l => l.size == f))
    require(equalWhereSelectIsZero(a, b, selector))
    decreases(ds)
    ds match {
      case Nil() => ()
      case Cons(h, tl) =>
        lemmaEqualWhereSelectIsZeroOrList(a, b, f, tl, selector)
        lemmaEqualWhereSelectIsZeroOr(a, b, h, or(f, tl ++ List(selector)))
    }
  }.ensuring(_ => equalWhereSelectIsZero(a, b, or(f, ds ++ List(selector))))

  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectIsZeroNot(a: List[Boolean], b: List[Boolean], selector: List[Boolean]): Unit = {
    require(a.size == selector.size && b.size == selector.size)
    require(equalWhereSelectIsZero(a, b, selector))
    decreases(selector)
    selector match {
      case Nil() => ()
      case Cons(sh, stl) => lemmaEqualWhereSelectIsZeroNot(a.tail, b.tail, stl)
    }
  }.ensuring(_ => equalWhereSelectIsZero(not(a), not(b), selector))

  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectIsZeroAppend(a: List[Boolean], b: List[Boolean], selector: List[Boolean], aLast: Boolean, bLast: Boolean, selectorLast: Boolean): Unit = {
    require(a.size == selector.size && b.size == selector.size)
    require(equalWhereSelectIsZero(a, b, selector) && (selectorLast || aLast == bLast))
    decreases(selector)
    selector match {
      case Nil() => ()
      case Cons(sh, stl) => lemmaEqualWhereSelectIsZeroAppend(a.tail, b.tail, stl, aLast, bLast, selectorLast)
    }
  }.ensuring(_ => equalWhereSelectIsZero(a :+ aLast, b :+ bLast, selector :+ selectorLast))

  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectIsZeroReverse(a: List[Boolean], b: List[Boolean], selector: List[Boolean]): Unit = {
    require(a.size == selector.size && b.size == selector.size)
    require(equalWhereSelectIsZero(a, b, selector))
    decreases(selector)
    selector match {
      case Nil() => ()
      case Cons(sh, stl) =>
        lemmaEqualWhereSelectIsZeroReverse(a.tail, b.tail, stl)
        lemmaEqualWhereSelectIsZeroAppend(a.tail.reverse, b.tail.reverse, stl.reverse, a.head, b.head, sh)
    }
  }.ensuring(_ => equalWhereSelectIsZero(a.reverse, b.reverse, selector.reverse))

  @ghost @inlineOnce @opaque
  def lemmaEqualWhereSelectAllZeroThenEqual(a: List[Boolean], b: List[Boolean], selector: List[Boolean]): Unit = {
    require(a.size == selector.size && b.size == selector.size)
    require(equalWhereSelectIsZero(a, b, selector) && isAllZero(selector))
    decreases(selector)
    selector match {
      case Nil() => ()
      case Cons(sh, stl) => lemmaEqualWhereSelectAllZeroThenEqual(a.tail, b.tail, stl)
    }
  }.ensuring(_ => a == b)

  @ghost @inlineOnce @opaque
  def lemmaIsAllZeroAppend(l: List[Boolean]): Unit = {
    require(isAllZero(l))
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) => lemmaIsAllZeroAppend(t)
    }
  }.ensuring(_ => isAllZero(l :+ false))

  @ghost @inlineOnce @opaque
  def lemmaIsAllZeroReverse(l: List[Boolean]): Unit = {
    require(isAllZero(l))
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) =>
        lemmaIsAllZeroReverse(t)
        lemmaIsAllZeroAppend(t.reverse)
    }
  }.ensuring(_ => isAllZero(l.reverse))

  @ghost @inlineOnce @opaque
  def lemmaListFillFalseIsAllZero(l: List[Boolean]): Unit = {
    require(isAllZero(l))
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) => lemmaListFillFalseIsAllZero(t)
    }
  }.ensuring(_ => l == List.fill(l.size)(false))

  @ghost @inlineOnce @opaque
  def lemmaXorPreservesAllZero(a: List[Boolean], b: List[Boolean]): Unit = {
    require(a.size == b.size && isAllZero(b))
    decreases(a)
    a match {
      case Nil() => ()
      case Cons(h, t) => lemmaXorPreservesAllZero(t, b.tail)
    }
  }.ensuring(_ => xor(a, b) == a)

  @ghost @inlineOnce @opaque
  def lemmaXorCancel(a: List[Boolean], c: List[Boolean]): Unit = {
    require(a.size == c.size)
    decreases(a)
    a match {
      case Nil() => ()
      case Cons(h, t) => lemmaXorCancel(t, c.tail)
    }
  }.ensuring(_ => xor(c, xor(a, c)) == a)

  @ghost @inlineOnce @opaque
  def lemmaNotNot(l: List[Boolean]): Unit = {
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) => lemmaNotNot(t)
    }
  }.ensuring(_ => not(not(l)) == l)

  @ghost @inlineOnce @opaque
  def lemmaNotSnoc(l: List[Boolean], x: Boolean): Unit = {
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) => lemmaNotSnoc(t, x)
    }
  }.ensuring(_ => not(l :+ x) == (not(l) :+ !x))

  @ghost @inlineOnce @opaque
  def lemmaNotReverse(l: List[Boolean]): Unit = {
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) =>
        lemmaNotReverse(t)
        lemmaNotSnoc(t.reverse, h)
    }
  }.ensuring(_ => not(l.reverse) == not(l).reverse)

  /** not(not(m).reverse).reverse == m: the shape the decoder undoes when it recovers M_t from Y_t. */
  @ghost @inlineOnce @opaque
  def lemmaNotReverseNotReverse(m: List[Boolean]): Unit = {
    lemmaNotReverse(not(m))
    lemmaNotNot(m)
    assert(ListSpecs.reverseReverse(m))
  }.ensuring(_ => not(not(m).reverse).reverse == m)

  /** maskFromDiff inverts `xor(m, leftShift(m))`. */
  @ghost @inlineOnce @opaque
  def lemmaMaskFromDiff(m: List[Boolean]): Unit = {
    decreases(m)
    m match {
      case Nil() => ()
      case Cons(h, Nil()) => ()
      case Cons(h, tl) =>
        lemmaMaskFromDiff(tl)
        assert(leftShift(m) == tl.head :: leftShift(tl))
        assert(xor(m, leftShift(m)) == xor(h, tl.head) :: xor(tl, leftShift(tl)))
    }
  }.ensuring(_ => maskFromDiff(xor(m, leftShift(m))) == m)

  // --------------------------------------------------------------- DECODER UNROLLING ---------------------------------------------------------------

  /**
    * One step of Decoder.decodeOutputVector over abstract lists: each require is the value of one of its vals.
    * The packet's concatenations are kept out of this lemma on purpose.
    */
  @ghost @inlineOnce @opaque
  def lemmaDecodeChain(st: DecompressionState, ot: List[Boolean], it: List[Boolean], mt: List[Boolean], mtFromHt: List[Boolean],
    xtSize: BigInt, xtEncoded: List[Boolean], afterXt: List[Boolean], xt: List[Boolean],
    vtBits: List[Boolean], afterVt: List[Boolean], vt: BigInt,
    et: List[Boolean], afterEt: List[Boolean], kt: List[Boolean], afterKt: List[Boolean], ct: List[Boolean], afterCt: List[Boolean],
    dt: Boolean, afterHt: List[Boolean], ft: Boolean, diffSize: BigInt, diffEncoded: List[Boolean], afterQt: List[Boolean], ut: List[Boolean], selector: List[Boolean]): Unit = {
    require(xtSize == BasicEncodingFunctions.runLengthEncodingSize(ot, st.f) && xtSize != -1)
    require(ot.splitAtIndex(xtSize) == (xtEncoded, afterXt))
    require(xt == BasicEncodingFunctions.runLengthDecode(xtEncoded, st.f))
    require(afterXt.size >= 4 && afterXt.splitAtIndex(4) == (vtBits, afterVt) && vtBits.size == 4)
    require(vt == BasicEncodingFunctions.decodeNBits(vtBits))
    require({
      val etSize: BigInt = if vt == 0 || isAllZero(xt) then 0 else 1
      afterVt.size >= etSize && afterVt.splitAtIndex(etSize) == (et, afterEt)
    })
    require({
      val ktSize: BigInt = if et == List(true) then hammingWeight(xt) else 0
      afterEt.size >= ktSize && afterEt.splitAtIndex(ktSize) == (kt, afterKt)
    })
    require({
      val ctSize: BigInt = if kt.isEmpty then 0 else 1
      afterKt.size >= ctSize + 1 && afterKt.splitAtIndex(ctSize) == (ct, afterCt)
    })
    require(afterCt.size >= 1 && dt == afterCt.head && afterHt == afterCt.tail)
    require(mtFromHt == (
      if vt == 0 || isAllZero(xt) then
        xor(st.mtMinusOne, xt.reverse)
      else {
        val yt: List[Boolean] = if et == List(true) then kt else List.fill(hammingWeight(xt))(false)
        not(BasicEncodingFunctions.bitInsertionFunction(not(st.mtMinusOne).reverse, xt, yt)).reverse
      }
    ))
    require(
      if dt then !ft && mt == mtFromHt && afterQt == afterHt
      else !afterHt.isEmpty && (
        if afterHt.head == false then !ft && mt == mtFromHt && afterQt == afterHt.tail
        else ft
          && diffSize == BasicEncodingFunctions.runLengthEncodingSize(afterHt.tail, st.f) && diffSize != -1
          && afterHt.tail.splitAtIndex(diffSize) == (diffEncoded, afterQt)
          && mt == maskFromDiff(BasicEncodingFunctions.runLengthDecode(diffEncoded, st.f).reverse)
      )
    )
    require(dt || !afterQt.isEmpty)
    require(ut == (if dt then afterQt else afterQt.tail))
    require(
      if !dt && afterQt.head then !ut.isEmpty && BasicEncodingFunctions.countDecodePrefix(ut) == (st.f, it) && it.size == st.f
      else selector == (if (dt || ft) && ct == List(true) then or(xt.reverse, mt) else mt)
        && ut.size == hammingWeight(selector)
        && BasicEncodingFunctions.bitInsertionFunction(st.itMinusOne, selector, ut) == it
    )
    if dt then ()
    else if afterHt.head == false then {
      if afterQt.head then () else ()
    } else {
      if afterQt.head then () else ()
    }
  }.ensuring(_ => Decoder.decodeOutputVector(st, ot) == Some((it, mt)))

  /** q_t, as lemmaDecodeChain consumes it: `afterHt` is q_t ++ u_t. */
  @ghost @inlineOnce @opaque
  def lemmaDecodeQt(st: DecompressionState, mt: List[Boolean], mtFromHt: List[Boolean], dt: Boolean, ft: Boolean, qt: List[Boolean], ut: List[Boolean]): Unit = {
    require(mt.size == st.f)
    require(dt ==> !ft)
    require(ft || mtFromHt == mt)
    require(qt == (if dt then List[Boolean]() else if ft then List(true) ++ BasicEncodingFunctions.runLengthEncode(xor(mt, leftShift(mt)).reverse) else List(false)))
    if !dt && ft then {
      val diff = xor(mt, leftShift(mt))
      val diffEncoded = BasicEncodingFunctions.runLengthEncode(diff.reverse)
      assert((qt ++ ut).tail == diffEncoded ++ ut)
      BasicEncodingFunctions.lemmaRunLengthEncodingSizeCorrectWithSuffix(diff.reverse, ut)
      ListUtils.lemmaSplitAtIndexConcatSize(diffEncoded, ut, diffEncoded.size)
      BasicEncodingFunctions.lemmarunLengthEncodeRoundTrip(diff.reverse)
      assert(ListSpecs.reverseReverse(diff))
      lemmaMaskFromDiff(mt)
    }
  }.ensuring(_ => {
    val afterHt = qt ++ ut
    val diffEncoded = BasicEncodingFunctions.runLengthEncode(xor(mt, leftShift(mt)).reverse)
    if dt then mt == mtFromHt
    else !afterHt.isEmpty && (
      if afterHt.head == false then !ft && mt == mtFromHt && ut == afterHt.tail
      else ft
        && diffEncoded.size == BasicEncodingFunctions.runLengthEncodingSize(afterHt.tail, st.f) && diffEncoded.size != -1
        && afterHt.tail.splitAtIndex(diffEncoded.size) == (diffEncoded, ut)
        && mt == maskFromDiff(BasicEncodingFunctions.runLengthDecode(diffEncoded, st.f).reverse)
    )
  })

  /**
    * Decoder side of the round trip: discharges lemmaDecodeChain on a packet made of fields having the shape
    * produced by Encoder.outputVector (written right-associated, i.e., as the decoder peels them off). Each field
    * is consumed in turn, using the invertibility of the sub functions (RLE, BIT_4, COUNT, BE).
    *
    * What links the fields to the compressor's state is abstracted in the last two requires:
    * M_t is recoverable from h_t when f_t = 0, and I_t and I_{t-1} coincide outside of the selector when r_t = 0.
    */
  @ghost @inlineOnce @opaque
  def lemmaDecodeFields(st: DecompressionState, it: List[Boolean], mt: List[Boolean], xt: List[Boolean], yt: List[Boolean], vt: BigInt, et: List[Boolean], kt: List[Boolean], ct: List[Boolean], dt: Boolean, ft: Boolean, rt: Boolean, qt: List[Boolean], ut: List[Boolean], selector: List[Boolean]): Unit = {
    require(it.size == st.f && mt.size == st.f && xt.size == st.f && selector.size == st.f)
    require(0 <= vt && vt < 16)
    require(yt.size == hammingWeight(xt))
    require(et == (if vt == 0 || isAllZero(xt) then List[Boolean]() else if isAllZero(yt) then List(false) else List(true)))
    require(kt == (if vt == 0 || isAllZero(xt) || isAllZero(yt) then List[Boolean]() else yt))
    require(kt.isEmpty == ct.isEmpty && (ct.isEmpty || ct.size == 1))
    require(dt == (!ft && !rt))
    require(qt == (if dt then List[Boolean]() else if ft then List(true) ++ BasicEncodingFunctions.runLengthEncode(xor(mt, leftShift(mt)).reverse) else List(false)))
    require(selector == (if (dt || ft) && ct == List(true) then or(xt.reverse, mt) else mt))
    require(
      if rt then ut == List(true) ++ BasicEncodingFunctions.countEncode(st.f) ++ it
      else if dt then ut == BasicEncodingFunctions.bitExtractionFunction(it, selector)
      else ut == List(false) ++ BasicEncodingFunctions.bitExtractionFunction(it, selector)
    )
    require(ft || (
      if vt == 0 || isAllZero(xt) then xor(st.mtMinusOne, xt.reverse) == mt
      else not(BasicEncodingFunctions.bitInsertionFunction(not(st.mtMinusOne).reverse, xt, yt)).reverse == mt
    ))
    require(rt || equalWhereSelectIsZero(it, st.itMinusOne, selector))

    val vtBits = BasicEncodingFunctions.encodeNBits(vt, 4, BasicEncodingFunctions.pow2Fast(4))
    val rle = BasicEncodingFunctions.runLengthEncode(xt)
    val afterHt = qt ++ ut
    val afterCt = List(dt) ++ afterHt
    val afterKt = ct ++ afterCt
    val afterEt = kt ++ afterKt
    val afterVt = et ++ afterEt
    val afterXt = vtBits ++ afterVt
    val ot = rle ++ afterXt

    // h_t
    BasicEncodingFunctions.lemmaRunLengthEncodingSizeCorrectWithSuffix(xt, afterXt)
    ListUtils.lemmaSplitAtIndexConcatSize(rle, afterXt, rle.size)
    BasicEncodingFunctions.lemmarunLengthEncodeRoundTrip(xt)

    assert(vtBits.size == 4)
    ListUtils.lemmaSplitAtIndexConcatSize(vtBits, afterVt, 4)
    BasicEncodingFunctions.lemmaEncodeNBitsDecodeNBitsRoundTrip(vt, 4, BasicEncodingFunctions.pow2Fast(4), vtBits)
    assert(BasicEncodingFunctions.decodeNBits(vtBits) == vt)

    val etSize: BigInt = if vt == 0 || isAllZero(xt) then 0 else 1
    assert(et.size == etSize)
    ListUtils.lemmaSplitAtIndexConcatSize(et, afterEt, etSize)

    val ktSize: BigInt = if et == List(true) then hammingWeight(xt) else 0
    assert(kt.size == ktSize)
    ListUtils.lemmaSplitAtIndexConcatSize(kt, afterKt, ktSize)

    val ctSize: BigInt = if kt.isEmpty then 0 else 1
    assert(ct.size == ctSize)
    ListUtils.lemmaSplitAtIndexConcatSize(ct, afterCt, ctSize)
    assert(afterCt.head == dt && afterCt.tail == afterHt)

    // mask recovered from h_t: the decoder's Y_t is the encoder's one
    val ytDecoded: List[Boolean] = if et == List(true) then kt else List.fill(hammingWeight(xt))(false)
    if vt != 0 && !isAllZero(xt) && isAllZero(yt) then
      lemmaListFillFalseIsAllZero(yt)
    assert(vt == 0 || isAllZero(xt) || ytDecoded == yt)
    val mtFromHt: List[Boolean] =
      if vt == 0 || isAllZero(xt) then
        xor(st.mtMinusOne, xt.reverse)
      else
        not(BasicEncodingFunctions.bitInsertionFunction(not(st.mtMinusOne).reverse, xt, ytDecoded)).reverse
    assert(ft || mtFromHt == mt)

    // q_t
    val diffEncoded = BasicEncodingFunctions.runLengthEncode(xor(mt, leftShift(mt)).reverse)
    val afterQt: List[Boolean] = ut
    lemmaDecodeQt(st, mt, mtFromHt, dt, ft, qt, ut)

    // u_t
    val utBody: List[Boolean] = if dt then ut else ut.tail
    if rt then {
      assert(utBody == BasicEncodingFunctions.countEncode(st.f) ++ it)
      BasicEncodingFunctions.lemmaCountEncodeDecodeRoundTripWithSuffix(st.f, it)
    } else {
      BasicEncodingFunctions.lemmaBitExtractionSize(it, selector)
      BasicEncodingFunctions.lemmaBitInsertionExtraction(it, st.itMinusOne, selector)
    }

    lemmaDecodeChain(st, ot, it, mt, mtFromHt,
      rle.size, rle, afterXt, xt,
      vtBits, afterVt, vt,
      et, afterEt, kt, afterKt, ct, afterCt,
      dt, afterHt, ft, diffEncoded.size, diffEncoded, afterQt, utBody, selector)
  }.ensuring(_ => {
    val rle = BasicEncodingFunctions.runLengthEncode(xt)
    val vtBits = BasicEncodingFunctions.encodeNBits(vt, 4, BasicEncodingFunctions.pow2Fast(4))
    Decoder.decodeOutputVector(st, rle ++ (vtBits ++ (et ++ (kt ++ (ct ++ (List(dt) ++ (qt ++ ut))))))) == Some((it, mt))
  })

  // --------------------------------------------------------------- ENCODER UNROLLING ---------------------------------------------------------------

  /**
    * M_t can be recovered from h_t and M_{t-1}: D_t is one of the vectors OR-ed into X_t, so M_t and M_{t-1} coincide outside of X_t.
    * The first part of the result is for the case where the decoder does not read k_t: X_t = D_t (no older D's, V_t = 0) or X_t = 0.
    *
    * @param mt M_t
    * @param mtMinusOne M_{t-1}
    * @param f
    * @param ds the older D's that are OR-ed into X_t
    * @param xt X_t
    */
  @ghost @inlineOnce @opaque
  def lemmaMaskFromHt(mt: List[Boolean], mtMinusOne: List[Boolean], f: BigInt, ds: List[List[Boolean]], xt: List[Boolean]): Unit = {
    require(f >= 1 && mt.size == f && mtMinusOne.size == f)
    require((ds ++ List(xor(mt, mtMinusOne))).forall(l => l.size == f))
    require(xt == or(f, ds ++ List(xor(mt, mtMinusOne))).reverse)
    val dvect = xor(mt, mtMinusOne)
    val orX = or(f, ds ++ List(dvect))
    assert(ListSpecs.reverseReverse(orX))
    lemmaEqualWhereSelectIsZeroXor(mt, mtMinusOne)
    lemmaEqualWhereSelectIsZeroOrList(mt, mtMinusOne, f, ds, dvect)
    lemmaEqualWhereSelectIsZeroNot(mt, mtMinusOne, orX)
    lemmaEqualWhereSelectIsZeroReverse(not(mt), not(mtMinusOne), orX)
    BasicEncodingFunctions.lemmaBitExtractionSize(not(mt).reverse, xt)
    BasicEncodingFunctions.lemmaBitInsertionExtraction(not(mt).reverse, not(mtMinusOne).reverse, xt)
    lemmaNotReverseNotReverse(mt)
    if ds.isEmpty then {
      assert(orX == dvect)
      lemmaXorCancel(mt, mtMinusOne)
    }
    if isAllZero(xt) then {
      lemmaEqualWhereSelectIsZeroReverse(mt, mtMinusOne, orX)
      lemmaEqualWhereSelectAllZeroThenEqual(mt.reverse, mtMinusOne.reverse, xt)
      assert(ListSpecs.reverseReverse(mt))
      assert(ListSpecs.reverseReverse(mtMinusOne))
      lemmaIsAllZeroReverse(xt)
      lemmaXorPreservesAllZero(mtMinusOne, xt.reverse)
    }
  }.ensuring(_ => {
    BasicEncodingFunctions.lemmaBitExtractionSize(not(mt).reverse, xt)
    ((ds.isEmpty || isAllZero(xt)) ==> (xor(mtMinusOne, xt.reverse) == mt)) &&
    not(BasicEncodingFunctions.bitInsertionFunction(not(mtMinusOne).reverse, xt, BasicEncodingFunctions.bitExtractionFunction(not(mt).reverse, xt))).reverse == mt
  })

  /** For t > 0, the mask covers every bit that changed since I_{t-1} (bluebook section 4.2), so the bits outside of it can be copied from I_{t-1}. */
  @ghost @inlineOnce @opaque
  def lemmaInputChangesInsideMask(params: CompressionState, it: List[Boolean], pt: Boolean): Unit = {
    require(params.f == it.size && params.t > 0)
    params.lemmaInvariant()
    val mt = Encoder.updateMaskAndBuild(params, it, pt)._2
    lemmaEqualWhereSelectIsZeroXor(it, params.itMinusOne)
    lemmaEqualWhereSelectIsZeroOr(it, params.itMinusOne, params.btMinusOne, xor(it, params.itMinusOne))
    lemmaEqualWhereSelectIsZeroOr(it, params.itMinusOne, params.mtMinusOne, xor(it, params.itMinusOne))
  }.ensuring(_ => equalWhereSelectIsZero(it, params.itMinusOne, Encoder.updateMaskAndBuild(params, it, pt)._2))

  /**
    * The decoder inverts Encoder.outputVector: unrolls the encoder down to the fields of the packet, and hands them to lemmaDecodeFields.
    */
  @ghost @inlineOnce @opaque
  def lemmaDecodeOutputVector(params: CompressionState, it: List[Boolean], pt: Boolean, ft: Boolean, rt: Boolean, robustnessT: BigInt): Unit = {
    require(params.f == it.size)
    require(0 <= robustnessT && robustnessT <= MAX_R)
    require(params.t > robustnessT || (ft && rt))
    params.lemmaInvariant()
    val st = decoderView(params)
    val f = params.f

    val (bt, mt) = Encoder.updateMaskAndBuild(params, it, pt)
    val dvect = Encoder.updateChangeVector(params, mt)
    val ds = params.dts.take(robustnessT)
    ListSpecs.contentSubsetPreservesForall(params.dts, ds, (dv: List[Boolean]) => dv.size == f)
    ListSpecs.listAppendValidProp(List(dvect), ds, (dv: List[Boolean]) => dv.size == f)
    assert((ds ++ List(dvect)).forall(dv => dv.size == f))
    val xt: List[Boolean] = or(f, ds ++ List(dvect)).reverse
    val yt: List[Boolean] = BasicEncodingFunctions.bitExtractionFunction(not(mt).reverse, xt)
    val vt: BigInt = Encoder.compVt(params, robustnessT)
    assert(vt + 1 <= params.pts.size + 1 || params.pts.size == params.t)
    val et: List[Boolean] = if vt == 0 || isAllZero(xt) then List[Boolean]() else if isAllZero(yt) && vt > 0 && !isAllZero(xt) then List(false) else List(true)
    val kt: List[Boolean] = if vt == 0 || isAllZero(xt) || isAllZero(yt) then List[Boolean]() else yt
    val ct: List[Boolean] = if kt.isEmpty then List[Boolean]() else if !kt.isEmpty && !params.ptSetMoreThanOnceInHistory(pt, vt + 1) then List(false) else List(true)
    val dt: Boolean = Encoder.compDt(ft, rt)
    val rle = BasicEncodingFunctions.runLengthEncode(xt)
    val vtBits = BasicEncodingFunctions.encodeNBits(vt, 4, BasicEncodingFunctions.pow2Fast(4))
    val ht: List[Boolean] = rle ++ vtBits ++ et ++ kt ++ ct ++ List(dt)
    val qt: List[Boolean] =
      if dt then
        List[Boolean]()
      else if ft then
        List(true) ++ BasicEncodingFunctions.runLengthEncode(xor(mt, leftShift(mt)).reverse)
      else
        List(false)
    val ut: List[Boolean] =
      if dt && ct == List(true) then
        BasicEncodingFunctions.bitExtractionFunction(it, or(xt.reverse, mt))
      else if dt && ct != List(true) then
        BasicEncodingFunctions.bitExtractionFunction(it, mt)
      else if rt then
        List(true) ++ BasicEncodingFunctions.countEncode(f) ++ it
      else if !rt && ft && ct == List(true) then
        List(false) ++ BasicEncodingFunctions.bitExtractionFunction(it, or(xt.reverse, mt))
      else
        List(false) ++ BasicEncodingFunctions.bitExtractionFunction(it, mt)
    assert(Encoder.outputVector(params, it, pt, ft, rt, robustnessT) == (ht ++ qt ++ ut, bt, mt, dvect))

    val selector: List[Boolean] = if (dt || ft) && ct == List(true) then or(xt.reverse, mt) else mt
    BasicEncodingFunctions.lemmaBitExtractionSize(not(mt).reverse, xt)

    // M_t recovered from h_t: f_t = 0 implies t > R_t >= 0, so D_t = M_t XOR M_{t-1}
    if !ft then {
      assert(params.t > 0)
      assert(dvect == xor(mt, params.mtMinusOne))
      lemmaMaskFromHt(mt, params.mtMinusOne, f, ds, xt)
      if vt == 0 then {
        assert(robustnessT == 0)
        assert(ds.isEmpty)
      }
    }
    // I_t recovered from u_t: r_t = 0 implies t > 0 too
    if !rt then {
      lemmaInputChangesInsideMask(params, it, pt)
      lemmaEqualWhereSelectIsZeroOr(it, params.itMinusOne, xt.reverse, mt)
    }

    lemmaDecodeFields(st, it, mt, xt, yt, vt, et, kt, ct, dt, ft, rt, qt, ut, selector)
    ListUtils.lemmaPacketAssoc(rle, vtBits, et, kt, ct, List(dt), qt, ut)
  }.ensuring(_ => {
    val res = Encoder.outputVector(params, it, pt, ft, rt, robustnessT)
    Decoder.decodeOutputVector(decoderView(params), res._1) == Some((it, res._3))
  })

  /**
    * This theorem states that for any valid compression state and input vector, if we encode the input vector using the encoder and then decode the resulting output vector using the decoder, we will recover the original input vector. This is a fundamental property of the encoding and decoding process, ensuring that no information is lost during compression and decompression.
    * The state the decoder uses is the decoder view of the compressor's state, which corresponds to a synchronized state between the encoder and decoder.
    * 
    * Synchronization of the states is shown in another theorem.
    *
    * @return
    */
  @ghost @inlineOnce @opaque
  def mainRoundTripTheorem(compressionParams: CompressionState, it: List[Boolean], pt: Boolean, ft: Boolean, rt: Boolean, robustnessT: BigInt): Unit = {
    require(compressionParams.f == it.size)
    require(0 <= robustnessT && robustnessT <= MAX_R) 
    require(compressionParams.t > robustnessT || (ft && rt))
    val (ot, nextCs) = Encoder.step(compressionParams, it, pt, ft, rt, robustnessT)
    val decoderState = PocketExecSpec.decoderView(compressionParams)
    val (decodedItOpt, nextDecoderState) = Decoder.step(decoderState, ot)

    lemmaDecodeOutputVector(compressionParams, it, pt, ft, rt, robustnessT)
    assert(Decoder.decodeOutputVector(decoderState, ot).isDefined)
    val (encodedIt, encodedBt, encodedMt, encodedDt) = Encoder.outputVector(compressionParams, it, pt, ft, rt, robustnessT)
    assert(Decoder.decodeOutputVector(decoderState, ot).get._1 == it)
    assert(Decoder.decodeOutputVector(decoderState, ot).get._2 == encodedMt)
    assert(Decoder.decodeOutputVector(decoderState, ot).get._1.size == compressionParams.f)




    assert(decodedItOpt.isDefined)
    assert(decodedItOpt.get == it)
    assert(nextDecoderState.t == nextCs.t && nextDecoderState.f == nextCs.f && nextDecoderState.itMinusOne == it && nextDecoderState.mtMinusOne == nextCs.mtMinusOne)
  }.ensuring(_ => {
    val decoded = Decoder.step(PocketExecSpec.decoderView(compressionParams), Encoder.step(compressionParams, it, pt, ft, rt, robustnessT)._1) 
    decoded._1.isDefined && decoded._1.get == it
  })
}



/**
  * Bluebook section 5.2
  */
object BasicEncodingFunctions {
  val MAX_F: BigInt = 65535 // 2^16 - 1
  val ONE = true
  val ZERO = false


  /**
    * BE(a, b): the bit extraction of a relative to b, defined in Blue book section 5.2.4, eq. (11):
    *   BE(a,b) = a_{g_(H(b)-1)} || ... || a_{g_0}
    * where g_i is the position of the i-th '1' bit of b counting from the MSB, and H(b) is the
    * number of '1' bits in b. The bit at the *last* matching position (closest to the LSB) comes
    * first in the result, and the bit at the *first* matching position (closest to the MSB) comes
    * last — the reverse of a plain left-to-right (MSB-first) scan.
    *
    * @param a
    * @param b
    */
  def bitExtractionFunction(a: List[Boolean], b: List[Boolean]): List[Boolean] = {
    require(a.size == b.size)
    bitExtractionMsbFirst(a, b).reverse
  }.ensuring(res => true)

  /** Bits of a at the indexes where b is one, scanning from the MSB, in scan order
    * (a_{g_0}, ..., a_{g_(H(b)-1)}) — the reverse of bitExtractionFunction / BE(a,b). */
  private def bitExtractionMsbFirst(a: List[Boolean], b: List[Boolean]): List[Boolean] = {
    require(a.size == b.size)
    b match {
      case Nil() => Nil()
      case Cons(bh, btl) if bh == ONE =>
        Cons(a.head, bitExtractionMsbFirst(a.tail, btl))
      case Cons(bh, btl) =>
        bitExtractionMsbFirst(a.tail, btl)
    }
  }

  /**
    * Inverse of bitExtractionFunction: writes `bits` at the indexes where b is one, and keeps the bits of `base`
    * elsewhere. `bits` is in BE(a,b) order, i.e., its first bit goes at the last matching position (closest to the LSB).
    *
    * @param base
    * @param b
    * @param bits
    */
  def bitInsertionFunction(base: List[Boolean], b: List[Boolean], bits: List[Boolean]): List[Boolean] = {
    require(base.size == b.size && bits.size == hammingWeight(b))
    bitInsertionMsbFirst(base, b, bits.reverse)
  }.ensuring(res => res.size == b.size)

  /** Inverse of bitExtractionMsbFirst: writes `bits` in scan order at the indexes where b is one, scanning from the MSB. */
  private def bitInsertionMsbFirst(base: List[Boolean], b: List[Boolean], bits: List[Boolean]): List[Boolean] = {
    require(base.size == b.size && bits.size == hammingWeight(b))
    b match {
      case Nil() => Nil()
      case Cons(bh, btl) if bh == ONE =>
        Cons(bits.head, bitInsertionMsbFirst(base.tail, btl, bits.tail))
      case Cons(bh, btl) =>
        Cons(base.head, bitInsertionMsbFirst(base.tail, btl, bits))
    }
  }.ensuring(res => res.size == b.size)

  /**
    * Encode the list of bits using run-length encoding defined in the blue book (Section 5.2.3, page 5-2).
    * 
    * The facts that the COUNT encoding (Blue book Section 5.2.2, page 5-2) is used to encode the number of zeros
    * in the run-length encoding, that the COUNT encoding is defined only for 1 <= n <= MAX_F,
    * imply that the input list of bits must be of size at most MAX_F, otherwise the encoding could fail for 000...01.
    *
    * @param a
    */
  def runLengthEncode(a: List[Boolean]): List[Boolean] = {
    require(a.size <= MAX_F)
    decreases(a.size)
    val (leadingZeros, rest) = splitLeadingZeros(a)
    rest match {
      case Nil() => List(ONE, ZERO)
      case Cons(b, tl) => 
        val nLeadingZeros = leadingZeros.size
        val countEncoding = countEncode(nLeadingZeros + 1)
        assert(b == ONE)
        val rest = runLengthEncode(tl)
        countEncoding ++ rest
    }
  }.ensuring(res => res.size >= 2)

  /**
    * Decode a list of bits encoded using run-length encoding defined in the blue book (Section 5.2.3, page 5-2).
    * 
    * The expectedSize parameter is used to infer the number of trailing zeros as indicated in the blue book (Section 5.2.3, page 5-2).
    *
    * @param l
    * @param expectedSize
    */
  def runLengthDecode(l: List[Boolean], expectedSize: BigInt): List[Boolean] = {
    require(expectedSize >= 0 && expectedSize <= MAX_F)
    decreases(l.size)
    l match {
      case Nil() => List.fill(expectedSize)(ZERO) // Default value for invalid encoding
      case Cons(true, Cons(false, Nil())) => 
        val nTrailingZeros = expectedSize
        List.fill(nTrailingZeros)(ZERO)
      case Cons(hd, tl) => 
        val (currentCount, rest) = countDecodePrefix(l)
        if currentCount == -1 then 
          List.fill(expectedSize)(ZERO) // Default value for invalid encoding
        else 
          assert(currentCount >= 1)
          val nLeadingZeros = currentCount - 1
          val leadingZeros = List.fill(nLeadingZeros)(ZERO)
          val newExpectedSize = expectedSize - leadingZeros.size - 1
          if newExpectedSize < 0 then 
            List.fill(expectedSize)(ZERO) // Default value for invalid encoding
          else 
            val restDecoded = runLengthDecode(rest, newExpectedSize)
            leadingZeros ++ (ONE :: restDecoded)
    }
  }.ensuring(res => res.size == expectedSize)

  /**
    * Given a list of bits whose prefix is a run-length encoding (Blue book Section 5.2.3, page 5-2) of a vector of
    * size expectedSize, return the length of that prefix, i.e., up to and including the '10' terminator.
    * Return -1 if the prefix is not a valid encoding: invalid or truncated COUNT, missing terminator, or the runs
    * exceed expectedSize. When the result is not -1, runLengthDecode on the prefix does not hit any of its invalid cases.
    *
    * @param l
    * @param expectedSize
    */
  def runLengthEncodingSize(l: List[Boolean], expectedSize: BigInt): BigInt = {
    require(expectedSize >= 0 && expectedSize <= MAX_F)
    decreases(l.size)
    l match {
      case Nil() => BigInt(-1)
      case Cons(true, Cons(false, _)) => BigInt(2)
      case Cons(hd, tl) =>
        val countSize = countDecodeSize(l)
        if countSize <= 0 || countSize > l.size then
          BigInt(-1) // Invalid or truncated COUNT
        else
          val (currentCount, rest) = countDecodePrefix(l)
          if currentCount <= 0 || currentCount > expectedSize then
            BigInt(-1) // Invalid COUNT, or runs exceeding expectedSize
          else
            val restSize = runLengthEncodingSize(rest, expectedSize - currentCount)
            if restSize == -1 then BigInt(-1) else countSize + restSize
    }
  }.ensuring(res => res == -1 || (2 <= res && res <= l.size))


  /**
    * Split the given list of bits into two lists: the leading zeros and the rest of the list starting with the first one.
    * If the input list is only zeros, the first list will contain all the zeros and the second list will be empty.
    *
    * @param a
    * @return
    */
  @inlineOnce
  def splitLeadingZeros(a: List[Boolean]): (List[Boolean], List[Boolean]) = {
    decreases(a)
    a match {
      case Nil() => (Nil(), Nil())
      case Cons(false, tl) => {
        val (leading, rest) = splitLeadingZeros(tl)
        assert(leading.forall(b => b == ZERO))
        (Cons(ZERO, leading), rest)
      }
      case Cons(true, tl) => (Nil(), a)
    }
  }.ensuring(res => 
    res._1 == List.fill(res._1.size)(ZERO) &&&
    res._1.forall(b => b == ZERO) &&& 
    (res._1 ++ res._2 == a) &&& 
    (res._2.isEmpty || res._2.head == ONE))

  /**
    * Count encoding of the integer A using the counter encoding defined in the blue book (Section 5.2.2, page 5-2).
    *
    * @param k
    */
  @inlineOnce
  def countEncode(A: BigInt): List[Boolean] = {
    require(1 <= A && A <= MAX_F)
    if (A == 1) {
      assert(List(ZERO).size == BigInt(1))
      List(ZERO)
    } else if (2 <= A && A <= 33) {
      List(ONE, ONE, ZERO) ++ encode5Bits(A - 2)
    } else {
      val e = countE(A - 2)
      List(ONE, ONE, ONE) ++ encodeNBits(A - 2, e, pow2Fast(e))
    }
  }.ensuring(res => {
    if A == 1 then res.size == BigInt(1)
    else if 2 <= A && A <= 33 then res.size == BigInt(3) + BigInt(5)
    else res.size == BigInt(3) + countE(A - 2)
  })

  def countDecode(l: List[Boolean]): BigInt = {
    require(l.size >= 1)
    l match {
      case Cons(false, Nil()) => BigInt(1)
      case Cons(true, Cons(true, Cons(false, tl))) if tl.size == 5 => decode5Bits(tl) + 2
      case Cons(true, Cons(true, Cons(true, tl))) if (tl.size >= 1 && tl.size <= 32) => decodeNBits(tl) + 2
      case _ => BigInt(-1) // Invalid encoding
    }
  }.ensuring(res => res >= -1)

  /**
    * Given a list of bits whose prefix is a count encoding of an integer, return the length of the count encoding to decode
    *
    * @param l
    */
  def countDecodeSize(l: List[Boolean]): BigInt = {
    require(l.size >= 1)
    l match {
      case Cons(false, tl) => BigInt(1)
      case Cons(true, Cons(true, Cons(false, tl))) => BigInt(3) + BigInt(5)
      case Cons(true, Cons(true, Cons(true, tl))) => 
        // Here the size of the count encoding is 3 + countE, i.e., 3 + the number of leading zeros after the '111' prefix.
        val nLeadingZeros = numberOfLeadingZeros(tl)
        if nLeadingZeros > 10 then 
          BigInt(-1) // Invalid encoding, too many leading zeros
        else 
          BigInt(3) + nLeadingZerosToBitWidth(nLeadingZeros)
      case _ => BigInt(-1) // Invalid encoding
    }
  }.ensuring(res => res >= -1)

  def countDecodePrefix(l: List[Boolean]): (BigInt, List[Boolean]) = {
    require(l.size >= 1)
    val size = countDecodeSize(l)
    if size <= 0 then (BigInt(-1), l) // Invalid encoding
    else {
      val (prefix, rest) = l.splitAtIndex(size)
      ghostExpr(assert(prefix.size > 0))
      (countDecode(prefix), rest)
    }
  }.ensuring(res => res._1 >= -1 && (if res._1 > 0 then res._2.size < l.size else true))

  /**
    * Encode the integer n using the counter encoding defined in the blue book (Section 5.2.2, page 5-2), when the integer is in the range 0 <= n <= 31. The encoding is a list of 5 bits (booleans) representing the binary representation of n.
    * 
    * PROVEN TO BE INVERTIBLE: decode5Bits(encode5Bits(n)) == n for all n in [0, 31].
    *
    * @param n
    */
  def encode5Bits(n: BigInt): List[Boolean] = {
    require(0 <= n && n <= 31)
    encodeNBits(n, 5, pow2Fast(5))
  }.ensuring(res => res.size == 5 && decode5Bits(res) == n)

  def decode5Bits(l: List[Boolean]): BigInt = {
    require(l.size == 5)
    boolToBigInt(l.head) * 16 + boolToBigInt(l.tail.head) * 8 + boolToBigInt(l.tail.tail.head) * 4 + boolToBigInt(l.tail.tail.tail.head) * 2 + boolToBigInt(l.tail.tail.tail.tail.head)
  }

  /**
    * Returns the length of the bit string used to encode the integer n using the counter encoding defined in the blue book (Section 5.2.2, page 5-2).
    *
    * @param n
    */
  def countE(n: BigInt): BigInt = {
    require(32 <= n && n <= MAX_F)
    2 * (floorLog2(n) + 1) - 6
  } 

  /**
    * "Invert" function of countE, returning the bit width (i.e. countE) corresponding to a given number of leading zeros. 
    * The function is defined for 0 <= nZeros <= 10, 
    * which is the maximum number of leading zeros that can be produced by countE for n in [32, MAX_F].
    *
    * @param nZeros
    */
  def nLeadingZerosToBitWidth(nZeros: BigInt): BigInt = {
    BigInt(6) + nZeros * 2

  }
  
  /**
    * Returns the floor(log2(n)) of the integer n, which is the largest integer k such that 2^k <= n. The function is defined for 32 <= n <= MAX_F.
    * It purposefully does not use recursion nor floats for verification reasons, and instead lists all the possible intervals, which is for all possible return values i.e., floor(log2(n)) = i for 5 <= i <= 16, and the corresponding intervals of n.
    * Cite VMCAI paper (Bucev, Chassot, ...) for the technique of unfolding everything for verification when it is reasonable to do so, like in these numerical functions.
    *
    * @param n
    */
  def floorLog2(n: BigInt): BigInt = {
    require(32 <= n && n <= MAX_F)
    if 32 <= n && n <= 63 then 5
    else if 64 <= n && n <= 127 then 6
    else if 128 <= n && n <= 255 then 7
    else if 256 <= n && n <= 511 then 8
    else if 512 <= n && n <= 1023 then 9
    else if 1024 <= n && n <= 2047 then 10
    else if 2048 <= n && n <= 4095 then 11
    else if 4096 <= n && n <= 8191 then 12
    else if 8192 <= n && n <= 16383 then 13
    else if 16384 <= n && n <= 32767 then 14
    else if 32768 <= n && n <= 65535 then 15
    else if MAX_F == n then 16
    else {
      assert(false)
      -1
    }
  }

  def minNBitsToEncode(n: BigInt): BigInt = {
    require(32 <= n && n <= MAX_F)
    floorLog2(n) + 1
  }

  /**
    * 2^k, defined by recursion since Stainless has no built-in exponentiation on BigInt.
    *
    * @param k
    */
  def pow2(k: BigInt): BigInt = {
    require(k >= 0)
    decreases(k)
    if (k == 0) BigInt(1) else 2 * pow2(k - 1)
  }.ensuring(res => res >= 1)

  def pow2Fast(k: BigInt): BigInt = {
    require(k >= 0)
    if k == 0 then BigInt(1)
    else if k == 1 then BigInt(2)
    else if k == 2 then BigInt(4)
    else if k == 3 then BigInt(8)
    else if k == 4 then BigInt(16)
    else if k == 5 then BigInt(32)
    else if k == 6 then BigInt(64)
    else if k == 7 then BigInt(128)
    else if k == 8 then BigInt(256)
    else if k == 9 then BigInt(512)
    else if k == 10 then BigInt(1024)
    else if k == 11 then BigInt(2048)
    else if k == 12 then BigInt(4096)
    else if k == 13 then BigInt(8192)
    else if k == 14 then BigInt(16384)
    else if k == 15 then BigInt(32768)
    else if k == 16 then BigInt(65536)
    else if k == 17 then BigInt(131072)
    else if k == 18 then BigInt(262144)
    else if k == 19 then BigInt(524288)
    else if k == 20 then BigInt(1048576)
    else if k == 21 then BigInt(2097152)
    else if k == 22 then BigInt(4194304)
    else if k == 23 then BigInt(8388608)
    else if k == 24 then BigInt(16777216)
    else if k == 25 then BigInt(33554432)
    else if k == 26 then BigInt(67108864)
    else if k == 27 then BigInt(134217728)
    else if k == 28 then BigInt(268435456)
    else if k == 29 then BigInt(536870912)
    else if k == 30 then BigInt(1073741824)
    else if k == 31 then BigInt("2147483648")
    else if k == 32 then BigInt("4294967296")
    else pow2(k) // fallback to recursive definition for k > 32
  }.ensuring(res => res == pow2(k))

  /**
    * Encode the integer n as a big-endian (MSB first) bit string of exactly k bits. Generalizes encode5Bits to an arbitrary bit width k.
    *
    * `currentPow` caches 2^k so it is computed once by the caller and then only ever halved, instead of being recomputed from scratch at every recursive call.
    *
    * @param n
    * @param k
    * @param currentPow must be 2^k
    */
  def encodeNBits(n: BigInt, k: BigInt, currentPow: BigInt): List[Boolean] = {
    require(k >= 1 && 0 <= n && currentPow == pow2(k) && n < currentPow)
    decreases(k)
    if (k == 1) {
      List(n == 1)
    } else {
      val half = currentPow / 2
      if (n < half) {
        ZERO :: encodeNBits(n, k - 1, half)
      } else {
        ONE :: encodeNBits(n - half, k - 1, half)
      }
    }
  }.ensuring(res => res.size == k && (if n > 0 then numberOfLeadingZeros(res) < res.size else true))

  /**
    * Decode a big-endian (MSB first) bit string produced by encodeNBits back into an integer. Generalizes decode5Bits to an arbitrary bit width.
    *
    * @param l
    */
  def decodeNBits(l: List[Boolean]): BigInt = {
    require(l.size >= 1 && l.size <= 32)
    decodeNBits(l, pow2Fast(l.size - 1))

  }.ensuring(res => 0 <= res && res < pow2(l.size))

  /**
    * `currentPow` caches 2^(l.size - 1) so it is computed once by the caller and then only ever halved, instead of being recomputed from scratch at every recursive call.
    *
    * @param l
    * @param currentPow must be 2^(l.size - 1)
    */
  def decodeNBits(l: List[Boolean], currentPow: BigInt): BigInt = {
    require(l.size >= 1 && currentPow == pow2(l.size - 1))
    decreases(l)
    l match {
      case Cons(b, Nil()) => boolToBigInt(b)
      case Cons(b, tl) => boolToBigInt(b) * currentPow + decodeNBits(tl, currentPow / 2)
    }
  }.ensuring(res => 0 <= res && res < 2 * currentPow)
  

  def encodeMinNBits(n: BigInt): List[Boolean] = {
    require(32 <= n && n <= MAX_F)
    val minBits = minNBitsToEncode(n)
    ghostExpr(lemmaNLtPow2MinBits(n))
    encodeNBits(n, minBits, pow2Fast(minBits))
  }.ensuring(res => res.size == minNBitsToEncode(n))


  // --------------------------------------------------------------- LEMMAS ---------------------------------------------------------------

  /**
    * Main theorem for the round-trip property of run-length encoding from the bluebook (Section 5.2.3 page 5-2).
    *
    * @param a
    */
  @ghost @inlineOnce @opaque
  def lemmarunLengthEncodeRoundTrip(a: List[Boolean]): Unit = {
    require(a.size <= MAX_F)
    decreases(a.size)
    val encoded = runLengthEncode(a)
    val decoded = runLengthDecode(encoded, a.size)
    val (leadingZeros, rest) = splitLeadingZeros(a)
    rest match {
      case Nil() => 
        assert(encoded == List(ONE, ZERO))
        assert(decoded == List.fill(a.size)(ZERO))
        assert(a == List.fill(a.size)(ZERO))
        assert(runLengthDecode(runLengthEncode(a), a.size) == a)
      case Cons(b, tl) => 
        assert(encoded == countEncode(leadingZeros.size + 1) ++ runLengthEncode(tl))
        lemmaCountEncodeDecodeRoundTripWithSuffix(leadingZeros.size + 1, runLengthEncode(tl))
        assert(countDecodePrefix(countEncode(leadingZeros.size + 1) ++ runLengthEncode(tl)) == (leadingZeros.size + 1, runLengthEncode(tl)))
        lemmarunLengthEncodeRoundTrip(tl)
        assert(runLengthDecode(runLengthEncode(tl), tl.size) == tl)
    }
  }.ensuring(_ => runLengthDecode(runLengthEncode(a), a.size) == a)

  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodingSizeCorrectOnValidInput(a: List[Boolean]): Unit = {
    require(a.size <= MAX_F)
    decreases(a.size)
    val encoded = runLengthEncode(a)
    val (leadingZeros, rest) = splitLeadingZeros(a)
    rest match {
      case Nil() =>
        assert(encoded == List(ONE, ZERO))
      case Cons(b, tl) =>
        val n = leadingZeros.size + 1
        val tlEncoded = runLengthEncode(tl)
        assert(encoded == countEncode(n) ++ tlEncoded)
        lemmaCountDecodeSizeCorrect(n, countEncode(n))
        lemmaCountDecodeSizeIsNotAffectedBySuffix(n, countEncode(n), tlEncoded)
        assert(countDecodeSize(encoded) == countEncode(n).size)
        lemmaCountEncodeDecodeRoundTripWithSuffix(n, tlEncoded)
        assert(countDecodePrefix(encoded) == (n, tlEncoded))
        lemmaRunLengthEncodingSizeCorrectOnValidInput(tl)
        assert(runLengthEncodingSize(tlEncoded, tl.size) == tlEncoded.size)
        assert(a.size - n == tl.size)
    }
  }.ensuring(_ => runLengthEncodingSize(runLengthEncode(a), a.size) == runLengthEncode(a).size)

  /** Same as lemmaRunLengthEncodingSizeCorrectOnValidInput, when the encoding is followed by the rest of a packet. */
  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodingSizeCorrectWithSuffix(a: List[Boolean], suffix: List[Boolean]): Unit = {
    require(a.size <= MAX_F)
    decreases(a.size)
    val encoded = runLengthEncode(a)
    val (leadingZeros, rest) = splitLeadingZeros(a)
    rest match {
      case Nil() =>
        assert(encoded == List(ONE, ZERO))
      case Cons(b, tl) =>
        val n = leadingZeros.size + 1
        val tlEncoded = runLengthEncode(tl)
        assert(encoded == countEncode(n) ++ tlEncoded)
        assert(ListSpecs.appendAssoc(countEncode(n), tlEncoded, suffix))
        lemmaCountDecodeSizeCorrect(n, countEncode(n))
        lemmaCountDecodeSizeIsNotAffectedBySuffix(n, countEncode(n), tlEncoded ++ suffix)
        assert(countDecodeSize(encoded ++ suffix) == countEncode(n).size)
        lemmaCountEncodeDecodeRoundTripWithSuffix(n, tlEncoded ++ suffix)
        assert(countDecodePrefix(encoded ++ suffix) == (n, tlEncoded ++ suffix))
        lemmaRunLengthEncodingSizeCorrectWithSuffix(tl, suffix)
        assert(runLengthEncodingSize(tlEncoded ++ suffix, tl.size) == tlEncoded.size)
        assert(a.size - n == tl.size)
    }
  }.ensuring(_ => runLengthEncodingSize(runLengthEncode(a) ++ suffix, a.size) == runLengthEncode(a).size)

  @ghost @inlineOnce @opaque
  def lemmaBitExtractionMsbFirstSize(a: List[Boolean], b: List[Boolean]): Unit = {
    require(a.size == b.size)
    decreases(b)
    b match {
      case Nil() => ()
      case Cons(bh, btl) => lemmaBitExtractionMsbFirstSize(a.tail, btl)
    }
  }.ensuring(_ => bitExtractionMsbFirst(a, b).size == hammingWeight(b))

  @ghost @inlineOnce @opaque
  def lemmaBitExtractionSize(a: List[Boolean], b: List[Boolean]): Unit = {
    require(a.size == b.size)
    lemmaBitExtractionMsbFirstSize(a, b)
  }.ensuring(_ => bitExtractionFunction(a, b).size == hammingWeight(b))

  @ghost @inlineOnce @opaque
  def lemmaBitInsertionExtractionMsbFirst(a: List[Boolean], base: List[Boolean], b: List[Boolean]): Unit = {
    require(a.size == b.size && base.size == b.size)
    require(equalWhereSelectIsZero(a, base, b))
    decreases(b)
    lemmaBitExtractionMsbFirstSize(a, b)
    b match {
      case Nil() => ()
      case Cons(bh, btl) => lemmaBitInsertionExtractionMsbFirst(a.tail, base.tail, btl)
    }
  }.ensuring(_ => {
    lemmaBitExtractionMsbFirstSize(a, b)
    bitInsertionMsbFirst(base, b, bitExtractionMsbFirst(a, b)) == a
  })

  /**
    * Inserting BE(a,b) back into `base` recovers a, as long as a and base coincide outside of b.
    *
    * @param a
    * @param base
    * @param b
    */
  @ghost @inlineOnce @opaque
  def lemmaBitInsertionExtraction(a: List[Boolean], base: List[Boolean], b: List[Boolean]): Unit = {
    require(a.size == b.size && base.size == b.size)
    require(equalWhereSelectIsZero(a, base, b))
    lemmaBitExtractionSize(a, b)
    assert(ListSpecs.reverseReverse(bitExtractionMsbFirst(a, b)))
    lemmaBitInsertionExtractionMsbFirst(a, base, b)
  }.ensuring(_ => {
    lemmaBitExtractionSize(a, b)
    bitInsertionFunction(base, b, bitExtractionFunction(a, b)) == a
  })


  /**
    * Main lemma for round-trip property of COUNT encode and decode WITH AN ARBITRARY SUFFIX, as defined in the blue book (Section 5.2.2, page 5-2).
    * 
    */
  @ghost @inlineOnce @opaque
  def lemmaCountEncodeDecodeRoundTripWithSuffix(n: BigInt, suffix: List[Boolean]): Unit = {
    require(1 <= n && n <= MAX_F)
    val encoded = countEncode(n)
    val wholeSeq = encoded ++ suffix
    lemmaCountDecodeSizeCorrect(n, encoded)
    if n == 1 then ()
    else if 2 <= n && n <= 33 then 
      val size = countDecodeSize(encoded)
      val (prefix, rest) = wholeSeq.splitAtIndex(size)
      ListUtils.lemmaSplitAtIndexConcatSize(encoded, suffix, size)
    else 
      assert(encoded.size == 3 + countE(n - 2))
      val size = countDecodeSize(encoded)
      lemmaCountDecodeSizeIsNotAffectedBySuffix(n, encoded, suffix)
      val (prefix, rest) = wholeSeq.splitAtIndex(size)
      ListUtils.lemmaSplitAtIndexConcatSize(encoded, suffix, size)
      lemmaCountEncodeDecodeRoundTrip(n)
    
  }.ensuring(_ => countDecodePrefix(countEncode(n) ++ suffix) == (n, suffix))


  /**
    * Main lemma for round-trip property of COUNT encode and decode, as defined in the blue book (Section 5.2.2, page 5-2).
    * 
    */
  @ghost @inlineOnce @opaque
  def lemmaCountEncodeDecodeRoundTrip(n: BigInt): Unit = {
    require(1 <= n && n <= MAX_F)
    decreases(n)
    if (n == 1) {
      ()
    } else if (2 <= n && n <= 33) {
      ()
    } else {
      val e = countE(n - 2)
      lemmaEncodeNBitsDecodeNBitsRoundTrip(n - 2, e, pow2Fast(e), encodeNBits(n - 2, e, pow2Fast(e)))
    }
  }.ensuring(_ => countDecode(countEncode(n)) == n)

  /**
    * n is, by definition of floorLog2, smaller than 2^(floorLog2(n) + 1) == 2^minNBitsToEncode(n), so encodeNBits(n, minNBitsToEncode(n)) is well-defined.
    *
    * @param n
    */
  @ghost @inlineOnce @opaque
  def lemmaNLtPow2MinBits(n: BigInt): Unit = {
    require(32 <= n && n <= MAX_F)
  }.ensuring(_ => n < pow2(minNBitsToEncode(n)))

  /**
    * Main correctness lemma for the encode/decode round-trip. Shows that decoding the result of encoding n with k bits yields n again.
    *
    * @param n
    * @param k
    * @param currentPow
    * @param l
    */
  @ghost @inlineOnce @opaque
  def lemmaEncodeNBitsDecodeNBitsRoundTrip(n: BigInt, k: BigInt, currentPow: BigInt, l: List[Boolean]): Unit = {
    require(k >= 1 && 0 <= n && currentPow == pow2(k) && n < currentPow)
    require(l == encodeNBits(n, k, currentPow))
    decreases(k)
    l match {
      case Cons(b, Nil()) => ()
      case Cons(b, tl) => lemmaEncodeNBitsDecodeNBitsRoundTrip(n - (if b then currentPow / 2 else 0), k - 1, currentPow / 2, tl)
    }
  }.ensuring(_ => decodeNBits(l, currentPow / 2) == n)

  @ghost @inlineOnce @opaque
  def lemmaEncodeCountEBitsRoundTrip(n: BigInt, leadingZeros: BigInt): Unit = {
    require(32 <= n && n <= MAX_F)
    require(leadingZeros == countE(n) - minNBitsToEncode(n))
    lemmaCountEImpliesTheRightNumberOfLeadingZeros(n)
    lemmaNLtPow2MinBits(n)
    lemmaEncodeNBitsDecodeNBitsRoundTrip(n, countE(n), pow2Fast(countE(n)), encodeNBits(n, countE(n), pow2Fast(countE(n))))
  }.ensuring(_ => decodeNBits(encodeNBits(n, countE(n), pow2Fast(countE(n)))) == n)

  @ghost @inlineOnce @opaque
  def lemmaEncodeCountEBitsAddsTheRightNumberOfLeadingZeros(n: BigInt): Unit = {
    require(32 <= n && n <= MAX_F)
    lemmaCountEImpliesTheRightNumberOfLeadingZeros(n)
  }.ensuring(_ => {
    encodeNBits(n, countE(n), pow2Fast(countE(n))).size == countE(n) &&
    numberOfLeadingZeros(encodeNBits(n, countE(n), pow2Fast(countE(n)))) == countE(n) - minNBitsToEncode(n)
  })

  @ghost @inlineOnce @opaque
  def lemmaCountDecodeSizeCorrect(n: BigInt, l: List[Boolean]): Unit = {
    require(1 <= n && n <= MAX_F)
    require(l == countEncode(n))
    decreases(n)
    if (n == 1) {
      ()
    } else if (2 <= n && n <= 33) {
      ()
    } else {
      val e = countE(n - 2)
      lemmaEncodeCountEBitsAddsTheRightNumberOfLeadingZeros(n - 2)
    }
  }.ensuring(_ => countDecodeSize(l) == l.size)

  @ghost @inlineOnce @opaque
  def lemmaCountDecodeSizeIsNotAffectedBySuffix(n: BigInt, l: List[Boolean], suffix: List[Boolean]): Unit = {
    require(1 <= n && n <= MAX_F)
    require(l == countEncode(n))
     l match {
      case Cons(false, tl) => 
        assert(countDecodeSize(l) == BigInt(1))
        assert(countDecodeSize(l ++ suffix) == countDecodeSize(l))
      case Cons(true, Cons(true, Cons(false, tl))) => 
        assert(countDecodeSize(l) == BigInt(3) + BigInt(5))
        assert(countDecodeSize(l ++ suffix) == countDecodeSize(l))
      case Cons(true, Cons(true, Cons(true, tl))) => 
        val nLeadingZeros = numberOfLeadingZeros(tl)
        assert(nLeadingZeros < tl.size)
        lemmaNLeadingZerosNotAffectedBySuffixWhenSmallerThanList(tl, suffix)
        assert(numberOfLeadingZeros(tl ++ suffix) == nLeadingZeros)
        if nLeadingZeros > 10 then 
          assert(countDecodeSize(l) == BigInt(-1)) 
          assert(countDecodeSize(l ++ suffix) == countDecodeSize(l))
        else 
          assert(countDecodeSize(l) == BigInt(3) + nLeadingZerosToBitWidth(nLeadingZeros))
          assert(countDecodeSize(l ++ suffix) == countDecodeSize(l))
      case _ => assert(countDecodeSize(l) == BigInt(-1)) 
    }
  }.ensuring(_ => countDecodeSize(l ++ suffix) == countDecodeSize(l))

  @ghost @inlineOnce @opaque
  def lemmaNLeadingZerosNotAffectedBySuffixWhenSmallerThanList(l: List[Boolean], suffix: List[Boolean]): Unit = {
    require(numberOfLeadingZeros(l) < l.size)
    decreases(l)
    val nLeadingZeros = numberOfLeadingZeros(l)
    assert(nLeadingZeros < l.size)
    l match {
      case Nil() => ()
      case Cons(true, tl) => ()
      case Cons(false, tl) => 
        lemmaNLeadingZerosNotAffectedBySuffixWhenSmallerThanList(tl, suffix)
    }

  }.ensuring(_ => numberOfLeadingZeros(l ++ suffix) == numberOfLeadingZeros(l))

  /**
    * Return the number of leading zeros in the given list of bits. If the list is empty, return 0.
    *
    * @param l
    */
  def numberOfLeadingZeros(l: List[Boolean]): BigInt = {
    decreases(l)
    l match {
      case Nil() => BigInt(0)
      case Cons(true, tl) => BigInt(0)
      case Cons(false, Cons(true, tl)) => BigInt(1)
      case Cons(false, Cons(false, Cons(true, tl))) => BigInt(2)
      case Cons(false, Cons(false, Cons(false, Cons(true, tl)))) => BigInt(3)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl))))) => BigInt(4)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl)))))) => BigInt(5)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl))))))) => BigInt(6)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl)))))))) => BigInt(7)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl))))))))) => BigInt(8)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl)))))))))) => BigInt(9)
      case Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(false, Cons(true, tl))))))))))) => BigInt(10)
      case Cons(false, tl) => BigInt(1) + numberOfLeadingZeros(tl)
    }
  }.ensuring(res => res >= 0 && res <= l.size)


  /**
  * This lemma shows the relationship between the number of leading zeros implied by countE and the encoded number n
  *
  * @param n
  */
  @ghost @inlineOnce @opaque
  def lemmaCountEImpliesTheRightNumberOfLeadingZeros(n: BigInt): Unit = {
    require(32 <= n && n <= MAX_F)

  }.ensuring(_ => {
    if 32 <= n && n <= 63 then countE(n) == minNBitsToEncode(n)
    else if 64 <= n && n <= 127 then countE(n) == minNBitsToEncode(n) + 1
    else if 128 <= n && n <= 255 then countE(n) == minNBitsToEncode(n) + 2
    else if 256 <= n && n <= 511 then countE(n) == minNBitsToEncode(n) + 3
    else if 512 <= n && n <= 1023 then countE(n) == minNBitsToEncode(n) + 4
    else if 1024 <= n && n <= 2047 then countE(n) == minNBitsToEncode(n) + 5
    else if 2048 <= n && n <= 4095 then countE(n) == minNBitsToEncode(n) + 6
    else if 4096 <= n && n <= 8191 then countE(n) == minNBitsToEncode(n) + 7
    else if 8192 <= n && n <= 16383 then countE(n) == minNBitsToEncode(n) + 8
    else if 16384 <= n && n <= 32767 then countE(n) == minNBitsToEncode(n) + 9
    else if 32768 <= n && n <= 65535 then countE(n) == minNBitsToEncode(n) + 10
    else true
  })
}

object Utils {
  def xor(a: Boolean, b: Boolean): Boolean = (a && !b) || (!a && b)
  def xor(aa: List[Boolean], bb: List[Boolean]): List[Boolean] = {
    require(aa.size == bb.size)
    aa match {
      case Nil() => Nil()
      case Cons(a, atl) => {
        val (b, btl) = (bb.head, bb.tail)
        xor(a, b) :: xor(atl, btl)
      }
    }
  }.ensuring(res => res.size == aa.size && res.size == bb.size)

  def or(aa: List[Boolean], bb: List[Boolean]): List[Boolean] = {
    require(aa.size == bb.size)
    aa match {
      case Nil() => Nil()
      case Cons(a, atl) => {
        val (b, btl) = (bb.head, bb.tail)
        (a || b) :: or(atl, btl)
      }
    }
  }.ensuring(res => res.size == aa.size && res.size == bb.size)

  def or(bitwidth: BigInt, aas: List[List[Boolean]]): List[Boolean] = {
    require(bitwidth >= 1 && aas.forall(l => l.size == bitwidth))
    decreases(aas)
    aas match {
      case Nil() => Nil()
      case Cons(hd, Nil()) => hd
      case Cons(hd, tl) => or(hd, or(bitwidth, tl))
    }
  }.ensuring(res => if aas.isEmpty then res.isEmpty else res.size == bitwidth)

  def not(aa: List[Boolean]): List[Boolean] = {
    aa match {
      case Nil() => Nil()
      case Cons(a, atl) => (!a) :: not(atl)
    }
  }.ensuring(res => res.size == aa.size)

  def leftShift(aa: List[Boolean]): List[Boolean] = {
    aa match {
      case Nil() => Nil()
      case Cons(a, tl) => tl ++ List(false)
    }
  }.ensuring(res => res.size == aa.size)

  /** Inverse of `xor(m, leftShift(m))`: m_{f-1} = d_{f-1} and m_i = d_i XOR m_{i+1}. */
  def maskFromDiff(dd: List[Boolean]): List[Boolean] = {
    dd match {
      case Nil() => Nil()
      case Cons(d, Nil()) => List(d)
      case Cons(d, dtl) => {
        val mtl = maskFromDiff(dtl)
        xor(d, mtl.head) :: mtl
      }
    }
  }.ensuring(res => res.size == dd.size)

  /** a and b coincide at every index where the selector is zero. */
  @ghost
  def equalWhereSelectIsZero(a: List[Boolean], b: List[Boolean], selector: List[Boolean]): Boolean = {
    require(a.size == selector.size && b.size == selector.size)
    decreases(selector)
    selector match {
      case Nil() => true
      case Cons(sh, stl) => (sh || a.head == b.head) && equalWhereSelectIsZero(a.tail, b.tail, stl)
    }
  }

  /** H(a), the number of '1' bits in a (bluebook section 5.2.4). Named for the same reason as PocketExecSpec.isAllZero. */
  def hammingWeight(aa: List[Boolean]): BigInt = aa.count(b => b == true)

  def boolToBigInt(b: Boolean): BigInt = if b then 1 else 0
  def min(a: BigInt, b: BigInt): BigInt = if a < b then a else b
}

object ListUtils {
  /** The packet as the encoder concatenates it (left-associated) is the packet as the decoder peels it off (right-associated). */
  @ghost @inlineOnce @opaque
  def lemmaPacketAssoc[A](a: List[A], b: List[A], c: List[A], d: List[A], e: List[A], f: List[A], g: List[A], h: List[A]): Unit = {
    assert(ListSpecs.appendAssoc(g, Nil[A](), h))
    assert(ListSpecs.appendAssoc(a ++ b ++ c ++ d ++ e ++ f, g, h))
    assert(ListSpecs.appendAssoc(a ++ b ++ c ++ d ++ e, f, g ++ h))
    assert(ListSpecs.appendAssoc(a ++ b ++ c ++ d, e, f ++ (g ++ h)))
    assert(ListSpecs.appendAssoc(a ++ b ++ c, d, e ++ (f ++ (g ++ h))))
    assert(ListSpecs.appendAssoc(a ++ b, c, d ++ (e ++ (f ++ (g ++ h)))))
    assert(ListSpecs.appendAssoc(a, b, c ++ (d ++ (e ++ (f ++ (g ++ h))))))
  }.ensuring(_ => a ++ b ++ c ++ d ++ e ++ f ++ g ++ h == a ++ (b ++ (c ++ (d ++ (e ++ (f ++ (g ++ h)))))))

  @ghost @inlineOnce @opaque
  def lemmaSplitAtIndexConcatSize[A](l1: List[A], l2: List[A], index: BigInt): Unit = {
    require(l1.size == index)
    l1 match {
      case Nil() => ()
      case Cons(h, tl) => lemmaSplitAtIndexConcatSize(tl, l2, index - 1)
    }

  }.ensuring(_ => (l1 ++ l2).splitAtIndex(index) == (l1, l2))

  /** `l.drop(n).drop(m) == l.drop(n + m)`, the standard drop-composition fact. */
  @ghost @inlineOnce @opaque
  def lemmaDropDrop[A](l: List[A], n: BigInt, m: BigInt): Unit = {
    require(n >= 0 && m >= 0)
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) =>
        if n > 0 then {
          lemmaDropDrop(t, n - 1, m)
          assert(t.drop(n - 1).drop(m) == t.drop(n - 1 + m))
        } else ()
    }
  }.ensuring(_ => l.drop(n).drop(m) == l.drop(n + m))

  @ghost @inlineOnce @opaque
  def lemmaTakeTakeWhileSizeLe[A](l: List[A], k: BigInt, p: A => Boolean): Unit = {
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) =>
        if k <= 0 then ()
        else if p(h) then {
          lemmaTakeTakeWhileSizeLe(t, k - 1, p)
          assert(t.take(k - 1).takeWhile(p).size <= t.takeWhile(p).size)
        } else ()
    }
  }.ensuring(_ => l.take(k).takeWhile(p).size <= l.takeWhile(p).size)

  @ghost @inlineOnce @opaque
  def lemmaTakeDropTakeWhileSizeLe[A](l: List[A], n: BigInt, r: BigInt, p: A => Boolean): Unit = {
    require(n >= 0 && r >= 0)
    decreases(l)
    l match {
      case Nil() => ()
      case Cons(h, t) =>
        if n <= 0 then ()
        else if r <= 0 then lemmaTakeTakeWhileSizeLe(l, n, p)
        else {
          lemmaTakeDropTakeWhileSizeLe(t, n - 1, r - 1, p)
          assert(t.take(n - 1).drop(r - 1).takeWhile(p).size <= t.drop(r - 1).takeWhile(p).size)
        }
    }
  }.ensuring(_ => l.take(n).drop(r).takeWhile(p).size <= l.drop(r).takeWhile(p).size)

  @ghost @inlineOnce @opaque
  def lemmaConsDropTakeWhileSizeDelta[A](x: A, l: List[A], r: BigInt, p: A => Boolean): Unit = {
    require(r >= 0)
    if r > 0 then {
      lemmaDropDrop(l, r - 1, 1)
      assert(l.drop(r - 1).drop(1) == l.drop(r))
      l.drop(r - 1) match {
        case Nil() =>
          assert(l.drop(r) == Nil[A]())
        case Cons(h, t) =>
          assert(Cons(h, t).drop(1) == t)
          assert(l.drop(r) == t)
      }
    } else ()
  }.ensuring(_ => Cons(x, l).drop(r).takeWhile(p).size <= l.drop(r).takeWhile(p).size + 1)
}