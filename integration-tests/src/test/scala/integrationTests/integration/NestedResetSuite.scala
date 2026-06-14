package integrationTests.integration

import continuations.*

class NestedResetSuite extends munit.FunSuite:

  test("nested reset") {
    val result = reset {
      val x = reset {
        shift[Int, Int](k => k(1))
      }
      x + 10
    }
    assertEquals(result, 11)
  }
