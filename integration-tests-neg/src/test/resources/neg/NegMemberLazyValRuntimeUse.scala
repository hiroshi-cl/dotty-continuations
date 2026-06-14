import continuations.*

class NegMemberLazyValRuntimeUse:
  lazy val xs: List[CpsTransform[Int] ?=> Int] = // error: direct CPS context-function values cannot be stored
    List((ctx: CpsTransform[Int]) ?=> 1)
