package datastructure.arrays

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*
import stainless.math.Unsigned.*
import datastructure.Utils


/**
  * Represents a bit array, underlined by an array of unsigned bytes (`uint8_t` in the generated C). The specification is given by a list
  * of booleans.
  * The case class carries the size of the array as a ghost variable, which is equal to the length of the list, as it might not be a multiple of 8. If the size is not a multiple of 8, the last bits of the last byte are ignored.
  * Bits are packed MSB first: bit i of the list is bit 7 - i % 8 of byte i / 8, i.e., the one selected by the mask 0x80 >>> (i % 8).
  * The executable code computes these indexes as i >>> 3 and i & 7: divisions only appear in ghost code.
  *
  * @param data
  * @param toList
  * @param size
  */
case class BitArray(private val data: Array[UByte], private var _isize: Int, @ghost private var toList: List[Boolean], @ghost private var _size: BigInt) {

  // Links the content of the list and the content of the array, and _size with the actual size of the array.
  @ghost @pure def valid: Boolean = {
    data.length <= Int.MaxValue / 8 && 
    0 <= _size && _size <= 8 * BigInt(data.length) &&
    _size == toList.size && 
    _size <= BigInt(Int.MaxValue) &&
    _isize == _size.toInt &&
    BitArray.sameArrayListContent(0, toList, data, _size)
  }

  @ghost @pure def getList: List[Boolean] = toList

  /** What `valid` demands of the executable fields: a size in bits that fits in the bytes. Unlike `valid`, it can be
    * checked at run time, and it is all that code building a BitArray outside of Scala has to guarantee. */
  @pure def wellSized: Boolean = {
    data.length <= (Int.MaxValue >>> 3) && 0 <= _isize && _isize <= 8 * data.length
  }

  /** The size in bits as stored, whether or not the array is valid; it is `size` when the array is valid. */
  @pure def storedSize: Int = _isize

  /** Makes the ghost list the one the bytes hold. It assumes nothing of the ghost state, so this is how a BitArray
    * built outside of Scala, of which only `wellSized` can be checked, becomes valid. */
  @ghost def establishValid(): Unit = {
    require(wellSized)
    BitArray.lemmaWellSizedBounds(_isize, data.length)
    _size = BigInt(_isize)
    BitArray.lemmaBitsFromSameContent(0, data, _size)
    toList = BitArray.bitsFrom(data, 0, _size)
    assert(data.length <= Int.MaxValue / 8)
    assert(0 <= _size && _size <= 8 * BigInt(data.length))
    assert(_size == toList.size)
    assert(_size <= BigInt(Int.MaxValue))
    assert(_isize == _size.toInt)
    assert(BitArray.sameArrayListContent(0, toList, data, _size))
    assert(valid)
  }.ensuring(_ => valid && size == old(this).storedSize && storedSize == old(this).storedSize)

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
    val byte = data(i >>> 3)
    (byte & (0x80.toUByte >>> (i & 7).toUByte)) != 0.toUByte
  }.ensuring(res => valid && res == toList(BigInt(i)))

  @ghost def apply(ii: BigInt): Boolean = {
    require(valid)
    require(ii >= 0 && ii < _size)
    BitArray.lemmaSameArrayListContentImpliesSameApply(0, toList, data, _size, ii)
    val byte = data((ii / 8).toInt)
    (byte & (0x80.toUByte >>> (ii % 8).toInt.toUByte)) != 0.toUByte
  }.ensuring(res => valid && res == toList(ii))

  def update(i: Int, v: Boolean): Unit = {
    require(valid)
    require(i >= 0 && i < size)
    ghostExpr({
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(BigInt(i), data.length)
    })
    val byteIndex = i >>> 3
    val mask = 0x80.toUByte >>> (i & 7).toUByte
    val byte = data(byteIndex)
    val newByte = if (v) byte | mask else byte & ~mask
    ghostExpr(BitArray.lemmaSameArrayListContentPreservedByUpdated(0, toList, data, _size, BigInt(i), v, newByte))

    ghostExpr({toList = toList.updated(BigInt(i), v)})
    data(byteIndex) = newByte
  }.ensuring(_ => valid && old(this).toList.updated(BigInt(i), v) == toList)

  @opaque def update(ii: BigInt, v: Boolean): Unit = {
    require(valid)
    require(ii >= 0 && ii < _size)
    val i = ii.toInt
    ghostExpr({
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      Utils.lemmaConversionBackForth(ii)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(ii, data.length)
    })
    val byteIndex = i >>> 3
    val mask = 0x80.toUByte >>> (i & 7).toUByte
    val byte = data(byteIndex)
    val newByte = if (v) byte | mask else byte & ~mask
    ghostExpr(BitArray.lemmaSameArrayListContentPreservedByUpdated(0, toList, data, _size, ii, v, newByte))

    ghostExpr({toList = toList.updated(ii, v)})
    data(byteIndex) = newByte
  }.ensuring(_ => valid && old(this).toList.updated(ii, v) == toList)

  /** Zeroes the bytes from index k on. The ghost list is left as it is, so the array is no longer valid: `clear`
    * sets it again. GenC compiles the tail call to a jump, so there is no recursion in the generated C. */
  private def clearFrom(k: Int): Unit = {
    require(0 <= k && k <= data.length)
    decreases(data.length - k)
    if (k < data.length) {
      data(k) = 0.toUByte
      clearFrom(k + 1)
    }
  }.ensuring(_ => data == BitArray.zeroedFrom(old(this).data, k) && _isize == old(this)._isize && _size == old(this)._size && toList == old(this).toList)

  /** Sets every bit to zero, a byte at a time. The bits of the last byte past the size are zeroed as well. */
  def clear(): Unit = {
    require(valid)
    @ghost val data0 = snapshot(data)
    clearFrom(0)
    ghostExpr({
      BitArray.lemmaBitsFromZeroed(data0, 0, _size)
      BitArray.lemmaBitsFromSameContent(0, data, _size)
      toList = BitArray.bitsFrom(data, 0, _size)
    })
  }.ensuring(_ => valid && size == old(this).size && _size == old(this)._size && toList == List.fill(_size)(false))
}

