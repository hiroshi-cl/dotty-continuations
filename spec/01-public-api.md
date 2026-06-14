# 01 公開APIライブラリ仕様

## 対象ソースファイル

- `library/src/main/scala/continuations/ControlContext.scala`
- `library/src/main/scala/continuations/CpsSym.scala`
- `library/src/main/scala/continuations/CpsTransform.scala`
- `library/src/main/scala/continuations/package.scala`

## 概要

このライブラリは選択的CPS変換のランタイム基盤と、ユーザが直接記述するフロントエンドAPIを提供する。

ユーザは `shift` / `reset` を直接スタイルで記述する。コンパイラプラグインが `CpsTransform[R]` 型を持つメソッドシグネチャを検出し、`shift$transformed` / `reset$transformed` と `ControlContext` によるCPS表現へ変換する。変換後のコードはすべてライブラリ内のクラスと関数だけで動作する。

## ControlContext

### 責務

選択的CPS変換後の計算を表すランタイム表現。値 `A` を生成する計算を、成功継続 `A => R` と例外継続 `Exception => R` を受け取って最終結果 `R` にする関数として保持する。

`map` / `flatMap` / `foreach` / `flatMapCatch` により、CPS変換後の式を Scala の for-comprehension 風に合成できる。

### シグネチャ

```scala
final class ControlContext[+A, R](val fun: (A => R, Exception => R) => R)
```

```scala
def foreach(f: A => R): R
```

```scala
def map[A1](f: A => A1): ControlContext[A1, R]
```

```scala
def flatMap[A1](f: A => ControlContext[A1, R]): ControlContext[A1, R]
```

```scala
def flatMapCatch[A1 >: A](handler: Exception => ControlContext[A1, R]): ControlContext[A1, R]
```

```scala
object ControlContext:
  def apply[A, R](fun: (A => R, Exception => R) => R): ControlContext[A, R]
```

### 意味論・アルゴリズム

`ControlContext` はコンストラクタ引数 `fun` をそのまま保持する。

`foreach(f)` は `fun(f, throw _)` を実行する。成功時は `f` を成功継続として渡し、例外継続には受け取った例外を再throwする関数を使う。`ControlContext` を通常の値 `R` に戻す出口である。

`map(f)` は新しい `ControlContext[A1, R]` を作る。新しい計算が実行されると、元の `fun` に成功継続と例外継続を渡す。元の計算が値 `a` を成功継続へ渡した場合、`f(a)` を計算してから外側の成功継続 `k` に渡す。`f(a)` または `k(...)` が `Exception` を投げた場合は、外側の例外継続 `eh` に渡す。

`flatMap(f)` も新しい `ControlContext[A1, R]` を作る。元の計算が値 `a` を成功継続へ渡した場合、`f(a)` で次の `ControlContext` を得て、その内部の `fun(k, eh)` を実行する。`f(a)` または次の計算開始時に `Exception` が投げられた場合は `eh` に渡す。

`flatMapCatch(handler)` は例外継続を差し替える。元の `fun` の成功継続にはそのまま `k` を渡す。元の計算が例外継続へ例外 `e` を渡した場合、`handler(e)` で回復用の `ControlContext` を作り、その `fun(k, eh)` を実行する。

`object ControlContext.apply` は `new ControlContext(fun)` の薄いファクトリ。

### 設計判断: Answer type modification を削除した理由

scala-continuations の `@cpsParam[B,C]` は B≠C をサポートし、reset の外側に出る型と中間の答え型を別々に取れた。このプロジェクトは B=C=R に固定しこれを削除した。

理由は2点:
- 型パラメータが3個から2個（`A`, `R`）に減り、ユーザーが書く型注釈が単純になる
- Scala の型システムで B≠C を表現すると型注釈が煩雑になるため

`reset$transformed[A](body: ControlContext[A, A])` が答え型 `A` と結果型 `A` を同一にしているのもこの設計の帰結。

### 型の扱い

`A` は共変 `+A`。成功値の型を表す。`R` は最終的なreset後の結果型で、不変。

`fun` の型は `(A => R, Exception => R) => R`。第一引数は成功継続、第二引数は例外継続。

`map` は `A => A1` を受けて `ControlContext[A1, R]` に変換する。`R` は変えない。

`flatMap` は `A => ControlContext[A1, R]` を受けるため、同じ答え型 `R` を保ったまま計算を連結する。

`flatMapCatch[A1 >: A]` は例外ハンドラが元の成功型 `A` のスーパータイプ `A1` を返せるようにしている。成功側の `k` と例外回復側の `handler(e).fun(k, eh)` を同じ型で扱うための境界。

### 不変条件・前提

