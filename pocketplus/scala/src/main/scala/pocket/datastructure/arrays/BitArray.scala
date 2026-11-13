package datastructure.arrays

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*
import datastructure.Utils


/**
  * Represents a bit array, underlined by an array of bytes. The specification is given by a list
  * of booleans.
  * The case class carries the size of the array as a ghost variable, which is equal to the length of the list, as it might not be a multiple of 8. If the size is not a multiple of 8, the last bits of the last byte are ignored.
  *
  * @param data
  * @param toList
  * @param size
  */
case class BitArray(private val data: Array[Byte], private var _isize: Int, @ghost private var toList: List[Boolean], @ghost private var _size: BigInt) {

  // Links the content of the list and the content of the array, and _size with the actual size of the array.
  @ghost @pure def valid: Boolean = {
    0 <= _size && _size <= 8 * BigInt(data.length) &&
    _size == toList.size && 
    _size <= BigInt(Int.MaxValue) &&
    _isize == _size.toInt &&
    BitArray.sameArrayListContent(0, toList, data, _size)
  }

 

  @ghost @pure def getList: List[Boolean] = toList

  @pure @ghost def sizeBigInt: BigInt = {
    require(valid)
    _size
  }.ensuring(res =>
    valid && res == toList.size && res >= 0 && res <= 8 * BigInt(data.length)
  )

  @pure def size: Int = {
    require(valid)
    ghostExpr(Utils.lemmaToIntBigIntConversionBothDirections(_size, 0))
    _isize
  }.ensuring(res =>
    valid && res == toList.size.toInt && res >= 0
  )

  def apply(i: Int): Boolean = {
    require(valid)
    require(i >= 0 && i < size)
    ghostExpr({
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(BigInt(i), data.length)
      BitArray.lemmaSameArrayListContentImpliesSameApply(0, toList, data, _size, BigInt(i))
    })
    val byte = data(i / 8)
    (byte & (1 << (i % 8))) != 0
  }.ensuring(res => valid && res == toList(BigInt(i)))

  def update(i: Int, v: Boolean): Unit = {
    require(valid)
    require(i >= 0 && i < size)
    ghostExpr({
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(BigInt(i), data.length)
    })
    val byteIndex = i / 8
    val mask = 1 << (i % 8)
    val byte = data(byteIndex)
    val newByte = BitArray.lowByte(if (v) byte | mask else byte & ~mask)
    ghostExpr(BitArray.lemmaSameArrayListContentPreservedByUpdated(0, toList, data, _size, BigInt(i), v, newByte))

    toList = toList.updated(BigInt(i), v)
    data(byteIndex) = newByte
  }.ensuring(_ => valid && old(this).toList.updated(BigInt(i), v) == toList)
}

object BitArray {
  @ghost @pure def bitIndex(i: BigInt): (BigInt, BigInt) = {
    i / 8 -> i % 8
  }
  @ghost @pure @opaque def sameArrayListContent(from: BigInt, list: List[Boolean], array: Array[Byte], _size: BigInt): Boolean = {
    require(_size == list.size)
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    decreases(_size - from)
    if from == _size then true
    else {
      lemmaBitIndexInBounds(from, array.length)
      val (byteIndex, bIndex) = bitIndex(from)
      val byte = array(byteIndex.toInt)
      val bit = (byte & (1 << bIndex.toInt)) != 0
      bit == list(from) && sameArrayListContent(from + 1, list, array, _size)
    }
  }

  /** Narrows x to a byte with the same 8 low bits, i.e., x.toByte without overflowing. */
  @pure @opaque def lowByte(x: Int): Byte = {
    val low = x & 0xff
    (if (low >= 128) low - 256 else low).toByte
  }

  @ghost @opaque @inlineOnce
  def lemmaLowByteBit(x: Int, c: Int): Unit = {
    require(0 <= c && c < 8)
    unfold(lowByte(x))
  }.ensuring(_ => ((lowByte(x) & (1 << c)) != 0) == ((x & (1 << c)) != 0))

  /** Setting (v) or clearing (!v) bit b of a byte changes bit b only. */
  @ghost @opaque @inlineOnce
  def lemmaSetBit(byte: Byte, b: Int, c: Int, v: Boolean): Unit = {
    require(0 <= b && b < 8 && 0 <= c && c < 8)
  }.ensuring(_ =>
    (((if (v) byte | (1 << b) else byte & ~(1 << b)) & (1 << c)) != 0) == (if (b == c) v else (byte & (1 << c)) != 0)
  )

  @ghost @pure @inline def bitIndexInBounds(i: BigInt, length: Int): Boolean = {
    0 <= i / 8 && i / 8 < BigInt(length) && 0 <= (i / 8).toInt && (i / 8).toInt < length &&
    0 <= i % 8 && i % 8 < 8 && 0 <= (i % 8).toInt && (i % 8).toInt < 8
  }

  /** For a non-negative index, the Int byte and bit index computations agree with the BigInt ones. */
  @ghost @opaque @inlineOnce
  def lemmaIntBitIndex(i: Int): Unit = {
    require(i >= 0)
  }.ensuring(_ => BigInt(i / 8) == BigInt(i) / 8 && BigInt(i % 8) == BigInt(i) % 8 && (BigInt(i) / 8).toInt == i / 8 && (BigInt(i) % 8).toInt == i % 8)

