package pocket

/** Minimal JSON reader for well-formed, machine-generated fixtures (no error recovery). */
object Json {
  def parse(s: String): Any = parseValue(s, 0)._1

  private def ws(s: String, i: Int): Int = { var j = i; while (j < s.length && s(j).isWhitespace) j += 1; j }

  private def parseValue(s: String, i0: Int): (Any, Int) = {
    val i = ws(s, i0)
    s(i) match {
      case '{' => parseObj(s, i)
      case '[' => parseArr(s, i)
      case '"' => parseStr(s, i)
      case 't' => (true, i + 4)
      case 'f' => (false, i + 5)
      case 'n' => (null, i + 4)
      case _   => parseNum(s, i)
    }
  }

  private def parseObj(s: String, i0: Int): (Map[String, Any], Int) = {
    var i = ws(s, i0 + 1)
    if (s(i) == '}') return (Map.empty, i + 1)
    var m = Map.empty[String, Any]
    var go = true
    while (go) {
      val (k, i1) = parseStr(s, ws(s, i))
      val (v, i3) = parseValue(s, ws(s, i1) + 1)
      m += (k -> v)
      i = ws(s, i3)
      go = s(i) == ','
      if (go) i += 1
    }
    (m, i + 1)
  }

  private def parseArr(s: String, i0: Int): (List[Any], Int) = {
    var i = ws(s, i0 + 1)
    if (s(i) == ']') return (Nil, i + 1)
    val buf = scala.collection.mutable.ListBuffer.empty[Any]
    var go = true
    while (go) {
      val (v, i1) = parseValue(s, i)
      buf += v
      i = ws(s, i1)
      go = s(i) == ','
      if (go) i += 1
    }
    (buf.toList, i + 1)
  }

  private def parseStr(s: String, i0: Int): (String, Int) = {
    var i = i0 + 1
    val sb = new StringBuilder
    while (s(i) != '"') {
      if (s(i) == '\\') { sb += s(i + 1); i += 2 } else { sb += s(i); i += 1 }
    }
    (sb.toString, i + 1)
  }

  private def parseNum(s: String, i0: Int): (Double, Int) = {
    var i = i0
    while (i < s.length && (s(i).isDigit || "+-.eE".contains(s(i)))) i += 1
    (s.substring(i0, i).toDouble, i)
  }
}