- `fun` は必ず成功継続または例外継続を使って `R` を返す前提。
- 捕捉する例外は `Exception` のみ。`Throwable` 全般や `Error` は対象外。
- `map` / `flatMap` の中では、ユーザ関数や継続実行中に投げられた `Exception` を例外継続へ送る。
- `foreach` のデフォルト例外継続は再throwなので、未処理例外は通常の例外として外へ出る。

### エラー診断

このファイル内にエラー・警告メッセージ文字列はない。

---

## CpsSym

### 責務

CPS変換対象または変換済みシンボルをマークするためのアノテーション定義。実装は空で、意味付けはコンパイラプラグイン側がこのアノテーションを検出することで行う想定。

### シグネチャ

```scala
class CpsSym extends StaticAnnotation
```

### 意味論・アルゴリズム

実行時ロジックはない。`scala.annotation.StaticAnnotation` を継承する空クラスを定義しているだけ。

コンパイル時にシンボルへ付与されるメタ情報として使われる設計であり、このファイル単体では変換処理・検証処理・診断処理は行わない。

### 型の扱い

`CpsSym` は `StaticAnnotation` のサブクラス。型パラメータ、メソッド、コンストラクタ引数はない。

### 不変条件・前提

- `CpsSym` 自体は状態を持たない。
- このアノテーションの意味はライブラリ内では定義されておらず、コンパイラプラグイン側が解釈する前提。

### エラー診断

このファイル内にエラー・警告メッセージ文字列はない。

---

## CpsTransform

### 責務

`shift` / `reset` のコンパイル時文脈を表すマーカー型。`CpsTransform[R]` の given context がある場所だけで `shift` を呼べるようにするための型レベルの印。

実装は空で、実際のCPS変換はコンパイラプラグインがこの型を手掛かりに行う想定。

### シグネチャ

```scala
class CpsTransform[R]
```

### 意味論・アルゴリズム

実行時ロジックはない。型パラメータ `R` を持つ空クラスを定義しているだけ。

`package.scala` の `shift` と `reset` はこの型を context function / context parameter として使う。`reset[A]` の本体内では `CpsTransform[A]` が必要になり、`shift[A, R]` は `CpsTransform[R] ?=> A` という型で表される。

### 型の扱い

`R` はresetの答え型を表す。`CpsTransform[R]` の値自体にフィールドやメソッドはない。型パラメータだけが意味を持つ。

### 不変条件・前提

- `CpsTransform` のインスタンスを通常のランタイム値として操作する前提ではない。
- `shift` / `reset` の型付けと、コンパイラプラグインによる選択的CPS変換の目印として使われる前提。

### 設計判断: compiletime.Erased / erased を削除した理由

当初設計では `class CpsTransform[R] extends caps.Control, compiletime.Erased` とする予定だった。`compiletime.Erased` を継承することでインスタンスが実行時に消去され、`shift`/`reset` 呼び出し側が `erased` キーワードを書かずに済む利点があった。

削除した理由: `compiletime.erasedValue` は `-language:experimental.erasedDefinitions` 下でのみ有効な実験的機能であり、`erased def` からしか呼び出せない。`shift`/`reset` を `erased def` にするとその呼び出し側もすべて erased context が必要になり、ユーザーコードと相容れない。実験的機能の制約が設計コストを上回るため削除した。

現行の `class CpsTransform[R]` はシンプルなマーカークラス。プラグイン変換後のコードでは `CpsTransform[R] ?=> A` な式は `ControlContext[A, R]` に置き換えられるため、変換済みコードでは `CpsTransform` インスタンスは実際には生成されない。

### エラー診断

このファイル内にエラー・警告メッセージ文字列はない。

---

## package.scala（公開API関数）

### 責務

ユーザが利用する公開API関数を定義する。

通常ソース上では `shift` / `reset` を使って直接スタイルで継続計算を書く。コンパイラプラグインによる変換後は、`shift$transformed` / `reset$transformed` と `ControlContext` によるCPS表現へ落ちる。

`shiftR` / `shiftUnitR` は `ControlContext` を直接構築する低レベルAPI。

### シグネチャ

```scala
def shift[A, R](f: (A => R) => R): CpsTransform[R] ?=> A
```

```scala
def reset[A](body: CpsTransform[A] ?=> A): A
```

```scala
def shiftR[A, R](f: (A => R) => R): ControlContext[A, R]
```

```scala
def shiftUnitR[A, R](x: A): ControlContext[A, R]
```

```scala
def shift$transformed[A, R](f: (A => R) => R): ControlContext[A, R]
```

```scala
def reset$transformed[A](body: ControlContext[A, A]): A
```

### 意味論・アルゴリズム

