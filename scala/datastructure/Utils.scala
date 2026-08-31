package datastructure


import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*

object Utils {
  @ghost def sameArrayListContent[T](arr: Array[T], from: Int, list: List[T]): Boolean = {
    if (from < 0 || from > arr.length) {
      false
    } else if (from == arr.length){
      list == Nil[T]()
    } else {
      list match {
        case Nil() => false
        case Cons(h, t) =>
          arr(from) == h && sameArrayListContent(arr, from + 1, t)
      }
    }
  }.ensuring(res => if res then 0 <= from && from <= arr.length else true)

  @ghost @opaque @inlineOnce
  def lemmaSameArrayListContentImpliesSameLength[T](arr: Array[T], from: Int, list: List[T]): Unit = {
    decreases(list)
    require(sameArrayListContent(arr, from, list))

    if (from < 0 || from > arr.length) {
      // false
      ()
    } else if (from == arr.length){
      Utils.compareIntPreservedByToBigInt(arr.length, from)
      ()
    } else {
      list match {
        case Nil() => 
          ()
        case Cons(h, tail) =>
          assert((0 <= from && from <= arr.length))
          lemmaSameArrayListContentImpliesSameLength(arr, from + 1, tail)
          assert((0 <= from + 1 && from + 1 <= arr.length))
          assert((arr.length == from + 1 + tail.size.toInt))
          assert(list.size == tail.size + 1)
          Utils.additionIntPreservedByToBigInt(tail.size.toInt, 1)
          Utils.lemmaConversionBackForth(tail.size)
          Utils.lemmaConversionBackForth(list.size)
          Utils.lemmaConversionBackForth(arr.length)
          assert(1 + tail.size == list.size)
          Utils.additionIntPreservedByToInt(1, tail.size, list.size)
          assert(1 + tail.size.toInt == list.size.toInt)
          Utils.compareIntPreservedByToBigInt(arr.length, Int.MaxValue)
          Utils.additionIntPreservedByToBigInt(from + 1, tail.size.toInt)
          Utils.compareIntPreservedByToBigInt(from + list.size.toInt, from + 1 + tail.size.toInt)

          Utils.additionIntPreservedByToBigInt(from, 1)
          ()
      }
    }
  }.ensuring(_ => (0 <= from && from <= arr.length) && (arr.length == from + list.size.toInt) && (BigInt(arr.length) == BigInt(from) + list.size))

  @ghost @opaque @inlineOnce
  def lemmaSameArrayListContentImpliesSameApply[T](arr: Array[T], from: Int, list: List[T], i: Int): Unit = {
    decreases(list)
    require(sameArrayListContent(arr, from, list))
    require(i >= from && i < arr.length)

    val j = i - from
    list match {
      case Nil() => ()
      case Cons(h, tail) if (i == from) =>
        assert(arr(from) == h)
      case Cons(h, tail) =>
        assert(i > from)
        lemmaSameArrayListContentImpliesSameApply(arr, from + 1, tail, i)
        subtractionIntPreservedByToBigInt(j, 1)
    }
  }.ensuring(_ => arr(i) == list(BigInt(i - from)))


  @opaque @inlineOnce @ghost
  def lemmaSameArrayListContentPreservedByUpdatedIBeforeFrom[T](arr: Array[T], from: Int, list: List[T], i: Int, v: T): Unit = {
    require(sameArrayListContent(arr, from, list))
    require(i >= 0 && i < arr.length)
    require(i < from)
    decreases(list)
    lemmaSameArrayListContentImpliesSameLength(arr, from, list)
    list match {
      case Nil() => ()
      case Cons(h, tail) =>
        assert(arr(from) == arr.updated(i, v)(from))
        lemmaSameArrayListContentPreservedByUpdatedIBeforeFrom(arr, from + 1, tail, i, v)
    }
  }.ensuring(_ => sameArrayListContent(arr.updated(i, v), from, list))

