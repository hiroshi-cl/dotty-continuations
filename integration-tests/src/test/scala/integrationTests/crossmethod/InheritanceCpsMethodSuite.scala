package integrationTests.crossmethod

import continuations.*
import integrationTests.crossmethod.SeparateCompilationCpsInheritanceFixtures as Separate

class InheritanceCpsMethodSuite extends munit.FunSuite:

  trait ConcreteBase:
    def make: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 1))

    def callThis(n: Int): Int =
      reset[Int](this.make(n))

  trait AbstractBase:
    def make: Int => (CpsTransform[Int] ?=> Int)

    def callThis(n: Int): Int =
      reset[Int](this.make(n))

  class Child extends ConcreteBase:
    override def make: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 10))

    def callSuper(n: Int): Int =
      reset[Int](super.make(n))

  class AbstractChild extends AbstractBase:
    override def make: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 20))

  class StableHolder:
    val child: ConcreteBase = new Child

  class ProtectedBase:
    protected def make: Int => (CpsTransform[Int] ?=> Int) =
      x => shift[Int, Int](k => k(x + 30))

  class ProtectedChild extends ProtectedBase:
    def callProtected(n: Int): Int =
      reset[Int](make(n))

  private def callConcrete(base: ConcreteBase): Int =
    reset[Int](base.make(5))

  private def callAbstract(base: AbstractBase): Int =
    reset[Int](base.make(5))

  test("inheritance: concrete trait method dispatches through base-typed receiver") {
    val base: ConcreteBase = new Child

    assertEquals(callConcrete(base), 15)
  }

  test("inheritance: this and super calls keep distinct dispatch targets") {
    val child = new Child

    assertEquals(child.callThis(5), 15)
    assertEquals(child.callSuper(5), 6)
  }

  test("inheritance: direct construction and stable field receiver rewrite") {
    val holder = new StableHolder

    assertEquals(reset[Int](new Child().make(5)), 15)
    assertEquals(reset[Int](holder.child.make(5)), 15)
  }

  test("inheritance: abstract trait declaration dispatches to concrete implementation") {
    val base: AbstractBase = new AbstractChild

    assertEquals(base.callThis(5), 25)
    assertEquals(callAbstract(base), 25)
  }

  test("inheritance: protected member can be consumed through subclass wrapper") {
    assertEquals(new ProtectedChild().callProtected(5), 35)
  }

  test("inheritance: separate compilation concrete trait dispatch remains visible through TASTy") {
    val base: Separate.ConcreteBase = new Separate.Child

    assertEquals(reset[Int](base.make(5)), 15)
    assertEquals(base.callThis(5), 15)
    assertEquals(new Separate.Child().callSuper(5), 6)
  }

  test("inheritance: separate compilation abstract trait dispatch remains visible through TASTy") {
    val base: Separate.AbstractBase = new Separate.AbstractChild

    assertEquals(reset[Int](base.make(5)), 25)
    assertEquals(base.callThis(5), 25)
  }

  test("inheritance: separate compilation stable and protected receivers remain visible through TASTy") {
    val holder = new Separate.StableHolder

    assertEquals(reset[Int](holder.child.make(5)), 15)
    assertEquals(new Separate.ProtectedChild().callProtected(5), 35)
  }
