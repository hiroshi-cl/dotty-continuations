package integrationTests.crossmethod

import continuations.*

case class Box[A](value: A)

class LocalCpsValSuite extends munit.FunSuite:

  test("local val with CPS return is rewritten through local transformed helper") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 2))

      reset[Int] {
        inner(5)
      }

    assertEquals(outer, 7)
  }

  test("aliased local CPS val keeps transformed rewrite") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 3))
      val alias = inner

      reset[Int] {
        alias(4)
      }

    assertEquals(outer, 7)
  }

  test("local CPS val remains a plain function value in pure context") {
    def pureUse: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 1))
      val alias = inner

      reset[Int] {
        alias(6)
      }

    assertEquals(pureUse, 7)
  }

  test("local CPS val eta over local CPS def remains a plain function value in pure context") {
    def pureUse: Int =
      def inner(x: Int): CpsTransform[Int] ?=> Int =
        shift[Int, Int](k => k(x + 1))
      val alias: Int => (CpsTransform[Int] ?=> Int) = inner

      reset[Int] {
        alias(6)
      }

    assertEquals(pureUse, 7)
  }

  test("local CPS val eta over local CPS def can be stored without being invoked") {
    def pureUse: Int =
      def inner(x: Int): CpsTransform[Int] ?=> Int =
        shift[Int, Int](k => k(x + 1))
      val alias: Int => (CpsTransform[Int] ?=> Int) = inner
      val stored = alias

      7

    assertEquals(pureUse, 7)
  }

  test("strict local CPS val shares immediately consumed CPS prelude with transformed sibling") {
    def outer: Int =
      reset[Int] {
        val inner: Int => (CpsTransform[Int] ?=> Int) =
          val base = shift[Int, Int](k => k(10))
          x => shift[Int, Int](k => k(base + x))

        inner(5)
      }

    assertEquals(outer, 15)
  }

  test("strict local CPS val storage prelude side effect runs once with transformed-only storage") {
    def outer: Int =
      var initCount = 0
      val inner: Int => (CpsTransform[Int] ?=> Int) =
        initCount += 1
        x => shift[Int, Int](k => k(x + initCount))

      val value = reset[Int] {
        inner(6)
      }

      value + initCount

    assertEquals(outer, 8)
  }

  test("local CPS val function body and shift callback side effects run when invoked") {
    def outer: Int =
      var bodyCount = 0
      var callbackCount = 0
      val inner: Int => (CpsTransform[Int] ?=> Int) =
        x =>
          bodyCount += 1
          shift[Int, Int] { k =>
            callbackCount += 1
            k(x)
          }

      val value = reset[Int] {
        inner(5)
      }

      value + bodyCount + callbackCount

    assertEquals(outer, 7)
  }

  test("local CPS val selected by if captures mutable state shared with CPS invocation") {
    def outer: Int =
      var selectionCount = 0
      var captured = 1
      val inner: Int => (CpsTransform[Int] ?=> Int) =
        if captured == 1 then
          selectionCount += 1
          x =>
            captured += 10
            shift[Int, Int](k => k(x + captured))
        else
          selectionCount += 100
          x => shift[Int, Int](k => k(x + captured + 100))

      captured = 2
      val value = reset[Int] {
        inner(5)
      }

      value + captured + selectionCount

    assertEquals(outer, 30)
  }

  test("local CPS val selected by match captures mutable state shared with CPS invocation") {
    def outer: Int =
      var selectionCount = 0
      var captured = 2
      val inner: Int => (CpsTransform[Int] ?=> Int) =
        captured match
          case 2 =>
            selectionCount += 1
            x =>
              captured += 20
              shift[Int, Int](k => k(x + captured))
          case _ =>
            selectionCount += 100
            x => shift[Int, Int](k => k(x + captured + 100))

      captured = 3
      val value = reset[Int] {
        inner(4)
      }

      value + captured + selectionCount

    assertEquals(outer, 51)
  }

  test("local CPS val selected by try/catch captures mutable state on normal and catch paths") {
    def normalPath: Int =
      var selectionCount = 0
      var captured = 3
      val inner: Int => (CpsTransform[Int] ?=> Int) =
        try
          selectionCount += 1
          x =>
            captured += 30
            shift[Int, Int](k => k(x + captured))
        catch
          case _: IllegalStateException =>
            selectionCount += 100
            x => shift[Int, Int](k => k(x + captured + 100))

      captured = 4
      val value = reset[Int] {
        inner(2)
      }

      value + captured + selectionCount

    def catchPath: Int =
      var selectionCount = 0
      var captured = 5
      val inner: Int => (CpsTransform[Int] ?=> Int) =
        try
          selectionCount += 1
          throw new IllegalStateException("select catch branch")
        catch
          case _: IllegalStateException =>
            selectionCount += 10
            x =>
              captured += 40
              shift[Int, Int](k => k(x + captured))

      captured = 6
      val value = reset[Int] {
        inner(1)
      }

      value + captured + selectionCount

    assertEquals(normalPath, 71)
    assertEquals(catchPath, 104)
  }

  test("local CPS val can be passed to a CPS-arg method") {
    def consume(body: CpsTransform[Int] ?=> Int): Int =
      reset[Int](body + 1)

    def outer: Int =
      val staged: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))

      consume(staged(6))

    assertEquals(outer, 7)
  }

  test("local CPS val forwards as higher-order value into CPS function parameter") {
    def consumeF(f: Int => (CpsTransform[Int] ?=> Int)): Int =
      reset[Int] {
        f(6)
      }

    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 1))

      consumeF(inner)

    assertEquals(outer, 7)
  }

  test("typed and chained local CPS val aliases forward as higher-order values") {
    def consumeF(f: Int => (CpsTransform[Int] ?=> Int)): Int =
      reset[Int] {
        f(5)
      }

    def typedAlias: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 2))
      val alias: Int => (CpsTransform[Int] ?=> Int) = inner

      consumeF(alias)

    def aliasChain: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 2))
      val alias1: Int => (CpsTransform[Int] ?=> Int) = inner
      val alias2 = alias1

      consumeF(alias2)

    assertEquals(typedAlias, 7)
    assertEquals(aliasChain, 7)
  }

  test("local lazy CPS val is rewritten through transformed sibling") {
    def outer: Int =
      lazy val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 2))

      reset[Int] {
        inner(5)
      }

    assertEquals(outer, 7)
  }

  test("local lazy CPS val initializes once along CPS path") {
    def outer: Int =
      var initCount = 0
      lazy val inner: Int => CpsTransform[Int] ?=> Int =
        initCount += 1
        x => shift[Int, Int](k => k(x))

      val first = reset[Int] {
        inner(3)
      }
      val second = reset[Int] {
        inner(4)
      }

      first + second + initCount

    assertEquals(outer, 8)
  }

  test("local lazy CPS val initializer side effect runs once under transformed reads") {
    def outer: Int =
      var initCount = 0
      lazy val inner: Int => CpsTransform[Int] ?=> Int =
        initCount += 1
        x => shift[Int, Int](k => k(x))

      val value = reset[Int] {
        inner(5)
      }

      value + initCount

    assertEquals(outer, 6)
  }

  test("local lazy CPS val forwards as higher-order value") {
    def consumeF(f: Int => (CpsTransform[Int] ?=> Int)): Int =
      reset[Int] {
        f(5)
      }

    def outer: Int =
      lazy val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 2))

      consumeF(inner)

    assertEquals(outer, 7)
  }

  test("local CPS var initial value is rewritten through transformed sibling") {
    def outer: Int =
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 1))

      reset[Int] {
        inner(6)
      }

    assertEquals(outer, 7)
  }

  test("local CPS var initializer side effect runs once with transformed-only storage") {
    def outer: Int =
      var initCount = 0
      var inner: Int => CpsTransform[Int] ?=> Int =
        initCount += 1
        x => shift[Int, Int](k => k(x))

      val value = reset[Int] {
        inner(5)
      }

      value + initCount

    assertEquals(outer, 6)
  }

  test("local CPS var initializer supports immediately consumed CPS prelude") {
    def outer: Int =
      reset[Int] {
        var inner: Int => CpsTransform[Int] ?=> Int =
          val base = shift[Int, Int](k => k(10))
          x => shift[Int, Int](k => k(base + x))

        inner(1)
      }

    assertEquals(outer, 11)
  }

  test("local CPS var initializer with immediate CPS prelude evaluates side effects once") {
    def outer: Int =
      var initCount = 0
      val value = reset[Int] {
        var inner: Int => CpsTransform[Int] ?=> Int =
          initCount += 1
          val base = shift[Int, Int](k => k(10))
          x => shift[Int, Int](k => k(base + x + initCount))

        inner(1)
      }

      value + initCount

    assertEquals(outer, 13)
  }

  test("local CPS var reassignment updates later CPS reads") {
    def outer: Int =
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 1))
      inner = x => shift[Int, Int](k => k(x + 2))

      reset[Int] {
        inner(5)
      }

    assertEquals(outer, 7)
  }

  test("local CPS var assignment supports immediately consumed CPS prelude") {
    def outer: Int =
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))

      reset[Int] {
        inner =
          val base = shift[Int, Int](k => k(10))
          x => shift[Int, Int](k => k(base + x))

        inner(1)
      }

    assertEquals(outer, 11)
  }

  test("local CPS var assignment with immediate CPS prelude evaluates side effects once") {
    def outer: Int =
      var assignCount = 0
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))

      val value = reset[Int] {
        inner =
          assignCount += 1
          val base = shift[Int, Int](k => k(10))
          x => shift[Int, Int](k => k(base + x + assignCount))

        inner(1)
      }

      value + assignCount

    assertEquals(outer, 13)
  }

  test("local CPS var assignment RHS side effect runs once with transformed-only storage") {
    def outer: Int =
      var assignCount = 0
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      inner =
        assignCount += 1
        x => shift[Int, Int](k => k(x + 1))

      val value = reset[Int] {
        inner(5)
      }

      value + assignCount

    assertEquals(outer, 7)
  }

  test("local CPS var reassignment is visible when forwarded as higher-order value") {
    def consumeF(f: Int => (CpsTransform[Int] ?=> Int)): Int =
      reset[Int] {
        f(5)
      }

    def outer: Int =
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 1))
      inner = x => shift[Int, Int](k => k(x + 2))

      consumeF(inner)

    assertEquals(outer, 7)
  }

  test("local CPS var snapshot keeps old function value after reassignment") {
    def outer: Int =
      var inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 1))
      val snapshot = inner
      inner = x => shift[Int, Int](k => k(x + 10))

      val oldValue = reset[Int] {
        snapshot(5)
      }
      val newValue = reset[Int] {
        inner(5)
      }

      oldValue * 100 + newValue

    assertEquals(outer, 615)
  }

  test("local CPS val alias chain keeps transformed rewrite") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x + 4))
      val alias1 = inner
      val alias2 = alias1

      reset[Int] {
        alias2(3)
      }

    assertEquals(outer, 7)
  }

  test("nested local CPS val in inner scope is rewritten through nearest transformed sibling") {
    def outer: Int =
      val base = 5

      def innerScope(): Int =
        val inner: Int => CpsTransform[Int] ?=> Int =
          x => shift[Int, Int](k => k(base + x))
        val alias = inner

        reset[Int] {
          alias(2)
        }

      innerScope()

    assertEquals(outer, 7)
  }

  test("local val of List[CPS function value] extracts transformed element via head") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val xs: List[Int => (CpsTransform[Int] ?=> Int)] = List(inner)

      reset[Int] {
        xs.head(1)
      }

    assertEquals(outer, 1)
  }

  test("local val of Option[CPS function value] extracts transformed element via get") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val opt: Option[Int => (CpsTransform[Int] ?=> Int)] = Option(inner)

      reset[Int] {
        opt.get(2)
      }

    assertEquals(outer, 2)
  }

  test("local val of user-defined Box[CPS function value] extracts transformed element via value field") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val box: Box[Int => (CpsTransform[Int] ?=> Int)] = Box(inner)

      reset[Int] {
        box.value(3)
      }

    assertEquals(outer, 3)
  }

  test("local val of Option[Box[CPS function value]] extracts via nested select") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val nested: Option[Box[Int => (CpsTransform[Int] ?=> Int)]] = Option(Box(inner))

      reset[Int] {
        nested.get.value(4)
      }

    assertEquals(outer, 4)
  }

  test("sum of List Option Box and Option[Box] local container extractions evaluates to 10") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val xs: List[Int => (CpsTransform[Int] ?=> Int)] = List(inner)
      val opt: Option[Int => (CpsTransform[Int] ?=> Int)] = Option(inner)
      val box: Box[Int => (CpsTransform[Int] ?=> Int)] = Box(inner)
      val nested: Option[Box[Int => (CpsTransform[Int] ?=> Int)]] = Option(Box(inner))

      reset[Int] {
        xs.head(1) + opt.get(2) + box.value(3) + nested.get.value(4)
      }

    assertEquals(outer, 10)
  }

  test("local lazy val of Option[CPS function value] is rewritten through transformed sibling") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      lazy val opt: Option[Int => (CpsTransform[Int] ?=> Int)] = Option(inner)

      reset[Int] {
        opt.get(5)
      }

    assertEquals(outer, 5)
  }

  test("aliased local container val of CPS function value keeps transformed rewrite") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val xs: List[Int => (CpsTransform[Int] ?=> Int)] = List(inner)
      val alias = xs

      reset[Int] {
        alias.head(6)
      }

    assertEquals(outer, 6)
  }

  test("local val of Map[String, Int => CPS] extracts via apply and reset") {
    def outer: Int =
      val inner: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val m: Map[String, Int => (CpsTransform[Int] ?=> Int)] = Map("key" -> inner)

      reset[Int] {
        m("key")(7)
      }

    assertEquals(outer, 7)
  }

  test("local polymorphic CPS val is rewritten through transformed sibling") {
    def outer: String =
      val poly: [A] => A => (CpsTransform[A] ?=> A) =
        [A] => (x: A) => shift[A, A](k => k(x))

      reset[String] {
        poly[String]("ok")
      }

    assertEquals(outer, "ok")
  }

  test("aliased local polymorphic CPS val keeps transformed rewrite") {
    def outer: String =
      val poly: [A] => A => (CpsTransform[A] ?=> A) =
        [A] => (x: A) => shift[A, A](k => k(x))
      val alias = poly

      reset[String] {
        alias[String]("ok")
      }

    assertEquals(outer, "ok")
  }

  test("zero-arg local polymorphic CPS val keeps transformed rewrite") {
    def outer: String =
      val poly: [A] => () => (CpsTransform[A] ?=> A) =
        [A] => () => shift[A, A](k => k("ok".asInstanceOf[A]))

      reset[String] {
        poly[String]()
      }

    assertEquals(outer, "ok")
  }

  test("curried local polymorphic CPS val keeps transformed rewrite") {
    def outer: String =
      val poly: [A] => Int => A => (CpsTransform[A] ?=> A) =
        [A] => (_: Int) => (x: A) => shift[A, A](k => k(x))

      reset[String] {
        poly[String](1)("ok")
      }

    assertEquals(outer, "ok")
  }

  test("aliased curried local polymorphic CPS val keeps transformed rewrite") {
    def outer: String =
      val poly: [A] => Int => A => (CpsTransform[A] ?=> A) =
        [A] => (_: Int) => (x: A) => shift[A, A](k => k(x))
      val alias = poly

      reset[String] {
        alias[String](1)("ok")
      }

    assertEquals(outer, "ok")
  }

  test("local val of Tuple2[CPS function value, Int] extracts via _1 inside reset") {
    def outer: Int =
      val left: Int => CpsTransform[Int] ?=> Int =
        x => shift[Int, Int](k => k(x))
      val pair: (Int => (CpsTransform[Int] ?=> Int), Int) = {
        val inner = left
        (inner, 0)
      }

      reset[Int] {
        pair._1(5)
      }

    assertEquals(outer, 5)
  }
