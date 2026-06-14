import continuations.*

// CPS 返り値型アノテーションを省略した場合、Scala の型推論は
// context function 型を自動補完しないためコンパイルエラーになる。
// 明示的な `: CpsTransform[T] ?=> T` アノテーションが必須。

object NegInferredCpsReturnType:
  def simple() = shift[Int, Int](k => k(7)) // error: No given instance of type continuations.CpsTransform[Int]

  def inExpr(x: Int) = shift[Int, Int](k => k(x)) + 1 // error: No given instance of type continuations.CpsTransform[Int]

  def withLet() =
    val n = 3
    shift[Int, Int](k => k(n)) * 2 // error: No given instance of type continuations.CpsTransform[Int]

  def curried(prefix: Int)(x: Int) =
    prefix + shift[Int, Int](k => k(x)) // error: No given instance of type continuations.CpsTransform[Int]
