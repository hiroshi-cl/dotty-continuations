package integrationTests.crossmethod

import continuations.*

final case class PairCpsVal(left: Int, right: Int)

class PatternCpsValSuite extends munit.FunSuite:

  test("pattern val with immediate CPS tuple rhs is lifted through reset") {
    val result =
      reset[Int] {
        val (a, b) =
          shift[(Int, Int), Int](k => k((3, 4)))
        a + b
      }

    assertEquals(result, 7)
  }

  test("pattern val with immediate CPS rhs evaluates scrutinee only once") {
    var evalCount = 0

    val result =
      reset[Int] {
        val (a, b) =
          shift[(Int, Int), Int] { k =>
            evalCount += 1
            k((3, 4))
          }
        a + b
      }

    assertEquals(result, 7)
    assertEquals(evalCount, 1)
  }

  test("pattern val with immediate CPS case class rhs is lifted through reset") {
    val result =
      reset[Int] {
        val PairCpsVal(a, b) =
          shift[PairCpsVal, Int](k => k(PairCpsVal(2, 5)))
        a + b
      }

    assertEquals(result, 7)
  }

  test("nested pattern val with immediate CPS tuple rhs is lifted through reset") {
    val result =
      reset[Int] {
        val ((a, b), c) =
          shift[((Int, Int), Int), Int](k => k(((1, 2), 4)))
        a + b + c
      }

    assertEquals(result, 7)
  }

  test("multiple immediate CPS pattern vals compose in sequence") {
    val result =
      reset[Int] {
        val (a, b) =
          shift[(Int, Int), Int](k => k((1, 2)))
        val PairCpsVal(c, d) =
          shift[PairCpsVal, Int](k => k(PairCpsVal(3, 1)))
        a + b + c + d
      }

    assertEquals(result, 7)
  }

  test("refutable pattern val with immediate CPS rhs keeps MatchError behavior") {
    intercept[MatchError] {
      reset[Int] {
        val Some(x) =
          shift[Option[Int], Int](k => k(None))
        x
      }
    }
  }
