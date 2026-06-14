package integrationTests.crossmethod

import continuations.{shift, reset, shiftR, CpsTransform, ControlContext}

case class ContainerBox[A](value: A)

class MemberContainerDefProvider:
  def make: ContainerBox[Int => (CpsTransform[Int] ?=> Int)] =
    ContainerBox(x => shift[Int, Int](k => k(x + 11)))

  def consume(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
    reset[Int](xs.head(5))

  def consumeViaThis(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
    reset[Int](this.make.value(1) + xs.head(2))

object MemberContainerDefProvider:
  def make: ContainerBox[Int => (CpsTransform[Int] ?=> Int)] =
    ContainerBox(x => shift[Int, Int](k => k(x + 21)))

  def consume(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
    reset[Int](xs.head(5))

class CpsArgMethodSuite extends munit.FunSuite:

  object MemberFunctionValueFactory:
    def make: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 10))

  def m1(body: CpsTransform[Int] ?=> Int): Int =
    reset[Int](body * 2)

  def m2(x: Int)(body: CpsTransform[Int] ?=> Int): Int =
    reset[Int](body + x)

  def m3(body: CpsTransform[Int] ?=> Int): Int =
    reset[Int](body + body)

  def m4(b1: CpsTransform[Int] ?=> Int, b2: CpsTransform[Int] ?=> Int): Int =
    reset[Int](b1 + b2)

  def m5(body: CpsTransform[Int] ?=> Int): CpsTransform[Int] ?=> Int =
    shift[Int, Int](k => k(10))

  def m6(body: CpsTransform[Int] ?=> Int): CpsTransform[Int] ?=> Int =
    reset[Int](body + 1)

  def consume(cc: CpsTransform[Int] ?=> Int): Int =
    reset[Int](cc)

  def shadowOuter(body: CpsTransform[Int] ?=> Int): Int =
    shadowInner {
      shift[Int, Int](k => k(6))
    }

  def shadowInner(body: CpsTransform[Int] ?=> Int): Int =
    reset[Int](body + 1)

  def polyConsume(f: [A] => A => (CpsTransform[A] ?=> A)): String =
    reset[String](f[String]("ok"))

  def polyConsumeCurried(f: [A] => Int => A => (CpsTransform[A] ?=> A)): String =
    reset[String](f[String](1)("ok"))

  def consumeNonTailCallbackRunner(
    runner: (Int => (CpsTransform[Int] ?=> Int), Int) => (CpsTransform[Int] ?=> Int),
    cb: Int => (CpsTransform[Int] ?=> Int)
  ): Int =
    reset[Int](runner(cb, 4))

  def memberCpsFunction(x: Int): CpsTransform[Int] ?=> Int =
    shift[Int, Int](k => k(x + 1))

  def polyConsumeNonTailCallbackRunner[A](
    runner: (A => (CpsTransform[A] ?=> A), A) => (CpsTransform[A] ?=> A),
    cb: A => (CpsTransform[A] ?=> A),
    seed: A
  ): A =
    reset[A](runner(cb, seed))

  def consumeStoredFunctionValue(f: Int => (CpsTransform[Int] ?=> Int)): Int =
    val g = f
    reset[Int](g(5))

  def idFunctionValue(f: Int => (CpsTransform[Int] ?=> Int)): Int => (CpsTransform[Int] ?=> Int) =
    f

  def roundTripBox[A](b: ContainerBox[A]): ContainerBox[A] =
    b

  def consumeFunctionValue(f: Int => (CpsTransform[Int] ?=> Int)): Int =
    reset[Int](f(5))

  test("cps-arg: basic single CPS-arg") {
    assertEquals(m1(shift[Int, Int](k => k(5))), 10)
  }

  test("cps-arg: CPS-arg and regular arg mixed") {
    assertEquals(m2(3)(shift[Int, Int](k => k(7))), 10)
  }

  test("cps-arg: body used twice inside reset") {
    assertEquals(m3(shift[Int, Int](k => k(4))), 8)
  }

  test("cps-arg: two CPS-arg params") {
    assertEquals(m4(shift[Int, Int](k => k(3)), shift[Int, Int](k => k(7))), 10)
  }

  test("cps-arg: CPS-arg and CPS-return") {
    val stub = classOf[CpsArgMethodSuite].getDeclaredMethods.find(_.getName == "m5$transformed").getOrElse {
      fail("m5$transformed not generated")
    }
    val result = stub.invoke(this, shiftR[Int, Int](k => k(5))).asInstanceOf[ControlContext[Int, Int]]
    assertEquals(result.foreach(identity), 10)
  }

  test("cps-arg: CPS-arg and CPS-return can flow through another def before reset") {
    def staged(): CpsTransform[Int] ?=> Int =
      m6 {
        shift[Int, Int](k => k(8))
      }

    assertEquals(reset[Int](staged()), 9)
  }

  test("cps-arg: CPS-arg and CPS-return can be passed to another method before consumption") {
    def staged(): CpsTransform[Int] ?=> Int =
      m6 {
        shift[Int, Int](k => k(5))
      }

    assertEquals(consume(staged()), 6)
  }

  test("cps-arg: same-name CPS parameter in callee does not capture caller parameter") {
    assertEquals(
      shadowOuter {
        shift[Int, Int](k => k(100))
      },
      7
    )
  }

  test("cps-arg: polymorphic CPS function parameter") {
    val poly: [A] => A => (CpsTransform[A] ?=> A) =
      [A] => (x: A) => shift[A, A](k => k(x))

    assertEquals(polyConsume(poly), "ok")
  }

  test("cps-arg: curried polymorphic CPS function parameter") {
    val poly: [A] => Int => A => (CpsTransform[A] ?=> A) =
      [A] => (_: Int) => (x: A) => shift[A, A](k => k(x))

    assertEquals(polyConsumeCurried(poly), "ok")
  }

  test("cps-arg: local polymorphic CPS val forwards into poly CPS parameter through plain value and alias") {
    def forwardPlain: String =
      val poly: [A] => A => (CpsTransform[A] ?=> A) =
        [A] => (x: A) => shift[A, A](k => k(x))

      polyConsume(poly)

    def forwardAlias: String =
      val poly: [A] => A => (CpsTransform[A] ?=> A) =
        [A] => (x: A) => shift[A, A](k => k(x))
      val alias = poly

      polyConsume(alias)

    assertEquals(forwardPlain, "ok")
    assertEquals(forwardAlias, "ok")
  }

  test("cps-arg: local curried polymorphic CPS val forwards into poly CPS parameter through plain value and alias") {
    def forwardPlain: String =
      val poly: [A] => Int => A => (CpsTransform[A] ?=> A) =
        [A] => (_: Int) => (x: A) => shift[A, A](k => k(x))

      polyConsumeCurried(poly)

    def forwardAlias: String =
      val poly: [A] => Int => A => (CpsTransform[A] ?=> A) =
        [A] => (_: Int) => (x: A) => shift[A, A](k => k(x))
      val alias = poly

      polyConsumeCurried(alias)

    assertEquals(forwardPlain, "ok")
    assertEquals(forwardAlias, "ok")
  }

  test("cps-arg: function value with non-tail CPS callback parameter forwards through plain value and alias") {
    def forwardPlain: Int =
      val cb: Int => (CpsTransform[Int] ?=> Int) =
        x => shift[Int, Int](k => k(x + 1))
      val runner: (Int => (CpsTransform[Int] ?=> Int), Int) => (CpsTransform[Int] ?=> Int) =
        (callback, n) => callback(n) + 1

      consumeNonTailCallbackRunner(runner, cb)

    def forwardAlias: Int =
      val cb: Int => (CpsTransform[Int] ?=> Int) =
        x => shift[Int, Int](k => k(x + 1))
      val runner: (Int => (CpsTransform[Int] ?=> Int), Int) => (CpsTransform[Int] ?=> Int) =
        (callback, n) => callback(n) + 1
      val alias = runner

      consumeNonTailCallbackRunner(alias, cb)

    assertEquals(forwardPlain, 6)
    assertEquals(forwardAlias, 6)
  }

  test("cps-arg: member CPS method eta still works as a function value") {
    val f: Int => (CpsTransform[Int] ?=> Int) = memberCpsFunction
    val g = f

    assertEquals(reset[Int](g(6)), 7)
  }

  test("cps-arg: polymorphic method consumes function value with non-tail CPS callback parameter") {
    val cb: String => (CpsTransform[String] ?=> String) =
      x => shift[String, String](k => k(x + "!"))
    val runner: (String => (CpsTransform[String] ?=> String), String) => (CpsTransform[String] ?=> String) =
      (callback, seed) => callback(seed) + "?"

    assertEquals(polyConsumeNonTailCallbackRunner[String](runner, cb, "ok"), "ok!?")
  }

  test("cps-arg: member method returning CPS function value direct apply") {
    assertEquals(reset[Int](MemberFunctionValueFactory.make(5)), 15)
  }

  test("cps-arg: member method returning CPS function value can be held before reset") {
    val f = MemberFunctionValueFactory.make

    assertEquals(reset[Int](f(5)), 15)
  }

  test("cps-arg: CPS function value parameter can be stored locally before consumption") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    assertEquals(consumeStoredFunctionValue(f), 6)
  }

  test("cps-arg: CPS function value parameter can be returned through identity before consumption") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 2))
    val g = idFunctionValue(f)

    assertEquals(reset[Int](g(5)), 7)
  }

  test("cps-arg: CPS function value parameter forwards through identity into higher-order consumer") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 3))

    assertEquals(consumeFunctionValue(idFunctionValue(f)), 8)
  }

  test("container-return: roundTrip generic identity passes Box[CPS fn] through local rewrite") {
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))
    val r = roundTripBox(ContainerBox(inner))
    assertEquals(reset[Int](r.value(5)), 6)
  }

  test("cps-arg: nested if selects CPS function value parameter or member method") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    def choose(cond: Boolean): Int =
      val g = if cond then f else MemberFunctionValueFactory.make
      reset[Int](g(5))

    assertEquals(choose(true), 6)
    assertEquals(choose(false), 15)
  }

  test("cps-arg: nested block stores CPS function value parameter alias") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 4))

    val g =
      val h = f
      h

    assertEquals(reset[Int](g(5)), 9)
  }

  test("cps-arg: nested match selects CPS function value parameter or member method") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 5))

    def choose(n: Int): Int =
      val g = n match
        case 0 => f
        case _ => MemberFunctionValueFactory.make
      reset[Int](g(5))

    assertEquals(choose(0), 10)
    assertEquals(choose(1), 15)
  }

  test("cps-arg: nested try selects CPS function value parameter or member method") {
    val f: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 6))

    def choose(fail: Boolean): Int =
      val g =
        try
          if fail then throw new RuntimeException("fallback")
          else f
        catch case _: RuntimeException => MemberFunctionValueFactory.make
      reset[Int](g(5))

    assertEquals(choose(false), 11)
    assertEquals(choose(true), 15)
  }

  test("container-param: local consume(List(inner))") {
    def consumeList(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
      reset[Int](xs.head(5))

    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    assertEquals(consumeList(List(inner)), 6)
  }

  test("container-param: local consume with forwarding via local alias ys = xs") {
    def forwardList(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
      val ys = xs
      reset[Int](ys.head(5))

    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    assertEquals(forwardList(List(inner)), 6)
  }

  test("container-param: local consume with Map value side") {
    def consumeMapValue(m: Map[String, Int => (CpsTransform[Int] ?=> Int)]): Int =
      reset[Int](m("key")(5))

    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    assertEquals(consumeMapValue(Map("key" -> inner)), 6)
  }

  test("container-param: direct CPS param and container CPS param mixed") {
    def consumeMixed(body: CpsTransform[Int] ?=> Int, xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
      reset[Int](body + xs.head(5))

    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    assertEquals(consumeMixed(shift[Int, Int](k => k(10)), List(inner)), 16)
  }

  test("container-param: pure condition rewrites to transformed container param") {
    def consumeIfNonEmpty(xs: List[Int => (CpsTransform[Int] ?=> Int)]): Int =
      if xs.nonEmpty then reset[Int](xs.head(5)) else 0

    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))
    val empty: List[Int => (CpsTransform[Int] ?=> Int)] = Nil

    assertEquals(consumeIfNonEmpty(List(inner)), 6)
    assertEquals(consumeIfNonEmpty(empty), 0)
  }

  test("container-param: CPS result branch rewrites pure condition") {
    def chooseResult(xs: List[Int => (CpsTransform[Int] ?=> Int)]): CpsTransform[Int] ?=> Int =
      if xs.nonEmpty then xs.head(5) else 0

    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))
    val empty: List[Int => (CpsTransform[Int] ?=> Int)] = Nil

    assertEquals(reset[Int](chooseResult(List(inner))), 6)
    assertEquals(reset[Int](chooseResult(empty)), 0)
  }

  test("member container-return: direct select inside reset") {
    val provider = MemberContainerDefProvider()

    assertEquals(reset[Int](provider.make.value(5)), 16)
  }

  test("member container-return: transformed body is generated") {
    val provider = MemberContainerDefProvider()
    val transformed = classOf[MemberContainerDefProvider].getDeclaredMethods
      .find(_.getName == "make$transformed")
      .getOrElse(fail("make$transformed not generated"))
    val box = transformed.invoke(provider).asInstanceOf[ContainerBox[Int => ControlContext[Int, Int]]]

    assertEquals(box.value(5).foreach(identity), 16)
  }

  test("member container-return: value can be held before reset") {
    val provider = MemberContainerDefProvider()
    val box = provider.make

    assertEquals(reset[Int](box.value(5)), 16)
  }

  test("member container-param: argument is rewritten") {
    val provider = MemberContainerDefProvider()
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 2))

    assertEquals(reset[Int](provider.consume(List(inner)) + 1), 8)
  }

  test("member container-param: transformed body is generated") {
    val provider = MemberContainerDefProvider()
    val transformed = classOf[MemberContainerDefProvider].getDeclaredMethods
      .find(_.getName == "consume$transformed")
      .getOrElse(fail("consume$transformed not generated"))
    val inner: Int => ControlContext[Int, Int] =
      x => shiftR[Int, Int](k => k(x + 2))

    assertEquals(transformed.invoke(provider, List(inner)).asInstanceOf[Int], 7)
  }

  test("member containers: this-select definitions rewrite together") {
    val provider = MemberContainerDefProvider()
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 3))

    assertEquals(provider.consumeViaThis(List(inner)), 17)
  }

  test("member containers: object definitions work") {
    val inner: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 4))

    assertEquals(reset[Int](MemberContainerDefProvider.make.value(5)), 26)
    assertEquals(reset[Int](MemberContainerDefProvider.consume(List(inner)) + 1), 10)
  }
