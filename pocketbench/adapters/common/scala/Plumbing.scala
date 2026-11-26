/* Shared pocketbench adapter plumbing for the JVM adapters: subcommand dispatch, strict keyed-flag
 * parsing, the record container, the warmup + timed loop and the summary JSON. The Scala copy of
 * adapters/common/c, held to the same behaviour by tests/test_plumbing.py. */
package pocketbench.adapter

import java.io.IOException
import java.nio.file.{Files, Paths}

final case class Record(kind: Int, bits: Long, data: Array[Byte])

final class HookError(val message: String) extends Exception(message)

object HookError {
  def notRepresentable: HookError = HookError("input not representable by this codec's API")
  def code(n: Int): HookError = HookError(s"codec error $n")
}

enum Decoded {
  case Field(data: Array[Byte], bits: Long)
  case Failed
}

trait Codec {
  type Enc
  type Dec

  def buildProfile: String
  def limitations: String

  /** `f` is None when the input is shorter than its 4-byte header; `mask` is None when the input
    * holds no mask record. */
  def encCreate(f: Option[Long], mask: Option[Record]): Enc
  /** Returns the packet, ceil(bits / 8) bytes, and its length in bits. */
  def encPacket(state: Enc, flag: Int, field: Array[Byte]): (Array[Byte], Long)

  /** `f` is 0 when unknown; `records` is the whole input, for codecs that pre-scan it. */
  def decCreate(f: Long, records: IndexedSeq[Record]): Dec
  /** `availBits` is min(declaredBits, 8 * data.length), the bits actually present. A HookError stops
    * the pass at this record. */
  def decPacket(state: Dec, data: Array[Byte], declaredBits: Long, availBits: Long): Decoded
  def decLost(state: Dec): Unit
  def decTrailerF(state: Dec): Long
}

object Plumbing {
  private val StatusDecoded = 0x00
  private val StatusFailed = 0x01
  private val StatusLost = 0x02

  private final class UsageError(msg: String) extends Exception(msg)

  private final case class Args(in: String, out: String, warmup: Long, iterations: Option[Long])

  private final case class Pass(out: Array[Byte], recordsOut: Int, stoppedAt: Option[Int],
                                error: Option[String])

  private def parseArgs(sub: String, argv: Seq[String]): Args = {
    val seen = scala.collection.mutable.Map.empty[String, String]
    for (arg <- argv) {
      if (!arg.startsWith("--"))
        throw UsageError(s"$sub: unexpected positional argument \"$arg\"; the contract passes " +
          "keyed flags (--key=value)")
      val eq = arg.indexOf('=', 2)
      if (eq < 0) throw UsageError(s"$sub: flag \"$arg\" needs a value, written --key=value")
      val key = arg.substring(2, eq)
      val value = arg.substring(eq + 1)
      if (!Set("in", "out", "warmup", "iterations").contains(key))
        throw UsageError(s"$sub: unknown flag --$key; this adapter does not implement it, so the " +
          "harness and the adapter disagree about the contract")
      if (seen.contains(key)) throw UsageError(s"$sub: flag --$key given more than once")
      if ((key == "in" || key == "out") && value.isEmpty)
        throw UsageError(s"$sub: flag --$key needs a non-empty value")
      if ((key == "warmup" || key == "iterations") && value.toLongOption.forall(_ < 0))
        throw UsageError(s"$sub: flag --$key must be a non-negative integer, got \"$value\"")
      seen(key) = value
    }
    for (key <- Seq("in", "out") if !seen.contains(key))
      throw UsageError(s"$sub: missing required flag --$key")
    Args(seen("in"), seen("out"), seen.get("warmup").fold(0L)(_.toLong),
      seen.get("iterations").map(_.toLong))
  }

  private def be32(buf: Array[Byte], pos: Int): Long =
    ((buf(pos) & 0xffL) << 24) | ((buf(pos + 1) & 0xffL) << 16) | ((buf(pos + 2) & 0xffL) << 8) |
      (buf(pos + 3) & 0xffL)

  private def putBe32(out: java.io.ByteArrayOutputStream, v: Long): Unit = {
    out.write((v >>> 24).toInt & 0xff)
    out.write((v >>> 16).toInt & 0xff)
    out.write((v >>> 8).toInt & 0xff)
    out.write(v.toInt & 0xff)
  }

  private def bytesFor(bits: Long): Int = ((bits + 7) / 8).toInt

  private def parseContainer(sub: String, buf: Array[Byte]): (Option[Long], IndexedSeq[Record]) = {
    if (buf.length < 4) return (None, IndexedSeq.empty)
    val records = IndexedSeq.newBuilder[Record]
    var pos = 4
    while (pos < buf.length) {
      if (buf.length - pos < 5)
        throw UsageError(s"$sub: malformed record container: record header cut off at byte $pos")
      val kind = buf(pos) & 0xff
      val bits = be32(buf, pos + 1)
      pos += 5
      val len = math.min((bits + 7) / 8, (buf.length - pos).toLong).toInt
      records += Record(kind, bits, java.util.Arrays.copyOfRange(buf, pos, pos + len))
      pos += len
    }
    (Some(be32(buf, 0)), records.result())
  }

