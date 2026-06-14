package continuations.reifyreflect.cats

import _root_.cats.implicits.given
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.cats.given

type EitherS[A] = Either[String, A]

class EitherCatsSuite extends munit.FunSuite:

  test("reify returns summed value for Either via cats.Monad") {
    val result: EitherS[Int] = reify[EitherS, Int] {
      val x = reflect[EitherS, Int, Int](Right(1))
      val y = reflect[EitherS, Int, Int](Right(2))
      x + y
    }
    assertEquals(result, Right(3))
  }

  test("reflect propagates Left for Either via cats.Monad") {
    val result: EitherS[Int] = reify[EitherS, Int] {
      val x = reflect[EitherS, Int, Int](Right(1))
      val y = reflect[EitherS, Int, Int](Left("boom"): EitherS[Int])
      x + y
    }
    assertEquals(result, Left("boom"))
  }

  test("reflect and reify round-trip for Either via cats.Monad") {
    val result: EitherS[Int] = reify[EitherS, Int] {
      reflect[EitherS, Int, Int](Right(42): EitherS[Int])
    }
    assertEquals(result, Right(42))
  }
