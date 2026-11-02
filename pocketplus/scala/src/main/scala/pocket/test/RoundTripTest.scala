package pocket.test

import stainless.collection.{List as SList, Nil as SNil}
import stainless.lang.{Some as SSome}
import pocket.{Encoder, Decoder}

/** Round-trips randomized packet sequences through PocketExecSpec's Encoder.step and Decoder.step. */
object RoundTripTest {
  def toSList(a: Seq[Boolean]): SList[Boolean] = SList(a*)
  def bits(l: SList[Boolean]): String = l.toScala.map(b => if b then '1' else '0').mkString

  case class Failure(step: Int, f: BigInt, pt: Boolean, ft: Boolean, rt: Boolean, robustnessT: BigInt, expected: String, got: String)

  /** Encodes and decodes `steps` random input vectors of size f, drawn from `seed` along with M_0 and the
    * per-step parameters (R_t, p_t, f_t, r_t). Checks that every step recovers I_t and that the decoder stays
    * in sync with the encoder. */
  def runSequence(f: Int, steps: Int, seed: Long): Option[Failure] = {
    import pocket.PocketExecSpec.*
    val rnd = new scala.util.Random(seed)
    val F = BigInt(f)

    // Sparse random bits, so that masks stay partly predictable and the compressed branches are exercised.
    def randBits(): SList[Boolean] = toSList(IndexedSeq.fill(f)(rnd.nextInt(4) == 0))

    val m0 = randBits()
    var params = CompressionState(
      t = 0, f = F, m0 = m0, pts = SNil(),
      itMinusOne = build0(F), btMinusOne = build0(F), mtMinusOne = m0, dts = SNil()
    )
    // The decompressor starts from garbage: f_0 = r_0 = 1 must be enough to synchronize it.
    var st = DecompressionState(0, F, randBits(), randBits())
    var it = randBits()
    var failure: Option[Failure] = None
    var t = 0

    while (t < steps && failure.isEmpty) {
      val robustnessT = BigInt(rnd.nextInt(8))
      // t <= R_t must be sent with the full mask and uncompressed (bluebook 3.3.2); random flags afterwards.
      val forced = params.t <= robustnessT
      val pt = rnd.nextInt(5) == 0
      val ft = forced || rnd.nextInt(4) == 0
      val rt = forced || rnd.nextInt(5) == 0
      val (packet, nextParams) = Encoder.step(params, it, pt, ft, rt, robustnessT)
      val (res, nextSt) = Decoder.step(st, packet)

      res match {
        case SSome(got) if got == it && nextSt == decoderView(nextParams) => ()
        case SSome(got) => failure = Some(Failure(t, F, pt, ft, rt, robustnessT, bits(it), bits(got)))
        case _ => failure = Some(Failure(t, F, pt, ft, rt, robustnessT, bits(it), "None"))
      }

      params = nextParams
      st = nextSt
      // Flip a few bits of the previous input, so consecutive vectors are correlated as in real telemetry.
      it = toSList(it.toScala.map(b => if rnd.nextInt(6) == 0 then !b else b))
      t += 1
    }
    failure
  }

  /** A well formed packet with a trailing bit, or with its last bit cut off, must be rejected. */
  def malformedPacketsRejected(): Boolean = {
    import pocket.PocketExecSpec.*
    val F = BigInt(8)
    val m0 = build0(F)
    val params = CompressionState(
      t = 0, f = F, m0 = m0, pts = SNil(),
      itMinusOne = build0(F), btMinusOne = build0(F), mtMinusOne = m0, dts = SNil()
    )
    val (packet, _) = Encoder.step(params, SList.fill(F)(true), pt = true, ft = true, rt = true, robustnessT = 0)
    val st = DecompressionState(0, F, build0(F), build0(F))
    Decoder.step(st, packet)._1 == SSome(SList.fill(F)(true)) &&
      Decoder.step(st, packet ++ SList(false))._1.isEmpty &&
      Decoder.step(st, packet.take(packet.size - 1))._1.isEmpty
  }

  /** Optional first argument: path of the throughput CSV (default target/roundtrip-throughput.csv). */
  def main(args: Array[String]): Unit = {
    val runs = 25
    val steps = 5
    val seedRnd = new scala.util.Random(0L)
    val csvPath = java.nio.file.Paths.get(if args.nonEmpty then args(0) else "target/roundtrip-throughput.csv")
    val csv = new StringBuilder("run,f,steps,seed,seconds,kbit_per_s,passed\n")

    var ok = true
    for (run <- 0 until runs) {
      val seed = seedRnd.nextLong()
      val f = 1024 + seedRnd.nextInt(8) * 1024 // 1024, 2048, ..., 8192
      val name = s"run $run: F=$f, $steps steps, seed=$seed"
      // Purely indicative: wall-clock time of the whole run (encode + decode + random generation), no warm-up.
      val timeBefore = System.nanoTime()
      val result = runSequence(f, steps, seed)
      val timeAfter = System.nanoTime()
      val seconds = (timeAfter - timeBefore) / 1e9
      val kbitPerSecond = f.toLong * steps / seconds / 1e3
      val throughput = f"${steps / seconds}%.0f steps/s, $kbitPerSecond%.1f kbit/s"
      csv.append(f"$run,$f,$steps,$seed,$seconds%.9f,$kbitPerSecond%.3f,${result.isEmpty}\n")
      result match {
        case None => println(s"PASS  $name ($throughput)")
        case Some(fail) =>
          ok = false
          println(s"FAIL  $name ($throughput) at t=${fail.step} (pt=${fail.pt}, ft=${fail.ft}, rt=${fail.rt}, R_t=${fail.robustnessT})")
          println(s"  expected: ${fail.expected}")
          println(s"  got     : ${fail.got}")
      }
    }
    Option(csvPath.getParent).foreach(dir => java.nio.file.Files.createDirectories(dir))
    java.nio.file.Files.writeString(csvPath, csv.toString)
    println(s"Throughput data written to $csvPath")

    if (malformedPacketsRejected()) println("PASS  malformed packets rejected")
    else {
      ok = false
      println("FAIL  malformed packets rejected")
    }
    if (!ok) sys.exit(1)
  }
}
