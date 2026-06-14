package continuations.reifyreflect

import continuations.reifyreflect.instances.Id
import continuations.reifyreflect.instances.given

class IdReflectSuite extends munit.FunSuite:

  test("reify returns plain value for Id") {
    val result: Id[Int] = reify[Id, Int] {
      val x = reflect[Id, Int, Int](1)
      val y = reflect[Id, Int, Int](2)
      x + y
    }
    assertEquals(result, 3)
  }

  test("reflect and reify round-trip for Id") {
    val value: Id[Int] = 42
    val result: Id[Int] = reify[Id, Int] {
      reflect[Id, Int, Int](value)
    }
    assertEquals(result, value)
  }
