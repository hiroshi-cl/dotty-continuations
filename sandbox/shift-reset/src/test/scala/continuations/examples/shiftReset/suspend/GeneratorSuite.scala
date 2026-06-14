package continuations.examples.shiftReset.suspend

import continuations.*

class GeneratorSuite extends munit.FunSuite:

  test("emit suspends and accumulates generated values") {
    val result = reset {
      emit(1)
      emit(2)
      emit(3)
      Nil
    }

    assertEquals(result, List(1, 2, 3))
  }