  @opaque @inlineOnce @ghost
  def lemmaSameArrayListContentPreservedByUpdated[T](arr: Array[T], from: Int, list: List[T], i: Int, v: T): Unit = {
    require(sameArrayListContent(arr, from, list))
    require(i >= 0 && i < arr.length)
    require(i >= from)
    decreases(list)
    val j = i - from
    lemmaSameArrayListContentImpliesSameLength(arr, from, list)
    assert((arr.length == from + list.size.toInt))
    compareIntPreservedByToBigInt(j, 0)
    lemmaToIntBigIntConversionBothDirections(list.size, arr.length - from)
    lemmaToIntBigIntConversionBothDirections(list.size, j)
    assert(j >= 0)
    assert(j < list.size.toInt)
    assert(BigInt(j) >= 0 && BigInt(j) < list.size)
    list match {
      case Nil() => ()
      case Cons(h, tail) if (i == from) =>lemmaSameArrayListContentPreservedByUpdatedIBeforeFrom(arr, from + 1, tail, i, v)
      case Cons(h, tail) =>
        assert(i > from)
        lemmaSameArrayListContentPreservedByUpdated(arr, from + 1, tail, i, v)
        assert(sameArrayListContent(arr.updated(i, v), from + 1, tail.updated(BigInt(j- 1), v)))
        subtractionIntPreservedByToBigInt(j, 1)
        assert(BigInt(j - 1) == BigInt(j) - 1)
        assert(Cons(h, tail.updated(BigInt(j - 1), v)) == list.updated(BigInt(j), v))
    }
  }.ensuring(_ => sameArrayListContent(arr.updated(i, v), from, list.updated(BigInt(i - from), v)))

  @opaque @inlineOnce @ghost
  def compareIntPreservedByToBigInt(a: Int, b: Int): Unit = {
  }.ensuring(_ => (a < b) == (BigInt(a) < BigInt(b)) && (a > b) == (BigInt(a) > BigInt(b)) && (a == b) == (BigInt(a) == BigInt(b)))

  @opaque @inlineOnce @ghost
  def additionIntPreservedByToBigInt(a: Int, b: Int): Unit = {
    require(BigInt(Int.MinValue) <= BigInt(a) + BigInt(b) && BigInt(a) + BigInt(b) <= BigInt(Int.MaxValue))
    assert(Int.MinValue <= a + b && a + b <= Int.MaxValue)
    assert(BigInt(a + b) == BigInt(a) + BigInt(b))
  }.ensuring(_ => BigInt(a + b) == BigInt(a) + BigInt(b))

  @opaque @inlineOnce @ghost
  def additionIntPreservedByToInt(a: BigInt, b: BigInt, c: BigInt): Unit = {
    require(a + b == c)
    require(Int.MinValue <= c && c <= Int.MaxValue)
    require(Int.MinValue <= a && a <= Int.MaxValue)
    require(Int.MinValue <= b && b <= Int.MaxValue)
    assert(a.toInt + b.toInt == c.toInt)
  }.ensuring(_ => a.toInt + b.toInt == c.toInt)

  @opaque @inlineOnce @ghost
  def subtractionIntPreservedByToBigInt(a: Int, b: Int): Unit = {
    require(BigInt(Int.MinValue) <= BigInt(a) - BigInt(b) && BigInt(a) - BigInt(b) <= BigInt(Int.MaxValue))
    assert(Int.MinValue <= a - b && a - b <= Int.MaxValue)
    assert(BigInt(a - b) == BigInt(a) - BigInt(b))
  }.ensuring(_ => BigInt(a - b) == BigInt(a) - BigInt(b))

  @opaque @inlineOnce @ghost
  def compareBigIntPreservedByToInt(a: BigInt, b: BigInt): Unit = {
    require(Int.MinValue <= a && a <= Int.MaxValue && Int.MinValue <= b && b <= Int.MaxValue)
  }.ensuring(_ => (a < b) == (a.toInt < b.toInt) && (a > b) == (a.toInt > b.toInt) && (a == b) == (a.toInt == b.toInt))

  @opaque @inlineOnce @ghost
  def lemmaToIntBigIntConversionBothDirections(a: BigInt, b: Int): Unit = {
    require(Int.MinValue <= a && a <= Int.MaxValue)
  }.ensuring(_ => (a.toInt == b) == (a == BigInt(b)) && (a < BigInt(b)) == (a.toInt < b) && (a > BigInt(b)) == (a.toInt > b) && (a <= BigInt(b)) == (a.toInt <= b) && (a >= BigInt(b)) == (a.toInt >= b))

  @opaque @inlineOnce @ghost
  def lemmaConversionBackForth(a: BigInt): Unit = {
    require(Int.MinValue <= a && a <= Int.MaxValue)
  }.ensuring(_ => a == BigInt(a.toInt))

  @opaque @inlineOnce @ghost
  def lemmaConversionBackForth(a: Int): Unit = {
  }.ensuring(_ => BigInt(a).toInt == a)

}