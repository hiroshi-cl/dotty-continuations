package continuations

def shift[A, R](f: (A => R) => R): CpsTransform[R] ?=> A = ???

def reset[A](body: CpsTransform[A] ?=> A): A = ???

/** Low-level runtime constructor for a captured continuation computation. */
def shiftR[A, R](f: (A => R) => R): ControlContext[A, R] =
  ControlContext((k, _) => f(k))

/** Low-level runtime lift for a pure value. */
def shiftUnitR[A, R](x: A): ControlContext[A, R] =
  ControlContext((k, _) => k(x))

/** Fixed compiler-plugin ABI for transformed `shift` calls. User code should call `shift`. */
def shift$transformed[A, R](f: (A => R) => R): ControlContext[A, R] = shiftR(f)

/** Fixed compiler-plugin ABI for transformed `reset` calls. User code should call `reset`. */
def reset$transformed[A](body: ControlContext[A, A]): A = body.foreach(identity)
