package integrationTests.crossmethod

import continuations.*

class LocalCpsMethodSuite extends munit.FunSuite:

  test("local def with CPS arg is rewritten through local transformed helper") {
    def outer: Int =
      def inner(body: CpsTransform[Int] ?=> Int): Int =
        reset[Int](body + 1)

      inner {
        shift[Int, Int](k => k(6))
      }

    assertEquals(outer, 7)
  }

  test("local def with CPS return is rewritten through local transformed helper") {
    def outer: Int =
      def inner(x: Int): CpsTransform[Int] ?=> Int =
        shift[Int, Int](k => k(x + 2))

      reset[Int] {
        inner(5)
      }

    assertEquals(outer, 7)
  }

  test("local def with CPS arg and CPS return is rewritten through local transformed helper") {
    def outer: Int =
      def inner(body: CpsTransform[Int] ?=> Int): CpsTransform[Int] ?=> Int =
        reset[Int](body + 1)

      reset[Int] {
        inner {
          shift[Int, Int](k => k(8))
        }
      }

    assertEquals(outer, 9)
  }

  test("forward reference local def with CPS arg is rewritten through local transformed helper") {
    def outer: Int =
      val result = inner {
        shift[Int, Int](k => k(10))
      }

      def inner(body: CpsTransform[Int] ?=> Int): Int =
        reset[Int](body + 2)

      result

    assertEquals(outer, 12)
  }

  test("forward reference local def with CPS return is rewritten through local transformed helper") {
    def outer: Int =
      val result = reset[Int] {
        inner(4)
      }

      def inner(x: Int): CpsTransform[Int] ?=> Int =
        shift[Int, Int](k => k(x + 3))

      result

    assertEquals(outer, 7)
  }

  test("forward reference local def with CPS arg and CPS return is rewritten through local transformed helper") {
    def outer: Int =
      val result = reset[Int] {
        inner {
          shift[Int, Int](k => k(6))
        }
      }

      def inner(body: CpsTransform[Int] ?=> Int): CpsTransform[Int] ?=> Int =
        reset[Int](body + 1)

      result

    assertEquals(outer, 7)
  }

  test("multiple local CPS methods in the same owner are rewritten independently") {
    def outer: Int =
      def plusOne(body: CpsTransform[Int] ?=> Int): Int =
        reset[Int](body + 1)

      def plusTwo(body: CpsTransform[Int] ?=> Int): Int =
        reset[Int](body + 2)

      plusOne {
        shift[Int, Int](k => k(3))
      } + plusTwo {
        shift[Int, Int](k => k(4))
      }

    assertEquals(outer, 10)
  }

  test("curried local CPS method keeps pure and CPS parameter lists aligned") {
    def outer: Int =
      def inner(x: Int)(body: CpsTransform[Int] ?=> Int): Int =
        reset[Int](body + x)

      inner(5) {
        shift[Int, Int](k => k(2))
      }

    assertEquals(outer, 7)
  }

  test("local def with regular arg and using CpsTransform is rewritten through local transformed helper") {
    def outer: Int =
      def inner(x: Int)(using CpsTransform[Int]): Int =
        shift[Int, Int](k => k(x + 1))

      reset[Int] {
        inner(6)
      }

    assertEquals(outer, 7)
  }

  test("nullary local def with using CpsTransform is rewritten through local transformed helper") {
    def outer: Int =
      def inner(using CpsTransform[Int]): Int =
        shift[Int, Int](k => k(7))

      reset[Int] {
        inner
      }

    assertEquals(outer, 7)
  }

  test("empty-paren local def with using CpsTransform is rewritten through local transformed helper") {
    def outer: Int =
      def inner()(using CpsTransform[Int]): Int =
        shift[Int, Int](k => k(7))

      reset[Int] {
        inner()
      }

    assertEquals(outer, 7)
  }

  test("nested local CPS method is rewritten through the nearest transformed helper") {
    def outer: Int =
      def mid(x: Int): Int =
        def inner(body: CpsTransform[Int] ?=> Int): Int =
          reset[Int](body + x)

        inner {
          shift[Int, Int](k => k(3))
        }

      mid(4)

    assertEquals(outer, 7)
  }

  test("local CPS arg + CPS return can flow through another local def before reset consumes it") {
    def outer: Int =
      def inner(body: CpsTransform[Int] ?=> Int): CpsTransform[Int] ?=> Int =
        reset[Int](body + 1)

      def staged(): CpsTransform[Int] ?=> Int = inner {
        shift[Int, Int](k => k(8))
      }

      reset[Int](staged())

    assertEquals(outer, 9)
  }

  test("local CPS arg + CPS return can be passed through another local method before consumption") {
    def outer: Int =
      def inner(body: CpsTransform[Int] ?=> Int): CpsTransform[Int] ?=> Int =
        reset[Int](body + 2)

      def consume(cc: CpsTransform[Int] ?=> Int): Int =
        reset[Int](cc)

      def staged(): CpsTransform[Int] ?=> Int = inner {
        shift[Int, Int](k => k(5))
      }

      consume(staged())

    assertEquals(outer, 7)
  }

  test("local method returning CPS function value can be directly applied under reset") {
    def outer: Int =
      def make: Int => (CpsTransform[Int] ?=> Int) =
        x => shift[Int, Int](k => k(x + 10))

      reset[Int](make(5))

    assertEquals(outer, 15)
  }

  test("local method returning CPS function value can be held before reset") {
    def outer: Int =
      def make: Int => (CpsTransform[Int] ?=> Int) =
        x => shift[Int, Int](k => k(x + 10))

      val f = make
      reset[Int](f(5))

    assertEquals(outer, 15)
  }

  test("local method returning CPS function value explicit eta still works") {
    def outer: Int =
      def make: Int => (CpsTransform[Int] ?=> Int) =
        x => shift[Int, Int](k => k(x + 10))

      val f: Int => (CpsTransform[Int] ?=> Int) = make
      reset[Int](f(5))

    assertEquals(outer, 15)
  }

  test("nested block can hold local method returning CPS function value") {
    def outer: Int =
      val result =
        def make: Int => (CpsTransform[Int] ?=> Int) =
          x => shift[Int, Int](k => k(x + 10))

        val f = make
        reset[Int](f(5))

      result

    assertEquals(outer, 15)
  }
