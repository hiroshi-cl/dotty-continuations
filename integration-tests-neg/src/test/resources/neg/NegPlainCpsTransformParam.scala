import continuations.*

object NegPlainCpsTransformParam:
  def bad(ctx: CpsTransform[Int]): Int = // error: unsupported direct CpsTransform parameter shape
    given CpsTransform[Int] = ctx
    shift[Int, Int](k => k(7))

