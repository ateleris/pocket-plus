/* The shared Scala plumbing's test double: an identity "codec" with the same behaviour as
 * adapters/mock/adapter.py, so tests/test_plumbing.py holds every plumbing copy to one contract. */
package scalaidentity

import pocketbench.adapter.{Codec, Decoded, HookError, Plumbing, Record}

object Adapter extends Codec {
  type Enc = Unit
  final class Dec(val f: Long)

  val buildProfile = ""
  val limitations = "identity test double: every packet is its own field"

  def encCreate(f: Option[Long], mask: Option[Record]): Unit =
    if (f.isEmpty) throw HookError.notRepresentable

  def encPacket(state: Unit, flag: Int, field: Array[Byte]): (Array[Byte], Long) =
    if ((flag & 0x80) != 0) throw HookError.code(7)
    else (field, 8L * field.length)

  def decCreate(f: Long, records: IndexedSeq[Record]): Dec = Dec(f)

  def decPacket(state: Dec, data: Array[Byte], declaredBits: Long, availBits: Long): Decoded =
    if (declaredBits == 13) throw HookError.code(-9)
    else if (state.f == 0) Decoded.Failed
    else Decoded.Field(data, 8L * data.length)

  def decLost(state: Dec): Unit = ()

  def decTrailerF(state: Dec): Long = state.f

  def main(args: Array[String]): Unit = Plumbing.main(this, args)
}
