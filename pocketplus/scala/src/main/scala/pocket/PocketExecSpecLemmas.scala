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
    if (k == 0) assert(BigInt(1 << 0) == pow2Fast(BigInt(0)))
    else if (k == 1) assert(BigInt(1 << 1) == pow2Fast(BigInt(1)))
    else if (k == 2) assert(BigInt(1 << 2) == pow2Fast(BigInt(2)))
    else if (k == 3) assert(BigInt(1 << 3) == pow2Fast(BigInt(3)))
    else if (k == 4) assert(BigInt(1 << 4) == pow2Fast(BigInt(4)))
    else if (k == 5) assert(BigInt(1 << 5) == pow2Fast(BigInt(5)))
    else if (k == 6) assert(BigInt(1 << 6) == pow2Fast(BigInt(6)))
    else if (k == 7) assert(BigInt(1 << 7) == pow2Fast(BigInt(7)))
    else if (k == 8) assert(BigInt(1 << 8) == pow2Fast(BigInt(8)))
    else if (k == 9) assert(BigInt(1 << 9) == pow2Fast(BigInt(9)))
    else if (k == 10) assert(BigInt(1 << 10) == pow2Fast(BigInt(10)))
    else if (k == 11) assert(BigInt(1 << 11) == pow2Fast(BigInt(11)))
    else if (k == 12) assert(BigInt(1 << 12) == pow2Fast(BigInt(12)))
    else if (k == 13) assert(BigInt(1 << 13) == pow2Fast(BigInt(13)))
    else if (k == 14) assert(BigInt(1 << 14) == pow2Fast(BigInt(14)))
    else if (k == 15) assert(BigInt(1 << 15) == pow2Fast(BigInt(15)))
    else if (k == 16) assert(BigInt(1 << 16) == pow2Fast(BigInt(16)))
    else if (k == 17) assert(BigInt(1 << 17) == pow2Fast(BigInt(17)))
    else if (k == 18) assert(BigInt(1 << 18) == pow2Fast(BigInt(18)))
    else if (k == 19) assert(BigInt(1 << 19) == pow2Fast(BigInt(19)))
    else if (k == 20) assert(BigInt(1 << 20) == pow2Fast(BigInt(20)))
    else if (k == 21) assert(BigInt(1 << 21) == pow2Fast(BigInt(21)))
    else if (k == 22) assert(BigInt(1 << 22) == pow2Fast(BigInt(22)))
    else if (k == 23) assert(BigInt(1 << 23) == pow2Fast(BigInt(23)))
    else if (k == 24) assert(BigInt(1 << 24) == pow2Fast(BigInt(24)))
    else if (k == 25) assert(BigInt(1 << 25) == pow2Fast(BigInt(25)))
    else if (k == 26) assert(BigInt(1 << 26) == pow2Fast(BigInt(26)))
    else if (k == 27) assert(BigInt(1 << 27) == pow2Fast(BigInt(27)))
    else if (k == 28) assert(BigInt(1 << 28) == pow2Fast(BigInt(28)))
    else if (k == 29) assert(BigInt(1 << 29) == pow2Fast(BigInt(29)))
  }.ensuring(_ => BigInt(1 << k) == pow2(BigInt(k)))

  /** One step of encodeNBits on machine integers, for k >= 2: the first bit is rem >= half, where half is
    * 2^(k-1), and the others encode what remains on k - 1 bits, with the weight halved. */
  @ghost @opaque @inlineOnce
  def lemmaEncodeNBitsStep(rem: Int, k: Int, half: Int): Unit = {
    require(2 <= k && k <= 30 && 0 <= rem && 0 <= half)
    require(BigInt(half) == pow2(BigInt(k) - 1) && BigInt(rem) < pow2(BigInt(k)))
    val bit = rem >= half
    val rest = if (bit) rem - half else rem
    datastructure.Utils.compareIntPreservedByToBigInt(rem, half)
    datastructure.Utils.compareIntPreservedByToBigInt(rest, 0)
    datastructure.Utils.subtractionIntPreservedByToBigInt(k, 1)
    if (bit) {
      datastructure.Utils.subtractionIntPreservedByToBigInt(rem, half)
    }
    datastructure.Utils.lemmaHalfIntToBigInt(half)
    val bigK = BigInt(k)
    val bigRem = BigInt(rem)
    val weight = pow2(bigK) / 2
    assert(pow2(bigK) == 2 * pow2(bigK - 1))
    assert(pow2(bigK - 1) == 2 * pow2(bigK - 2))
    assert(weight == pow2(bigK - 1) && weight == BigInt(half))
    assert(bigK - 1 == BigInt(k - 1))
    assert(BigInt(half >>> 1) == pow2(BigInt(k - 1) - 1))
    assert((bigRem < weight) == !bit)
    assert(BigInt(rest) == (if (bit) bigRem - weight else bigRem))
    assert(BigInt(rest) < pow2(BigInt(k - 1)))
    assert(encodeNBits(bigRem, bigK, pow2(bigK)) == (if (bigRem < weight) Cons(ZERO, encodeNBits(bigRem, bigK - 1, weight)) else Cons(ONE, encodeNBits(bigRem - weight, bigK - 1, weight))))
  }.ensuring(_ => {
    val bit = rem >= half
    val rest = if (bit) rem - half else rem
    0 <= rest && BigInt(half >>> 1) == pow2(BigInt(k - 1) - 1) && BigInt(rest) < pow2(BigInt(k - 1)) &&
    encodeNBits(BigInt(rem), BigInt(k), pow2(BigInt(k))) == Cons(bit, encodeNBits(BigInt(rest), BigInt(k - 1), pow2(BigInt(k - 1))))
  })

  /** The last step of encodeNBits on machine integers: the single bit left is rem >= half, where half is 1. */
  @ghost @opaque @inlineOnce
  def lemmaEncodeNBitsLast(rem: Int, k: Int, half: Int): Unit = {
    require(k == 1 && 0 <= rem && 0 <= half)
    require(BigInt(half) == pow2(BigInt(k) - 1) && BigInt(rem) < pow2(BigInt(k)))
    assert(pow2(BigInt(k) - 1) == BigInt(1))
    assert(pow2(BigInt(k)) == BigInt(2))
    datastructure.Utils.compareIntPreservedByToBigInt(half, 1)
    datastructure.Utils.compareIntPreservedByToBigInt(rem, 1)
    datastructure.Utils.compareIntPreservedByToBigInt(rem, half)
  }.ensuring(_ => encodeNBits(BigInt(rem), BigInt(k), pow2(BigInt(k))) == List(rem >= half))

  // --------------------------------------------------------------- COUNT (BLUE BOOK 5.2.2) ---------------------------------------------------------------
  // For writeCount: countEncode of the specification, as the fixed-width fields that are written.

  /** COUNT(1) is the single bit 0. */
  @ghost @opaque @inlineOnce
  def lemmaCountEncodeOne(a: Int): Unit = {
    require(a == 1)
    datastructure.Utils.compareIntPreservedByToBigInt(a, 1)
  }.ensuring(_ => countEncode(BigInt(a)) == List(ZERO))

  /** The prefixes 110 and 111 of COUNT are 6 and 7 on 3 bits. */
  @ghost @opaque @inlineOnce
  def lemmaCountPrefix(): Unit = {
    assert(pow2(BigInt(3)) == BigInt(8) && pow2(BigInt(2)) == BigInt(4) && pow2(BigInt(1)) == BigInt(2))
    assert(encodeNBits(BigInt(0), BigInt(1), BigInt(2)) == List(ZERO))
    assert(encodeNBits(BigInt(1), BigInt(1), BigInt(2)) == List(ONE))
    assert(encodeNBits(BigInt(2), BigInt(2), BigInt(4)) == List(ONE, ZERO))
    assert(encodeNBits(BigInt(3), BigInt(2), BigInt(4)) == List(ONE, ONE))
  }.ensuring(_ => BigInt(6) < pow2(BigInt(3)) && BigInt(7) < pow2(BigInt(3)) &&
    encodeNBits(BigInt(6), BigInt(3), pow2(BigInt(3))) == List(ONE, ONE, ZERO) &&
    encodeNBits(BigInt(7), BigInt(3), pow2(BigInt(3))) == List(ONE, ONE, ONE))

  /** COUNT(a) for 2 <= a <= 33 is 110 followed by a - 2 on 5 bits. */
  @ghost @opaque @inlineOnce
  def lemmaCountEncodeSmall(a: Int): Unit = {
    require(2 <= a && a <= 33)
    datastructure.Utils.compareIntPreservedByToBigInt(a, 1)
    datastructure.Utils.compareIntPreservedByToBigInt(a, 2)
    datastructure.Utils.compareIntPreservedByToBigInt(a, 33)
    datastructure.Utils.subtractionIntPreservedByToBigInt(a, 2)
    assert(BigInt(a - 2) == BigInt(a) - 2)
    assert(pow2Fast(BigInt(5)) == pow2(BigInt(5)) && pow2(BigInt(5)) == BigInt(32))
    lemmaCountPrefix()
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

  /** A run of n zeros splits into itself and nothing. */
  @ghost @inlineOnce @opaque
  def lemmaSplitLeadingZerosOnly(n: BigInt): Unit = {
    require(n >= 0)
    decreases(n)
    if (n > 0) {
      lemmaSplitLeadingZerosOnly(n - 1)
    }
  }.ensuring(_ => splitLeadingZeros(List.fill(n)(ZERO)) == (List.fill(n)(ZERO), Nil[Boolean]()))

  /** One step of the run-length encoding: n zeros and a one are encoded as COUNT(n + 1), followed by the
    * encoding of what comes after. */
  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodeZerosThenOne(n: BigInt, tl: List[Boolean]): Unit = {
    require(n >= 0 && n + 1 + tl.size <= MAX_F)
    lemmaSplitLeadingZerosThenOne(n, tl)
  }.ensuring(_ => runLengthEncode(List.fill(n)(ZERO) ++ Cons(ONE, tl)) == countEncode(n + 1) ++ runLengthEncode(tl))

  /** The end of the run-length encoding: trailing zeros are encoded as the terminator '10' alone. */
  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodeZeros(n: BigInt): Unit = {
    require(0 <= n && n <= MAX_F)
    lemmaSplitLeadingZerosOnly(n)
  }.ensuring(_ => runLengthEncode(List.fill(n)(ZERO)) == List(ONE, ZERO))

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
