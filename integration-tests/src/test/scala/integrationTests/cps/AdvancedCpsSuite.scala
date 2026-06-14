package integrationTests.cps

import continuations.*

class AdvancedCpsSuite extends munit.FunSuite:

  test("if branch in shift context") {
    val result = reset {
      val x = shift[Int, Int](k => k(1))
      if x > 0 then x * 2 else x * 3
    }
    assertEquals(result, 2)
  }

  test("shift with non-trivial continuation") {
    val result = reset {
      val x = shift[Int, Int](k => k(k(1)))
      x + 10
    }
    assertEquals(result, 21)
  }

  test("reset with no shift returns plain value") {
    val result = reset {
      val x = 42
      x + 1
    }
    assertEquals(result, 43)
  }