object BitArray {
  @ghost @pure def bitIndex(i: BigInt): (BigInt, BigInt) = {
    i / 8 -> i % 8
  }
  @ghost @pure @opaque def sameArrayListContent(from: BigInt, list: List[Boolean], array: Array[UByte], _size: BigInt): Boolean = {
    require(_size == list.size)
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    decreases(_size - from)
    if from == _size then true
    else {
      lemmaBitIndexInBounds(from, array.length)
      val (byteIndex, bIndex) = bitIndex(from)
      val byte = array(byteIndex.toInt)
      val bit = (byte & (0x80.toUByte >>> bIndex.toInt.toUByte)) != 0.toUByte
      bit == list(from) && sameArrayListContent(from + 1, list, array, _size)
    }
  }

  /** The Int conditions of `wellSized`, on BigInt as `valid` states them. */
  @ghost @opaque @inlineOnce
  def lemmaWellSizedBounds(isize: Int, length: Int): Unit = {
    require(0 <= length && length <= (Int.MaxValue >>> 3) && 0 <= isize && isize <= 8 * length)
    Utils.additionIntPreservedByToBigInt(4 * length, 4 * length)
    Utils.additionIntPreservedByToBigInt(2 * length, 2 * length)
    Utils.additionIntPreservedByToBigInt(length, length)
    Utils.compareIntPreservedByToBigInt(isize, 8 * length)
    Utils.compareIntPreservedByToBigInt(isize, 0)
    Utils.compareIntPreservedByToBigInt(length, Int.MaxValue / 8)
    Utils.lemmaConversionBackForth(isize)
  }.ensuring(_ =>
    length <= Int.MaxValue / 8 &&
    0 <= BigInt(isize) && BigInt(isize) <= 8 * BigInt(length) && BigInt(isize) <= BigInt(Int.MaxValue) && BigInt(isize).toInt == isize
  )

  /** Bit i of the array, MSB first. */
  @ghost @pure def bitAt(array: Array[UByte], i: BigInt): Boolean = {
    require(0 <= i && i < 8 * BigInt(array.length))
    lemmaBitIndexInBounds(i, array.length)
    (array((i / 8).toInt) & (0x80.toUByte >>> (i % 8).toInt.toUByte)) != 0.toUByte
  }

