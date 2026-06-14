package integrationTests.crossmethod

import continuations.*

class MethodAccessCrossMethodSuite extends munit.FunSuite:

  class Bla:
    val x = 8
    def y[T]: T = 9.asInstanceOf[T]

  def bla1(): CpsTransform[Bla] ?=> Bla = shift[Bla, Bla](k => k(new Bla))
  def bla2(): CpsTransform[Int] ?=> Bla = shift[Bla, Int](k => k(new Bla))

  test("t3225Mono: method access on shift result") {
    assertEquals(8, reset(bla1()).x)
    assertEquals(8, reset(bla2().x))
    assertEquals(9, reset(bla2().y[Int]))
  }
