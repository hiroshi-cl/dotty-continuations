import continuations.*

object NegLocalDirectCpsStorage:
  def strictAnnotated(): Unit =
    val inner: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
      (ctx: CpsTransform[Int]) ?=> 1

  def strictInferred(): Unit =
    val inner = // error: direct CPS context-function values cannot be stored
      (ctx: CpsTransform[Int]) ?=> 2

  def lazyAnnotated(): Unit =
    lazy val inner: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
      (ctx: CpsTransform[Int]) ?=> 3

  def mutableAnnotated(): Unit =
    var inner: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
      (ctx: CpsTransform[Int]) ?=> 4

  def containerAnnotated(): Unit =
    val xs: List[CpsTransform[Int] ?=> Int] = // error: direct CPS context-function values cannot be stored
      List((ctx: CpsTransform[Int]) ?=> 5)

  def containerInferred(): Unit =
    val xs = // error: direct CPS context-function values cannot be stored
      List((ctx: CpsTransform[Int]) ?=> 6)
