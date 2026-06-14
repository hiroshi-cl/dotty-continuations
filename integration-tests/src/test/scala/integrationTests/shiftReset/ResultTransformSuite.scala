package integrationTests.shiftReset

import continuations.*

class ResultTransformSuite extends munit.FunSuite:

  test("shift can transform the continuation result") {
    val result = reset {
      val x = shift[Int, Int](k => k(1) * 100)
      x + 2
    }

    assertEquals(result, 300)
  }
