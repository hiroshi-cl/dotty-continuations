package integrationTests.shiftReset

import continuations.*

class NonLocalExitSuite extends munit.FunSuite:

  test("shift can abort without invoking the continuation") {
    val result = reset {
      shift[Int, Int](_ => 999)
    }

    assertEquals(result, 999)
  }
