package datastructure.arrays

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*



object BitArray {
  @ghost @pure def bitIndex(i: BigInt): (BigInt, BigInt) = {
    i / 8 -> i % 8
  }
  @ghost @pure def sameArrayListContent(from: BigInt, list: List[Boolean], array: Array[Byte], _size: BigInt): Boolean = {
    require(_size == list.size)
    require(_size >= 0 && _size <= BigInt(Int.MaxValue) * 8 && from >= 0 && from <= _size && _size <= 8 * BigInt(array.length))
    decreases(_size - from)
    if from == _size then true
    else {
      val (byteIndex, bIndex) = bitIndex(from)
      val byte = array(byteIndex.toInt)
      val bit = (byte & (1 << bIndex.toInt)) != 0
      bit == list(from) && sameArrayListContent(from + 1, list, array, _size)
    }
  }
}

/**
  * Represents a bit array, underlined by an array of bytes. The specification is given by a list
  * of booleans.
  * The case class carries the size of the array as a ghost variable, which is equal to the length of the list, as it might not be a multiple of 8. If the size is not a multiple of 8, the last bits of the last byte are ignored.
  *
  * @param data
  * @param toList
  * @param size
  */
case class BitArray(private val data: Array[Byte], @ghost private var toList: List[Boolean], private var _size: BigInt) {

  // Links the content of the list and the content of the array, and _size with the actual size of the array.
  @ghost @pure def valid: Boolean = {
    0 <= _size && _size <= 8 * BigInt(data.length) &&
    _size == toList.size && 
    BitArray.sameArrayListContent(0, toList, data, _size)
  }

 

  @ghost @pure def getList: List[Boolean] = toList

  @pure def size: BigInt = {
    require(valid)
    _size
  }.ensuring(res =>
    valid && res == toList.size && res >= 0 && res <= BigInt(Int.MaxValue)
  )

  def apply(i: BigInt): Boolean = {
    require(valid)
    require(i >= 0 && i < size)
    ??? : Boolean
  }.ensuring(res => valid && res == toList(i))

  def update(i: BigInt, v: Boolean): Unit = {
    require(valid)
    require(i >= 0 && i < size)
    ??? : Unit
  }.ensuring(_ => valid && old(this).toList.updated(i, v) == toList)
}