# sandbox: reify-reflect 実装仕様

## 対象ソースファイル一覧

```
sandbox/reify-reflect/core/src/main/scala/continuations/reifyreflect/CpsMonad.scala
sandbox/reify-reflect/core/src/main/scala/continuations/reifyreflect/package.scala
sandbox/reify-reflect/core/src/main/scala/continuations/reifyreflect/instances/Id.scala
sandbox/reify-reflect/core/src/main/scala/continuations/reifyreflect/instances/ListMonad.scala
sandbox/reify-reflect/core/src/main/scala/continuations/reifyreflect/instances/OptionMonad.scala
sandbox/reify-reflect/core/src/main/scala/continuations/reifyreflect/instances/ReaderMonad.scala
sandbox/reify-reflect/cats/src/main/scala/continuations/reifyreflect/cats/CatsMonadInstances.scala
sandbox/reify-reflect/scalaz/src/main/scala/continuations/reifyreflect/scalaz/ScalazMonadInstances.scala
sandbox/reify-reflect/zio/src/main/scala/continuations/reifyreflect/zio/ZioMonadInstance.scala
sandbox/reify-reflect/zio/src/main/scala/continuations/reifyreflect/zio/package.scala
```

## 概要

### reify / reflect とは何か

`reflect` と `reify` は、継続プラグインが提供する `shift` / `reset` を用いて、任意のモナド `M[_]` をいわゆる「直接スタイル（direct style）」で扱えるようにする操作の対である。

- `reflect(ma: M[A])` は、`M[A]` という包まれた値を CPS 変換文脈の内側で通常値 `A` として取り出す。継続 `k: A => M[R]` を `flatMap` で接続することで実現する。
- `reify(body)` は、直接スタイルで書かれた本体（内部で `reflect` を呼べる）を `M[A]` に持ち上げる。内部的には `reset` を使って CPS 領域を閉じ、最終値を `pure` で包む。

この対により、for 内包表記を使わずにモナディックな計算を直接スタイルで記述できる。

### 理論的背景

Andrzej Filinski (1994) "Representing Monads" の中心的なアイデアは、**限定継続（shift/reset）があれば任意のモナドを直接スタイルでエンコードできる**というものである。

等式的には次の性質が成立する:

```
reify { reflect(ma) }  ≡  ma
reflect(reify { a })   ≡  a
```

本実装における対応は以下の通りである:

```
reflect(ma: M[A])  = shift[A, M[R]](k => cm.flatMap(ma)(k))
reify(body)        = reset[M[A]](cm.pure(body))
```

`reify { reflect(ma) }` の展開例（Option モナドの場合）:

```
reset[Option[B]] {
  cm.pure(shift[A, Option[B]] { k => cm.flatMap(ma)(k) })
}
```

プラグインの ANF/CPS 変換後、`shift` と `reset` の代数的性質と `flatMap` の整合から `ma` に収束する。等式的性質の理論的正しさはモナド則（left identity, right identity, associativity）と shift/reset の健全性に依存する。

### CpsMonad 抽象とプラグインの関係

プラグイン（`continuations` パッケージ）が提供する `shift` / `reset` は、CPS 変換の文脈制御を担う。`reify-reflect` はその上に乗る薄いアダプタ層であり、特定のモナド `M` に対する `pure` / `flatMap` の知識を `CpsMonad[M]` トレイトに集約することで、`shift` / `reset` を任意のモナドに橋渡しする。

`CpsMonad[M]` は Scala 3 の `given` 機構を通じて解決される。利用者は対象モナドに対応する `given CpsMonad[M]` をスコープに置くだけでよい。

---

## core

### CpsMonad 抽象

**ファイル**: `CpsMonad.scala`
**パッケージ**: `continuations.reifyreflect`

`M[_]` に対する最小限のモナド抽象を定義するトレイト。

