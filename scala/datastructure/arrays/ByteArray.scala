package datastructure.arrays

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*
import datastructure.Utils

@mutable
case class ByteArray(private val data: Array[Byte], @ghost private var toList: List[Byte]) {
  
  @ghost def valid: Boolean = Utils.sameArrayListContent(data, 0, toList)

  @ghost def getList: List[Byte] = toList

  def size: BigInt = {
    require(valid)
    ghostExpr(Utils.lemmaSameArrayListContentImpliesSameLength(data, 0, toList))
    ghostExpr(Utils.lemmaToIntBigIntConversionBothDirections(toList.size, data.length))
    assert(BigInt(data.length) == toList.size)
    BigInt(data.length)
  }.ensuring(res => valid && res == toList.size && res >= 0 && res <= BigInt(Int.MaxValue))

  def apply(i: BigInt): Byte = {
    require(valid)
    require(i >= 0 && i < size)
    ghostExpr(Utils.lemmaSameArrayListContentImpliesSameLength(data, 0, toList))
    ghostExpr(Utils.lemmaToIntBigIntConversionBothDirections(toList.size, data.length))
    ghostExpr(Utils.compareBigIntPreservedByToInt(i, size))
    assert(size <= BigInt(Int.MaxValue))
    assert(i.toInt >= 0 && i.toInt < data.length)
    ghostExpr({
      Utils.lemmaSameArrayListContentImpliesSameApply(data, 0, toList, i.toInt)
      Utils.compareBigIntPreservedByToInt(i, size)
      Utils.lemmaConversionBackForth(i)
      assert(size == toList.size)
      assert(0 <= i && i < toList.size)
    })
    data(i.toInt)
  }.ensuring(res => valid && res == toList(i))

  def update(i: BigInt, v: Byte): Unit = {
    require(valid)
    require(i >= 0 && i < size)
    ghostExpr({Utils.lemmaSameArrayListContentImpliesSameLength(data, 0, toList)
      Utils.compareBigIntPreservedByToInt(i, size)
      Utils.sameArrayListContent(data, 0, toList)
      Utils.lemmaSameArrayListContentPreservedByUpdated(data, 0, toList, i.toInt, v)
      Utils.lemmaSameArrayListContentImpliesSameLength(data, 0, toList)
      Utils.lemmaToIntBigIntConversionBothDirections(toList.size, data.length)
      Utils.compareBigIntPreservedByToInt(i, size)
      unfold(size)
    })

    toList = toList.updated(i, v)
    data(i.toInt) = v
  }.ensuring(_ => valid && old(this).toList.updated(i, v) == toList)
}


object TestByteArray {
  def test(): Unit = {
    val arr: ByteArray with arr.valid = ByteArray(Array(1, 2, 3), List(1, 2, 3))
    arr(0) = 10
    assert(arr(0) == 10)
  }
  def test2(arr: ByteArray with arr.valid): Unit = {
    require(arr.size > 0)
    require(arr(0) == 1)
    assert(arr(0) == 1)
    assert(arr.getList.head == 1)
    arr.update(0, 10)
    assert(arr(0) == 10)

  }
}