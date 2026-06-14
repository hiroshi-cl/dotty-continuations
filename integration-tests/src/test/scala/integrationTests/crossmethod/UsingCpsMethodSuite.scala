package integrationTests.crossmethod

import continuations.*

class UsingCpsMethodHelpers:
  def shiftedNullary(using CpsTransform[Int]): Int =
    shift[Int, Int](k => k(7))

  def shiftedEmpty()(using CpsTransform[Int]): Int =
    shift[Int, Int](k => k(7))

  def shifted(x: Int)(using CpsTransform[Int]): Int =
    shift[Int, Int](k => k(x + 2))

  def shiftedExpr(x: Int)(using CpsTransform[Int]): Int =
    shift[Int, Int](k => k(x + 1)) + 1

class UsingCpsMethodSuite extends munit.FunSuite:

  def runMemberUsingMethod: Int =
    val helper = new UsingCpsMethodHelpers

    reset[Int] {
      helper.shifted(5)
    }

  def runMemberNullaryUsingMethod: Int =
    val helper = new UsingCpsMethodHelpers

    reset[Int] {
      helper.shiftedNullary
    }

  def runMemberEmptyParenUsingMethod: Int =
    val helper = new UsingCpsMethodHelpers

    reset[Int] {
      helper.shiftedEmpty()
    }

  def runMemberUsingMethodExpr: Int =
    val helper = new UsingCpsMethodHelpers

    reset[Int] {
      helper.shiftedExpr(5)
    }

  test("using CpsTransform method works across class boundary") {
    assertEquals(runMemberUsingMethod, 7)
  }

  test("nullary using CpsTransform method works across class boundary") {
    assertEquals(runMemberNullaryUsingMethod, 7)
  }

  test("empty-paren using CpsTransform method works across class boundary") {
    assertEquals(runMemberEmptyParenUsingMethod, 7)
  }

  test("using CpsTransform method body can compose shifted result") {
    assertEquals(runMemberUsingMethodExpr, 7)
  }
