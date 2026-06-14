package integrationTests.shiftReset

import continuations.*

class CoroutineSuite extends munit.FunSuite:

  test("shift can save a continuation and resume it later") {
    val log = collection.mutable.ListBuffer.empty[String]
    var saved: Int => Unit = null

    reset {
      log += "A"
      val x = shift[Int, Unit] { k =>
        saved = k
        log += "paused"
      }
      log += s"B: $x"
    }

    saved(42)

    assertEquals(log.toList, List("A", "paused", "B: 42"))
  }