  private def putRecord(out: java.io.ByteArrayOutputStream, kind: Int, bits: Long,
                        data: Array[Byte]): Unit = {
    out.write(kind)
    putBe32(out, bits)
    out.write(java.util.Arrays.copyOf(data, bytesFor(bits)))
  }

  private def compressPass(c: Codec, f: Option[Long], records: IndexedSeq[Record]): Pass = {
    val out = java.io.ByteArrayOutputStream()
    putBe32(out, f.getOrElse(0L))
    val state =
      try c.encCreate(f, records.headOption)
      catch { case e: HookError => return Pass(out.toByteArray, 0, Some(0), Some(e.message)) }
    var done = 0
    var stop: Option[(Int, String)] = None
    var i = 1
    while (stop.isEmpty && i < records.length) {
      val r = records(i)
      try {
        val (packet, bits) = c.encPacket(state, r.kind, r.data)
        putRecord(out, 0, bits, packet)
        done += 1
      } catch { case e: HookError => stop = Some((i, e.message)) }
      i += 1
    }
    Pass(out.toByteArray, done, stop.map(_._1), stop.map(_._2))
  }

  private def decompressPass(c: Codec, f: Option[Long], records: IndexedSeq[Record]): Pass = {
    val body = java.io.ByteArrayOutputStream()
    val state =
      try c.decCreate(f.getOrElse(0L), records)
      catch { case e: HookError => return Pass(Array[Byte](0, 0, 0, 0), 0, Some(0), Some(e.message)) }
    var done = 0
    var stop: Option[(Int, String)] = None
    var i = 0
    while (stop.isEmpty && i < records.length) {
      val r = records(i)
      if ((r.kind & 1) != 0) {
        c.decLost(state)
        putRecord(body, StatusLost, 0, Array.emptyByteArray)
        done += 1
      } else {
        try {
          c.decPacket(state, r.data, r.bits, math.min(r.bits, 8L * r.data.length)) match {
            case Decoded.Field(data, bits) => putRecord(body, StatusDecoded, bits, data)
            case Decoded.Failed => putRecord(body, StatusFailed, 0, Array.emptyByteArray)
          }
          done += 1
        } catch { case e: HookError => stop = Some((i, e.message)) }
      }
      i += 1
    }
    val out = java.io.ByteArrayOutputStream()
    putBe32(out, c.decTrailerF(state))
    body.writeTo(out)
    Pass(out.toByteArray, done, stop.map(_._1), stop.map(_._2))
  }

  private def jsonStr(s: String): String = {
    val b = StringBuilder("\"")
    for (ch <- s) {
      if (ch == '"' || ch == '\\') b.append('\\').append(ch)
      else if (ch < 0x20) b.append(f"\\u${ch.toInt}%04x")
      else b.append(ch)
    }
    b.append('"').toString
  }

  private def verb(c: Codec, sub: String, argv: Seq[String]): Int = {
    val args = parseArgs(sub, argv)
    val input =
      try Files.readAllBytes(Paths.get(args.in))
      catch { case _: IOException => System.err.println(s"$sub: cannot read ${args.in}"); return 1 }
    val (f, records) = parseContainer(sub, input)
    val onePass: () => Pass =
      if (sub == "compress") () => compressPass(c, f, records) else () => decompressPass(c, f, records)

    for (_ <- 0L until args.warmup) onePass()
    val nanos = scala.collection.mutable.ArrayBuffer.empty[Long]
    val iterations = args.iterations.getOrElse(0L)
    var result: Pass = null
    if (iterations > 0) {
      var k = 0L
      while (k < iterations && (result == null || result.stoppedAt.isEmpty)) {
        val t0 = System.nanoTime()
        result = onePass()
        nanos += System.nanoTime() - t0
        k += 1
      }
    } else result = onePass()

    var rc = 0
    try Files.write(Paths.get(args.out), result.out)
    catch { case _: IOException => System.err.println(s"$sub: cannot write ${args.out}"); rc = 1 }
    val summary = StringBuilder(s"{\"records_in\":${records.length},\"records_out\":${result.recordsOut}")
    summary.append(",\"stopped_at\":").append(result.stoppedAt.fold("null")(_.toString))
    summary.append(",\"error\":").append(result.error.fold("null")(jsonStr))
    if (args.iterations.isDefined) summary.append(",\"nanos\":[").append(nanos.mkString(",")).append(']')
    println(summary.append('}'))
    rc
  }

  /** Returns the process exit code: 0 ok, 1 runtime error, 2 contract violation. */
  def run(c: Codec, argv: Array[String]): Int = {
    if (argv.isEmpty) {
      System.err.println("usage: adapter <capabilities|compress|decompress> [--key=value ...]")
      return 2
    }
    try argv(0) match {
      case "capabilities" =>
        println(s"{\"build_profile\":${jsonStr(c.buildProfile)},\"limitations\":${jsonStr(c.limitations)}}")
        0
      case sub @ ("compress" | "decompress") => verb(c, sub, argv.toSeq.tail)
      case other =>
        System.err.println(s"unknown subcommand: $other")
        2
    } catch {
      case e: UsageError =>
        System.err.println(e.getMessage)
        2
    }
  }

  def main(c: Codec, argv: Array[String]): Unit = {
    val rc = run(c, argv)
    System.out.flush()
    System.exit(rc)
  }
}
