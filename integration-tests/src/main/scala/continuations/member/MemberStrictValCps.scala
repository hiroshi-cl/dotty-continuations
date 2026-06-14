package continuations.member

import continuations.CpsTransform
import continuations.reset
import continuations.shift

class MemberStrictValCps:
  val one: Int => CpsTransform[Int] ?=> Int =
    x => shift[Int, Int](k => k(x))

  val xs: List[Int => CpsTransform[Int] ?=> Int] =
    List(x => shift[Int, Int](k => k(x)))

  val many: List[Int => CpsTransform[Int] ?=> Int] =
    List(x => shift[Int, Int](k => k(x)), x => shift[Int, Int](k => k(x + 1)))

  def viaUnqualified: Int =
    reset[Int](xs.head(1))

  def viaThis: Int =
    reset[Int](this.xs.head(1))

object MemberStrictValCps:
  val one: Int => CpsTransform[Int] ?=> Int =
    x => shift[Int, Int](k => k(x + 1))

  val xs: List[Int => CpsTransform[Int] ?=> Int] =
    List(x => shift[Int, Int](k => k(x + 2)))

class MemberStrictValCpsWithEffect:
  var initCount: Int = 0

  val xs: List[Int => CpsTransform[Int] ?=> Int] =
    initCount += 1
    List(x => shift[Int, Int](k => k(x + 3)))
