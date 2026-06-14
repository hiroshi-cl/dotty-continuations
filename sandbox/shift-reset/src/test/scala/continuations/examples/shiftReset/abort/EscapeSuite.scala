package continuations.examples.shiftReset.abort

import continuations.*

class EscapeSuite extends munit.FunSuite:

  test("escape can invoke the current continuation") {
    val result = reset {
      escape[Int, Int](k => k(100))
    }

    assertEquals(result, 100)
  }

  test("escape can discard the current continuation") {
    val result = reset {
      escape[Int, Int](_ => 42)
      100
    }

    assertEquals(result, 42)
  }