```scala
trait CpsMonad[M[_]]:
  def pure[A](a: A): M[A]
  def flatMap[A, B](ma: M[A])(f: A => M[B]): M[B]
  def map[A, B](ma: M[A])(f: A => B): M[B] = flatMap(ma)(a => pure(f(a)))
```

- `pure` は通常値 `A` をモナド値 `M[A]` に持ち上げる抽象メソッドである。
- `flatMap` は `M[A]` を受け取り、`A => M[B]` の継続的な計算を接続して `M[B]` を返す抽象メソッドである。
- `map` は `flatMap` と `pure` のみに依存する具象実装であり、`flatMap(ma)(a => pure(f(a)))` として定義される。インスタンスは `pure` と `flatMap` の2メソッドを実装すればよい。
- コード上はモナド則の検査を行わないが、`reify` / `reflect` の意味論が正しくなるには、実装がモナド則（特に `pure` と `flatMap` の整合性）を満たすことが前提となる。

---

### reify・reflect の意味論

**ファイル**: `package.scala`
**パッケージ**: `continuations.reifyreflect`

```scala
package continuations

package object reifyreflect:
  import continuations.*

  def reflect[M[_], A, R](ma: M[A])(using cm: CpsMonad[M]): CpsTransform[M[R]] ?=> A =
    shift[A, M[R]](k => cm.flatMap(ma)(k))

  def reify[M[_], A](body: CpsTransform[M[A]] ?=> A)(using cm: CpsMonad[M]): M[A] =
    reset[M[A]](cm.pure(body))
```

#### reflect

型パラメータは `M[_]`, `A`, `R`。

- `A` は反映される値の型。
- `R` は外側の `reify` 全体の結果型。
- 戻り値は文脈関数型 `CpsTransform[M[R]] ?=> A` であり、`CpsTransform[M[R]]` が与えられる CPS 文脈内でのみ `A` として使える。

実装は `shift[A, M[R]](k => cm.flatMap(ma)(k))`。

`shift` に渡される継続 `k: A => M[R]` は、反映された値 `A` を受け取って最終的なモナド結果 `M[R]` を生成する。`cm.flatMap(ma)(k)` により、モナドの中身 `A` が後続計算 `k` に渡される。`reflect(ma)` 以降の直接スタイル計算は、実際には `flatMap` の継続として合成される。

#### reify

型パラメータは `M[_]`, `A`。

- `body` の型は `CpsTransform[M[A]] ?=> A`（CPS 変換文脈を要求する直接スタイル本体）。
- `using cm: CpsMonad[M]` により、対象モナド `M` の `pure` / `flatMap` が暗黙に供給される。

実装は `reset[M[A]](cm.pure(body))`。

`body` 自体は直接スタイル上で `A` を返すため、最終値は `cm.pure(body)` によって `M[A]` に持ち上げられる。途中に `reflect` がある場合、その `shift` により後続計算が `flatMap` へ組み込まれる。`reset` が CPS 領域を閉じることで全体として `M[A]` が得られる。

#### 不変条件

- `reflect` と `reify` の `M` は同じ `CpsMonad[M]` に基づく必要がある。
- `reflect` の型パラメータ `R` は、外側の `reify` の結果型と一致している必要がある。型 `CpsTransform[M[R]]` がその対応関係を型として強制する。
- `shift` / `reset` / `CpsTransform` は `continuations.*` から来る。このファイルでの定義はない。

---

### モナドインスタンス: Id

**ファイル**: `instances/Id.scala`

恒等モナド `Id` の型エイリアスと `CpsMonad` インスタンスを提供する。効果を持たない通常計算を `reify` / `reflect` の枠組みに載せるためのインスタンスである。

```scala
type Id[A] = A

given CpsMonad[Id] with
  def pure[A](a: A): Id[A] = a
  def flatMap[A, B](ma: Id[A])(f: A => Id[B]): Id[B] = f(ma)
```

