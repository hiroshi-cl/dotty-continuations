import continuations.*

object NegShiftInWhile:
  def test: Int = reset {
    var i = 0
    while i < 3 do // error: shift in while is not supported
      val x = shift[Int, Int] { k => k(i) }
      i += x
    i
  }
