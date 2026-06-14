package continuations.reifyreflect.instances

import continuations.reifyreflect.CpsMonad

given CpsMonad[Option] with
  def pure[A](a: A): Option[A] = Some(a)
  def flatMap[A, B](ma: Option[A])(f: A => Option[B]): Option[B] = ma.flatMap(f)