- `Id[A]` は `A` の単純な型エイリアスであり、包む構造を持たない。
- `pure` は値 `a` をそのまま返す。`A` が即 `Id[A]` になる。
- `flatMap` は `ma: Id[A]`（実体は `A`）を受け取り、関数 `f: A => Id[B]` を直接適用する（`f(ma)`）。
- `reflect` された `Id[A]` は追加効果なしに通常値として継続へ渡される。
- `Id` はトップレベル型エイリアスであり、型ラムダではない。given は名前なし。

---

### モナドインスタンス: ListMonad

**ファイル**: `instances/ListMonad.scala`

標準ライブラリの `List` に対する `CpsMonad` インスタンスを提供する。`reflect` によってリストの各要素を非決定的な直接スタイル値として扱える。

```scala
given CpsMonad[List] with
  def pure[A](a: A): List[A] = List(a)
  def flatMap[A, B](ma: List[A])(f: A => List[B]): List[B] = ma.flatMap(f)
```

- `pure` は単一要素リスト `List(a)` を返す。
- `flatMap` は標準の `List.flatMap` に委譲する（`ma.flatMap(f)`）。
- `reflect(ma)` を使うと `ma` の各要素が後続継続に渡され、各継続結果のリストが連結される。`reify` の結果は全ての分岐結果を列挙した `List` になる。
- 空リストを `reflect` すると後続継続は一度も実行されず、結果も空リストになる。
- `List` は標準の単項型コンストラクタ `List[_]` として `CpsMonad[List]` に渡される。given は名前なし。

---

### モナドインスタンス: OptionMonad

**ファイル**: `instances/OptionMonad.scala`

標準ライブラリの `Option` に対する `CpsMonad` インスタンスを提供する。`reflect` によって `Some` の値を直接スタイルで取り出し、`None` を短絡として扱える。

```scala
given CpsMonad[Option] with
  def pure[A](a: A): Option[A] = Some(a)
  def flatMap[A, B](ma: Option[A])(f: A => Option[B]): Option[B] = ma.flatMap(f)
```

- `pure` は値 `a` を `Some(a)` に包む。
- `flatMap` は標準の `Option.flatMap` に委譲する（`ma.flatMap(f)`）。
- `reflect(Some(a))` は `a` を後続継続へ渡す。`reflect(None)` は後続継続を実行せず、結果全体を `None` にする。
- `reify` の最後の通常値は `Some` に包まれるが、途中で `None` が反映されると `flatMap` の意味により短絡する。
- 失敗・欠損の表現は `None` のみ。エラー情報は保持しない。
- `Option` は標準の単項型コンストラクタ `Option[_]` として `CpsMonad[Option]` に渡される。given は名前なし。

---

### モナドインスタンス: ReaderMonad

**ファイル**: `instances/ReaderMonad.scala`

環境 `R` を読む Reader モナドを opaque type として定義し、その `CpsMonad` インスタンスを提供する。

```scala
opaque type Reader[R, A] = R => A

object Reader:
  def apply[R, A](f: R => A): Reader[R, A] = f

def run[R, A](r: Reader[R, A])(env: R): A = r(env)

given [R]: CpsMonad[[A] =>> Reader[R, A]] with
  def pure[A](a: A): Reader[R, A] = Reader(_ => a)
  def flatMap[A, B](ma: Reader[R, A])(f: A => Reader[R, B]): Reader[R, B] = Reader(r => run(f(run(ma)(r)))(r))
```

#### 型

`Reader[R, A]` は `R => A` の opaque type。外部には抽象型として公開され、ファイル内でのみ関数として扱える。

`Reader` は2型引数を取るため、`CpsMonad` に渡すには `R` を固定して `A` だけを残す型ラムダが必要である。

```scala
given [R]: CpsMonad[[A] =>> Reader[R, A]]
```

この given は任意の環境型 `R` ごとに `CpsMonad` インスタンスを提供する。

#### 公開 API