`shift[A, R](f)` は宣言上、`CpsTransform[R] ?=> A` を返す。実装は `???`。通常実行される関数ではなく、コンパイラプラグインが変換対象として扱う入口。

`reset[A](body)` も実装は `???`。`body` は `CpsTransform[A] ?=> A`、つまり `reset` の中だけで `CpsTransform[A]` の文脈が利用可能になる形を型で表す。これも直接実行される想定ではなく、変換対象の構文的API。

`shiftR[A, R](f)` は `ControlContext((k, _) => f(k))` を返す。実行時に成功継続 `k` を受け取り、例外継続は無視して、ユーザ関数 `f` に `k` を渡して `R` を得る。これがshiftの基本的なCPS表現。

`shiftUnitR[A, R](x)` は `ControlContext((k, _) => k(x))` を返す。値 `x` をそのまま成功継続に渡すだけの純粋なCPS値。

`shift$transformed[A, R](f)` は `shiftR(f)` を呼ぶだけ。変換後コードが `shift` 相当を参照するための別名。

`reset$transformed[A](body)` は `body.foreach(identity)` を実行する。`ControlContext[A, A]` を、成功継続 `identity` とデフォルト例外継続で実行し、通常値 `A` に戻す。

### 型の扱い

`shift` は戻り値型に context function 型 `CpsTransform[R] ?=> A` を使う。これにより、`R` を答え型として持つreset文脈の中でだけ値 `A` として扱える。

`reset` は `body: CpsTransform[A] ?=> A` を受ける。resetの本体の答え型は `A` に固定される。

`shiftR` と `shift$transformed` は直接 `ControlContext[A, R]` を返す。`A` はshiftが現在位置へ返す値の型、`R` は捕獲された継続全体の答え型。

`shiftUnitR` は通常値 `A` を `ControlContext[A, R]` に持ち上げる。

`reset$transformed` は `ControlContext[A, A]` だけを受ける。resetの外へ出る値型と答え型が一致する必要がある。

### 不変条件・前提

- `shift` と `reset` は実装が `???` なので、未変換のまま実行すると `NotImplementedError` になる。
- `shiftR` は例外継続を使わない。`f(k)` 内の例外捕捉はここでは行わない。
- `reset$transformed` は `ControlContext.foreach(identity)` に依存するため、未処理の `Exception` は再throwされる。
- `reset$transformed` の入力は答え型が `A` と一致する `ControlContext[A, A]` でなければならない。

### 設計判断: shift$transformed / reset$transformed をライブラリで事前定義する理由

`$transformed` は原則コンパイラプラグインが自動で生成する固定 ABI となっている。しかし `shift$transformed` と `reset$transformed` は例外的にライブラリ内に事前定義されている。これは library と plugin の循環依存を避けるためである。

### 設計判断: reify, reifyR を library から削除した理由

scala-continuations では `reify(...)` を `reifyR(...)` に変換するロジックが存在し、`reifyR` は `def reifyR[A,R](ctx: => ControlContext[A,R]): ControlContext[A,R] = ctx`（identity 関数）として定義されていた。

このプロジェクトでは `$transformed` ABI を採用したことで `shift$` と `reset` の変換済み実体を `shift$transformed` と `reset$transformed` として直接記述できる。そのため scala-continuations が `reifyR` を必要としていた「変換済み実体への迂回」が不要になり、`reifyR` はメインライブラリからも削除し、プラグインからの参照も除去した。library の4ファイル（ControlContext.scala / CpsSym.scala / CpsTransform.scala / package.scala）に `reifyR` は存在しない。

なお、 Filinski の `reify`/`reflect`（monadic reflection operator）は `sandbox/reify-reflect` で実装済みであるが、同名の無関係な operator である。

### エラー診断

明示的なエラー・警告メッセージ文字列はない。

以下2つは `???` 実装であり、未変換のまま実行された場合、Scala 標準の `Predef.???` により `NotImplementedError` が発生する。

```scala
def shift[A, R](f: (A => R) => R): CpsTransform[R] ?=> A = ???
```

```scala
def reset[A](body: CpsTransform[A] ?=> A): A = ???
```

---

## 関連文書

- [00-overview.md](00-overview.md) — 全体アーキテクチャ概観
- [02-stub-phase.md](02-stub-phase.md) — コンパイラプラグインのスタブフェーズ仕様
- [04-cps-phase.md](04-cps-phase.md) — CPS変換フェーズ仕様（`shift$transformed` / `reset$transformed` の生成元）
- [05-shared-infrastructure.md](05-shared-infrastructure.md) — プラグイン共通インフラ（`CpsSym` / `CpsTransform` の参照方法を含む）
