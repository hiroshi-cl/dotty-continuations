package continuations.reifyreflect.cats

import _root_.cats.implicits.given
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.cats.given

class OptionCatsSuite extends munit.FunSuite:

  test("reify returns summed value for Option via cats.Monad") {
    val result: Option[Int] = reify[Option, Int] {
      val x = reflect[Option, Int, Int](Some(1))
      val y = reflect[Option, Int, Int](Some(2))
      x + y
    }
    assertEquals(result, Some(3))
  }

  test("reflect propagates None for Option via cats.Monad") {
    val result: Option[Int] = reify[Option, Int] {
      val x = reflect[Option, Int, Int](Some(1))
      val y = reflect[Option, Int, Int](None: Option[Int])
      x + y
    }
    assertEquals(result, None)
  }

  test("reflect and reify round-trip for Option via cats.Monad") {
    val result: Option[Int] = reify[Option, Int] {
      reflect[Option, Int, Int](Some(42))
    }
    assertEquals(result, Some(42))
  }
