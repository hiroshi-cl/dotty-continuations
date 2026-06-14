package continuations

import munit.FunSuite

class ControlContextSuite extends FunSuite:

  // --- foreach ---

  test("foreach: shiftUnitR returns wrapped value") {
    assertEquals(shiftUnitR[Int, Int](42).foreach(identity), 42)
  }

  test("foreach: shiftR with two continuations sums results") {
    assertEquals(shiftR[Int, Int](k => k(1) + k(2)).foreach(identity), 3)
  }

  test("foreach: error handler throws on exception ControlContext") {
    val ctx: ControlContext[Int, Int] = ControlContext((_, eh) => eh(new RuntimeException("err")))
    intercept[RuntimeException](ctx.foreach(identity))
  }

  test("foreach: error handler not called on normal ControlContext") {
    var called = false
    shiftUnitR[Int, Int](7).fun(_ + 1, _ => { called = true; -1 })
    assert(!called)
  }

  // --- map ---

  test("map: shiftUnitR transforms value") {
    assertEquals(shiftUnitR[Int, Int](10).map(_ * 2).foreach(identity), 20)
  }

  test("map: shiftR transforms continuation result") {
    assertEquals(shiftR[Int, Int](k => k(3)).map(_ + 1).foreach(identity), 4)
  }

  // --- flatMap ---

  test("flatMap: shiftUnitR chain") {
    assertEquals(shiftUnitR[Int, Int](5).flatMap(x => shiftUnitR(x * 3)).foreach(identity), 15)
  }

  test("flatMap: shiftR then shiftUnitR") {
    assertEquals(shiftR[Int, Int](k => k(2)).flatMap(x => shiftUnitR(x + 10)).foreach(identity), 12)
  }

  test("flatMap: shiftR with two continuations") {
    assertEquals(shiftR[Int, Int](k => k(1) + k(2)).flatMap(x => shiftUnitR(x * 10)).foreach(identity), 30)
  }

  test("flatMap: exception thrown in continuation body is routed to eh") {
    val ctx = shiftUnitR[Int, Int](1).flatMap(_ => throw new RuntimeException("boom"))
    intercept[RuntimeException] {
      ctx.foreach(identity)
    }
  }

  test("map: exception thrown in continuation body is routed to eh") {
    val ctx = shiftUnitR[Int, Int](1).map(_ => throw new RuntimeException("boom"))
    intercept[RuntimeException] {
      ctx.foreach(identity)
    }
  }

  test("map: exception thrown by the outer success continuation is routed to eh") {
    val boom = new RuntimeException("outer continuation failed")
    var handled: Exception = null
    val ctx = shiftUnitR[Int, Int](1).map(identity)

    val result = ctx.fun(_ => throw boom, e => { handled = e; 42 })

    assertEquals(result, 42)
    assert(handled eq boom)
  }

  test("flatMap: exception thrown while starting the next context is routed to eh") {
    val boom = new RuntimeException("next context failed")
    var handled: Exception = null
    val next: ControlContext[Int, Int] = ControlContext((_, _) => throw boom)
    val ctx = shiftUnitR[Int, Int](1).flatMap(_ => next)

    val result = ctx.fun(identity, e => { handled = e; 42 })

    assertEquals(result, 42)
    assert(handled eq boom)
  }

  // --- flatMapCatch ---

  test("flatMapCatch: normal ControlContext unaffected") {
    assertEquals(shiftUnitR[Int, Int](1).flatMapCatch(_ => shiftUnitR(99)).foreach(identity), 1)
  }

  test("flatMapCatch: catches exception and replaces with handler result") {
    val ctx: ControlContext[Int, Int] = ControlContext((_, eh) => eh(new RuntimeException("caught")))
    assertEquals(ctx.flatMapCatch(_ => shiftUnitR(42)).foreach(identity), 42)
  }

  test("flatMapCatch: propagates exception via eh when handler re-throws") {
    val ctx: ControlContext[Int, Int] = ControlContext((_, eh) => eh(new IllegalArgumentException("boom")))
    intercept[IllegalArgumentException] {
      ctx.flatMapCatch[Int](e => ControlContext((_, eh) => eh(e))).foreach(identity)
    }
  }

  test("flatMapCatch: catches exception thrown by map body") {
    val ctx =
      shiftUnitR[Int, Int](1)
        .map(_ => throw new RuntimeException("boom"))
        .flatMapCatch(_ => shiftUnitR(41))

    assertEquals(ctx.foreach(identity), 41)
  }

  test("flatMapCatch: catches exception thrown by flatMap body") {
    val ctx =
      shiftUnitR[Int, Int](1)
        .flatMap(_ => throw new RuntimeException("boom"))
        .flatMapCatch(_ => shiftUnitR(42))

    assertEquals(ctx.foreach(identity), 42)
  }

  test("flatMapCatch: exception thrown inside handler propagates outward") {
    val ctx: ControlContext[Int, Int] = ControlContext((_, eh) => eh(new RuntimeException("caught")))

    intercept[IllegalStateException] {
      ctx.flatMapCatch(_ => throw new IllegalStateException("handler failed")).foreach(identity)
    }
  }

  // --- shiftR / shiftUnitR ---

  test("shiftUnitR: foreach returns value") {
    assertEquals(shiftUnitR[Int, Int](42).foreach(identity), 42)
  }

  test("shiftR: applies f to continuation") {
    assertEquals(shiftR[Int, Int](k => k(7) * 2).foreach(identity), 14)
  }

  // --- reset$transformed ---

  test("reset$transformed: basic shift") {
    assertEquals(reset$transformed(shiftR[Int, Int](k => k(42))), 42)
  }

  test("reset$transformed: shift with map") {
    assertEquals(reset$transformed(shiftR[Int, Int](k => k(3)).map(_ * 2)), 6)
  }

  test("reset$transformed: shiftUnitR") {
    assertEquals(reset$transformed(shiftUnitR[Int, Int](10)), 10)
  }
