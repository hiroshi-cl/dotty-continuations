package continuations.examples.shiftReset.suspend

import continuations.*

def emit[A](x: A)(using CpsTransform[List[A]]): Unit =
  shift[Unit, List[A]](k => x :: k(()))
