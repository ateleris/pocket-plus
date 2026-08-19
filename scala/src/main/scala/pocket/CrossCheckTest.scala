package pocket

import stainless.collection.{List as SList, Nil as SNil}

/** Cross-checks PocketExecSpec's encoder against PocketPlus's array-based Decompressor. */
object CrossCheckTest {
  def toArray(l: SList[Boolean]): Array[Boolean] = {
    l.toScala.toArray
  }

  def toSList(a: Seq[Boolean]): SList[Boolean] = SList(a*)
  def bits(a: Array[Boolean]): String = a.map(b => if b then '1' else '0').mkString

  case class Failure(step: Int, expected: String, got: String, packetBits: Int)

  def runSequence(f: Int, steps: Int, seed: Long): Option[Failure] = {
    import PocketExecSpec.*
    val rnd = new scala.util.Random(seed)
    val F = BigInt(f)
    val m0 = build0(F)

    val dst = DecompressorState(f, Array.fill(f)(false), Array.fill(f)(false), 0)
    Decompressor.decompressorInit(dst)

    def randBits(): SList[Boolean] = toSList(IndexedSeq.fill(f)(rnd.nextBoolean()))

    var params = CompressionParameters(
      t = 0, f = F, m0 = m0, robustnessT = 0, pts = SList(true), ft = true, rt = true, ct = 0,
      itMinusOne = build0(F), btMinusOne = build0(F), mtMinusOne = m0, dts = SNil()
    )
    var it = randBits()
    var failure: Option[Failure] = None
    var t = 0

    while (t < steps && failure.isEmpty) {
      val (packet, nextParams) = encodingStep(params, it, rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean())

      val packetArr = toArray(packet)
      val recovered = Array.fill(f)(false)
      Decompressor.decompress(dst, packetArr, 0, recovered)

      val expected = bits(toArray(it))
      val got = bits(recovered)
      if (got != expected) failure = Some(Failure(t, expected, got, packetArr.length))

      params = nextParams
      it = randBits()
      t += 1
    }
    failure
  }

  def main(args: Array[String]): Unit = {
    val cases = Seq(
      ("F=8, 30 steps", 8, 30, 1L),
      ("F=64, 50 steps", 64, 50, 2L),
      ("F=512, 30 steps", 512, 30, 3L),
      ("F=4096, 10 steps", 4096, 10, 4L),
      ("F=65535, 5 steps", 65535, 5, 5L)
    )

    var ok = true
    for ((name, f, steps, seed) <- cases) {
      runSequence(f, steps, seed) match {
        case None => println(s"PASS  $name")
        case Some(fail) =>
          ok = false
          println(s"FAIL  $name at t=${fail.step} (packet ${fail.packetBits} bits)")
          println(s"  expected: ${fail.expected}")
          println(s"  got     : ${fail.got}")
      }
    }
    if (!ok) sys.exit(1)
  }
}
