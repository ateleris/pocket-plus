package pocket

import stainless.collection.{List as SList, Nil as SNil}
import java.nio.file.{Files, Paths}

/** Encode test harness: pass an input vector and the expected packet, get a pass/fail report. */
object SpecTest {
  def toArray(l: SList[Boolean]): Array[Boolean] = l.toScala.toArray
  def toSList(a: Seq[Boolean]): SList[Boolean] = SList(a*)
  def bits(a: Array[Boolean]): String = a.map(b => if b then '1' else '0').mkString
  def bitsOf(s: String): Array[Boolean] = s.map(_ == '1').toArray

  def bytesToBits(bytes: Array[Byte]): Array[Boolean] = {
    val out = Array.fill(bytes.length * 8)(false)
    for (i <- bytes.indices; b <- 0 until 8)
      out(i * 8 + b) = ((bytes(i) >> (7 - b)) & 1) == 1
    out
  }

  /** Raw binary file as MSB-first bits (bit 7 of byte 0 first). */
  def readBin(path: String): Array[Boolean] = bytesToBits(Files.readAllBytes(Paths.get(path)))

  def hexToBytes(s: String): Array[Byte] =
    Array.tabulate(s.length / 2)(i => ((Character.digit(s(2 * i), 16) << 4) + Character.digit(s(2 * i + 1), 16)).toByte)

  def bytesToHex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** Pads bits to a byte boundary with false, then packs 8 bits/byte MSB first. */
  def packBitsPadded(bits: Array[Boolean]): Array[Byte] = {
    val padded = bits ++ Array.fill((8 - bits.length % 8) % 8)(false)
    padded.grouped(8).map(g => g.zipWithIndex.map((b, i) => if b then 1 << (7 - i) else 0).sum.toByte).toArray
  }

  case class Case(name: String, params: PocketExecSpec.CompressionParameters, input: Array[Boolean], expected: Array[Boolean])

  /** Convenience for the common t=0 case: robustnessT=0, forced ft=rt=pt=true, zeroed prior state. */
  def t0Case(name: String, f: Int, input: Array[Boolean], expected: Array[Boolean]): Case = {
    import PocketExecSpec.*
    Case(name, CompressionParameters(
      t = 0, f = f, m0 = build0(f), robustnessT = 0, pts = SList(true), ft = true, rt = true, ct = 0,
      itMinusOne = build0(f), btMinusOne = build0(f), mtMinusOne = build0(f), dts = SNil()
    ), input, expected)
  }

  def run(c: Case): Boolean = {
    val (packet, _, _, _) = EncodingStep.step(c.params, toSList(c.input))
    val got = toArray(packet)
    val ok = bits(got) == bits(c.expected)
    if (ok) println(s"PASS  ${c.name}")
    else {
      println(s"FAIL  ${c.name}")
      println(s"  expected (${c.expected.length} bits): ${bits(c.expected)}")
      println(s"  got      (${got.length} bits): ${bits(got)}")
    }
    ok
  }

  /** A packet sequence: F, robustness R, initial mask, per-packet input + explicit {pt,ft,rt}
    * flags, and the golden byte-padded, concatenated compressed output. */
  case class SeqCase(name: String, f: Int, robustnessT: Int, m0: Array[Boolean],
      packets: Seq[Array[Boolean]], flags: Seq[(Boolean, Boolean, Boolean)], expected: Array[Byte])

  def runSeq(c: SeqCase): Boolean = {
    import PocketExecSpec.*
    var params = CompressionParameters(
      t = 0, f = c.f, m0 = toSList(c.m0), robustnessT = c.robustnessT,
      pts = SList(c.flags(0)._1), ft = c.flags(0)._2, rt = c.flags(0)._3, ct = 0,
      itMinusOne = build0(c.f), btMinusOne = build0(c.f), mtMinusOne = toSList(c.m0), dts = SNil()
    )
    val out = new scala.collection.mutable.ArrayBuffer[Byte]()
    for (i <- c.packets.indices) {
      val (nextPt, nextFt, nextRt) = if (i + 1 < c.flags.length) c.flags(i + 1) else (false, false, false)
      val (packet, nextParams) = encodingStep(params, toSList(c.packets(i)), nextPt, nextFt, nextRt)
      out ++= packBitsPadded(toArray(packet))
      params = nextParams
    }
    val got = out.toArray
    val ok = bytesToHex(got) == bytesToHex(c.expected)
    if (ok) println(s"PASS  ${c.name}")
    else {
      println(s"FAIL  ${c.name}")
      println(s"  expected (${c.expected.length} bytes): ${bytesToHex(c.expected)}")
      println(s"  got      (${got.length} bytes): ${bytesToHex(got)}")
    }
    ok
  }

  def loadDifferentialCases(path: String): Seq[SeqCase] = {
    val root = Json.parse(new String(Files.readAllBytes(Paths.get(path)))).asInstanceOf[Map[String, Any]]
    root("cases").asInstanceOf[List[Any]].map { raw =>
      val c = raw.asInstanceOf[Map[String, Any]]
      val f = c("F").asInstanceOf[Double].toInt
      def bitsOfHex(hex: String) = bytesToBits(hexToBytes(hex)).take(f)
      val flags = c("flags").asInstanceOf[List[Any]].map(_.asInstanceOf[Map[String, Any]]).map { fl =>
        (fl("pt").asInstanceOf[Double] != 0.0, fl("ft").asInstanceOf[Double] != 0.0, fl("rt").asInstanceOf[Double] != 0.0)
      }
      SeqCase(
        name = c("id").asInstanceOf[String],
        f = f,
        robustnessT = c("R").asInstanceOf[Double].toInt,
        m0 = bitsOfHex(c("m0").asInstanceOf[String]),
        packets = c("packets").asInstanceOf[List[Any]].map(p => bitsOfHex(p.asInstanceOf[String])),
        flags = flags,
        expected = hexToBytes(c("compressed").asInstanceOf[String])
      )
    }
  }

  def main(args: Array[String]): Unit = {
    val cases: Seq[Case] = Seq(
      t0Case(
        name = "t=0, F=8, uncompressed init",
        f = 8,
        input = bitsOf("10100011"),
        expected = bitsOf("100000011011100011010100011")
      )
    )
    val seqCases = loadDifferentialCases("src/test/res/test-vectors/differential/cases.json")

    val ok = cases.map(run) ++ seqCases.map(runSeq)
    if (!ok.forall(identity)) sys.exit(1)
  }
}
