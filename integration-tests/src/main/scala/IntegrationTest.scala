import continuations.*

// プラグインが読み込まれ、フェーズがクラッシュしないことを確認するだけ
object IntegrationTest:
  val ctx = shiftUnitR[Int, Int](42)
