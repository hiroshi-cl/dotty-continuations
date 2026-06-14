package integrationTests.crossmethod

import continuations.*

class BasicsCrossMethodSuite extends munit.FunSuite:

  def m0(): CpsTransform[Int] ?=> Int =
    shift[Int, Int](k => k(k(7))) * 2

  def m1(): CpsTransform[Int] ?=> Int =
    2 * shift[Int, Int](k => k(k(7)))

  test("basics: m0 cross-method") {
    assertEquals(28, reset(m0()))
  }

  test("basics: m1 cross-method") {
    assertEquals(28, reset(m1()))
  }
