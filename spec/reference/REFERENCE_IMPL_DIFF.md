# dotty-continuations と参考実装 scala-continuations の差分

このドキュメントは、このリポジトリが Scala 2 向けの参考実装
[`scala-continuations`](https://github.com/scala/scala-continuations) と比べて
何を引き継ぎ、何を変更し、何を未対応としているかを
**このファイル単体で**把握できるようにまとめたものである。

ここでいう参考実装は主に次の実装を指す。

- `library`
- `plugin`

dotty-continuations は参考実装の直接移植ではない。
**selective CPS transformation という考え方は引き継ぎつつ、Scala 3 の型システム・
context function・TASTy・dotc plugin API に合わせて再設計した実装**である。

## 1. 要約

最も大きな差分は次の 5 点である。

1. CPS の表現が違う。
   参考実装は `A @cpsParam[B, C]`、こちらは `CpsTransform[R] ?=> A` を使う。
2. answer type modification を削っている。
   参考実装は `B != C` を扱えるが、こちらは `B = C = R` に固定している。
3. ABI が違う。
   参考実装はシンボルの型を書き換える方向だが、こちらは `$transformed` メソッドを明示生成する。
4. コンパイラ統合点が違う。
   参考実装は Scala 2 compiler の annotation checker / analyzer plugin を使うが、
   こちらは Scala 3 plugin phase と TASTy 前後の分離を使う。
5. サポート範囲を絞っている。
   `while` 内 CPS、`finally` 内 CPS は現状サポート外。
   named `val` は local/member の主要形状をサポート済みで、
   direct CPS context-function ストレージや trait member など残余形状が未対応。

## 2. upstream から引き継いでいるもの

完全に別物ではなく、以下は参考実装と同じ方向性である。

- `shift` / `reset` を selective CPS transformation で実現する
- ANF 的に CPS 呼び出しを正規化してから CPS 変換する
- 変換後ランタイム表現として `ControlContext` 相当の値を使う
- `try/catch` を `flatMapCatch` 的な形で扱う
- cross-method continuations をサポート対象にする

つまり「継続をユーザーに直接 AST マクロとして見せる」のではなく、
**型で CPS 性を検出し、コンパイラ変換で `flatMap` 連鎖に落とす**
という主戦略は参考実装を継承している。

## 3. 型表現の差分

### 参考実装

upstream の中心は型注釈ベースの CPS 表現である。

```scala
A @cpsParam[B, C]
```

この型は、

- 式の見かけ上の値型は `A`
- その式は continuation context の中で評価される
- その context の answer type を `B` から `C` に変更しうる

という意味を持つ。

### このリポジトリ

こちらでは CPS 表現を Scala 3 の context function で表している。

```scala
CpsTransform[R] ?=> A
```

この設計により、CPS 性は型注釈ではなく通常の型として TASTy に残る。
そのため Scala 2 版で必要だった annotation checker 系の仕組みを使わず、
**型を直接見て CPS メソッドや CPS 引数を判定できる**。

### 重要な差分

- 参考実装は annotation-driven
- こちらは type-driven

この差分は単なる表記上の違いではなく、
以降の ABI・フェーズ構成・残留チェックの設計全体に影響している。

## 4. answer type modification の差分

### 参考実装

参考実装は `@cpsParam[B, C]` を通じて answer type modification をサポートする。
すなわち、継続の内側と外側で answer type が変わってよい。

これは表現力の源だが、型推論・エラーメッセージ・API の理解コストを上げる。

### このリポジトリ

こちらではその機能を削り、

```scala
B = C = R
```

に固定している。

その結果、

- ランタイム型は `ControlContext[A, R]` に簡略化できる
- `linearize` の考慮が単純になる
- API が小さくなる
- ユーザーに要求する型注釈が減る

一方で、参考実装より表現力は狭い。

## 5. ランタイム API の差分

### 参考実装のランタイム

参考実装の `scala.util.continuations` には、概ね次のような API 群がある。

- `shift`
- `reset`
- `shiftUnit`
- `shiftUnit0`
- `shiftUnitR`
- `reify`
- `reifyR`
- `run`
- `reset0`
- `ControlContext[A, B, C]`

### このリポジトリのランタイム

こちらは API をかなり絞っている。

- `shift`
- `reset`
- `shiftR`
- `shiftUnitR`
- `shift$transformed`
- `reset$transformed`
- `ControlContext[A, R]`
- `CpsTransform[R]`

### reify / reflect 命名の注意点

- 参考実装に実際に存在するのは `reify` / `reifyR` であり、
  `reflect` は built-in API ではない
- このリポジトリで計画されている `reify-reflect` 系モジュールは、
  参考実装の API をそのまま移植する話ではなく、
  別レイヤとして設計し直す想定である

## 6. ABI の差分

### 参考実装

参考実装は主に **型変換ベース** で動く。
`SelectiveCPSTransform.transformInfo` がシンボルの型を CPS 変換後の型に書き換えるため、
名前付きメソッドや関数値は変換後型として扱われる。

この設計では、こちらが採用しているような
`foo$transformed` という companion ABI を前提にしていない。

### このリポジトリ

こちらでは CPS シグネチャを持つ名前付きメソッドに対して
**`$transformed` メソッドを追加生成する ABI** を採用している。

例:

```scala
def foo(): CpsTransform[Int] ?=> Int
def foo$transformed(): ControlContext[Int, Int]
```

この設計を採る理由は次の通り。

- Scala 3 では context function 型が通常の型として TASTy に保存される
- cross-compilation unit で transformed 実装を名前解決したい
- 元メソッドの表面 API は保ちつつ、実体は transformed 側に寄せたい

そのため、

1. `posttyper` 後 `pickler` 前に `$transformed` スタブを追加する
2. そのシグネチャを TASTy に書き込む
3. `pickler` 後のフェーズでスタブ body を実装に置換する

という Scala 3 固有の段取りを取っている。

## 7. フェーズ構成の差分

### 参考実装

大まかには次の 3 要素で成立している。

- `CPSAnnotationChecker`
- `SelectiveANFTransform`
- `SelectiveCPSTransform`

annotation checker と analyzer plugin が
`@cpsParam` の伝播や型整合性の維持に深く関わる。

### このリポジトリ

こちらの phase 構成は次の通り。

- `SelectiveCPSStubPhase`
- `SelectiveANFTransform`
- `SelectiveCPSTransform`

特徴は次の通り。

- annotation checker が不要
- TASTy へ `$transformed` シグネチャを書き込むため stub phase が必要
- ANF 後の抽出ノードを `@CpsSym` でマーキングする
- CPS transform は `@CpsSym` と型情報を元に変換を進める

## 8. ANF の差分

### 参考実装

参考実装の ANF 変換は Scala 2 compiler の AST に強く依存している。
`LabelDef`、synthetic case symbol、virtpatmat などの形も考慮している。

また、`@cpsParam` ベースの annotation 情報を持ちながら
式の impure / pure を追跡するロジックが大きい。

### このリポジトリ

こちらの ANF は Dotty の typed tree で、
**context function の auto-apply**

```scala
Apply(Select(qual, "apply"), List(givenArg))
```

を CPS 呼び出しとして認識する。

ANF の役割は次の通り。

- CPS 式を let-binding 化する
- 抽出した `ValDef` に `@CpsSym` を付ける
- `If` / `Match` / `Try` の分岐内の CPS を再帰的に ANF 化する
- 不正位置の CPS を残留チェックで検出する

これは参考実装の思想を引き継いでいるが、
検出方法も AST 形状もかなり変わっている。

## 9. CPS 変換の差分

### 参考実装

参考実装は `ControlContext[A, B, C]` を使いながら
`flatMap` / `flatMapCatch` / `mapFinally` 等へ落としていく。
ただし `finally` は実装コメントでも分かる通り難所で、
`mapFinally` の利用は実質無効化されている。

### このリポジトリ

こちらは `ControlContext[A, R]` へ落とす。
`@CpsSym` で抽出された `ValDef` 群を `transBlock` で走査し、

- CPS 文の後ろが CPS なら `flatMap`
- CPS 文の後ろが pure なら `map`
- 末尾 pure 式は `shiftUnitR`

へ変換する。

また `Try` では、

- `try` 本体や `catch` 本体の CPS を `transTailValue` で変換
- 例外ハンドリングは `flatMapCatch`

という方針を取る。

## 10. named val の扱いの差分

ここは誤解しやすいので明示する。

### 参考実装で未対応なのは何か

参考実装で明示的に禁止されているのは
**by-value の CPS 値定義**である。

例:

```scala
val x: Int @cps[Unit] = ...
```

これは ANF 変換でエラーになる。

一方で、関数値の末尾が CPS になるような値、たとえば

```scala
val f: Int => (Int @cps[Unit]) = ...
```

のようなものは、
`transformInfo` による型変換の延長で扱う設計で、
こちらのような `$transformed` companion を生やす方式ではない。

### このリポジトリで対応済みの形状

local `val` / `lazy val` / `var` の transformed sibling 生成と、
class/object member の strict/lazy/mutable CPS val 変換はサポート済みである。

### このリポジトリで現状未対応な形状

残余として未対応なのは限定的なケースである。

- direct CPS context-function ストレージ（`val f: CpsTransform[Int] ?=> Int = ...` 相当）
- trait member の CPS val

`def` への書き換えで回避できる場合はそちらを使う。

### 結論

named `val` まわりは参考実装とこちらで
「禁止している対象」が同一ではない。

- 参考実装:
  by-value CPS 値定義を禁止
- こちら:
  local/member の主要形状はサポート済み。
  direct CPS context-function ストレージや trait member など残余形状が現状未対応

## 11. `reify` / `reflect` まわりの差分

これも名前が似ていて混同しやすい。

### 参考実装にあるもの

参考実装の library/plugin に組み込まれているのは、

- `reify`
- `reifyR`

である。

`SelectiveCPSTransform` は `reify(...)` を `reifyR(...)` に変換する。

### 参考実装にないもの

`reflect` は参考実装の built-in runtime API ではない。
テストコード中では `shift` を使って定義された補助関数名として出てくるが、
標準提供 API ではない。

### このリポジトリとの関係

`sandbox/reify-reflect` モジュールとして `reify` / `reflect` は実装済みである。
Cats、Scalaz、ZIO 向けアダプタと成功・短絡テストも存在する。

このモジュールの `reify` / `reflect` は Filinski の monadic reflection に理論的背景を持つ。

- `reflect: M[A] => A` 相当の操作でモナド計算を直接スタイルへ反映する
- `reify` でその直接スタイル計算を `M[A]` に戻す
- 実現手段として shift/reset を利用するが、目的は shift/reset 変換補助ではない

参考実装の変換補助としての `reify/reifyR`（`SelectiveCPSTransform` が `reify(...)` を
`reifyR(...)` に書き換えて `ControlContext` を受け渡す内部 ABI）とは、
由来・抽象化レベル・役割が異なる。両者を同一 API または直接移植として扱ってはならない。

## 12. `try/catch` と `finally` の差分

### `try/catch`

ここは参考実装と比較的近い。
どちらも `ControlContext` 側の例外 combinator を使って
CPS 中の例外を扱う方向である。

### `finally`

ここは参考実装でも難所である。
参考実装の `ControlContext` には `mapFinally` が存在するが、
plugin 側ではコメントアウトされており、
通常経路・例外経路・継続生成後の実行順が壊れる問題がある。

このリポジトリはその状況を踏まえ、
初期版では **`finally` 内 CPS を禁止** している。
`try/finally` 自体の pure な形は扱っても、
`finally` 内で `shift` を許す設計は採っていない。

したがって、ここは upstream より機能を削ったというより、
**参考実装の未整理な領域を明示的に切り落としている** と見る方が近い。

## 13. `while` の差分

参考実装には `while` を含むテストや `LabelDef` 周辺の処理がある。
ただしその系統は Scala 2 compiler の内部表現に強く依存している。

このリポジトリでは Dotty 向け初期実装としてそこまで追わず、
`while` 内 CPS は未サポートにしている。

将来的に対応するなら、
再帰 `DefDef` への脱糖など別方式を採る想定である。

## 14. テスト戦略の差分

### 参考実装

参考実装は plugin を通した統合テストの比重が大きい。
Scala compiler 内部表現との結びつきが強く、
black-box に近い確認も多い。

### このリポジトリ

こちらはフェーズ単位の AST テストを厚めに書いている。

- `library` のユニットテスト
- `plugin` の AST 直接テスト
- `integration-tests` の統合テスト
- `integration-tests-neg` のネガティブテスト

特に、

- `@CpsSym` の付与確認
- `$transformed` スタブ生成確認
- `transBlock` の `map` / `flatMap` 連鎖確認
- 残留 CPS の検出確認

のように、変換中間表現を直接検証するスタイルが参考実装より強い。

## 15. このリポジトリで意図的に簡略化している点

以下は「未実装」というより、現時点で意図的にスコープを絞っている部分である。

- answer type modification を削除
- `CpsTransform[R] ?=> A` の Form 2 に集中
- named `val` のうち direct CPS context-function ストレージや trait member は現状サポート外
- `while` 内 CPS をサポートしない
- `finally` 内 CPS をサポートしない
- `reifyR` をランタイム API から外す

これらは実装量削減だけでなく、
Scala 3 版としての ABI と変換ロジックを安定させるための選択でもある。

## 16. 進捗の見方

参考実装比で見ると、現在の中核は

- library の簡略 runtime
- stub + ANF + CPS の plugin パイプライン
- `shift/reset`
- cross-method continuations
- `try/catch`
- nested `reset`

までが実装の中心である。

一方で、upstream が持っていた広い表現力のうち、

- answer type modification
- `while` を含む広い構文カバレッジ
- `finally` の完全サポート

などはまだ対象外か、別設計として後段に送られている。

`reify` / `reflect` は `sandbox/reify-reflect` モジュールで実装済みである。
ただしメインプラグインの変換補助 ABI（参考実装の `reifyR` 相当）とは別物であり、
Filinski 型の monadic reflection operator として独立した位置づけである。

## 17. 結論

このリポジトリは参考実装の Scala 3 直訳版ではない。

より正確には、

- 参考実装の selective CPS の発想を継承し
- Scala 3 の型表現と TASTy を活かして
- answer type modification など難しい部分を削り
- `$transformed` ABI を中心に再構成した

**縮小だが明示的で、移植より再設計に近い実装**である。

upstream との差分は「足りない機能一覧」だけで捉えるより、
**Scala 3 で成立しやすい最小コアへ再分解した**
と捉える方が実態に近い。
