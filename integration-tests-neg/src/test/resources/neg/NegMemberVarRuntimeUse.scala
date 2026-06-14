import continuations.*

class NegMemberVarRuntimeUse:
  class C:
    var xs: CpsTransform[Int] ?=> Int = // error: direct CPS context-function values cannot be stored
      (ctx: CpsTransform[Int]) ?=> 1
