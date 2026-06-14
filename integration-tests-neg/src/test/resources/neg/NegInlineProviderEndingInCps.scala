import continuations.*

object NegInlineProviderEndingInCps:
  inline def bad: Int => (CpsTransform[Int] ?=> Int) =
    value => shift[Int, Int](k => k(value)) // error: inline CPS providers are not supported; move the CPS provider body to a non-inline def

  transparent inline def badTransparent: Int => (CpsTransform[Int] ?=> Int) =
    value => shift[Int, Int](k => k(value)) // error: inline CPS providers are not supported; move the CPS provider body to a non-inline def

  def existing: Int => (CpsTransform[Int] ?=> Int) =
    value => shift[Int, Int](k => k(value))

  inline def badDelegated: Int => (CpsTransform[Int] ?=> Int) =
    existing // error: inline CPS providers are not supported; move the CPS provider body to a non-inline def

  transparent inline def badTransparentDelegated: Int => (CpsTransform[Int] ?=> Int) =
    existing // error: inline CPS providers are not supported; move the CPS provider body to a non-inline def

case class NegInlineBox[A](value: A)

class NegInlineContainerProvider:
  inline def badBox: NegInlineBox[Int => (CpsTransform[Int] ?=> Int)] =
    NegInlineBox(value => shift[Int, Int](k => k(value))) // error: inline CPS providers are not supported; move the CPS provider body to a non-inline def

  inline def badConsume(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
    reset[Int](xs.head(1)) // error: inline CPS providers are not supported; move the CPS provider body to a non-inline def
