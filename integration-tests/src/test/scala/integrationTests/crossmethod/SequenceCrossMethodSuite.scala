package integrationTests.crossmethod

import continuations.*

class SequenceCrossMethodSuite extends munit.FunSuite:

  def double(n: Int)(k: Int => Unit): Unit = k(n * 2)

  test("t2864: multiple shifts in sequence") {
    reset {
      val result1 = shift[Int, Unit](double(100))
      val result2 = shift[Int, Unit](double(result1))
      assertEquals(400, result2)
    }
  }
