package continuations.reifyreflect.cats

import _root_.cats.implicits.given
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.cats.given

class ListCatsSuite extends munit.FunSuite:

  test("reflect computes cartesian product for List via cats.Monad") {
    val result: List[Int] = reify[List, Int] {
      reflect[List, Int, Int](List(1, 2)) + reflect[List, Int, Int](List(10, 20))
    }
    assertEquals(result, List(11, 21, 12, 22))
  }

  test("reflect propagates empty list for List via cats.Monad") {
    val result: List[Int] = reify[List, Int] {
      val x = reflect[List, Int, Int](List(1))
      val y = reflect[List, Int, Int](List.empty[Int])
      x + y
    }
    assertEquals(result, Nil)
  }

  test("reflect and reify round-trip single value for List via cats.Monad") {
    val result: List[Int] = reify[List, Int] {
      reflect[List, Int, Int](List(5))
    }
    assertEquals(result, List(5))
  }
