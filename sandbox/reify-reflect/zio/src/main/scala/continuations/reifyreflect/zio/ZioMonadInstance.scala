package continuations.reifyreflect.zio

import _root_.zio.ZIO
import continuations.reifyreflect.CpsMonad

given zioMonad[Env, Err]: CpsMonad[[A] =>> ZIO[Env, Err, A]] with
  def pure[A](a: A): ZIO[Env, Err, A] = ZIO.succeed(a)
  def flatMap[A, B](ma: ZIO[Env, Err, A])(f: A => ZIO[Env, Err, B]): ZIO[Env, Err, B] =
    ma.flatMap(f)
