package continuations.reifyreflect.scalaz

import _root_.scalaz.\/
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.scalaz.given

class DisjunctionScalazSuite extends munit.FunSuite:

  type EitherS[A] = String \/ A

  test("reify returns summed value for disjunction") {
    val result: EitherS[Int] = reify[EitherS, Int] {
      val x = reflect[EitherS, Int, Int](\/.right(1))
      val y = reflect[EitherS, Int, Int](\/.right(2))
      x + y
    }
    assertEquals(result, \/.right(3))
  }

  test("reflect propagates left for disjunction") {
    val result: EitherS[Int] = reify[EitherS, Int] {
      val x = reflect[EitherS, Int, Int](\/.right(1))
      val y = reflect[EitherS, Int, Int](\/.left("boom"): EitherS[Int])
      x + y
    }
    assertEquals(result, \/.left("boom"))
  }

  test("reflect and reify round-trip for disjunction") {
    val value: EitherS[Int] = \/.right(42)
    val result: EitherS[Int] = reify[EitherS, Int] {
      reflect[EitherS, Int, Int](value)
    }
    assertEquals(result, value)
  }
