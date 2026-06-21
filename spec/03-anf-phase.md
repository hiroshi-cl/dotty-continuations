# 03 ANFフェーズ仕様

## 対象ソースファイル

- `plugin/src/main/scala/continuations/plugin/phase/anf/SelectiveANFTransform.scala`
- `plugin/src/main/scala/continuations/plugin/phase/anf/ANFBodyTransformer.scala`

## 概要（フェーズの位置づけ）

フェーズ名: `selectiveanf`

フェーズの順序:

```scala
override val runsAfter: Set[String] = Set("pickler")
override val runsBefore: Set[String] = Set("firstTransform")
```

このフェーズは `pickler` の直後、`firstTransform` の直前に実行される。処理は2段階。

1. `ANFBodyTransformer` による ANF 変換: `CpsTransform[R]` パラメータを持つ `DefDef` の本体を A-正規形（ANF）に変換する。具体的には、引数位置・selector 位置などの非 tail 位置に現れる CPS 式を `@CpsSym` 付き synthetic `ValDef` に抽出し、後続の CPS 変換フェーズが扱いやすい形に正規化する。
2. `transformPackageDef` による残留 CPS 式診断: ANF 変換後のツリーを走査し、不正な `shift` 使用や未対応パターンを報告する。

## ANF化の変換規則

### 変換エントリポイント

`SelectiveANFTransform.transformDefDef` は `hasCpsTransformParam(tree.symbol)` が真で、かつ RHS が空でない `DefDef` だけを対象にする。対象の場合、`ctx.withOwner(tree.symbol)` で owner を切り替え、`ANFBodyTransformer.transformTail(tree.rhs)` を呼ぶ。返された `(stmts, newRhs)` を `mkBlock(stmts, newRhs)` で RHS にまとめ、`cpy.DefDef(tree)(rhs = fullRhs)` で置換する。

### `ANFBodyTransformer` の主要内部関数

| 関数名 | 役割 |
|--------|------|
| `transform(tree)` | 外側エントリ。`DefDef` はそのまま返す（ネスト定義に入らない）。それ以外は `transformTail` を呼ぶ。 |
| `transformTail(tree)` | tail 位置の変換。tail 位置の CPS 式そのものは抽出しない。 |
| `transformInline(tree)` | 引数位置・selector 位置・qualifier 位置の変換。CPS 式を synthetic `ValDef` に抽出する。 |
| `transformStat(stat)` | block 内の 1 文の変換。 |
| `containsCpsExpr(tree)` | 木全体を走査して `isCpsExpr` が真のノードがあるかを調べる。`DefDef` には入らない。 |
| `isCpsExpr(tree)` | ノード単体の即時 CPS 式判定（後述）。 |
| `mkBlock(stmts, expr)` | 文リストが空なら `expr` を返し、空でなければ `tpd.Block(stmts, expr)` を作るユーティリティ。 |

### 外側 API

`transform(tree)` は `DefDef` をそのまま返す（ネストした定義には入らない）。それ以外は `transformTail` を呼び、文リストが空なら式だけ、空でなければ `tpd.Block(stmts, t)` を返す。

### 構文形ごとの変換規則（`transformTail`）

**`Block(stats, expr)`**

各 stat を `transformStat` で変換し、最後の `expr` を `transformTail` で変換する。`stats` 由来の文と `expr` 由来の文を連結して `mkBlock(newStats ++ exprStmts, newExpr)` に包む。外側には追加文を返さず `(Nil, block)` を返す。

**`If(cond, thenp, elsep)`**

条件 `cond` は inline 位置なので `transformInline` で変換する。then/else は tail 位置なのでそれぞれ `transformTail` で変換する。各分岐は `mkBlock(thenStmts, newThen)`、`mkBlock(elseStmts, newElse)` にまとめる。条件から出た文は `if` の前に置く。純粋値の lift（`shiftUnitR`）はここでは行わず、後続の CPS 変換フェーズの責務とする。

**`Match(sel, cases)`**

まず `cases.exists(cd => !cd.guard.isEmpty && containsCpsExpr(cd.guard))` を検査する。guard に CPS 式が含まれる場合は、該当 guard ごとに `"shift in match guard is not supported"` エラーを報告し、`(Nil, tree)` を返して変換を打ち切る。

guard に CPS が含まれない場合、selector は inline 位置として `transformInline` で変換する。各 case body は tail 位置として `transformTail` で変換し、case body を `mkBlock(bodyStmts, newBody)` に置換する。`shiftUnitR`（純粋値の lift）は CPS フェーズが担う。

**`Apply(fun, args)`**

まず `tryDesugarBooleanShortCircuit(fun, args)` を試みる。`Boolean.&&`/`Boolean.||` の呼び出しで RHS に CPS 式が含まれる場合、短絡評価を維持するために `If` に脱糖する：

