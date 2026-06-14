package integrationTests.crossmethod

import continuations.*

class TryCatchCrossMethodSuite extends munit.FunSuite:

  def tryCatchFoo(): CpsTransform[Int] ?=> Int =
    try shift[Int, Int](k => k(7))
    catch case _: Throwable => 9

  def tryCatchBar(): CpsTransform[Int] ?=> Int =
    try 7
    catch case _: Throwable => 9

  test("trycatch0: try/catch with shift") {
    assertEquals(10, reset(tryCatchFoo() + 3))
    assertEquals(10, reset(tryCatchBar() + 3))
  }

  def fatal: Int = throw new Exception("fatal")

  def tryCatchFoo1(): CpsTransform[Int] ?=> Int =
    try { fatal; shift[Int, Int](k => k(7)) }
    catch case _: Throwable => 9

  def tryCatchFoo2(): CpsTransform[Int] ?=> Int =
    try { shift[Int, Int](k => k(7)); fatal }
    catch case _: Throwable => 9

  test("trycatch1: exception thrown before/after shift is caught") {
    assertEquals(12, reset(tryCatchFoo1() + 3))
    assertEquals(12, reset(tryCatchFoo2() + 3))
  }

  def tryCatchFoo3(): CpsTransform[Int] ?=> Int =
    try {
      val x = shift[Int, Int](k => k(7))
      throw new IllegalStateException(s"bad: $x")
    } catch {
      case _: IllegalStateException =>
        val y = shift[Int, Int](k => k(9))
        y + 1
    }

  test("trycatch2: catch branch in CPS method may itself contain shift") {
    assertEquals(13, reset(tryCatchFoo3() + 3))
  }
