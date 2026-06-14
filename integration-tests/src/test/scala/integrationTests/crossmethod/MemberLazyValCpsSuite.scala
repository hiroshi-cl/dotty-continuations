package integrationTests.crossmethod

import continuations.reset
import continuations.member.{MemberLazyValCps, MemberLazyValCpsWithEffect}

class MemberLazyValCpsSuite extends munit.FunSuite:

  test("member lazy function-valued CPS provider can be consumed in reset") {
    assertEquals(reset[Int](new MemberLazyValCps().one(1)), 1)
  }

  test("member lazy val containing function-valued CPS provider can be consumed in reset") {
    assertEquals(reset[Int](new MemberLazyValCps().xs.head(1)), 1)
  }

  test("member lazy val can be consumed through this qualifier") {
    assertEquals(new MemberLazyValCps().viaThis, 1)
  }

  test("member lazy val can be consumed through aliased receiver") {
    val c = new MemberLazyValCps()
    assertEquals(reset[Int](c.xs.head(1)), 1)
  }

  test("object member lazy vals can be consumed in reset") {
    assertEquals(reset[Int](MemberLazyValCps.one(1)), 2)
    assertEquals(reset[Int](MemberLazyValCps.xs.head(1)), 3)
  }

  test("member lazy val initializer side effect runs once") {
    val c = new MemberLazyValCpsWithEffect()
    assertEquals(reset[Int](c.xs.head(1)) + reset[Int](c.xs.head(1)) + c.initCount, 9)
  }
