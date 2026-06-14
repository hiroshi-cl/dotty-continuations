package continuations.reifyreflect.instances

import continuations.reifyreflect.CpsMonad

type Id[A] = A

given CpsMonad[Id] with
  def pure[A](a: A): Id[A] = a
  def flatMap[A, B](ma: Id[A])(f: A => Id[B]): Id[B] = f(ma)
