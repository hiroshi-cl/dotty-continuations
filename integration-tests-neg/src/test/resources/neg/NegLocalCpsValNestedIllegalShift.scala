import continuations.*

object NegLocalCpsValNestedIllegalShift:
  def test: Int =
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => List(x).map(_ => shift[Int, Int](k => k(x))).head // error: shift cannot be used in this position (not directly inside reset)

    reset[Int] {
      f(1)
    }
