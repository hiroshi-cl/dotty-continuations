package integrationTests.cps

import continuations.*

class BasicCpsSuite extends munit.FunSuite:

  def f(x: Int): Int = x * 2
  def g(x: Int, y: Int): Int = x + y

  test("shift in let position produces correct value") {
    val result = reset {
      val x = shift[Int, Int](k => k(1))
      x + 1
    }
    assertEquals(result, 2)
  }

  test("shift in apply argument position produces correct value") {
    val result = reset {
      f(shift[Int, Int](k => k(1)))
    }
    assertEquals(result, 2)
  }

  test("multiple shifts chain correctly") {
    val result = reset {
      g(shift[Int, Int](k => k(1)), shift[Int, Int](k => k(2)))
    }
    assertEquals(result, 3)
  }
