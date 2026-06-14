package continuations.reifyreflect.zio

import _root_.zio.*
import continuations.reifyreflect.zio.{reflectZIO, reifyZIO}
import zio.test.*

object ReadmeExamplesZioSuite extends ZIOSpecDefault:
  def spec = suite("ReadmeExamplesZioSuite")(
    test("program example") {
      val program: Task[Int] = reifyZIO[Any, Throwable, Int] {
        val x = reflectZIO[Any, Throwable, Int, Int](ZIO.succeed(10))
        val y = reflectZIO[Any, Throwable, Int, Int](ZIO.succeed(x + 5))
        x + y
      }
      program.map(value => assertTrue(value == 25))
    },
    test("withEnv example") {
      val withEnv: RIO[String, Int] = reifyZIO[String, Throwable, Int] {
        val name = reflectZIO[String, Throwable, String, Int](ZIO.service[String])
        name.length
      }
      withEnv.provide(ZLayer.succeed("hello")).map(value => assertTrue(value == 5))
    }
  )
