package continuations.examples.shiftReset.around

import continuations.*

class TracingSuite extends munit.FunSuite:

  test("inspect logs values while preserving the result") {
    val log = collection.mutable.ListBuffer.empty[String]

    val result = reset {
      val x = inspect[Int, Int]("x", 10, log)
      val y = inspect[Int, Int]("y", x + 20, log)
      y + 10
    }

    assertEquals(result, 40)
    assertEquals(log.toList, List("x = 10", "y = 30"))
  }
