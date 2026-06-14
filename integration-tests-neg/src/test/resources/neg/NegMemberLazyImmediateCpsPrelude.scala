import continuations.*

class NegMemberLazyImmediateCpsPrelude:
  lazy val bad: Int => (CpsTransform[Int] ?=> Int) =
    val base = shift[Int, Int](k => k(10)) // error: member lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def
    x => shift[Int, Int](k => k(base + x))

  def test: Int = reset[Int](bad(1))
