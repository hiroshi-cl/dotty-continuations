package continuations.reifyreflect.zio

import _root_.zio.*
import continuations.reifyreflect.zio.{reflectZIO, reifyZIO}
import zio.test.*

object ZioEnvSuite extends ZIOSpecDefault:
  def spec = suite("ZioEnvSuite")(
    test("injects String environment through ZIO.service") {
      val program: ZIO[String, Nothing, Int] = reifyZIO[String, Nothing, Int] {
        val name = reflectZIO[String, Nothing, String, Int](ZIO.service[String])
        name.length
      }
      program.provide(ZLayer.succeed("hello")).map(value => assertTrue(value == 5))
    },
    test("adds multiple Int services") {
      val program: ZIO[Int, Nothing, Int] = reifyZIO[Int, Nothing, Int] {
        val x = reflectZIO[Int, Nothing, Int, Int](ZIO.service[Int])
        val y = reflectZIO[Int, Nothing, Int, Int](ZIO.service[Int])
        x + y
      }
      program.provide(ZLayer.succeed(7)).map(value => assertTrue(value == 14))
    }
  )
