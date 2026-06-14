import continuations.*

object NegDirectCpsStorage:
  val strictAnnotated: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
    (ctx: CpsTransform[Int]) ?=> 1

  val strictInferred = // error: direct CPS context-function values cannot be stored
    (ctx: CpsTransform[Int]) ?=> 2

  lazy val lazyAnnotated: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
    (ctx: CpsTransform[Int]) ?=> 3

  var mutableAnnotated: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
    (ctx: CpsTransform[Int]) ?=> 4

  val containerAnnotated: List[CpsTransform[Int] ?=> Int] = // error: direct CPS context-function values cannot be stored
    List((ctx: CpsTransform[Int]) ?=> 5)

  val containerInferred = // error: direct CPS context-function values cannot be stored
    List((ctx: CpsTransform[Int]) ?=> 6)

  val nestedInDelayedProvider: Int => CpsTransform[Int] ?=> Int =
    x =>
      val one = // error: direct CPS context-function values cannot be stored
        (ctx: CpsTransform[Int]) ?=> x
      one

  val polyDirectProvider: [A] => (CpsTransform[Int] ?=> Int) = // error: direct CPS context-function values cannot be stored
    [A] => (ctx: CpsTransform[Int]) ?=> 8

  def methodProvider: CpsTransform[Int] ?=> Int =
    shift[Int, Int](k => k(7))

  val delayedProvider: Int => CpsTransform[Int] ?=> Int =
    x => shift[Int, Int](k => k(x))

  val delayedProviderContainer: List[Int => CpsTransform[Int] ?=> Int] =
    List(x => shift[Int, Int](k => k(x)))