  /** The bits of the array from index `from` to `_size`, as a list. */
  @ghost @pure def bitsFrom(array: Array[UByte], from: BigInt, _size: BigInt): List[Boolean] = {
    require(0 <= from && from <= _size && _size <= 8 * BigInt(array.length))
    decreases(_size - from)
    if from == _size then Nil[Boolean]()
    else Cons(bitAt(array, from), bitsFrom(array, from + 1, _size))
  }.ensuring(res => res.size == _size - from)

  @ghost @opaque @inlineOnce
  def lemmaBitsFromApply(array: Array[UByte], from: BigInt, _size: BigInt, i: BigInt): Unit = {
    require(0 <= from && from <= i && i < _size && _size <= 8 * BigInt(array.length))
    decreases(i - from)
    if (from < i) {
      lemmaBitsFromApply(array, from + 1, _size, i)
    }
  }.ensuring(_ => bitsFrom(array, from, _size)(i - from) == bitAt(array, i))

  /** The array holds the list of its own bits. */
  @ghost @opaque @inlineOnce
  def lemmaBitsFromSameContent(from: BigInt, array: Array[UByte], _size: BigInt): Unit = {
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    decreases(_size - from)
    val list = bitsFrom(array, 0, _size)
    if (from < _size) {
      lemmaBitIndexInBounds(from, array.length)
      lemmaBitsFromApply(array, 0, _size, from)
      lemmaBitsFromSameContent(from + 1, array, _size)
    }
    unfold(sameArrayListContent(from, list, array, _size))
  }.ensuring(_ => sameArrayListContent(from, bitsFrom(array, 0, _size), array, _size))

  /** The array with the bytes from index k on set to zero: the functional model of `clearFrom`, whose postcondition
    * it states. The base case returns a copy because a recursive function may not return its array parameter itself. */
  @ghost @pure def zeroedFrom(array: Array[UByte], k: Int): Array[UByte] = {
    require(0 <= k && k <= array.length)
    decreases(array.length - k)
    if (k == array.length) freshCopy(array) else zeroedFrom(array.updated(k, 0.toUByte), k + 1)
  }.ensuring(res => res.length == array.length)

  /** In the array zeroed from index k on, a byte is zero from k on and unchanged before. */
  @ghost @opaque @inlineOnce
  def lemmaZeroedFromApply(array: Array[UByte], k: Int, j: Int): Unit = {
    require(0 <= k && k <= array.length && 0 <= j && j < array.length)
    decreases(array.length - k)
    if (k < array.length) {
      lemmaZeroedFromApply(array.updated(k, 0.toUByte), k + 1, j)
    }
  }.ensuring(_ => zeroedFrom(array, k)(j) == (if (j >= k) 0.toUByte else array(j)))

  /** The bits of an array zeroed from its first byte are all false. */
  @ghost @opaque @inlineOnce
  def lemmaBitsFromZeroed(array: Array[UByte], from: BigInt, _size: BigInt): Unit = {
    require(0 <= from && from <= _size && _size <= 8 * BigInt(array.length))
    decreases(_size - from)
    if (from < _size) {
      lemmaBitIndexInBounds(from, array.length)
      lemmaZeroedFromApply(array, 0, (from / 8).toInt)
      lemmaBitsFromZeroed(array, from + 1, _size)
    }
  }.ensuring(_ => bitsFrom(zeroedFrom(array, 0), from, _size) == List.fill(_size - from)(false))

  /** Setting (v) or clearing (!v) bit b of a byte, counting from the MSB, changes bit b only. */
  @ghost @opaque @inlineOnce
  def lemmaSetBit(byte: UByte, b: Int, c: Int, v: Boolean): Unit = {
    require(0 <= b && b < 8 && 0 <= c && c < 8)
  }.ensuring(_ =>
    (((if (v) byte | (0x80.toUByte >>> b.toUByte) else byte & ~(0x80.toUByte >>> b.toUByte)) & (0x80.toUByte >>> c.toUByte)) != 0.toUByte) == (if (b == c) v else (byte & (0x80.toUByte >>> c.toUByte)) != 0.toUByte)
  )

  @ghost @pure @inline def bitIndexInBounds(i: BigInt, length: Int): Boolean = {
    0 <= i / 8 && i / 8 < BigInt(length) && 0 <= (i / 8).toInt && (i / 8).toInt < length &&
    0 <= i % 8 && i % 8 < 8 && 0 <= (i % 8).toInt && (i % 8).toInt < 8
  }

