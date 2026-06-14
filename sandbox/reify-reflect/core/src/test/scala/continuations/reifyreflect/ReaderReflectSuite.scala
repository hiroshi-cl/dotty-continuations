package continuations.reifyreflect

import continuations.reifyreflect.instances.Reader
import continuations.reifyreflect.instances.given
import continuations.reifyreflect.instances.run

class ReaderReflectSuite extends munit.FunSuite:

  test("reflect reads environment twice for Reader") {
    val result: Reader[Int, Int] = reify[[A] =>> Reader[Int, A], Int] {
      val x = reflect[[A] =>> Reader[Int, A], Int, Int](Reader(identity))
      val y = reflect[[A] =>> Reader[Int, A], Int, Int](Reader(identity))
      x + y
    }
    assertEquals(run(result)(10), 20)
  }

  test("reflect and reify round-trip pure value for Reader") {
    val result: Reader[Int, Int] = reify[[A] =>> Reader[Int, A], Int] {
      reflect[[A] =>> Reader[Int, A], Int, Int](Reader(_ => 42))
    }
    assertEquals(run(result)(0), 42)
  }

  test("reflect composes flatMap chain for Reader") {
    val result: Reader[Int, Int] = reify[[A] =>> Reader[Int, A], Int] {
      val doubled = reflect[[A] =>> Reader[Int, A], Int, Int](Reader(_ * 2))
      doubled + 3
    }
    assertEquals(run(result)(5), 13)
  }