- `&&`: `(qualStmts, If(newQual, mkBlock(rhsStmts, newRhs), Literal(false)))`
- `||`: `(qualStmts, If(newQual, Literal(true), mkBlock(elseStmts, newElse)))`

`None` が返った場合（通常の Apply）は以下を実行する：

- `fun` が `Select(qual, name)` の場合、qualifier は inline 位置として `transformInline` し、`cpy.Select(fun)(newQual, name)` に戻す。それ以外の `fun` は tail 位置として `transformTail` する。
- `applyClauseParamInfos(fun.tpe)` で現在 Apply clause の `paramInfos` を取得する（`PolyType` は `resType` を再帰して `MethodType` を探す）。
- 各引数 `args(i)` に対して:
  - `paramInfos.exists(ps => i < ps.size && ps(i).isInstanceOf[ExprType])` が true なら by-name 引数:
    - CPS 式が含まれる場合は `"shift inside a by-name argument is not supported"` エラーを報告し、元の引数を返す。
    - CPS 式が含まれない場合は `transformTail` し、`(Nil, mkBlock(argStmts, newArg))` を返す（by-name 境界を超えてホイストしない）。
  - それ以外は通常の inline 位置として `transformInline` する。
- 抽出文を連結し `cpy.Apply(tree)(newFun, newArgs)` に戻す。

**`TypeApply(fun, targs)`**

`fun` を tail 位置として `transformTail` し、`cpy.TypeApply(tree)(newFun, targs)` に戻す。

**`Select(qual, name)`**

qualifier を inline 位置として `transformInline` し、`cpy.Select(tree)(newQual, name)` に戻す。

**`Typed(expr, tpt)`**

`expr` を tail 位置として `transformTail` し、`cpy.Typed(tree)(newExpr, tpt)` に戻す。

**`Try(body, cases, finalizer)`**

`containsCpsExpr(tree)` が偽なら変換せず `(Nil, tree)` を返す。真なら、`finalizer` に CPS 式が含まれる場合は `"shift in finally is not supported"` エラーを出す。次に、catch clause の guard に CPS 式が含まれる場合は `"shift in match guard is not supported"` エラーを各 guard ごとに報告し、`(Nil, tree)` を返す。guard に CPS がない場合、body は tail 位置として `transformTail` し、各 case body も tail 位置として `transformTail` する。finalizer 自体は変換せずそのまま渡す。結果は `cpy.Try(tree)(mkBlock(bodyStmts, newBody), newCases, finalizer)`。

**`WhileDo(_, _)`**

`containsCpsExpr(tree)` が真ならエラーを出す。変換はせず `(Nil, tree)` を返す。

**`SeqLiteral(elems, elemtpt)`**

varargs 展開後の `SeqLiteral` ノードを処理する。各要素は inline 位置として `transformInline` し、抽出文を連結して `cpy.SeqLiteral(sl)(newElems, sl.elemtpt)` に戻す。これにより varargs 内の CPS 式が source 順で ANF 抽出される。

**その他**

変換せず `(Nil, tree)` を返す。

### inline 位置での CPS 式抽出（`transformInline`）

まず `transformTail(tree)` を適用する。その結果の `tree2` が `isCpsExpr(tree2)` なら、fresh な synthetic symbol を作り `@CpsSym` を付ける。`ValDef(sym, tree2)` を追加文として返し、式本体は `tpd.ref(sym)` に置換する。CPS 式でなければそのまま返す。

### block 内文の変換（`transformStat`）

**`ValDef`**

RHS を `transformTail` し、前置文 `stmts` の後ろに RHS 置換済み `ValDef` を置く。`ValDef` 自体は CPS 式かどうかで再抽出しない。

**その他の stat**

`transformTail` する。変換後 stat に `containsCpsExpr(newStat)` が真なら、ステートメント位置の CPS 式として synthetic `@CpsSym` `ValDef` に抽出する。偽ならそのまま stat として残す。

## アルゴリズム詳細

### CPS 式判定

`isCpsExpr(tree)` は以下の規則で即時 CPS 式を判定する。

- `Apply(_, args)` の args に `CpsTransform` 型の引数がある（`args.exists(a => isCpsTransformType(a.tpe))` が真）→ 真
- `Typed(expr, _)` → 中身 `expr` で再帰判定
- それ以外 → 偽

なお、typer による context function の自動適用により、`shift(...)` は `Apply(cfn, List(cpsTransformInstance))` 形に変換される。このとき `fun` は必ずしも `Select(_, "apply")` 形式とは限らず、判定は `Apply` 全体に対して args を見る形（`args.exists(...)`）で行う。

`containsCpsExpr(tree)` は木全体を走査して `isCpsExpr` が真のノードがあるかを調べる。`DefDef` には入らない。

### synthetic `ValDef` の生成