- `Reader.apply[R, A](f: R => A): Reader[R, A]` — 関数から Reader を構築する。
- `run[R, A](r: Reader[R, A])(env: R): A` — 環境を渡して Reader を実行し `A` を得る。

#### pure / flatMap の実装

`pure` は環境を無視して常に `a` を返す Reader を作る。

```scala
Reader(_ => a)
```

`flatMap` は同じ環境 `r` を前段と後段の両方に渡す。

```scala
Reader(r => run(f(run(ma)(r)))(r))
```

処理手順:

1. 環境 `r: R` を受け取る Reader を作る。
2. `run(ma)(r)` で前段 Reader を環境 `r` の下で実行し `A` を得る。
3. 得られた `A` を `f` に渡して後段の `Reader[R, B]` を得る。
4. 後段 Reader も同じ環境 `r` で `run` し `B` を返す。

`reflect(ma)` は、現在の環境 `R` に依存して得られる `A` を直接スタイル値として後続計算へ渡す。後続計算が生成する Reader も同一環境で評価される。

#### 不変条件

- `flatMap` では前段と後段に必ず同じ環境 `r` を渡す。環境の変更や局所的な上書き操作はこのファイルでは提供されない。
- `pure` は環境に依存しない値を返す。
- opaque type の実体は `R => A` だが、外部から直接関数として扱うことはできない。公開 API は `Reader.apply` と `run` に限定される。

---

## バックエンド

バックエンドは外部ライブラリのモナド型クラスを `CpsMonad` に橋渡しするアダプタ層である。各バックエンドは core モジュールに依存し、対象ライブラリの `pure` / `flatMap` 相当の操作を `CpsMonad` の実装として提供する。

---

### cats バックエンド

**ファイル**: `cats/CatsMonadInstances.scala`
**パッケージ**: `continuations.reifyreflect.cats`

Cats の `cats.Monad[M]` を `CpsMonad[M]` に適合させるアダプタ。任意の型コンストラクタ `M[_]` について、Cats の `Monad[M]` が summon できる場合に `CpsMonad[M]` を提供する。

```scala
given [M[_]](using M: Monad[M]): CpsMonad[M] with
  def pure[A](a: A): M[A] = M.pure(a)
  def flatMap[A, B](ma: M[A])(f: A => M[B]): M[B] = M.flatMap(ma)(f)
```

- `pure` は Cats の `M.pure(a)` に委譲する。
- `flatMap` は Cats の `M.flatMap(ma)(f)` に委譲する。
- `using M: Monad[M]` により Cats 側のモナド辞書を受け取り、`CpsMonad[M]` の実装に変換する。
- 外部シンボルは `_root_.cats.Monad` として import されており、同名シンボルとの衝突を避けている。
- モナド則は委譲先の Cats `Monad` 実装が満たすことを前提とし、このアダプタ自身では検証しない。

#### 使用例

```scala
import cats.effect.IO
import continuations.reifyreflect.*
import continuations.reifyreflect.cats.given

// Option: Some で値を取り出す
val r1: Option[Int] = reify {
  val x = reflect(Some(3))
  val y = reflect(Some(4))
  x + y
}
// r1 == Some(7)

// Option: None で短絡
val r2: Option[Int] = reify {
  val x = reflect(None: Option[Int])
  x + 1  // None で短絡; ここには到達しない
}
// r2 == None

// IO (cats-effect)
val program: IO[Int] = reify {
  val x = reflect(IO(println("hello")) *> IO.pure(10))
  val y = reflect(IO.pure(x * 2))
  y + 1
}
// program.unsafeRunSync() == 21
```

`Option` と `IO` はどちらも `cats.Monad` のインスタンスを持つため、`continuations.reifyreflect.cats.given` をインポートするだけで `reify` / `reflect` が利用できる。

---

### scalaz バックエンド

**ファイル**: `scalaz/ScalazMonadInstances.scala`
**パッケージ**: `continuations.reifyreflect.scalaz`

