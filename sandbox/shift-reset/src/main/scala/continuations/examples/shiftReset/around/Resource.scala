package continuations.examples.shiftReset.around

import continuations.*

def registerCleanup[R](cleanup: => Unit)(using CpsTransform[R]): Unit =
  shift[Unit, R](k => try k(())
  finally cleanup)
