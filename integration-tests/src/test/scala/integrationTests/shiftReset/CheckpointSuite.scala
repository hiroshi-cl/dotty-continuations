package integrationTests.shiftReset

import continuations.*

class CheckpointSuite extends munit.FunSuite:

  test("saved continuation can be replayed with a different value") {
    var saved: Int => String = null

    val initial = reset {
      val value = shift[Int, String] { k =>
        saved = k
        k(1)
      }
      s"value: $value"
    }

    assertEquals(initial, "value: 1")
    assertEquals(saved(100), "value: 100")
  }
