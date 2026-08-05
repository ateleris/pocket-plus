object Test {
  val ONE = true
  val ZERO = false
  val MAX_F: BigInt = 65535 // 2^16 - 1
  def encode5Bits(n: BigInt): List[Boolean] = {
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

  def fiveBitsToBigInt(l: List[Boolean]): BigInt = {
    l.reverse.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (b, i)) =>
      if b then acc + (BigInt(1) << i) else acc
    }
  }


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

  import math.log
  import math.floor
  def log2(n: BigInt): Double = {
    require(n > 0)
    log(n.toDouble) / log(2)
  }

  

  def countE(n: BigInt): BigInt = {
    require(32 <= n && n <= MAX_F)
    2 * (floorLog2(n) + 1) - 6
  } 

  def minNumberOfBitsToEncode(n: BigInt): BigInt = {
    require(32 <= n && n <= MAX_F)
    floorLog2(n) + 1
  }

  def generateIntervals(): Unit = {
    println(s"values , countE, minBits")
    (32 to MAX_F.toInt).map { n =>
      val countEN = countE(n)
      val minBits = minNumberOfBitsToEncode(n)
      (n, (countEN, minBits))
    }.groupBy(_._2).toSeq.sorted.foreach { case ((countEN, minBits), values) =>
      println(s"${values.minBy(_._1)._1} to ${values.maxBy(_._1)._1}, $countEN, $minBits")
    }
  }

  def test(): Unit = {
    assert((0 to 31).map(n => fiveBitsToBigInt(encode5Bits(n)) == n).count(_ == false) == 0)
    assert((32 to MAX_F.toInt).map(n => floorLog2(n) == floor(log2(n))).count(_ == false) == 0)
}
  @main def main(args: String*): Unit = {
    Test.test()
    println("Test passed")
    Test.generateIntervals()
  }
}
