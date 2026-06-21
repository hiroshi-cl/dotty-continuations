import continuations.*

object NegShiftInByNameArg:
  def withByName(thunk: => Int): Int = thunk

  def test: Int = reset {
    withByName(shift[Int, Int] { k => k(42) }) // error: shift inside a by-name argument is not supported
  }
