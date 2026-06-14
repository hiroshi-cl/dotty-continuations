import continuations.*

object NegShiftInFinally:
  def test: Int = reset {
    try {
      42
    } finally {
      val _ = shift[Unit, Int] { k => k(()) } // error: shift in finally is not supported
    }
  }
