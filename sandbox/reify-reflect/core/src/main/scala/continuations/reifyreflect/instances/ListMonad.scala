package continuations.reifyreflect.instances

import continuations.reifyreflect.CpsMonad

given CpsMonad[List] with
  def pure[A](a: A): List[A] = List(a)
  def flatMap[A, B](ma: List[A])(f: A => List[B]): List[B] = ma.flatMap(f)
