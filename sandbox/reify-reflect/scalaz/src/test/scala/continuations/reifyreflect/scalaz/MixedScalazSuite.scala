package continuations.reifyreflect.scalaz

import _root_.scalaz.Maybe
import _root_.scalaz.\/
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.scalaz.given

class MixedScalazSuite extends munit.FunSuite:

  type EitherS[A] = String \/ A

  test("nested reify combines Maybe and disjunction") {
    val result: Maybe[EitherS[Int]] = reify[Maybe, EitherS[Int]] {
      val x = reflect[Maybe, Int, EitherS[Int]](Maybe.just(10))
      reify[EitherS, Int] {
        val y = reflect[EitherS, Int, Int](\/.right(x + 5))
        y
      }
    }
    assertEquals(result, Maybe.just(\/.right(15)))
  }

  test("Maybe sums two reflected values") {
    val result: Maybe[Int] = reify[Maybe, Int] {
      val x = reflect[Maybe, Int, Int](Maybe.just(20))
      val y = reflect[Maybe, Int, Int](Maybe.just(22))
      x + y
    }
    assertEquals(result, Maybe.just(42))
  }
