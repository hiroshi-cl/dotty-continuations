package integrationTests.crossmethod

import continuations.*

class ListFlatMapCrossMethodSuite extends munit.FunSuite:

  test("t2934: list flatMap via shift") {
    assertEquals(
      List(3, 4, 5),
      reset {
        val x = shift[Int, List[Int]](k => List(1, 2, 3).flatMap(k))
        List(x + 2)
      }
    )
  }
