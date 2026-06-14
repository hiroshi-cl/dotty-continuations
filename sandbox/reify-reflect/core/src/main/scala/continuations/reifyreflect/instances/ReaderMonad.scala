package continuations.reifyreflect.instances

import continuations.reifyreflect.CpsMonad

opaque type Reader[R, A] = R => A

object Reader:
  def apply[R, A](f: R => A): Reader[R, A] = f

def run[R, A](r: Reader[R, A])(env: R): A = r(env)

given [R]: CpsMonad[[A] =>> Reader[R, A]] with
  def pure[A](a: A): Reader[R, A] = Reader(_ => a)
  def flatMap[A, B](ma: Reader[R, A])(f: A => Reader[R, B]): Reader[R, B] = Reader(r => run(f(run(ma)(r)))(r))
