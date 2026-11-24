package datastructure.arrays

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*
import stainless.math.Unsigned.*
import datastructure.Utils


/**
  * Represents a bit array, underlined by an array of unsigned bytes (`uint8_t` in the generated C). The specification is given by a list
  * of booleans, `toList`, which is a ghost projection of the content of the array (as `map` in bolts' MutableLongMap), not a stored field.
  * The case class carries the size of the array, as it might not be a multiple of 8. If the size is not a multiple of 8, the last bits of the last byte are ignored.
  * Bits are packed MSB first: bit i of the list is bit 7 - i % 8 of byte i / 8, i.e., the one selected by the mask 0x80 >>> (i % 8).
  * The executable code computes these indexes as i >>> 3 and i & 7: divisions only appear in ghost code.
  * The class has no ghost field: the specification (`toList` and `_size`) is computed from the executable state.
  *
  * @param data
  * @param _isize
  */
case class BitArray(private val data: Array[UByte], private var _isize: Int) {

  /** The size as a BigInt, for the specification. */
  @ghost @pure def _size: BigInt = BigInt(_isize)

  // Links the size with the actual size of the array.
  @ghost @pure def valid: Boolean = {
    data.length <= Int.MaxValue / 8 &&
    0 <= _isize && _size <= 8 * BigInt(data.length)
  }

  /** The abstract content of the bit array: its first `_size` bits. */
  @ghost @pure def toList: List[Boolean] = {
    require(valid)
    BitArray.bitsFrom(data, 0, _size)
  }.ensuring(res => res.size == _size)

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
      Utils.lemmaConversionBackForth(_isize)
      Utils.compareIntPreservedByToBigInt(i, _isize)
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(BigInt(i), data.length)
      BitArray.lemmaBitsFromApply(data, 0, _size, BigInt(i))
    })
    val byte = data(i >>> 3)
    (byte & (0x80.toUByte >>> (i & 7).toUByte)) != 0.toUByte
  }.ensuring(res => valid && res == toList(BigInt(i)))

  @ghost def apply(ii: BigInt): Boolean = {
    require(valid)
    require(ii >= 0 && ii < _size)
    BitArray.lemmaBitIndexInBounds(ii, data.length)
    BitArray.lemmaBitsFromApply(data, 0, _size, ii)
    val byte = data((ii / 8).toInt)
    (byte & (0x80.toUByte >>> (ii % 8).toInt.toUByte)) != 0.toUByte
  }.ensuring(res => valid && res == toList(ii))

  def update(i: Int, v: Boolean): Unit = {
    require(valid)
    require(i >= 0 && i < size)
    ghostExpr({
      Utils.lemmaConversionBackForth(_isize)
      Utils.compareIntPreservedByToBigInt(i, _isize)
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(BigInt(i), data.length)
    })
    @ghost val oldList = toList
    val byteIndex = i >>> 3
    val mask = 0x80.toUByte >>> (i & 7).toUByte
    val byte = data(byteIndex)
    val newByte = if (v) byte | mask else byte & ~mask
    ghostExpr(BitArray.lemmaBitsFromUpdated(data, 0, _size, BigInt(i), v, newByte))
    data(byteIndex) = newByte
    ghostExpr(assert(toList == oldList.updated(BigInt(i), v)))
  }.ensuring(_ => valid && old(this).toList.updated(BigInt(i), v) == toList)

  @opaque def update(ii: BigInt, v: Boolean): Unit = {
    require(valid)
    require(ii >= 0 && ii < _size)
    val i = ii.toInt
    ghostExpr({
      Utils.lemmaConversionBackForth(_isize)
      Utils.compareIntPreservedByToBigInt(i, _isize)
      Utils.lemmaToIntBigIntConversionBothDirections(_size, i)
      Utils.lemmaConversionBackForth(ii)
      BitArray.lemmaIntBitIndex(i)
      BitArray.lemmaBitIndexInBounds(ii, data.length)
    })
    @ghost val oldList = toList
    val byteIndex = i >>> 3
    val mask = 0x80.toUByte >>> (i & 7).toUByte
    val byte = data(byteIndex)
    val newByte = if (v) byte | mask else byte & ~mask
    ghostExpr(BitArray.lemmaBitsFromUpdated(data, 0, _size, ii, v, newByte))
    data(byteIndex) = newByte
    ghostExpr(assert(toList == oldList.updated(ii, v)))
  }.ensuring(_ => valid && old(this).toList.updated(ii, v) == toList)

  /** Zeroes the bytes from index k on. GenC compiles the tail call to a jump, so there is no recursion in the
    * generated C. */
  private def clearFrom(k: Int): Unit = {
    require(0 <= k && k <= data.length)
    decreases(data.length - k)
    if (k < data.length) {
      data(k) = 0.toUByte
      clearFrom(k + 1)
    }
  }.ensuring(_ => data == BitArray.zeroedFrom(old(this).data, k) && _isize == old(this)._isize && _size == old(this)._size)

  /** Sets every bit to zero, a byte at a time. The bits of the last byte past the size are zeroed as well. */
  def clear(): Unit = {
    require(valid)
    @ghost val data0 = snapshot(data)
    clearFrom(0)
    ghostExpr(BitArray.lemmaBitsFromZeroed(data0, 0, _size))
  }.ensuring(_ => valid && size == old(this).size && _size == old(this)._size && toList == List.fill(_size)(false))
}

object BitArray {
  @ghost @pure def bitIndex(i: BigInt): (BigInt, BigInt) = {
    i / 8 -> i % 8
  }
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

  /** Setting bit i of the array to v in the byte holding it, i.e., replacing that byte by newByte, updates the bits at i only. */
  @ghost @opaque @inlineOnce
  def lemmaBitsFromUpdated(array: Array[UByte], from: BigInt, _size: BigInt, i: BigInt, v: Boolean, newByte: UByte): Unit = {
    require(0 <= from && from <= _size && _size <= 8 * BigInt(array.length))
    require(0 <= i && i < _size && bitIndexInBounds(i, array.length))
    require(newByte == (if (v) array((i / 8).toInt) | (0x80.toUByte >>> (i % 8).toInt.toUByte) else array((i / 8).toInt) & ~(0x80.toUByte >>> (i % 8).toInt.toUByte)))
    decreases(_size - from)
    if (from < _size) {
      lemmaBitIndexInBounds(from, array.length)
      lemmaUpdatedBit(array, i, v, newByte, from)
      lemmaBitsFromUpdated(array, from + 1, _size, i, v, newByte)
      val newArray = array.updated((i / 8).toInt, newByte)
      val h = bitAt(array, from)
      val t = bitsFrom(array, from + 1, _size)
      val newT = bitsFrom(newArray, from + 1, _size)
      assert(bitAt(newArray, from) == (if (from == i) v else h))
      assert(bitsFrom(array, from, _size) == Cons(h, t))
      assert(bitsFrom(newArray, from, _size) == Cons(bitAt(newArray, from), newT))
      if (from < i) {
        assert(newT == t.updated(i - from - 1, v))
        assert(Cons(h, t).updated(i - from, v) == Cons(h, t.updated(i - from - 1, v)))
      } else if (from == i) {
        assert(newT == t)
        assert(Cons(h, t).updated(i - from, v) == Cons(v, t))
      } else {
        assert(newT == t)
      }
    }
  }.ensuring(_ =>
    bitsFrom(array.updated((i / 8).toInt, newByte), from, _size) ==
      (if (from <= i) bitsFrom(array, from, _size).updated(i - from, v) else bitsFrom(array, from, _size))
  )
}
