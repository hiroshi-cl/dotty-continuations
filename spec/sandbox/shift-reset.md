# sandbox: shift-reset 利用例 実装仕様

## 対象ソースファイル一覧

```
sandbox/shift-reset/src/main/scala/continuations/examples/shiftReset/abort/Escape.scala
sandbox/shift-reset/src/main/scala/continuations/examples/shiftReset/around/Resource.scala
sandbox/shift-reset/src/main/scala/continuations/examples/shiftReset/around/Tracing.scala
sandbox/shift-reset/src/main/scala/continuations/examples/shiftReset/suspend/Generator.scala
```

## 概要

このミニプロジェクトは、`shift/reset` による選択的 CPS 変換プラグインを使った代表的な継続利用パターンを4例実証する。

各例は `continuations.*` をインポートし、`CpsTransform[R]` のコンテキスト境界（`using` パラメータ）を通じてプラグインの CPS 変換を受けるコードとして実装される。`reset { ... }` ブロックが変換の境界を定め、ブロック内で `shift[A, B] { k => ... }` を呼ぶことで現在の継続 `k` を捕捉する。各例はこの仕組みの上に薄いラッパー関数を書いただけの形で示す。

4例の分類は以下のとおりである。

- **abort**: 継続を破棄して計算を打ち切る（早期脱出）
- **around**: 継続の実行を何らかの処理で包む（リソース管理・トレーシング）
- **suspend**: 継続を複数回再開して値を蓄積する（ジェネレータ）

---

## abort/Escape

### 責務

`escape` は `shift/reset` による「早期脱出（abortive continuation）」のデモである。現在の継続 `k` をユーザー関数 `f` に渡し、`f` が `k` を呼ぶか呼ばずに返すかを選択できる形にする。

### 公開シグネチャ

```scala
def escape[A, B](f: (A => B) => A)(using CpsTransform[B]): A
```

### 制御フロー・shift/reset の使い方

実装:

```scala
shift[A, B](k => f(k).asInstanceOf[B])
```

制御の流れ:

1. CPS 変換対象文脈内で `escape(f)` が評価される。
2. `shift[A, B]` が、呼び出し地点から外側の `reset` までの継続を `k: A => B` として捕捉する。
3. 捕捉した `k` を `f` に渡す。
4. `f` が `k(a)` を呼んだ場合、`a: A` が元の `escape` の戻り値として継続へ渡され、外側の計算結果 `B` が得られる。
5. `f` が `k` を呼ばずに `A` を返した場合、その値を `asInstanceOf[B]` で `B` として扱い、外側の計算を打ち切った結果として返す。

### 型の扱い

- `A` は `escape` 自体の戻り値型。
- `B` は外側の reset/CPS 文脈全体の結果型。
- `f` の型は `(A => B) => A` であり、捕捉された継続を受け取る。
- `shift` 本体は `B` を返す必要があるため、`f(k): A` を `asInstanceOf[B]` で強制変換している。これは型安全性よりもデモ目的を優先した実装である。

### 不変条件・前提

- `using CpsTransform[B]` が存在する CPS 変換対象文脈で呼ばれることが前提。
- `f` が `k` を呼ばずに返す場合、`A` を `B` としてキャストするため、実行時に型が合わなければ破綻する。安全に使うには `f` が `k` を呼ぶか、または `A` と `B` が実行時に互換である必要がある。

---

## around/Resource

### 責務

`registerCleanup` は、捕捉した継続の実行を `try/finally` で包み、CPS 文脈に後始末処理を差し込むデモである。リソース解放や cleanup を、`shift` によって「残りの計算の周囲」に配置する。

### 公開シグネチャ

```scala
def registerCleanup[R](cleanup: => Unit)(using CpsTransform[R]): Unit
```

### 制御フロー・shift/reset の使い方

実装:

```scala
shift[Unit, R](k => try k(())
finally cleanup)
```

制御の流れ:

1. CPS 変換対象文脈内で `registerCleanup(cleanup)` が評価される。
2. `shift[Unit, R]` が、呼び出し地点から外側の `reset` までの継続を `k: Unit => R` として捕捉する。
3. `k(())` により、`registerCleanup` の戻り値 `Unit` を与えて残りの計算を再開する。
4. 再開された計算が正常終了しても例外終了しても、`finally cleanup` により `cleanup` が実行される。
5. `k(())` の結果、または例外の伝播が外側の結果になる。

### 型の扱い

