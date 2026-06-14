package integrationTests.integration

import continuations.*

class TryCatchSuite extends munit.FunSuite:

  test("try/catch with no exception") {
    val result = reset {
      try {
        val x = shift[Int, Int](k => k(1))
        x + 1
      } catch {
        case _: Exception => -1
      }
    }
    assertEquals(result, 2)
  }

  test("try/catch catches exception from continuation body") {
    val result = reset {
      try {
        val x = shift[Int, Int](k => k(1))
        if x > 0 then throw new RuntimeException("boom")
        x
      } catch {
        case _: RuntimeException => 99
      }
    }
    assertEquals(result, 99)
  }

  test("try/catch catch branch may itself contain shift") {
    val result = reset {
      try
        throw new RuntimeException("boom")
      catch {
        case _: RuntimeException =>
          val x = shift[Int, Int](k => k(5))
          x + 1
      }
    }
    assertEquals(result, 6)
  }

  test("try/catch propagates unmatched exceptions") {
    intercept[java.io.IOException] {
      reset {
        try {
          val x = shift[Int, Int](k => k(1))
          throw new java.io.IOException(s"bad: $x")
        } catch {
          case _: RuntimeException => 99
        }
      }
    }
  }
