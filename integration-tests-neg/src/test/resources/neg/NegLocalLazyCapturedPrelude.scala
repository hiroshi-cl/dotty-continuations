import continuations.*

object NegLocalLazyCapturedPrelude:
  def test: Int =
    lazy val bad: Int => (CpsTransform[Int] ?=> Int) =
      val base = 1 // error: local lazy val CPS storage RHS cannot capture initializer prelude values; use a strict val or def
      x => shift[Int, Int](k => k(base + x))

    reset[Int] {
      bad(6)
    }
