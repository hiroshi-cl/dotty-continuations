import continuations.*

object NegUsingCpsMixedMarkerR:
  def bad(using ctx1: CpsTransform[Int], ctx2: CpsTransform[String]): Int = // error: unsupported direct CpsTransform parameter shape
    shift[Int, Int](k => k(7))(using ctx1)

