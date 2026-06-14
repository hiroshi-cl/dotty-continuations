package continuations

package object reifyreflect:
  import continuations.*

  def reflect[M[_], A, R](ma: M[A])(using cm: CpsMonad[M]): CpsTransform[M[R]] ?=> A =
    shift[A, M[R]](k => cm.flatMap(ma)(k))

  def reify[M[_], A](body: CpsTransform[M[A]] ?=> A)(using cm: CpsMonad[M]): M[A] =
    reset[M[A]](cm.pure(body))
