package continuations.reifyreflect

import continuations.reifyreflect.instances.given

class OptionReflectSuite extends munit.FunSuite:

  test("reify returns summed value for Option") {
    val result: Option[Int] = reify[Option, Int] {
      val x = reflect[Option, Int, Int](Some(1))
      val y = reflect[Option, Int, Int](Some(2))
      x + y
    }
    assertEquals(result, Some(3))
  }

  test("reflect propagates None for Option") {
    val result: Option[Int] = reify[Option, Int] {
      val x = reflect[Option, Int, Int](Some(1))
      val y = reflect[Option, Int, Int](None: Option[Int])
      x + y
    }
    assertEquals(result, None)
  }

  test("reflect and reify round-trip for Option") {
    val value: Option[Int] = Some(42)
    val result: Option[Int] = reify[Option, Int] {
      reflect[Option, Int, Int](value)
    }
    assertEquals(result, value)
  }

  test("reflecting a reified pure value resumes with that value") {
    val result: Option[Int] = reify[Option, Int] {
      val value = reflect[Option, Int, Int](reify[Option, Int] {
        40
      })
      value + 2
    }
    assertEquals(result, Some(42))
  }
