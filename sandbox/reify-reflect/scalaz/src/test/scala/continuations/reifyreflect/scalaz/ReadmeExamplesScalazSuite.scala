package continuations.reifyreflect.scalaz

import _root_.scalaz.Maybe
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.scalaz.given

class ReadmeExamplesScalazSuite extends munit.FunSuite:

  test("README Maybe example evaluates to 25") {
    val result: Maybe[Int] = reify[Maybe, Int] {
      val x = reflect[Maybe, Int, Int](Maybe.just(10))
      val y = reflect[Maybe, Int, Int](Maybe.just(x + 5))
      x + y
    }
    assertEquals(result, Maybe.just(25))
  }
