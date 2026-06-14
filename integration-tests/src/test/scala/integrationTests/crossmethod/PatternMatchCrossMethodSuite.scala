package integrationTests.crossmethod

import continuations.*

class PatternMatchCrossMethodSuite extends munit.FunSuite:

  def matchTest0(x: Int): CpsTransform[Int] ?=> Int = x match
    case 7 => shift[Int, Int](k => k(k(k(x))))
    case 8 => shift[Int, Int](k => k(x))

  test("match0: both cases shift") {
    assertEquals(10, reset(1 + matchTest0(7)))
    assertEquals(9, reset(1 + matchTest0(8)))
  }

  def matchTest1(x: Int): CpsTransform[Int] ?=> Int = x match
    case 7 => shift[Int, Int](k => k(k(k(x))))
    case _ => x

  test("match1: wildcard case is plain") {
    assertEquals(10, reset(1 + matchTest1(7)))
    assertEquals(9, reset(1 + matchTest1(8)))
  }
