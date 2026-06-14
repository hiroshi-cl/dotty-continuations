package integrationTests.anf

import continuations.*

class ShiftInApplySuite extends munit.FunSuite:

  def f(x: Int): Int = x * 2
  def g(x: Int, y: Int): Int = x + y

  test("shift in apply argument position") {
    val result = reset {
      f(shift[Int, Int](k => k(1)))
    }
    assertEquals(result, 2)
  }

  test("multiple shifts in apply arguments") {
    val result = reset {
      g(shift[Int, Int](k => k(1)), shift[Int, Int](k => k(2)))
    }
    assertEquals(result, 3)
  }
