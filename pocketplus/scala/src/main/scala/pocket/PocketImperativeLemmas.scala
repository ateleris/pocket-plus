package pocket

import stainless.lang.{ghost => ghostExpr, *}
import stainless.annotation.*
import stainless.collection.*
import stainless.lang.StaticChecks.*

/**
  * Lemmas about lists and about the functional specification ([[PocketExecSpec]]) that the refinement proof of
  * the imperative implementation ([[PocketImperative]]) needs. They state nothing about the implementation
  * itself: they are kept here, and not with the specification, so that the specification file only holds what
  * its own proofs need.
  */
object PocketImperativeLemmas {
  import BasicEncodingFunctions.*

  // --------------------------------------------------------------- LISTS ---------------------------------------------------------------

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
    ListSpecs.snocIsAppend(l.take(i), l(i))
    ListSpecs.snocReverse(l.take(i), l(i))
    ()
  }.ensuring(_ => l.take(i + 1).reverse == Cons(l(i), l.take(i).reverse))

  // --------------------------------------------------------------- RUN-LENGTH ENCODING (BLUE BOOK 5.2.3) ---------------------------------------------------------------

  /** A list splits in at most one way into a run of zeros and a rest that does not start with a zero. */
  @ghost @inlineOnce @opaque
  def lemmaZerosPrefixUnique(n1: BigInt, r1: List[Boolean], n2: BigInt, r2: List[Boolean]): Unit = {
    require(n1 >= 0 && n2 >= 0)
    require(List.fill(n1)(ZERO) ++ r1 == List.fill(n2)(ZERO) ++ r2)
    require((r1.isEmpty || r1.head == ONE) && (r2.isEmpty || r2.head == ONE))
    decreases(n1)
    if (n1 > 0 && n2 > 0) {
      lemmaZerosPrefixUnique(n1 - 1, r1, n2 - 1, r2)
    }
  }.ensuring(_ => n1 == n2 && r1 == r2)

  /** A run of n zeros followed by a one splits into the run and the rest. It follows from the contract of
    * splitLeadingZeros alone, since the split it describes is unique. */
  @ghost @inlineOnce @opaque
  def lemmaSplitLeadingZerosThenOne(n: BigInt, tl: List[Boolean]): Unit = {
    require(n >= 0)
    val (leading, rest) = splitLeadingZeros(List.fill(n)(ZERO) ++ Cons(ONE, tl))
    lemmaZerosPrefixUnique(leading.size, rest, n, Cons(ONE, tl))
  }.ensuring(_ => splitLeadingZeros(List.fill(n)(ZERO) ++ Cons(ONE, tl)) == (List.fill(n)(ZERO), Cons(ONE, tl)))

  /** A run of n zeros splits into itself and nothing. */
  @ghost @inlineOnce @opaque
  def lemmaSplitLeadingZerosOnly(n: BigInt): Unit = {
    require(n >= 0)
    decreases(n)
    if (n > 0) {
      lemmaSplitLeadingZerosOnly(n - 1)
    }
  }.ensuring(_ => splitLeadingZeros(List.fill(n)(ZERO)) == (List.fill(n)(ZERO), Nil[Boolean]()))

  /** One step of the run-length encoding: n zeros and a one are encoded as COUNT(n + 1), followed by the
    * encoding of what comes after. */
  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodeZerosThenOne(n: BigInt, tl: List[Boolean]): Unit = {
    require(n >= 0 && n + 1 + tl.size <= MAX_F)
    lemmaSplitLeadingZerosThenOne(n, tl)
  }.ensuring(_ => runLengthEncode(List.fill(n)(ZERO) ++ Cons(ONE, tl)) == countEncode(n + 1) ++ runLengthEncode(tl))

  /** The end of the run-length encoding: trailing zeros are encoded as the terminator '10' alone. */
  @ghost @inlineOnce @opaque
  def lemmaRunLengthEncodeZeros(n: BigInt): Unit = {
    require(0 <= n && n <= MAX_F)
    lemmaSplitLeadingZerosOnly(n)
  }.ensuring(_ => runLengthEncode(List.fill(n)(ZERO)) == List(ONE, ZERO))

  /** A run of n zeros followed by a zero is a run of n + 1 zeros. */
  @ghost @inlineOnce @opaque
  def lemmaZerosThenZero(n: BigInt, l: List[Boolean]): Unit = {
    require(n >= 0)
    decreases(n)
    if (n > 0) {
      lemmaZerosThenZero(n - 1, l)
    }
  }.ensuring(_ => List.fill(n)(ZERO) ++ Cons(ZERO, l) == List.fill(n + 1)(ZERO) ++ l)
}
