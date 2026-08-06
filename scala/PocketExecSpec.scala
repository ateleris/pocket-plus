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
    require(1 <= newPacket.size && newPacket.size <= RunLengthEncoding.MAX_F)
    val compressed = newPacket
    compressed
  }.ensuring(res => res.size <= newPacket.size)
}

object RunLengthEncoding {
  val MAX_F: BigInt = 65535 // 2^16 - 1
  val ONE = true
  val ZERO = false

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

  /**
    * Encode the list of bits using run-length encoding defined in the blue book (Section 5.2.3, page 5-2).
    * 
    *
    * @param a
    */
  def runLengthEncoding(a: List[Boolean]): List[BigInt] = {
    Nil()
  }

  /**
    * Count encoding of the integer A using the counter encoding defined in the blue book (Section 5.2.2, page 5-2).
    *
    * @param k
    */
  def countEncode(A: BigInt): List[Boolean] = {
    require(1 <= A && A <= MAX_F)
    if (A == 1) {
      List(ZERO)
    } else if (2 <= A && A <= 33) {
      List(ONE, ONE, ZERO) ++ encode5Bits(A - 2)
    } else {
      val e = countE(A - 2)
      List(ONE, ONE, ONE) ++ encodeNBits(A - 2, e, pow2Fast(e))
    }
  }

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
    * Encode the integer n using the counter encoding defined in the blue book (Section 5.2.2, page 5-2), when the integer is in the range 0 <= n <= 31. The encoding is a list of 5 bits (booleans) representing the binary representation of n.
    * 
    * PROVEN TO BE INVERTIBLE: decode5Bits(encode5Bits(n)) == n for all n in [0, 31].
    *
    * @param n
    */
  def encode5Bits(n: BigInt): {res: List[Boolean] with res.size == 5 && decode5Bits(res) == n} = {
    require(0 <= n && n <= 31)
    encodeNBits(n, 5, pow2Fast(5))
  }
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
  @ghost 
  def pow2(k: BigInt): BigInt = {
    require(k >= 0)
    decreases(k)
    if (k == 0) BigInt(1) else 2 * pow2(k - 1)
  }.ensuring(res => res >= 1)

  def pow2Fast(k: BigInt): BigInt = {
    require(k >= 0 && k <= 32)
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
    else {
      assert(false)
      BigInt(0)
    }
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
  }.ensuring(res => res.size == k)

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
    leadingZeros(encodeNBits(n, countE(n), pow2Fast(countE(n)))) == countE(n) - minNBitsToEncode(n)
  })

  @ghost 
  def leadingZeros(l: List[Boolean]): BigInt = {
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
      case Cons(false, tl) => BigInt(1) + leadingZeros(tl)
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






  def boolToBigInt(b: Boolean): BigInt = if b then 1 else 0
}