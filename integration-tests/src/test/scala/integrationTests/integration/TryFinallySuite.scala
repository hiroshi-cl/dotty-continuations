package integrationTests.integration

import continuations.*

class TryFinallySuite extends munit.FunSuite:

  test("try/finally runs finalizer") {
    var ran = false
    val result = reset {
      try {
        val x = shift[Int, Int](k => k(1))
        x + 1
      } finally ran = true
    }
    assertEquals(result, 2)
    assert(ran)
  }

  test("try/finally runs finalizer when the continuation throws") {
    var runs = 0

    intercept[IllegalStateException] {
      reset[Int] {
        try {
          val x = shift[Int, Int](k => k(1))
          if x == 1 then throw new IllegalStateException("body failed")
          x
        } finally runs += 1
      }
    }

    assertEquals(runs, 1)
  }

  test("try/finally lets a finalizer exception replace the body exception") {
    val error = intercept[IllegalArgumentException] {
      reset[Int] {
        try {
          val x = shift[Int, Int](k => k(1))
          if x == 1 then throw new IllegalStateException("body failed")
          x
        } finally throw new IllegalArgumentException("finalizer failed")
      }
    }

    assertEquals(error.getMessage, "finalizer failed")
  }
