/* pocketplus-scala pocketbench adapter: per-packet hooks over Encoder.step and Decoder.step of
 * pocketplus's functional specification (PocketExecSpec.scala), run on the JVM. */
package pocketplusscala

import pocketbench.adapter.{Codec, Decoded, HookError, Plumbing, Record}
import pocket.{BasicEncodingFunctions, Decoder, Encoder}
import pocket.PocketExecSpec.{CompressionState, DecompressionState, build0}
import stainless.collection.{Cons, List as SList, Nil as SNil}
import stainless.lang.{Some as SSome}

object Adapter extends Codec {
  final class Enc(val f: Int, var params: CompressionState)
  final class Dec(val f: Long, var state: Option[DecompressionState])

  val buildProfile = "Stainless dotc to JVM bytecode, List[Boolean] / BigInt specification"
  val limitations =
    "functional specification (List[Boolean], BigInt), far slower than the codecs, so for " +
      "validation, not timing; packets up to 65535 bits; Encoder.step's preconditions (F, R <= 7, " +
      "f_t = r_t = 1 while t <= R_t) are not representable; no packet-loss handling and no " +
      "decoding with F unknown (every packet reports failed)"

  private val MaxF: Long = BasicEncodingFunctions.MAX_F.toLong
  private val MaxR: Int = pocket.PocketExecSpec.MAX_R.toInt

  private def stride(f: Long): Int = ((f + 7) / 8).toInt

  /** Bit i is bit 7 - i % 8 of byte i / 8, as in the record container. */
  private def toBits(bytes: Array[Byte], n: Long): SList[Boolean] = {
    var l: SList[Boolean] = SNil()
    var i = n - 1
    while (i >= 0) {
      l = Cons(((bytes((i >> 3).toInt) >> (7 - (i & 7).toInt)) & 1) == 1, l)
      i -= 1
    }
    l
  }

  private def toBytes(bits: SList[Boolean]): (Array[Byte], Long) = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var cur = 0
    var n = 0L
    var l = bits
    while (!l.isEmpty) {
      if (l.head) cur |= 0x80 >> (n & 7).toInt
      n += 1
      if ((n & 7) == 0) { out += cur.toByte; cur = 0 }
      l = l.tail
    }
    if ((n & 7) != 0) out += cur.toByte
    (out.toArray, n)
  }

  def encCreate(f: Option[Long], mask: Option[Record]): Enc = (f, mask) match {
    case (Some(fv), Some(m)) if fv >= 1 && fv <= MaxF && m.data.length == stride(fv) =>
      val m0 = toBits(m.data, fv)
      Enc(fv.toInt, CompressionState(0, fv, m0, SNil(), build0(fv), build0(fv), m0, SNil()))
    case _ => throw HookError.notRepresentable
  }

  def encPacket(state: Enc, flag: Int, field: Array[Byte]): (Array[Byte], Long) = {
    val robustness = flag & 0x0f
    val ft = (flag & 0x40) != 0
    val pt = (flag & 0x20) != 0
    val rt = (flag & 0x10) != 0
    if (field.length != stride(state.f) || robustness > MaxR ||
        !(state.params.t > robustness || (ft && rt)))
      throw HookError.notRepresentable
    val (packet, next) =
      Encoder.step(state.params, toBits(field, state.f), pt, ft, rt, BigInt(robustness))
    state.params = next
    toBytes(packet)
  }

  def decCreate(f: Long, records: IndexedSeq[Record]): Dec =
    if (f == 0) Dec(0, None)
    else if (f > MaxF) throw HookError.notRepresentable
    else Dec(f, Some(DecompressionState(0, f, build0(f), build0(f))))

  def decPacket(state: Dec, data: Array[Byte], declaredBits: Long, availBits: Long): Decoded =
    state.state match {
      case None => Decoded.Failed
      case Some(st) =>
        Decoder.step(st, toBits(data, availBits)) match {
          case (SSome(it), next) =>
            state.state = Some(next)
            val (bytes, bits) = toBytes(it)
            Decoded.Field(bytes, bits)
          case _ => Decoded.Failed
        }
    }

  def decLost(state: Dec): Unit = ()

  def decTrailerF(state: Dec): Long = state.f

  def main(args: Array[String]): Unit = Plumbing.main(this, args)
}