  @ghost @opaque @inlineOnce
  def lemmaBitIndexInBounds(i: BigInt, length: Int): Unit = {
    require(0 <= i && i < 8 * BigInt(length))
    assert(0 <= i / 8 && i / 8 < BigInt(length))
    Utils.lemmaToIntBigIntConversionBothDirections(i / 8, length)
    Utils.lemmaToIntBigIntConversionBothDirections(i / 8, 0)
    Utils.lemmaToIntBigIntConversionBothDirections(i % 8, 8)
    Utils.lemmaToIntBigIntConversionBothDirections(i % 8, 0)
  }.ensuring(_ => bitIndexInBounds(i, length))

  @ghost @opaque @inlineOnce
  def lemmaSameArrayListContentImpliesSameApply(from: BigInt, list: List[Boolean], array: Array[Byte], _size: BigInt, i: BigInt): Unit = {
    require(_size == list.size)
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    require(sameArrayListContent(from, list, array, _size))
    require(from <= i && i < _size)
    decreases(_size - from)
    lemmaBitIndexInBounds(i, array.length)
    unfold(sameArrayListContent(from, list, array, _size))
    if (from < i) {
      lemmaSameArrayListContentImpliesSameApply(from + 1, list, array, _size, i)
    }
  }.ensuring(_ => bitIndexInBounds(i, array.length) && ((array((i / 8).toInt) & (1 << (i % 8).toInt)) != 0) == list(i))

  @ghost @opaque @inlineOnce
  def lemmaUpdatedApply[T](list: List[T], i: BigInt, v: T, j: BigInt): Unit = {
    require(0 <= i && i < list.size)
    require(0 <= j && j < list.size)
    decreases(list)
    list match {
      case Cons(h, tail) if i != 0 && j != 0 => lemmaUpdatedApply(tail, i - 1, v, j - 1)
      case _ => ()
    }
  }.ensuring(_ => list.updated(i, v)(j) == (if (i == j) v else list(j)))

  /** Replacing the byte holding bit i by newByte, i.e., that byte with bit i set to v, changes bit i only. */
  @ghost @opaque @inlineOnce
  def lemmaUpdatedBit(array: Array[Byte], i: BigInt, v: Boolean, newByte: Byte, j: BigInt): Unit = {
    require(bitIndexInBounds(i, array.length) && bitIndexInBounds(j, array.length))
    require(newByte == lowByte(if (v) array((i / 8).toInt) | (1 << (i % 8).toInt) else array((i / 8).toInt) & ~(1 << (i % 8).toInt)))
    val byte = array((i / 8).toInt)
    val mask = 1 << (i % 8).toInt
    lemmaLowByteBit(if (v) byte | mask else byte & ~mask, (j % 8).toInt)
    lemmaSetBit(byte, (i % 8).toInt, (j % 8).toInt, v)
    Utils.compareBigIntPreservedByToInt(j / 8, i / 8)
    Utils.compareBigIntPreservedByToInt(j % 8, i % 8)
    assert((j == i) == (j / 8 == i / 8 && j % 8 == i % 8))
  }.ensuring(_ =>
    ((array.updated((i / 8).toInt, newByte)((j / 8).toInt) & (1 << (j % 8).toInt)) != 0) ==
      (if (j == i) v else (array((j / 8).toInt) & (1 << (j % 8).toInt)) != 0)
  )

  /** Setting bit i of the array to v in the byte holding it, i.e., replacing that byte by newByte, updates the list at i. */
  @ghost @opaque @inlineOnce
  def lemmaSameArrayListContentPreservedByUpdated(from: BigInt, list: List[Boolean], array: Array[Byte], _size: BigInt, i: BigInt, v: Boolean, newByte: Byte): Unit = {
    require(_size == list.size)
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    require(sameArrayListContent(from, list, array, _size))
    require(0 <= i && i < _size && bitIndexInBounds(i, array.length))
    require(newByte == lowByte(if (v) array((i / 8).toInt) | (1 << (i % 8).toInt) else array((i / 8).toInt) & ~(1 << (i % 8).toInt)))
    decreases(_size - from)
    assert(list.updated(i, v).size == _size)
    if (from < _size) {
      lemmaBitIndexInBounds(from, array.length)
      lemmaUpdatedBit(array, i, v, newByte, from)
      lemmaUpdatedApply(list, i, v, from)
      unfold(sameArrayListContent(from, list, array, _size))
      lemmaSameArrayListContentPreservedByUpdated(from + 1, list, array, _size, i, v, newByte)
      val newArray = array.updated((i / 8).toInt, newByte)
      assert(((newArray((from / 8).toInt) & (1 << (from % 8).toInt)) != 0) == list.updated(i, v)(from))
      unfold(sameArrayListContent(from, list.updated(i, v), newArray, _size))
    } else {
      unfold(sameArrayListContent(from, list.updated(i, v), array.updated((i / 8).toInt, newByte), _size))
    }
  }.ensuring(_ => sameArrayListContent(from, list.updated(i, v), array.updated((i / 8).toInt, newByte), _size))
}
