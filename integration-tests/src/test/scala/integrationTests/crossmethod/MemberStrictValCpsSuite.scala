package integrationTests.crossmethod

import continuations.reset
import continuations.member.{MemberStrictValCps, MemberStrictValCpsWithEffect}

class MemberStrictValCpsSuite extends munit.FunSuite:

  test("member strict function-valued CPS provider can be consumed in reset") {
    assertEquals(reset[Int](new MemberStrictValCps().one(1)), 1)
  }

  test("member strict val containing function-valued CPS provider can be consumed in reset") {
    assertEquals(reset[Int](new MemberStrictValCps().xs.head(1)), 1)
  }

  test("member strict val can be consumed through this qualifier") {
    assertEquals(new MemberStrictValCps().viaThis, 1)
  }

  test("member strict val can be consumed through unqualified select") {
    assertEquals(new MemberStrictValCps().viaUnqualified, 1)
  }

  test("member strict val can be consumed through aliased receiver") {
    val c = new MemberStrictValCps()
    assertEquals(reset[Int](c.xs.head(1)), 1)
  }

  test("member strict val varargs rewrites every SeqLiteral element and element type") {
    val c = new MemberStrictValCps()
    assertEquals(reset[Int](c.many.head(1) + c.many.tail.head(1)), 3)
  }

  test("object member strict vals can be consumed in reset") {
    assertEquals(reset[Int](MemberStrictValCps.one(1)), 2)
    assertEquals(reset[Int](MemberStrictValCps.xs.head(1)), 3)
  }

  test("member strict val initializer side effect runs once") {
    val c = new MemberStrictValCpsWithEffect()
    assertEquals(reset[Int](c.xs.head(1)) + c.initCount, 5)
  }
