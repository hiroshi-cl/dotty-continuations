package continuations.reifyreflect.zio

import _root_.zio.*
import continuations.reifyreflect.zio.{reflectZIO, reifyZIO}
import zio.test.*

object ZioReflectSuite extends ZIOSpecDefault:
  def spec = suite("ZioReflectSuite")(
    test("chains ZIO.succeed values") {
      val program = reifyZIO[Any, Nothing, Int] {
        reflectZIO[Any, Nothing, Int, Int](ZIO.succeed(10)) + reflectZIO[Any, Nothing, Int, Int](ZIO.succeed(5))
      }
      program.exit.map(exit => assertTrue(exit == Exit.succeed(15)))
    },
    test("round-trips a succeeded value") {
      val program = reifyZIO[Any, Nothing, Int] {
        reflectZIO[Any, Nothing, Int, Int](ZIO.succeed(42))
      }
      program.exit.map(exit => assertTrue(exit == Exit.succeed(42)))
    }
  )
