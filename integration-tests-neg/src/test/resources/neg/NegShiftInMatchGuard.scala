import continuations.*

object NegShiftInMatchGuard:
  def test(x: Int): Int = reset {
    x match
      case n if shift[Boolean, Int] { k => k(n > 0) } => // error: shift in match guard is not supported
        n
      case _ =>
        0
  }