抽出時は `newSymbol` で現在 owner 配下に synthetic symbol を作る。

- 名前: `SelectiveANFTransform.CpsTmpName.fresh()` (`$cpsTmp` プレフィックス付き一意名)
- flags: `Flags.Synthetic`
- 型: 抽出対象 `tree2.tpe.widen` または `newStat.tpe.widen`
- `coord`: 元ツリーの span

作成した symbol には `addCpsAnnotation(sym)` を適用して `@CpsSym` を付与する。

### `mkBlock` ユーティリティ

文リストが空なら `expr` を返し、空でなければ `tpd.Block(stmts, expr)` を作る。

### 残留 CPS 式診断（`transformPackageDef`）

パッケージ全体を2段階で走査する。

**第一走査**: サポート対象の CPS `ValDef` の span を `supportedLocalValSpans` に集める。対象は以下。

- ローカル保存 CPS val: `isLocalStoredCpsVal(vd.symbol)` かつ `isCpsValType(tpe)`
- メンバー CPS val: `isStrictMemberCpsVal`、`isLazyMemberCpsVal`、`isMutableMemberCpsVal` のいずれか

**第二走査**: 残留 CPS 式と未対応パターンを診断する。構文形ごとの扱いは以下。

- `TypeTree`: 常に無視する。型注釈中の CPS 型は式として扱わない。
- `ValDef` with `@CpsSym`: `tpt` だけ走査し、RHS はスキップする。ANF が抽出した CPS 式が RHS に残ることは正常。
- サポート対象ローカル CPS `ValDef`: `validateSupportedLocalCpsVal` を実行し、型と RHS 子要素を走査する。
- サポート対象メンバー CPS `ValDef`: 走査しない。
- サポート対象 CPS assignment: `isLocalMutableCpsVal(assign.lhs.symbol)` または `isMutableMemberCpsVal(assign.lhs.symbol)` なら走査しない。
- 直接 CPS context function 保存の未対応 `ValDef`: エラーを出し RHS を走査する。
- 名前付き CPS val の未対応 `ValDef`: エラーを出し RHS を走査する。
- 未対応 inline CPS provider `DefDef`: エラーを出す。
- `DefDef`: 戻り型が CPS context function 型、または `hasCpsTransformParam` の場合は cross-method CPS 定義として扱い、RHS に対して直接保存ポリシーのみ検査する。それ以外は RHS を通常走査する。
- `Apply(fun, args)`: `fun` は通常走査する。引数については、対応する形式パラメータ型が CPS context function 型なら `traverseChildren(arg)` のみ実行する。それ以外の引数は通常走査する。形式パラメータ型は `fun.tpe.widen` が `MethodType` なら `paramInfos`、それ以外なら `AnyType` を引数数分使う。
- `Closure`: 直接フラグせず、子を走査する。
- その他: `tree.tpe` が非 null、非 error、かつ `isCpsTransformFunctionType(tpe)` であり、サポート対象ローカル val の span 内でなければ、不正な `shift` 位置としてエラーを出す。その後、子を走査する。

### ローカル lazy CPS val の追加検証（`validateSupportedLocalCpsVal`）

`isLocalLazyCpsVal(vd.symbol)` かつ `storageRhsPreludeHasImmediateCps(vd.rhs)` ならエラー。さらに `reportNestedIllegalShiftInLocalCpsVal(vd.rhs)` で、ローカル CPS val 内の非 CPS `DefDef` に入った即時 CPS 式を検出する。

## 型・シンボルの扱い

`DefDef` 変換時は `ctx.withOwner(tree.symbol)` を使い、ANF 抽出で作られる一時シンボルの owner を現在の CPS 関数に揃える。

残留 CPS 式診断では `ValDef` の型を、シンボルが存在する場合は `vd.symbol.info`、存在しない場合は `vd.tpt.tpe` から取る。CPS 値かどうかは `isCpsValType`、CPS context function 型かどうかは `isCpsTransformFunctionType` で判定する。

`Apply` の合法引数位置判定では `fun.tpe.widen` を `MethodType` として見て、形式パラメータ型が CPS context function 型なら、その引数位置の CPS 式残留を許可する。

## 不変条件・前提

