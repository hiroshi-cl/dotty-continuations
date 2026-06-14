package integrationTests.crossmethod

import continuations.*

class BindChainRegressionSuite extends munit.FunSuite:

  def twice(n: Int)(k: Int => Int): Int = k(n * 2)

  test("sequential binds keep local defs and captured values well-scoped") {
    val result = reset {
      val result1 = shift[Int, Int](twice(5))
      def plusCaptured(x: Int): Int = x + result1
      val result2 = shift[Int, Int](k => k(plusCaptured(2)))
      result1 + result2
    }

    assertEquals(result, 22)
  }
