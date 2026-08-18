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
    val n = l.size.toInt
    val arr = Array.fill(n)(false)
    var cur = l
    var i = 0
    while (i < n) {
      arr(i) = cur.head
      cur = cur.tail
      i += 1
    }
    arr
  }

  def bits(l: List[Boolean]): String = bits(toArray(l))
  def bits(a: Array[Boolean]): String = a.map(b => if b then '1' else '0').mkString

  def main(args: Array[String]): Unit = {
    import PocketExecSpec.*

    val f = F.toInt
    val m0 = build0(F)

    val dst = DecompressorState(f, Array.fill(f)(false), Array.fill(f)(false), 0)
    Decompressor.decompressorInit(dst)

    // Runs one step through EncodingStep.outputVector, decodes the packet with the array-based
    // Decompressor, and returns (bt, mt) so the caller can build the next step's parameters.
    def step(label: String, params: CompressionParameters, it: List[Boolean]): (List[Boolean], List[Boolean]) = {
      val packet = EncodingStep.outputVector(params, it)
      val (bt, mt) = updateMaskAndBuild(params, it)

      val packetArr = toArray(packet)
      val recovered = Array.fill(f)(false)
      Decompressor.decompress(dst, packetArr, 0, recovered)

      println(s"--- t=${params.t} ($label) ---")
      println(s"input      : ${bits(it)}")
      println(s"packet     : ${bits(packetArr)}  (${packetArr.length} bits, vs $F raw)")
      println(s"recovered  : ${bits(recovered)}")
      println(s"round-trip : ${if bits(recovered) == bits(it) then "OK" else "MISMATCH"}")
      println()
      (bt, mt)
    }

    // t = 0: robustnessT = 0, so t <= robustnessT forces ft = rt = pts.head = true — the first
    // packet must always go out uncompressed so the decompressor can bootstrap.
    val it0 = List(true, false, true, false, false, false, true, true)
    val params0 = CompressionParameters(
      t = 0, f = F, m0 = m0, robustnessT = 0, pts = List(true), ft = true, rt = true, ct = 0,
      itMinusOne = build0(F), btMinusOne = build0(F), mtMinusOne = m0, dts = Nil()
    )
    val (bt0, mt0) = step("initial, uncompressed", params0, it0)

    // t = 1: one bit differs from it0 (index 6) -> compressed, predictive packet (ft = rt = false).
    val it1 = List(true, false, true, false, false, false, false, true)
    val params1 = CompressionParameters(
      t = 1, f = F, m0 = m0, robustnessT = 0, pts = List(false), ft = false, rt = false, ct = 0,
      itMinusOne = it0, btMinusOne = bt0, mtMinusOne = mt0, dts = Nil()
    )
    val (bt1, mt1) = step("one bit changed, compressed", params1, it1)

    // t = 2: same input again -> exercises the mask/build bookkeeping with no fresh input change.
    val it2 = it1
    val params2 = CompressionParameters(
      t = 2, f = F, m0 = m0, robustnessT = 0, pts = List(false), ft = false, rt = false, ct = 0,
      itMinusOne = it1, btMinusOne = bt1, mtMinusOne = mt1, dts = Nil()
    )
    step("repeated input, compressed", params2, it2)
  }
}
