package integrationTests.crossmethod

import continuations.*

case class SourceLevelBox[A](value: A)

object SourceLevelBoxProvider:
  def make: SourceLevelBox[Int => (CpsTransform[Int] ?=> Int)] =
    SourceLevelBox(x => shift[Int, Int](k => k(x + 10)))

object SourceLevelStablePolyProvider:
  def make[A]: A => (CpsTransform[A] ?=> A) =
    value => shift[A, A](k => k(value))

final class SourceLevelByNameCounter(var evaluations: Int)

object SourceLevelByNameProvider:
  def consumeByNameProvider(provider: => Int => (CpsTransform[Int] ?=> Int)): Int =
    val f = provider
    reset[Int](f(5))

  def countedProvider(counter: SourceLevelByNameCounter): Int => (CpsTransform[Int] ?=> Int) =
    counter.evaluations += 1
    value => shift[Int, Int](k => k(value + counter.evaluations))

class SourceLevelLoweringCpsFunctionValueSuite extends munit.FunSuite:

  def runLocalPolymorphicMethodHold: String =
    def make[A]: A => (CpsTransform[A] ?=> A) =
      value => shift[A, A](k => k(value))

    val f = make[String]

    reset[String](f("ok"))

  def runStableMemberPolymorphicMethodHold: String =
    val f = SourceLevelStablePolyProvider.make[String]

    reset[String](f("ok"))

  def runCurriedPolymorphicPartial: String =
    def make[A](prefix: A): A => (CpsTransform[A] ?=> A) =
      value =>
        val _ = value
        shift[A, A](k => k(prefix))

    val prefix = "ok"
    val f = make[String](prefix)

    reset[String](f("ignored"))

  def runByNameProviderEvaluation: (Int, Int) =
    val counter = SourceLevelByNameCounter(0)

    (
      SourceLevelByNameProvider.consumeByNameProvider(SourceLevelByNameProvider.countedProvider(counter)),
      counter.evaluations
    )

  test("local polymorphic method returning CPS function value can be held before reset") {
    assertEquals(runLocalPolymorphicMethodHold, "ok")
  }

  test("stable member polymorphic method returning CPS function value can be held before reset") {
    assertEquals(runStableMemberPolymorphicMethodHold, "ok")
  }

  test("curried polymorphic provider can be partially applied before reset consumption") {
    assertEquals(runCurriedPolymorphicPartial, "ok")
  }

  test("by-name provider returning CPS function value is evaluated once before reset consumption") {
    assertEquals(runByNameProviderEvaluation, (6, 1))
  }

  test("container-return: object member method returning Box[CPS fn] can be held before reset") {
    val b = SourceLevelBoxProvider.make
    assertEquals(reset[Int](b.value(5)), 15)
  }

  test("container-return: block-end form returning Box[CPS fn] can be held before reset") {
    val b =
      val x = 20
      SourceLevelBox[Int => (CpsTransform[Int] ?=> Int)](n => shift[Int, Int](k => k(n + x)))
    assertEquals(reset[Int](b.value(5)), 25)
  }