- `registerCleanup` 自体の戻り値型は `Unit`。
- `R` は外側の reset/CPS 文脈全体の結果型。
- `cleanup` は名前渡し引数 `=> Unit` であり、`registerCleanup` 呼び出し時ではなく `finally` 到達時に評価される。

### 不変条件・前提

- `using CpsTransform[R]` が存在する CPS 変換対象文脈で呼ばれることが前提。
- `cleanup` は `k(())` の終了時に必ず評価される。ただし `cleanup` 自体が例外を投げる場合、Scala の通常の `finally` と同様にその例外が結果を上書きする可能性がある。

---

## around/Tracing

### 責務

`inspect` は、CPS 文脈中の値をログに記録してから継続を再開するトレーシング用デモである。計算そのものの値は変えず、`shift` によって途中地点の観測処理を挿入する。

### 公開シグネチャ

```scala
def inspect[A, R](label: String, value: A, log: collection.mutable.ListBuffer[String])(using CpsTransform[R]): A
```

### 制御フロー・shift/reset の使い方

実装:

```scala
shift[A, R](k => {
  log += s"$label = $value"
  k(value)
})
```

制御の流れ:

1. CPS 変換対象文脈内で `inspect(label, value, log)` が評価される。
2. `shift[A, R]` が、呼び出し地点から外側の `reset` までの継続を `k: A => R` として捕捉する。
3. `log += s"$label = $value"` により、現在値を文字列化してログに追加する。
4. `k(value)` により、元の `inspect` 呼び出しの結果として `value` を渡し、残りの計算を再開する。
5. 継続の戻り値 `R` が `shift` 全体の結果になる。

### 型の扱い

- `A` は観測対象 `value` と `inspect` の戻り値型。
- `R` は外側の reset/CPS 文脈全体の結果型。
- `log` は `collection.mutable.ListBuffer[String]` であり、`+=` により副作用的に更新される。
- `inspect` は `value: A` を変更せず、そのまま継続へ渡す。

### 不変条件・前提

- `using CpsTransform[R]` が存在する CPS 変換対象文脈で呼ばれることが前提。
- `log` は mutable な `ListBuffer[String]` である必要がある。
- ログ追加は継続再開より前に実行されるため、継続側の計算は更新済みログを観測できる。

---

## suspend/Generator

### 責務

`emit` は、`shift/reset` による generator 風の値生成デモである。値 `x` を現在の継続結果リストの先頭に追加し、複数回の `emit` を通じて `List[A]` を構築する形を示す。

### 公開シグネチャ

```scala
def emit[A](x: A)(using CpsTransform[List[A]]): Unit
```

### 制御フロー・shift/reset の使い方

実装:

```scala
shift[Unit, List[A]](k => x :: k(()))
```

制御の流れ:

1. CPS 変換対象文脈内で `emit(x)` が評価される。
2. `shift[Unit, List[A]]` が、呼び出し地点から外側の `reset` までの継続を `k: Unit => List[A]` として捕捉する。
3. `k(())` により、`emit` の戻り値 `Unit` を与えて残りの計算を再開する。
4. 継続が返した `List[A]` の先頭に `x` を追加する。
5. その結果 `x :: k(())` が外側の generator 的な結果リストになる。

複数の `emit` が順に現れる場合、それぞれの `shift` が残りの計算を再開し、その戻り値リストの前に自身の値を追加する。

### 型の扱い

- `emit` 自体の戻り値型は `Unit`。
- 外側の CPS 文脈の結果型は固定で `List[A]`。
- `shift[Unit, List[A]]` により、捕捉される継続は概念的に `Unit => List[A]` になる。
- `x :: k(())` のため、`x` と継続が返すリスト要素の型は同じ `A` で揃う必要がある。

### 不変条件・前提

- `using CpsTransform[List[A]]` が存在する CPS 変換対象文脈で呼ばれることが前提。
- 継続 `k(())` は `List[A]` を返す必要がある。通常は外側の reset 文脈の末尾で空リストなどを返す構造により、`emit` された値が順にリストへ蓄積される。

---

## エラー診断

全4ファイルともエラー診断文字列なし（RA-shiftreset.md 調査時点）。

---

## 関連文書

- [../01-public-api.md](../01-public-api.md) — `shift`・`reset`・`CpsTransform` の公開 API 仕様
- [../06-architecture.md](../06-architecture.md) — プラグイン全体のアーキテクチャ概要
