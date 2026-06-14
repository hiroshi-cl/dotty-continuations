import continuations.*

object NegUsingCpsMultipleMarkers:
  def bad(using ctx1: CpsTransform[Int], ctx2: CpsTransform[Int]): Int = // error: unsupported direct CpsTransform parameter shape
    shift[Int, Int](k => k(7))(using ctx1)

