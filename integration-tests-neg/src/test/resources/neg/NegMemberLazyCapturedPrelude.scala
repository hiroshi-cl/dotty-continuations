import continuations.*

class NegMemberLazyCapturedPrelude:
  lazy val bad: Int => (CpsTransform[Int] ?=> Int) =
    val base = 1 // error: member lazy val CPS storage RHS cannot capture initializer prelude values; use a strict val or def
    x => shift[Int, Int](k => k(base + x))

  def test: Int = reset[Int](bad(6))
