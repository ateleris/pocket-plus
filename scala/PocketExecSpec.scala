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
    require(1 <= newPacket.size && newPacket.size <= Utils.MAX_F)
    val compressed = newPacket
    compressed
  }.ensuring(res => res.size <= newPacket.size)
}

object Utils {
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


  def counterEncode(A: BigInt): List[Boolean] = {
    require(1 <= A && A <= MAX_F)
    if (A == 1) {
      List(ZERO)
    } else if (2 <= A && A <= 33) {
      List(ONE, ONE, ZERO) ++ encode5Bits(A - 2)
    } else {
      List() // TODO
    }
  }
  /**
    * Encode the integer n using the counter encoding defined in the blue book (Section 5.2.2, page 5-2), when the integer is in the range 0 <= n <= 31. The encoding is a list of 5 bits (booleans) representing the binary representation of n.
    * 
    * PROVEN TO BE INVERTIBLE: decode5Bits(encode5Bits(n)) == n for all n in [0, 31].
    *
    * @param n
    */
  def encode5Bits(n: BigInt): {res: List[Boolean] with res.size == 5 && decode5Bits(res) == n} = {
    require(0 <= n && n <= 31)
    if n == 0 then List(ZERO, ZERO, ZERO, ZERO, ZERO)
    else if n == 1 then List(ZERO, ZERO, ZERO, ZERO, ONE)
    else if n == 2 then List(ZERO, ZERO, ZERO, ONE, ZERO)
    else if n == 3 then List(ZERO, ZERO, ZERO, ONE, ONE)
    else if n == 4 then List(ZERO, ZERO, ONE, ZERO, ZERO)
    else if n == 5 then List(ZERO, ZERO, ONE, ZERO, ONE)
    else if n == 6 then List(ZERO, ZERO, ONE, ONE, ZERO)
    else if n == 7 then List(ZERO, ZERO, ONE, ONE, ONE)
    else if n == 8 then List(ZERO, ONE, ZERO, ZERO, ZERO)
    else if n == 9 then List(ZERO, ONE, ZERO, ZERO, ONE)
    else if n == 10 then List(ZERO, ONE, ZERO, ONE, ZERO)
    else if n == 11 then List(ZERO, ONE, ZERO, ONE, ONE)
    else if n == 12 then List(ZERO, ONE, ONE, ZERO, ZERO)
    else if n == 13 then List(ZERO, ONE, ONE, ZERO, ONE)
    else if n == 14 then List(ZERO, ONE, ONE, ONE ,ZERO)
    else if n == 15 then List(ZERO ,ONE ,ONE ,ONE ,ONE)
    else if n == 16 then List(ONE ,ZERO ,ZERO ,ZERO ,ZERO)
    else if n == 17 then List(ONE ,ZERO ,ZERO ,ZERO ,ONE)
    else if n == 18 then List(ONE ,ZERO ,ZERO ,ONE ,ZERO)
    else if n == 19 then List(ONE ,ZERO ,ZERO ,ONE ,ONE)
    else if n == 20 then List(ONE ,ZERO ,ONE ,ZERO ,ZERO)
    else if n == 21 then List(ONE ,ZERO ,ONE ,ZERO ,ONE)
    else if n == 22 then List(ONE ,ZERO ,ONE ,ONE ,ZERO)
    else if n == 23 then List(ONE ,ZERO ,ONE ,ONE ,ONE)
    else if n == 24 then List(ONE ,ONE ,ZERO ,ZERO ,ZERO)
    else if n == 25 then List(ONE ,ONE ,ZERO ,ZERO ,ONE)
    else if n == 26 then List(ONE ,ONE ,ZERO ,ONE ,ZERO)
    else if n == 27 then List(ONE ,ONE ,ZERO ,ONE ,ONE)
    else if n == 28 then List(ONE ,ONE ,ONE ,ZERO ,ZERO)
    else if n == 29 then List(ONE ,ONE ,ONE ,ZERO ,ONE)
    else if n == 30 then List(ONE ,ONE ,ONE ,ONE ,ZERO)
    else List(ONE ,ONE ,ONE ,ONE ,ONE)
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






  def boolToBigInt(b: Boolean): BigInt = if b then 1 else 0
}