import continuations.*

object NegHigherRankCpsContainer:
  def consume[F[_]](f: F[Int => (CpsTransform[Int] ?=> Int)]): Int =
    reset[Int](f) // error: Found:    (f : F[Int =>
