package integrationTests.shiftReset

import continuations.*

class LazyDemandSuite extends munit.FunSuite:

  test("shift can expose continuation demand lazily through a thunk") {
    val log = collection.mutable.ListBuffer.empty[String]

    val thunk = reset {
      val x = shift[Int, () => Int](k => () => k(7)())
      () => {
        log += s"demanded: $x"
        x + 1
      }
    }

    assertEquals(log.toList, Nil)
    assertEquals(thunk(), 8)
    assertEquals(log.toList, List("demanded: 7"))
  }
