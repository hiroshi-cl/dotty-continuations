import continuations.*

object NegOpaqueCpsContainer:
  opaque type OpaqueCps = Int => (CpsTransform[Int] ?=> Int) // error: shift cannot be used in this position

  def consume(f: OpaqueCps): Int = ???
