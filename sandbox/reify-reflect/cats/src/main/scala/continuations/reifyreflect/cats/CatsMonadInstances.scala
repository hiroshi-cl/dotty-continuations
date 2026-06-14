package continuations.reifyreflect.cats

import _root_.cats.Monad
import continuations.reifyreflect.CpsMonad

given [M[_]](using M: Monad[M]): CpsMonad[M] with
  def pure[A](a: A): M[A] = M.pure(a)
  def flatMap[A, B](ma: M[A])(f: A => M[B]): M[B] = M.flatMap(ma)(f)
