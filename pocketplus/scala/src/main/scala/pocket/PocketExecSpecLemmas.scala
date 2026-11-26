package pocket

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*

/**
  * Lemmas about the functional specification ([[PocketExecSpec]]) that its own proofs do not need, and that the
  * refinement proof of the imperative implementation ([[PocketImperative]]) does. Where the implementation works
  * on machine integers, they are stated on machine integers.
  *
  * Nothing here refers to the implementation: the lemmas about its own ghost definitions (the prefix of a bit
  * array, the encoding left to write) are next to these definitions in PocketImperative.scala. So the
  * implementation depends on this file and on the specification, and this file on the specification only.
  */
object PocketExecSpecLemmas {
  import BasicEncodingFunctions.*

  // --------------------------------------------------------------- FIXED-WIDTH ENCODING (encodeNBits) ---------------------------------------------------------------
  // For writeBits: encodeNBits of the specification, stated on machine integers.

  /** 1 << k is 2^k as the specification defines it. */
  @ghost @opaque @inlineOnce
  def lemmaShiftIsPow2(k: Int): Unit = {
    require(0 <= k && k <= 29)
    decreases(k)
    if (k > 0) {
      lemmaShiftIsPow2(k - 1)
      val prev = 1 << (k - 1)
      assert((1 << k) == prev + prev)
      datastructure.Utils.additionIntPreservedByToBigInt(prev, prev)
      datastructure.Utils.subtractionIntPreservedByToBigInt(k, 1)
    }
  }.ensuring(_ => BigInt(1 << k) == pow2(BigInt(k)))

  /** One step of encodeNBits on machine integers, for k >= 2: the first bit is rem >= half, where half is
    * 2^(k-1), and the others encode what remains on k - 1 bits, with the weight halved. */
  @ghost @opaque @inlineOnce
  def lemmaEncodeNBitsStep(rem: Int, k: Int, half: Int): Unit = {
    require(2 <= k && k <= 30 && 0 <= rem && 0 <= half)
    require(BigInt(half) == pow2(BigInt(k) - 1) && BigInt(rem) < pow2(BigInt(k)))
    if (rem >= half) {
      datastructure.Utils.subtractionIntPreservedByToBigInt(rem, half)
    }
    val bigK = BigInt(k)
    val bigRem = BigInt(rem)
    val weight = pow2(bigK) / 2
    assert(encodeNBits(bigRem, bigK, pow2(bigK)) == (if (bigRem < weight) Cons(ZERO, encodeNBits(bigRem, bigK - 1, weight)) else Cons(ONE, encodeNBits(bigRem - weight, bigK - 1, weight))))
  }.ensuring(_ => {
    val bit = rem >= half
    val rest = if (bit) rem - half else rem
    0 <= rest && BigInt(half >>> 1) == pow2(BigInt(k - 1) - 1) && BigInt(rest) < pow2(BigInt(k - 1)) &&
    encodeNBits(BigInt(rem), BigInt(k), pow2(BigInt(k))) == Cons(bit, encodeNBits(BigInt(rest), BigInt(k - 1), pow2(BigInt(k - 1))))
  })

  // --------------------------------------------------------------- COUNT (BLUE BOOK 5.2.2) ---------------------------------------------------------------
  // For writeCount: countEncode of the specification, as the fixed-width fields that are written.

  /** COUNT(1) is the single bit 0. */
  @ghost @opaque @inlineOnce
  def lemmaCountEncodeOne(a: Int): Unit = {
    require(a == 1)
  }.ensuring(_ => countEncode(BigInt(a)) == List(ZERO))

  /** The prefixes 110 and 111 of COUNT are 6 and 7 on 3 bits. */
  @ghost @opaque @inlineOnce
  def lemmaCountPrefix(): Unit = {
  }.ensuring(_ => BigInt(6) < pow2(BigInt(3)) && BigInt(7) < pow2(BigInt(3)) &&
    encodeNBits(BigInt(6), BigInt(3), pow2(BigInt(3))) == List(ONE, ONE, ZERO) &&
    encodeNBits(BigInt(7), BigInt(3), pow2(BigInt(3))) == List(ONE, ONE, ONE))

  /** COUNT(a) for 2 <= a <= 33 is 110 followed by a - 2 on 5 bits. */
  @ghost @opaque @inlineOnce
  def lemmaCountEncodeSmall(a: Int): Unit = {
    require(2 <= a && a <= 33)
  }.ensuring(_ => BigInt(6) < pow2(BigInt(3)) && BigInt(a - 2) < pow2(BigInt(5)) &&
    countEncode(BigInt(a)) == encodeNBits(BigInt(6), BigInt(3), pow2(BigInt(3))) ++ encodeNBits(BigInt(a - 2), BigInt(5), pow2(BigInt(5))))

  // --------------------------------------------------------------- RUN-LENGTH ENCODING (BLUE BOOK 5.2.3) ---------------------------------------------------------------
  // runLengthEncode of the specification, as an encoder that scans the vector bit by bit meets it.

  /** A list splits in at most one way into a run of zeros and a rest that does not start with a zero. */
  @ghost @inlineOnce @opaque
  def lemmaZerosPrefixUnique(n1: BigInt, r1: List[Boolean], n2: BigInt, r2: List[Boolean]): Unit = {
    require(n1 >= 0 && n2 >= 0)
    require(List.fill(n1)(ZERO) ++ r1 == List.fill(n2)(ZERO) ++ r2)
    require((r1.isEmpty || r1.head == ONE) && (r2.isEmpty || r2.head == ONE))
    decreases(n1)
    if (n1 > 0 && n2 > 0) {
      lemmaZerosPrefixUnique(n1 - 1, r1, n2 - 1, r2)
    }
  }.ensuring(_ => n1 == n2 && r1 == r2)

  /** A run of n zeros followed by a one splits into the run and the rest. It follows from the contract of
    * splitLeadingZeros alone, since the split it describes is unique. */
  @ghost @inlineOnce @opaque
  def lemmaSplitLeadingZerosThenOne(n: BigInt, tl: List[Boolean]): Unit = {
    require(n >= 0)
    val (leading, rest) = splitLeadingZeros(List.fill(n)(ZERO) ++ Cons(ONE, tl))
    lemmaZerosPrefixUnique(leading.size, rest, n, Cons(ONE, tl))
  }.ensuring(_ => splitLeadingZeros(List.fill(n)(ZERO) ++ Cons(ONE, tl)) == (List.fill(n)(ZERO), Cons(ONE, tl)))

  /** One step of the run-length encoding: n zeros and a one are encoded as COUNT(n + 1), followed by the
    * encoding of what comes after. */
  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodeZerosThenOne(n: BigInt, tl: List[Boolean]): Unit = {
    require(n >= 0 && n + 1 + tl.size <= MAX_F)
    lemmaSplitLeadingZerosThenOne(n, tl)
  }.ensuring(_ => runLengthEncode(List.fill(n)(ZERO) ++ Cons(ONE, tl)) == countEncode(n + 1) ++ runLengthEncode(tl))

  /** A run of n zeros followed by a zero is a run of n + 1 zeros. */
  @ghost @inlineOnce @opaque
  def lemmaZerosThenZero(n: BigInt, l: List[Boolean]): Unit = {
    require(n >= 0)
    decreases(n)
    if (n > 0) {
      lemmaZerosThenZero(n - 1, l)
    }
  }.ensuring(_ => List.fill(n)(ZERO) ++ Cons(ZERO, l) == List.fill(n + 1)(ZERO) ++ l)
}