Scalaz の `scalaz.Monad[M]` を `CpsMonad[M]` に適合させるアダプタ。任意の `M[_]` について、Scalaz の `Monad[M]` が存在すれば `CpsMonad[M]` を提供する。

```scala
given [M[_]](using M: Monad[M]): CpsMonad[M] with
  def pure[A](a: A): M[A] = M.point(a)
  def flatMap[A, B](ma: M[A])(f: A => M[B]): M[B] = M.bind(ma)(f)
```

- `pure` は Scalaz の `M.point(a)` に委譲する（Scalaz では `pure` に相当するメソッドが `point`）。
- `flatMap` は Scalaz の `M.bind(ma)(f)` に委譲する（Scalaz では `flatMap` に相当するメソッドが `bind`）。
- `using M: Monad[M]` により Scalaz の型クラスインスタンスを受け取る。
- 外部シンボルは `_root_.scalaz.Monad` として import されている。
- `pure` と `flatMap` の意味論およびモナド則は委譲先の Scalaz `Monad` 実装に依存する。

#### 使用例

```scala
import _root_.scalaz.Maybe
import continuations.reifyreflect.*
import continuations.reifyreflect.scalaz.given

val r: Maybe[Int] = reify {
  val x = reflect(Maybe.just(10))
  val y = reflect(Maybe.just(x + 5))
  x + y
}
// r == Maybe.just(25)
```

`Maybe.just` は Scalaz の `Option` 相当の成功値コンストラクタである。`Maybe.empty` を `reflect` すると短絡し、`reify` の結果全体が `Maybe.empty` になる。Scalaz バックエンドでは `pure → M.point(a)`、`flatMap → M.bind(ma)(f)` と Scalaz の命名規約に従ったメソッド名が使われる。

---

### zio バックエンド

**ファイル**: `zio/ZioMonadInstance.scala`, `zio/package.scala`
**パッケージ**: `continuations.reifyreflect.zio`

ZIO の `ZIO[Env, Err, A]` を `CpsMonad` に適合させるアダプタ。`ZIO` は3型引数を取るため、`Env` と `Err` を固定して `[A] =>> ZIO[Env, Err, A]` という単項型コンストラクタを `CpsMonad` に渡す。

#### ZioMonadInstance

```scala
given zioMonad[Env, Err]: CpsMonad[[A] =>> ZIO[Env, Err, A]] with
  def pure[A](a: A): ZIO[Env, Err, A] = ZIO.succeed(a)
  def flatMap[A, B](ma: ZIO[Env, Err, A])(f: A => ZIO[Env, Err, B]): ZIO[Env, Err, B] =
    ma.flatMap(f)
```

- `pure` は `ZIO.succeed(a)` を使い、失敗しない成功値として `A` を ZIO 文脈に持ち上げる。
- `flatMap` は ZIO インスタンスメソッド `ma.flatMap(f)` に委譲する。環境型 `Env` とエラー型 `Err` は固定されたまま、成功値の型だけ `A` から `B` に変わる。
- `given` には名前 `zioMonad` が付いており、後述の `package.scala` から参照される。
- `Env` と `Err` は `CpsMonad` インスタンス内で固定され、`flatMap` に渡す関数も同じ `Env` と `Err` を返す必要がある。
- `pure` は `ZIO.succeed` による成功値なのでエラー `Err` を生成しない。エラー処理の意味論は ZIO 側に委譲されている。
- 外部シンボルは `_root_.zio.ZIO` として import されている。

#### ZIO 向け補助関数（package.scala）

ZIO に特化した `reflectZIO` / `reifyZIO` を公開する。

