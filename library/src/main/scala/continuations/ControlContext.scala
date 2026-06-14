package continuations

final class ControlContext[+A, R](val fun: (A => R, Exception => R) => R):
  def foreach(f: A => R): R =
    fun(f, throw _)
  def map[A1](f: A => A1): ControlContext[A1, R] =
    ControlContext((k, eh) =>
      fun(
        a =>
          try k(f(a))
          catch case e: Exception => eh(e), eh
      )
    )
  def flatMap[A1](f: A => ControlContext[A1, R]): ControlContext[A1, R] =
    ControlContext((k, eh) =>
      fun(
        a =>
          try f(a).fun(k, eh)
          catch case e: Exception => eh(e), eh
      )
    )
  def flatMapCatch[A1 >: A](handler: Exception => ControlContext[A1, R]): ControlContext[A1, R] =
    ControlContext((k, eh) => fun(k, e => handler(e).fun(k, eh)))

object ControlContext:
  def apply[A, R](fun: (A => R, Exception => R) => R): ControlContext[A, R] =
    new ControlContext(fun)
