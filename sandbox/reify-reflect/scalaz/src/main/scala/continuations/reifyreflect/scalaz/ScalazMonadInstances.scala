package continuations.reifyreflect.scalaz

import _root_.scalaz.Monad
import continuations.reifyreflect.CpsMonad

given [M[_]](using M: Monad[M]): CpsMonad[M] with
  def pure[A](a: A): M[A] = M.point(a)
  def flatMap[A, B](ma: M[A])(f: A => M[B]): M[B] = M.bind(ma)(f)
