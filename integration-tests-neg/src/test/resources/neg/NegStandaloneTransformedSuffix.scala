import continuations.*

class NegStandaloneTransformedSuffix:
  // A $transformed method with no corresponding original CPS method should be rejected
  def helper$transformed(x: Int): Int = x // error: manual $transformed definition requires a corresponding original CPS method