```scala
def reflectZIO[Env, Err, A, B](zio: ZIO[Env, Err, A]): CpsTransform[ZIO[Env, Err, B]] ?=> A =
  shift[A, ZIO[Env, Err, B]](k => zio.flatMap(k))

def reifyZIO[Env, Err, A](body: CpsTransform[ZIO[Env, Err, A]] ?=> A): ZIO[Env, Err, A] =
  given CpsMonad[[X] =>> ZIO[Env, Err, X]] = zioMonad[Env, Err]
  reset[ZIO[Env, Err, A]](summon[CpsMonad[[X] =>> ZIO[Env, Err, X]]].pure(body))
```

`reflectZIO` は core 側の `shift` を使い、ZIO の `flatMap` で継続を接続する。

```scala
shift[A, ZIO[Env, Err, B]](k => zio.flatMap(k))
```

型パラメータ `B` は、反映された `A` の後に続く計算全体の最終成功型を表す。

`reifyZIO` はまず ZIO 用の `CpsMonad` をローカル given として定義し、`body` を `pure` で `ZIO[Env, Err, A]` に持ち上げて `reset` に渡す。

```scala
given CpsMonad[[X] =>> ZIO[Env, Err, X]] = zioMonad[Env, Err]
reset[ZIO[Env, Err, A]](summon[CpsMonad[[X] =>> ZIO[Env, Err, X]]].pure(body))
```

不変条件:

- `Env` と `Err` は `reflectZIO` / `reifyZIO` の一連の CPS 計算内で固定される。
- `reflectZIO` に渡す `zio` と継続 `k` が返す ZIO は同じ `Env` / `Err` を共有する必要がある。
- `reifyZIO` は `zioMonad[Env, Err]` がスコープにあることを前提にしている。

#### 実装上の注意

直感的に `reset[ZIO[Env, Err, A]] { ZIO.succeed(body) }` と書きたくなるが、**これは動作しない**。理由は2つある:

1. `ZIO.succeed` は外部ライブラリのメソッドであり、プラグインが `$transformed` スタブを生成しない。
2. `ZIO.succeed` の引数型は `=> A`（by-name）であり `CpsTransform[?] ?=> A` ではないため、プラグインが `body` を CPS context function 型として認識できず変換トリガーが発動しない。

そのため `reifyZIO` では局所 `given` を立て、`CpsMonad.pure` 経由で `body` を渡す専用ヘルパーとして実装されている。この構造は `reify` コアの `reset[M[A]](cm.pure(body))` と同一である。

#### エラー伝播

`reflectZIO` の実装は `shift[A, ZIO[Env, Err, B]](k => zio.flatMap(k))` である。`ZIO.flatMap` は失敗（`Err`）があると短絡するため、エラーチャンネルは ZIO が保持したまま自然に伝播する。`reify-reflect` 側で特別なエラー処理を行う必要はない。

```scala
val r: Task[Int] = reifyZIO {
  val x = reflectZIO(ZIO.fail(new RuntimeException("boom")))
  x + 1  // ここには到達しない
}
// r.exit == Exit.Failure(...)
```

#### 使用例

```scala
import zio.*
import continuations.reifyreflect.zio.*

// Task[Int] の使用例
val program: Task[Int] = reifyZIO[Any, Throwable, Int] {
  val x = reflectZIO(ZIO.succeed(10))
  val y = reflectZIO(ZIO.succeed(x + 5))
  x + y
}
// program は ZIO[Any, Throwable, Int] で結果は 25

// RIO[String, Int]: 環境注入
val withEnv: RIO[String, Int] = reifyZIO[String, Throwable, Int] {
  val name = reflectZIO(ZIO.service[String])
  name.length
}
```

型引数 `[Env, Err, A]` を `reifyZIO` に明示することで `Env`・`Err`・`A` が固定され、`Task[Int] = ZIO[Any, Throwable, Int]`、`RIO[String, Int] = ZIO[String, Throwable, Int]` の型ラムダが解決される。

---

## 型・given・文脈関数の扱い

### 高カインド型パラメータ

