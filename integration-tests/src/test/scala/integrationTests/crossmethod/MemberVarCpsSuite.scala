package integrationTests.crossmethod

import continuations.*

class MemberVarCpsSuite extends munit.FunSuite:

  class FunctionC:
    var assignCount: Int = 0

    var xs: Int => CpsTransform[Int] ?=> Int =
      x => shift[Int, Int](k => k(x))

    def assignWithImmediateCpsPrelude(): Int =
      val value = reset[Int] {
        xs =
          assignCount += 1
          val base = shift[Int, Int](k => k(10))
          x => shift[Int, Int](k => k(base + x + assignCount))

        xs(1)
      }

      value + assignCount

  class ReceiverOrderC:
    var log: Vector[String] = Vector.empty
    val functionTarget = new FunctionC()

    def functionReceiver: FunctionC =
      log = log :+ "receiver"
      functionTarget

    def assignThroughEffectfulReceiver(): (Int, Vector[String]) =
      functionReceiver.xs = {
        log = log :+ "rhs"
        x => shift[Int, Int](k => k(x + 6))
      }

      (reset[Int](functionTarget.xs(1)), log)

    def assignImmediateCpsThroughEffectfulReceiver(): (Int, Vector[String]) =
      val value = reset[Int] {
        functionReceiver.xs = {
          log = log :+ "rhs"
          val base = shift[Int, Int](k => k(7))
          x => shift[Int, Int](k => k(base + x))
        }

        functionTarget.xs(1)
      }

      (value, log)

  test("class member var assignment RHS side effects and CPS prelude run once") {
    assertEquals(new FunctionC().assignWithImmediateCpsPrelude(), 13)
  }

  test("class member var assignment evaluates qualified receiver before RHS") {
    assertEquals(new ReceiverOrderC().assignThroughEffectfulReceiver(), (7, Vector("receiver", "rhs")))
  }

  test("class member var assignment evaluates qualified receiver before immediate CPS RHS prelude") {
    assertEquals(new ReceiverOrderC().assignImmediateCpsThroughEffectfulReceiver(), (8, Vector("receiver", "rhs")))
  }
