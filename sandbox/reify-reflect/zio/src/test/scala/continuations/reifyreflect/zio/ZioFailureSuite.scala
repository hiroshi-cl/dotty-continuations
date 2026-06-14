package continuations.reifyreflect.zio

import _root_.zio.*
import continuations.reifyreflect.zio.{reflectZIO, reifyZIO}
import zio.test.*

object ZioFailureSuite extends ZIOSpecDefault:
  def spec = suite("ZioFailureSuite")(
    test("short-circuits on ZIO.fail") {
      val program = reifyZIO[Any, String, Int] {
        reflectZIO[Any, String, Int, Int](ZIO.fail("boom"))
        999
      }
      program.exit.map(exit => assertTrue(exit == Exit.fail("boom")))
    },
    test("does not reach code after failure") {
      val program = reifyZIO[Any, String, Int] {
        val value = reflectZIO[Any, String, Int, Int](ZIO.fail("boom"))
        value + 1
      }
      program.exit.map(exit => assertTrue(exit.isFailure))
    }
  )
