package pocket

import stainless.collection.*

/** Runnable demo built directly on [[PocketExecSpec]] / [[EncodingStep]] — the Stainless-verified,
 *  `List[Boolean]`-based spec — rather than the GenC-oriented [[PocketPlus]] array implementation
 *  (which is only used here, on the decoding side, as an independent cross-check).
 *
 *  Compresses a couple of small hand-picked input vectors and prints every intermediate bit vector
 *  so the packet layout can be checked by eye against the blue book (section 5.3.3). Decompression
 *  goes through [[Decompressor]] (from PocketPlus.scala) to confirm the two independent
 *  implementations of the wire format agree.
 */
object Main {
  val F: BigInt = 8

  def toArray(l: List[Boolean]): Array[Boolean] = {
    l.toScala.toArray
  }

  def bits(l: List[Boolean]): String = bits(toArray(l))
  def bits(a: Array[Boolean]): String = a.map(b => if b then '1' else '0').mkString

  def main(args: Array[String]): Unit = {
    import PocketExecSpec.*

    val f = F.toInt
    val m0 = build0(F)

    val dst = DecompressorState(f, Array.fill(f)(false), Array.fill(f)(false), 0)
    Decompressor.decompressorInit(dst)

    // Encodes `it` under `params` via PocketExecSpec.encodingStep, decodes the packet with the
    // array-based Decompressor, and returns the next step's parameters.
    def step(label: String, params: CompressionParameters, it: List[Boolean],
        nextPt: Boolean, nextFt: Boolean, nextRt: Boolean): CompressionParameters = {
      val (packet, nextParams) = encodingStep(params, it, nextPt, nextFt, nextRt)

      val packetArr = toArray(packet)
      val recovered = Array.fill(f)(false)
      Decompressor.decompress(dst, packetArr, 0, recovered)

      println(s"--- t=${params.t} ($label) ---")
      println(s"input      : ${bits(it)}")
      println(s"packet     : ${bits(packetArr)}  (${packetArr.length} bits, vs $F raw)")
      println(s"recovered  : ${bits(recovered)}")
      println(s"round-trip : ${if bits(recovered) == bits(it) then "OK" else "MISMATCH"}")
      println()
      nextParams
    }

    // t = 0: robustnessT = 0, so t <= robustnessT forces ft = rt = pts.head = true — the first
    // packet must always go out uncompressed so the decompressor can bootstrap.
    val it0 = List(true, false, true, false, false, false, true, true)
    val params0 = CompressionParameters(
      t = 0, f = F, m0 = m0, robustnessT = 0, pts = List(true), ft = true, rt = true, ct = 0,
      itMinusOne = build0(F), btMinusOne = build0(F), mtMinusOne = m0, dts = Nil()
    )

    // t = 1: one bit differs from it0 (index 6) -> compressed, predictive packet (ft = rt = false).
    val it1 = List(true, false, true, false, false, false, false, true)
    val params1 = step("initial, uncompressed", params0, it0, nextPt = false, nextFt = false, nextRt = false)

    // t = 2: same input again -> exercises the mask/build bookkeeping with no fresh input change.
    val params2 = step("one bit changed, compressed", params1, it1, nextPt = false, nextFt = false, nextRt = false)

    step("repeated input, compressed", params2, it1, nextPt = false, nextFt = false, nextRt = false)
  }
}
