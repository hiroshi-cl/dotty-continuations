import continuations.*

object NegLocalLazyImmediateCpsPrelude:
  def test: Int = reset {
    lazy val bad: Int => (CpsTransform[Int] ?=> Int) =
      val base = shift[Int, Int](k => k(10)) // error: local lazy val/var CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def
      x => shift[Int, Int](k => k(base + x))

    bad(1)
  }
