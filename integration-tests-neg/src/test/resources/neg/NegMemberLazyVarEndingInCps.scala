import continuations.*

trait NegMemberLazyVarEndingInCps:
  var badVar: Int => CpsTransform[Int] ?=> Int =
    x => shift[Int, Int](k => k(x)) // error: CPS-valued storage in this owner or shape is not supported
