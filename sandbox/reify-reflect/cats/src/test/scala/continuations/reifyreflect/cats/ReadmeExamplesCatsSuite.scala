package continuations.reifyreflect.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.implicits.given
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.cats.given

class ReadmeExamplesCatsSuite extends munit.FunSuite:

  test("r1") {
    val r1: Option[Int] = reify[Option, Int] {
      val x = reflect[Option, Int, Int](Some(3))
      val y = reflect[Option, Int, Int](Some(4))
      x + y
    }
    assertEquals(r1, Some(7))
  }

  test("r2") {
    val r2: Option[Int] = reify[Option, Int] {
      val x = reflect[Option, Int, Int](None: Option[Int])
      x + 1
    }
    assertEquals(r2, None)
  }

  test("program") {
    val program: IO[Int] = reify[IO, Int] {
      val x = reflect[IO, Int, Int](IO(println("hello")) *> IO.pure(10))
      val y = reflect[IO, Int, Int](IO.pure(x * 2))
      y + 1
    }
    assertEquals(program.unsafeRunSync(), 21)
  }
