package integrationTests.crossmethod

import continuations.*

class QualifiedCrossMethodCallSuite extends munit.FunSuite:

  class Runner:
    def cpsValue(n: Int): CpsTransform[Int] ?=> Int =
      shift[Int, Int](k => k(n + 1))

    def cpsCurried(prefix: Int)(n: Int): CpsTransform[Int] ?=> Int =
      shift[Int, Int](k => k(prefix + n))

    def cpsPoly[A](value: A): CpsTransform[A] ?=> A =
      shift[A, A](k => k(value))

  class Holder:
    val runner = new Runner

  test("qualified instance method with CPS return preserves receiver") {
    val runner = new Runner
    assertEquals(
      3,
      reset {
        runner.cpsValue(2)
      }
    )
  }

  test("qualified curried instance method with CPS return preserves receiver and prefix args") {
    val runner = new Runner
    assertEquals(
      8,
      reset {
        runner.cpsCurried(5)(3)
      }
    )
  }

  test("stable qualifier chain with CPS return preserves receiver path") {
    val holder = new Holder
    assertEquals(
      17,
      reset {
        holder.runner.cpsValue(6) + 10
      }
    )
  }

  test("qualified polymorphic instance method with CPS return preserves receiver and type args") {
    val runner = new Runner
    assertEquals(
      "ok",
      reset {
        runner.cpsPoly[String]("ok")
      }
    )
  }
