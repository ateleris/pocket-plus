package pocket

import stainless.lang.*
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*

/** Lemmas about lists, on top of those of the Stainless library (`ListSpecs`). */
object ListLemmas {

  /** Appending an element and then a list is appending the list with that element in front. */
  @ghost @inlineOnce @opaque
  def lemmaAppendCons[A](l: List[A], x: A, tl: List[A]): Unit = {
    ListSpecs.appendAssoc(l, List(x), tl)
    ()
  }.ensuring(_ => (l ++ List(x)) ++ tl == l ++ Cons(x, tl))

  /** Writing element i and taking the first i + 1 elements is taking the first i and appending the value. */
  @ghost @inlineOnce @opaque
  def lemmaUpdatedTakeSnoc[A](l: List[A], i: BigInt, v: A): Unit = {
    require(0 <= i && i < l.size)
    decreases(l)
    l match {
      case Cons(h, tl) if i > 0 => lemmaUpdatedTakeSnoc(tl, i - 1, v)
      case _ => ()
    }
  }.ensuring(_ => l.updated(i, v).take(i + 1) == l.take(i) ++ List(v))

  /** The first i + 1 elements are the first i followed by element i. */
  @ghost @inlineOnce @opaque
  def lemmaTakeSnoc[A](l: List[A], i: BigInt): Unit = {
    require(0 <= i && i < l.size)
    decreases(l)
    l match {
      case Cons(h, tl) if i > 0 => lemmaTakeSnoc(tl, i - 1)
      case _ => ()
    }
  }.ensuring(_ => l.take(i + 1) == l.take(i) ++ List(l(i)))

  /** The first i + 1 elements, reversed, are element i followed by the first i, reversed. */
  @ghost @inlineOnce @opaque
  def lemmaTakeReverseCons[A](l: List[A], i: BigInt): Unit = {
    require(0 <= i && i < l.size)
    lemmaTakeSnoc(l, i)
    ListSpecs.snocReverse(l.take(i), l(i))
    ()
  }.ensuring(_ => l.take(i + 1).reverse == Cons(l(i), l.take(i).reverse))
}
