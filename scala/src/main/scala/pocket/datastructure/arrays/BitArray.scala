package datastructure.arrays

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*

/**
  * Represents a bit array, underlined by an array of bytes. The specification is given by a list
  * of booleans.
  * The case class carries the size of the array as a ghost variable, which is equal to the length of the list, as it might not be a multiple of 8. If the size is not a multiple of 8, the last bits of the last byte are ignored.
  *
  * @param data
  * @param toList
  * @param size
  */
@extern // TODO remove when it is implemented
case class BitArray(private val data: Array[Byte], @ghost private var toList: List[Boolean], @ghost private var _size: BigInt) {

  // Links the content of the list and the content of the array
  
  @ghost @pure def valid: Boolean = ??? : Boolean

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