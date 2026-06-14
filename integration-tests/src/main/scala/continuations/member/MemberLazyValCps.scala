package continuations.member

import continuations.CpsTransform
import continuations.reset
import continuations.shift

class MemberLazyValCps:
  lazy val one: Int => CpsTransform[Int] ?=> Int =
    x => shift[Int, Int](k => k(x))

  lazy val xs: List[Int => CpsTransform[Int] ?=> Int] =
    List(x => shift[Int, Int](k => k(x)))

  def viaThis: Int =
    reset[Int](this.xs.head(1))

object MemberLazyValCps:
  lazy val one: Int => CpsTransform[Int] ?=> Int =
    x => shift[Int, Int](k => k(x + 1))

  lazy val xs: List[Int => CpsTransform[Int] ?=> Int] =
    List(x => shift[Int, Int](k => k(x + 2)))

class MemberLazyValCpsWithEffect:
  var initCount: Int = 0

  lazy val xs: List[Int => CpsTransform[Int] ?=> Int] =
    initCount += 1
    List(x => shift[Int, Int](k => k(x + 3)))
