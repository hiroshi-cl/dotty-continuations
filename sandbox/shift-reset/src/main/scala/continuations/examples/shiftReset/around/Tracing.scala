package continuations.examples.shiftReset.around

import continuations.*

def inspect[A, R](label: String, value: A, log: collection.mutable.ListBuffer[String])(using CpsTransform[R]): A =
  shift[A, R](k => {
    log += s"$label = $value"
    k(value)
  })
