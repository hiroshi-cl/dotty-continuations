package integrationTests.crossmethod

import continuations.*

class IfThenElseCrossMethodSuite extends munit.FunSuite:

  def ifTest0(x: Int): CpsTransform[Int] ?=> Int =
    if x <= 7 then shift[Int, Int](k => k(k(k(x))))
    else shift[Int, Int](k => k(x))

  test("ifelse0: both branches shift") {
    assertEquals(10, reset(1 + ifTest0(7)))
    assertEquals(9, reset(1 + ifTest0(8)))
  }

  def ifTest1a(x: Int): CpsTransform[Int] ?=> Int =
    if x <= 7 then shift[Int, Int](k => k(k(k(x))))
    else x

  def ifTest1b(x: Int): CpsTransform[Int] ?=> Int =
    if x <= 7 then x
    else shift[Int, Int](k => k(k(k(x))))

  test("ifelse1: one branch shifts") {
    assertEquals(10, reset(1 + ifTest1a(7)))
    assertEquals(9, reset(1 + ifTest1a(8)))
    assertEquals(8, reset(1 + ifTest1b(7)))
    assertEquals(11, reset(1 + ifTest1b(8)))
  }

  def utilBool(x: Boolean): CpsTransform[Int] ?=> Boolean =
    shift[Boolean, Int](k => k(x))

  def ifTest2(x: Int): CpsTransform[Int] ?=> Int =
    if utilBool(x <= 7) then x - 1
    else x + 1

  test("ifelse3: shift in condition") {
    assertEquals(6, reset(ifTest2(7)))
    assertEquals(9, reset(ifTest2(8)))
  }
