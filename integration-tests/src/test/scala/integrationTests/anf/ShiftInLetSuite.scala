package integrationTests.anf

import continuations.*

class ShiftInLetSuite extends munit.FunSuite:

  test("shift in let position") {
    val result = reset {
      val x = shift[Int, Int](k => k(1))
      x + 1
    }
    assertEquals(result, 2)
  }
