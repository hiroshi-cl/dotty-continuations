package continuations.examples.shiftReset.abort

import continuations.*

def escape[A, B](f: (A => B) => A)(using CpsTransform[B]): A =
  shift[A, B](k => f(k).asInstanceOf[B])
