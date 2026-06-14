package continuations.reifyreflect.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.cats.given

class IoCatsSuite extends munit.FunSuite:

  test("reify sequences IO via cats.Monad") {
    val result = reify[IO, Int] {
      reflect[IO, Int, Int](IO.pure(10)) + reflect[IO, Int, Int](IO.pure(5))
    }.unsafeRunSync()
    assertEquals(result, 15)
  }

  test("reflect and reify round-trip for IO via cats.Monad") {
    val result = reify[IO, Int] {
      reflect[IO, Int, Int](IO.pure(42))
    }.unsafeRunSync()
    assertEquals(result, 42)
  }
