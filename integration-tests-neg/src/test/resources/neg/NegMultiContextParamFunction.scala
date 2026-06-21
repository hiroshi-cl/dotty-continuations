import continuations.*

object NegMultiContextParamFunction:
  // CpsTransform first, extra param second
  def testA: (CpsTransform[Int], String) ?=> Int = // error: context function types with CpsTransform alongside other context parameters are not yet supported
    42

  // Extra param first, CpsTransform second (order-independent detection)
  def testB: (String, CpsTransform[Int]) ?=> Int = // error: context function types with CpsTransform alongside other context parameters are not yet supported
    42