- ANF 変換対象は `CpsTransform` パラメータを持つ `DefDef` に限定される。
- ネストした `DefDef` には入らない。別の CPS 関数や通常関数の本体はこの変換の対象外。ネストした reset（別 CPS `DefDef`）は独立したスコープとして扱われ、ANF の状態（抽出した val リスト等）は局所で完結する。`SelectiveCPSTransform.prepareForUnit` が CompilationUnit 単位で状態をリセットし、フェーズ跨ぎの状態持ち越しは行わない。
- tail 位置の CPS 式は抽出しない。抽出対象は inline 位置、または block の statement 位置にある CPS 式。
- 抽出した CPS 式は必ず `@CpsSym` 付き synthetic `ValDef` になる。
- ANF により抽出された CPS 式は `@CpsSym` 付き `ValDef` RHS に残るため、残留 CPS 式エラーの対象にしない。
- CPS context function 型を形式パラメータに取る呼び出しでは、その引数位置に CPS 式が現れることは合法。
- CPS 型を戻り値に持つメソッドや CPS パラメータを持つメソッドの RHS は、通常の残留 CPS チェックではなく直接保存ポリシーのみを見る。
- `TypeTree` は式使用ではないため、残留 CPS 式検査から除外する。
- `try` の `finally` に CPS 式が入ることは未対応。
- `while` 内に CPS 式が入ることは未対応。
- `Match` の case guard はこの実装では変換されない。
- `Try` に CPS 式が含まれない場合は、内部を正規化せず元ツリーを返す。
- span を使って、サポート対象ローカル CPS val 内にある CPS 型ノードを一般エラーから除外する。

## エラー診断

### `ANFBodyTransformer` のエラー

```text
shift in finally is not supported
```

```text
shift in while is not supported
```

### `SelectiveANFTransform` のエラー

```text
direct CPS context-function values cannot be stored; use def or a function returning CPS instead
```

このエラーは CPS context function 型の値をローカル val に直接格納しようとした場合に発生する（`isUnsupportedDirectCpsContextFunctionStorage` / `CpsSymbols.hasUnsupportedDirectCpsTransformParam` が検出）。

```text
local lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def
```

```text
shift cannot be used in this position (not directly inside reset)
```

このエラーは以下の2つの経路で発生する。

1. 非 CPS lambda（`!hasCpsTransformParam` な `DefDef`）の配下に shift が含まれる場合（`reportNestedIllegalShiftInLocalCpsVal` → `reportFirstImmediateCpsExpr`）。
2. サポート対象外の位置に CPS 型のノードが残留している場合（`transformPackageDef` の最終残留チェック）。

```text
named val ending in CPS function is not supported yet; use def instead
```

このエラーは trait メンバーの CPS val に対して発生する。`CpsEligibility.isMemberStoredCpsVal` は `!sym.owner.is(Flags.Trait)` を要求するため、trait メンバーは `isUnsupportedNamedCpsVal` に分類され、上記メッセージで拒否される。

**opaque type 経由の shift**: opaque type は `LocalValueRewriteOps.isContainerOfCpsAppliedType` で `!sym.is(Flags.Opaque)` により CPS コンテナとして認識されない。変換対象外となり、ANF フェーズの残留チェック（`isCpsTransformFunctionType` 判定）でエラーが発生する間接的な拒否経路となる。

`UnsupportedInlineCpsProviderMessage` の本体は `CpsSymbols.scala` に `private[plugin] val UnsupportedInlineCpsProviderMessage` として定義されている。全文は [05-shared-infrastructure.md](05-shared-infrastructure.md) のエラー診断節を参照。

## 変換例

### shift ホイストと synthetic val 束縛

**変換前**（typer 後、shift が引数位置に現れる）:

```scala
// reset { h(shift(k => k(1)), pureExpr) }
// typer は context function を自動適用済み:
// Apply(h, List(Apply(shift_cfn, List(cpsTransformInstance)), pureExpr))
```

**変換後**（ANF 変換後）:

```scala
val $cpsTmp1: ControlContext[Int, R] = shift(k => k(1))  // @CpsSym, Synthetic
h($cpsTmp1, pureExpr)
```

`isCpsExpr($cpsTmp1 の RHS)` が真なので `transformInline` が synthetic `ValDef` に抽出する。`@CpsSym` を付与された `$cpsTmp1` は後続の CPS 変換フェーズで `flatMap`/`map` チェーンに書き換えられる。

### block 内 statement 位置の CPS 式抽出

```scala
// 変換前
{
  val x = shift(k => k(1))  // ValDef: transformStat が RHS を transformTail
  val y = g(shift(k => k(2)), x)  // g の引数が inline 位置
  y
}
```

```scala
// 変換後
{
  val x: ControlContext[Int, R] = shift(k => k(1))  // @CpsSym
  val $cpsTmp1: ControlContext[Int, R] = shift(k => k(2))  // @CpsSym, hoisted
  val y = g($cpsTmp1, x)
  y
}
```

## 関連文書

- [00-overview.md](00-overview.md) — プラグイン全体の構成とフェーズ順序
- [02-stub-phase.md](02-stub-phase.md) — ANF フェーズの前段となる stub 生成フェーズ
- [04-cps-phase.md](04-cps-phase.md) — ANF 変換後の CPS 変換フェーズ
- [05-shared-infrastructure.md](05-shared-infrastructure.md) — `CPSUtils`、`isCpsTransformType` 等の共通ユーティリティ
