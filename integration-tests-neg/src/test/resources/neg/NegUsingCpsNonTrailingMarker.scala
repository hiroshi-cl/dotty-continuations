import continuations.*

object NegUsingCpsNonTrailingMarker:
  def bad(using ctx: CpsTransform[Int])(x: Int): Int = // error: unsupported direct CpsTransform parameter shape
    shift[Int, Int](k => k(x))(using ctx)

