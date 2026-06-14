package integrationTests.shiftReset

import continuations.*

class MultiCallSuite extends munit.FunSuite:

  test("shift can invoke the continuation multiple times") {
    val result = reset {
      val x = shift[Int, Int](k => k(10) + k(20))
      x + 1
    }

    assertEquals(result, 32)
  }
