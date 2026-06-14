package continuations.reifyreflect.zio

import _root_.zio.*
import continuations.reifyreflect.zio.{reflectZIO, reifyZIO}
import zio.test.*

object ZioRealisticSuite extends ZIOSpecDefault:
  def spec = suite("ZioRealisticSuite")(
    test("updates a Ref-backed counter") {
      val program = reifyZIO[Any, Nothing, Int] {
        val ref = reflectZIO[Any, Nothing, Ref[Int], Int](Ref.make(0))
        reflectZIO[Any, Nothing, Unit, Int](ref.update(_ + 1))
        reflectZIO[Any, Nothing, Int, Int](ref.get)
      }
      program.map(value => assertTrue(value == 1))
    },
    test("processes ZIO.foreach sequentially") {
      val program = reifyZIO[Any, Nothing, Int] {
        val values = reflectZIO[Any, Nothing, List[Int], Int](ZIO.foreach(List(1, 2, 3))(n => ZIO.succeed(n)))
        values.sum
      }
      program.map(value => assertTrue(value == 6))
    }
  )
