package continuations.examples.shiftReset.around

import continuations.*

class ResourceSuite extends munit.FunSuite:

  test("registerCleanup runs cleanup after the body") {
    val log = collection.mutable.ListBuffer.empty[String]

    val result = reset {
      registerCleanup[Int](log += "cleanup")
      log += "body"
      42
    }

    assertEquals(result, 42)
    assertEquals(log.toList, List("body", "cleanup"))
  }

  test("registerCleanup runs cleanup and preserves an exception") {
    val log = collection.mutable.ListBuffer.empty[String]
    val expected = new IllegalStateException("boom")

    val thrown = intercept[IllegalStateException] {
      reset[Unit] {
        registerCleanup[Unit](log += "cleanup")
        log += "body"
        throw expected
      }
    }

    assert(thrown eq expected)
    assertEquals(log.toList, List("body", "cleanup"))
  }
