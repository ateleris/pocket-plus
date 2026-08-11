package pocket

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*

object PocketExecSpec {
  /**
    * Blue book specifies the length of the input vectors as F for 1 <= F <= 2^16 - 1. (Section 3.2, page 3-1).
    *
    * @param refPacket
    * @param mask
    * @param newPacket
    */
  def compressPocket(refPacket: List[Boolean], mask: List[Boolean], newPacket: List[Boolean]): List[Boolean] = {
    require(refPacket.size == mask.size && refPacket.size == newPacket.size)
    require(1 <= newPacket.size && newPacket.size <= BasicEncodingFunctions.MAX_F)
    val compressed = newPacket
    compressed
  }.ensuring(res => res.size <= newPacket.size)
}



/**
  * Bluebook section 5.2
  */
object BasicEncodingFunctions {
  val MAX_F: BigInt = 65535 // 2^16 - 1
  val ONE = true
  val ZERO = false


  /**
    * Returns a bit string with bits of a at the indexes i where b_i is one.
    * 
    * Defined in Blue book section 5.2.4.
    *
    * @param a
    * @param b
    */
  def bitExtractionFunction(a: List[Boolean], b: List[Boolean]): List[Boolean] = {
    require(a.size == b.size)
    b match {
      case Nil() => Nil()
      case Cons(bh, btl) if bh == ONE =>
        Cons(a.head, bitExtractionFunction(a.tail, btl))
      case Cons(bh, btl) =>
        bitExtractionFunction(a.tail, btl)
    }
  }.ensuring(res => true)

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
    * Encode the integer n as a big-endian (MSB first) bit string of exactly k bits. Generalizes [[encode5Bits]] to an arbitrary bit width k.
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
    * Decode a big-endian (MSB first) bit string produced by [[encodeNBits]] back into an integer. Generalizes [[decode5Bits]] to an arbitrary bit width.
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

  def boolToBigInt(b: Boolean): BigInt = if b then 1 else 0
}

object ListUtils {
  @ghost @inlineOnce @opaque
  def lemmaSplitAtIndexConcatSize[A](l1: List[A], l2: List[A], index: BigInt): Unit = {
    require(l1.size == index)
    l1 match {
      case Nil() => ()
      case Cons(h, tl) => lemmaSplitAtIndexConcatSize(tl, l2, index - 1)
    }

  }.ensuring(_ => (l1 ++ l2).splitAtIndex(index) == (l1, l2))
}