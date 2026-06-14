package continuations.reifyreflect.zio

import _root_.zio.ZIO
import continuations.*
import continuations.reifyreflect.CpsMonad

def reflectZIO[Env, Err, A, B](zio: ZIO[Env, Err, A]): CpsTransform[ZIO[Env, Err, B]] ?=> A =
  shift[A, ZIO[Env, Err, B]](k => zio.flatMap(k))

def reifyZIO[Env, Err, A](body: CpsTransform[ZIO[Env, Err, A]] ?=> A): ZIO[Env, Err, A] =
  given CpsMonad[[X] =>> ZIO[Env, Err, X]] = zioMonad[Env, Err]
  reset[ZIO[Env, Err, A]](summon[CpsMonad[[X] =>> ZIO[Env, Err, X]]].pure(body))
