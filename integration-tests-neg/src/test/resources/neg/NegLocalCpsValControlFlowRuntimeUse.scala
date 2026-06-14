import continuations.*

object NegLocalCpsValControlFlowRuntimeUse:
  def ifTail(flag: Boolean): Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    if flag then inner else inner // error: local CPS function value cannot be used as an ordinary runtime value

  def matchTail(flag: Boolean): Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    flag match
      case true => inner // error: local CPS function value cannot be used as an ordinary runtime value
      case false => inner

  def tryTail(flag: Boolean): Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    try
      if flag then inner else throw new IllegalStateException("fallback") // error: local CPS function value cannot be used as an ordinary runtime value
    catch
      case _: IllegalStateException => inner

  def ifValRhs(flag: Boolean): Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    val boxed: Any = if flag then inner else inner // error: local CPS function value cannot be used as an ordinary runtime value
    boxed

  def matchValRhs(flag: Boolean): Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    val boxed: Any = flag match // error: local CPS function value cannot be used as an ordinary runtime value
      case true => inner
      case false => inner
    boxed

  def tryValRhs(flag: Boolean): Any =
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x))
    val boxed: Any =
      try
        if flag then inner else throw new IllegalStateException("fallback") // error: local CPS function value cannot be used as an ordinary runtime value
      catch
        case _: IllegalStateException => inner
    boxed
