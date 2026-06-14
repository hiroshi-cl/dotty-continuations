package continuations.reifyreflect.cats

import _root_.cats.implicits.given
import continuations.reifyreflect.{reify, reflect}
import continuations.reifyreflect.cats.given

class MixedCatsSuite extends munit.FunSuite:

  test("nested reify mixes List outer with Option inner") {
    val outer: List[Int] = reify[List, Int] {
      val xs = reflect[List, Int, Int](List(1, 2, 3))
      val inner: Option[Int] = reify[Option, Int] {
        reflect[Option, Int, Int](Some(xs * 10))
      }
      inner.getOrElse(-1)
    }
    assertEquals(outer, List(10, 20, 30))
  }

  test("Either can lift values derived from Option") {
    type EitherS[A] = Either[String, A]
    val result: EitherS[Int] = reify[EitherS, Int] {
      val maybe: Option[Int] = reify[Option, Int] {
        reflect[Option, Int, Int](Some(5))
      }
      reflect[EitherS, Int, Int](Right(maybe.getOrElse(-1)): EitherS[Int]) + 1
    }
    assertEquals(result, Right(6))
  }