  /** For a non-negative index, the Int byte and bit index computations agree with the BigInt ones, and the shift
    * and mask that the executable code uses instead of a division (R-PERF-03) compute the same indexes. */
  @ghost @opaque @inlineOnce
  def lemmaIntBitIndex(i: Int): Unit = {
    require(i >= 0)
  }.ensuring(_ =>
    BigInt(i / 8) == BigInt(i) / 8 && BigInt(i % 8) == BigInt(i) % 8 && (BigInt(i) / 8).toInt == i / 8 && (BigInt(i) % 8).toInt == i % 8 &&
    (i >>> 3) == i / 8 && (i & 7) == i % 8
  )

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
  def lemmaSameArrayListContentImpliesSameApply(from: BigInt, list: List[Boolean], array: Array[UByte], _size: BigInt, i: BigInt): Unit = {
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
  }.ensuring(_ => bitIndexInBounds(i, array.length) && ((array((i / 8).toInt) & (0x80.toUByte >>> (i % 8).toInt.toUByte)) != 0.toUByte) == list(i))

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
  def lemmaUpdatedBit(array: Array[UByte], i: BigInt, v: Boolean, newByte: UByte, j: BigInt): Unit = {
    require(bitIndexInBounds(i, array.length) && bitIndexInBounds(j, array.length))
    require(newByte == (if (v) array((i / 8).toInt) | (0x80.toUByte >>> (i % 8).toInt.toUByte) else array((i / 8).toInt) & ~(0x80.toUByte >>> (i % 8).toInt.toUByte)))
    lemmaSetBit(array((i / 8).toInt), (i % 8).toInt, (j % 8).toInt, v)
    Utils.compareBigIntPreservedByToInt(j / 8, i / 8)
    Utils.compareBigIntPreservedByToInt(j % 8, i % 8)
    assert((j == i) == (j / 8 == i / 8 && j % 8 == i % 8))
  }.ensuring(_ =>
    ((array.updated((i / 8).toInt, newByte)((j / 8).toInt) & (0x80.toUByte >>> (j % 8).toInt.toUByte)) != 0.toUByte) ==
      (if (j == i) v else (array((j / 8).toInt) & (0x80.toUByte >>> (j % 8).toInt.toUByte)) != 0.toUByte)
  )

  /** Setting bit i of the array to v in the byte holding it, i.e., replacing that byte by newByte, updates the list at i. */
  @ghost @opaque @inlineOnce
  def lemmaSameArrayListContentPreservedByUpdated(from: BigInt, list: List[Boolean], array: Array[UByte], _size: BigInt, i: BigInt, v: Boolean, newByte: UByte): Unit = {
    require(_size == list.size)
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    require(sameArrayListContent(from, list, array, _size))
    require(0 <= i && i < _size && bitIndexInBounds(i, array.length))
    require(newByte == (if (v) array((i / 8).toInt) | (0x80.toUByte >>> (i % 8).toInt.toUByte) else array((i / 8).toInt) & ~(0x80.toUByte >>> (i % 8).toInt.toUByte)))
    decreases(_size - from)
    assert(list.updated(i, v).size == _size)
    if (from < _size) {
      lemmaBitIndexInBounds(from, array.length)
      lemmaUpdatedBit(array, i, v, newByte, from)
      lemmaUpdatedApply(list, i, v, from)
      unfold(sameArrayListContent(from, list, array, _size))
      lemmaSameArrayListContentPreservedByUpdated(from + 1, list, array, _size, i, v, newByte)
      val newArray = array.updated((i / 8).toInt, newByte)
      assert(((newArray((from / 8).toInt) & (0x80.toUByte >>> (from % 8).toInt.toUByte)) != 0.toUByte) == list.updated(i, v)(from))
      unfold(sameArrayListContent(from, list.updated(i, v), newArray, _size))
    } else {
      unfold(sameArrayListContent(from, list.updated(i, v), array.updated((i / 8).toInt, newByte), _size))
    }
  }.ensuring(_ => sameArrayListContent(from, list.updated(i, v), array.updated((i / 8).toInt, newByte), _size))
}
