import continuations.*

object NegLocalCpsValRuntimeUse:
  def hashCodeUse: Int =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    inner.hashCode() // error: local CPS function value cannot be used as an ordinary runtime value

  def eqUse: Boolean =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    inner.eq(inner) // error: local CPS function value cannot be used as an ordinary runtime value

  def anyStorage: Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    val boxed: Any = inner // error: local CPS function value cannot be used as an ordinary runtime value
    boxed

  def arbitraryReturn: Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    inner // error: local CPS function value cannot be used as an ordinary runtime value

  def blockWrappedAnyStorage: Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    val boxed: Any = { // error: local CPS function value cannot be used as an ordinary runtime value
      val alias = inner
      alias
    }
    boxed

  def unknownConsumer: Unit =
    def consume(value: Any): Unit = ()
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    consume(inner) // error: local CPS function value cannot be used as an ordinary runtime value
