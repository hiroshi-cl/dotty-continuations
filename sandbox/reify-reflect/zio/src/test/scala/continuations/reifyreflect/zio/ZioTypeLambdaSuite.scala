package continuations.reifyreflect.zio

import _root_.zio.*
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.zio.zioMonad
import zio.test.*

object ZioTypeLambdaSuite extends ZIOSpecDefault:
  def spec = suite("ZioTypeLambdaSuite")(test("uses reify and reflect through zioMonad given") {
    val program: ZIO[Any, Nothing, Int] = reify[[A] =>> ZIO[Any, Nothing, A], Int] {
      reflect[[A] =>> ZIO[Any, Nothing, A], Int, Int](ZIO.succeed(10)) +
        reflect[[A] =>> ZIO[Any, Nothing, A], Int, Int](ZIO.succeed(20))
    }
    program.map(value => assertTrue(value == 30))
  })
