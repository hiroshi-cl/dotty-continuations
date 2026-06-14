import continuations.*

object NegTupleElementInlineCpsClosure:
  def test: Int =
    val pair = (
      (x: Int) => shift[Int, Int](k => k(x)), // error: tuple CPS value element cannot be safely transformed without re-evaluating its RHS; bind it to a local val first
      0
    )

    reset[Int] {
      pair._1(5)
    }