`CpsMonad[M[_]]` の `M` は単項型コンストラクタである。`List`, `Option`, `Id` のように最初から単項のものはそのまま渡せる。2型引数以上の型（`Reader[R, A]`, `ZIO[Env, Err, A]`）は、余剰の型引数を外部の型パラメータとして固定し、型ラムダで1引数に変換してから渡す。

```scala
// Reader の場合
given [R]: CpsMonad[[A] =>> Reader[R, A]]

// ZIO の場合
given zioMonad[Env, Err]: CpsMonad[[A] =>> ZIO[Env, Err, A]]
```

### given の種類

- **名前なし given**: `Id`, `List`, `Option`, cats, scalaz の各インスタンス。コンパイラが型から自動解決する。
- **名前あり given**: ZIO の `zioMonad[Env, Err]`。`reifyZIO` 内のローカル given 定義から明示的に参照される。

### 文脈関数型

`reflect` の戻り値と `reify` の引数はどちらも `CpsTransform[M[R]] ?=> A` という文脈関数型（contextual function type）を使っている。これにより、`CpsTransform` が暗黙に供給される CPS 変換文脈の内側でのみ値として扱える型安全な設計になっている。

---

## 型推論の制約と対処

`reflect` の型パラメータ `R` は外側の `reify` から推論されるべきだが、Scala 3 の単一化では文脈からの推論が弱い場合がある。`R` が推論できない場合は **`reify` の型引数を明示する**ことで解決できる。

```scala
// R が推論できない場合は reify の型引数を明示する
reify[Option, Int] {
  val x = reflect(Some(1))
  x + 1
}
```

**注記**: `reflectIn` のような専用の型推論補助ヘルパーは**実装に存在しない**（`notes/DESIGN-reify-reflect.md` の検討メモに記載があるのみ）。型引数の明示で対処すること。

---

## 制約一覧

| 項目 | 内容 |
|---|---|
| `while` 内の `reflect` | **プラグイン本体側の制約**（[../03-anf-phase.md](../03-anf-phase.md) 参照: `shift in while is not supported`） |
| `finally` 内の `reflect` | **プラグイン本体側の制約**（同上: `shift in finally is not supported`） |
| ネストした `reify` | 外側と内側で型 `R` が異なるため原理上は可能。動作は確認されている（`nested reset calls are legal`） |
| スタックオーバーフロー | CPS 変換は末尾最適化されないため深い再帰に注意 |
| ZIO の `Env`/`Err` | `reifyZIO` / `reflectZIO` は同一の `Env`/`Err` を持つ ZIO 値しか扱えない |
| cats-effect の `IO` | `IO` は `cats.Monad` インスタンスを持つので `given` 経由で動作する |

`while` / `finally` 非対応はこの sandbox 実装側の制限ではなく、プラグイン本体（ANF 変換フェーズ）の制約である点に注意。

---

## 不変条件・前提

1. `CpsMonad[M]` の実装はモナド則（left identity, right identity, associativity）を満たす必要がある。コード上の検査はない。
2. `reflect` と `reify` は同一の `CpsMonad[M]` インスタンスのスコープ内で組み合わせて使う必要がある。
3. `reflect` の型パラメータ `R` は外側の `reify` の結果型と一致しなければならない。型 `CpsTransform[M[R]]` がこの整合をコンパイル時に保証する。
4. バックエンドアダプタ（cats/scalaz/zio）はモナド則を自前では検証せず、委譲先ライブラリの実装に依存する。
5. ZIO バックエンドでは `Env` と `Err` が一連の `reflectZIO` / `reifyZIO` 呼び出しを通じて固定されなければならない。
6. `shift` / `reset` / `CpsTransform` は `continuations.*` から供給される前提であり、`reify-reflect` サブプロジェクト内では定義されない。

---

## エラー診断

各ファイルに明示的なエラーメッセージや診断処理は存在しない。型エラーはコンパイラの given 解決や型パラメータ検査によって検出される。

---

## 関連文書

- [../01-public-api.md](../01-public-api.md)
- [../06-architecture.md](../06-architecture.md)
