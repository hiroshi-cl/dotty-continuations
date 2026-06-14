package integrationTests.crossmethod

import continuations.*

object SeparateCompilationCpsInheritanceFixtures:
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
