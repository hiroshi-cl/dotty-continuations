package continuations.reifyreflect.scalaz

import _root_.scalaz.Maybe
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.scalaz.given

class MaybeScalazSuite extends munit.FunSuite:

  test("reify returns summed value for Maybe") {
    val result: Maybe[Int] = reify[Maybe, Int] {
      val x = reflect[Maybe, Int, Int](Maybe.just(1))
      val y = reflect[Maybe, Int, Int](Maybe.just(2))
      x + y
    }
    assertEquals(result, Maybe.just(3))
  }

  test("reflect propagates empty for Maybe") {
    val result: Maybe[Int] = reify[Maybe, Int] {
      val x = reflect[Maybe, Int, Int](Maybe.just(1))
      val y = reflect[Maybe, Int, Int](Maybe.empty[Int])
      x + y
    }
    assertEquals(result, Maybe.empty)
  }

  test("reflect and reify round-trip for Maybe") {
    val value: Maybe[Int] = Maybe.just(42)
    val result: Maybe[Int] = reify[Maybe, Int] {
      reflect[Maybe, Int, Int](value)
    }
    assertEquals(result, value)
  }
