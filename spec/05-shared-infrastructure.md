# 05 プラグイン登録と共通基盤

## 対象ソースファイル一覧

| ファイル | パッケージ |
| --- | --- |
| `plugin/src/main/scala/continuations/plugin/SelectiveCPSPlugin.scala` | `continuations.plugin` |
| `plugin/src/main/scala/continuations/plugin/CPSUtils.scala` | `continuations.plugin` |
| `plugin/src/main/scala/continuations/plugin/shared/types/CpsSymbols.scala` | `continuations.plugin.shared.types` |
| `plugin/src/main/scala/continuations/plugin/shared/types/CpsTypeOps.scala` | `continuations.plugin.shared.types` |
| `plugin/src/main/scala/continuations/plugin/shared/types/CpsEligibility.scala` | `continuations.plugin.shared.types` |
| `plugin/src/main/scala/continuations/plugin/shared/local/LocalCpsStorageOps.scala` | `continuations.plugin.shared.local` |

## プラグイン登録とフェーズ構成（SelectiveCPSPlugin）

```scala
class SelectiveCPSPlugin extends StandardPlugin:
  val name = "continuations"
  val description = "applies selective CPS conversion (shift/reset)"
  override def initialize(options: List[String])(using Context): List[PluginPhase] =
```

`dotty.tools.dotc.plugins.StandardPlugin` を継承する Scala 3 標準プラグインクラス。

### フェーズ一覧

`initialize` が返す `List[PluginPhase]` の登録順：

1. `SelectiveCPSStubPhase`
2. `SelectiveANFTransform`
3. `SelectiveCPSTransform`

### runsAfter / runsBefore

`SelectiveCPSPlugin.scala` 自体には `runsAfter` / `runsBefore` の定義はない。各フェーズの挿入位置は各フェーズクラス側で定義される（[02-stub-phase.md](02-stub-phase.md)、[03-anf-phase.md](03-anf-phase.md)、[04-cps-phase.md](04-cps-phase.md) を参照）。

### その他の特記事項

- プラグインオプション（`options: List[String]`）は受け取るが使用しない。
- このファイル自体は型変換・シンボル解決を実装しない。実処理は各フェーズに委譲される。

## ユーティリティ合成（CPSUtils）

```scala
trait CPSUtils extends CpsSymbols with CpsTypeOps with CpsEligibility with LocalCpsStorageOps
```

4つの機能 trait を線形化順に合成するだけのファクサードトレイト。各フェーズの実装クラスは `CPSUtils` を mixin することで、以下の機能群をまとめて利用できる。

- `CpsSymbols` — CPS ランタイムシンボル解決・基本型判定
- `CpsTypeOps` — CPS 値型の再帰的検出と変換
- `CpsEligibility` — スタブ生成対象・未サポート形状の判定
- `LocalCpsStorageOps` — ローカル CPS 値の格納分類と RHS 分離

`CPSUtils` 自身は状態・メソッド・値を追加しない。

## シンボル定義（CpsSymbols）

```scala
trait CpsSymbols:
```

CPS プラグインが扱う標準シンボル、CPS マーカー、CPS 型の基本判定を提供するトレイト。`CpsTypeOps` から継承される前提で abstract な `isCpsValType` を参照する。

### ランタイムシンボルの解決

以下のシンボルはすべて `requiredClass` / `requiredPackage(...).requiredMethod(...)` で必須解決される。classpath 上に存在しない場合はコンパイルエラーになる。

| メソッド | 解決対象 |
| --- | --- |
| `cpsTransformClass` | `continuations.CpsTransform` |
| `controlContextClass` | `continuations.ControlContext` |
| `cpsSymClass` | `continuations.CpsSym` |
| `shiftUnitRMethod` | `continuations.shiftUnitR` |

### アノテーション操作

```scala
def addCpsAnnotation(sym: Symbol)(using Context): Unit =
def isCpsSymAnnotated(sym: Symbol)(using Context): Boolean =
```

`addCpsAnnotation` は `Annotation(tpd.New(cpsSymClass.typeRef, Nil))` を Symbol に追加する。`isCpsSymAnnotated` は Symbol が `CpsSym` annotation を持つかを返す。

### 基本型判定

```scala
def isCpsTransformType(tpe: Type)(using Context): Boolean =
```

`tpe.widen.dealias.typeSymbol == cpsTransformClass` で判定する。

```scala
def isCpsTransformFunctionType(tpe: Type)(using Context): Boolean =
```

`CpsTransform[R] ?=> A` 形式の context function を判定する。dealias した型の typeSymbol fullName が `scala.ContextFunction` で始まり、`argInfos` が空でなく、先頭引数が `CpsTransform` 型なら true。

```scala
def cpsFunctionR(tpe: Type)(using Context): Option[Type] =
```

CPS context function 型なら先頭引数 `CpsTransform[R]` の型引数先頭（`R`）を返す。そうでなければ `None`。

```scala
def isCpsValType(tpe: Type)(using Context): Boolean
```

abstract。実装は `CpsTypeOps` 側。

### CPS provider パラメータの形状判定

```scala
def hasCpsTransformParam(sym: Symbol)(using Context): Boolean =
```

`sym.paramSymss` のどこかに `CpsTransform[R]` 型パラメータがあるかを調べる。context function の `$anonfun` に対しても true になる。

```scala
private[plugin] def hasUnsupportedDirectCpsTransformParam(sym: Symbol)(using Context): Boolean =
```

direct `CpsTransform` parameter の許可形状を厳密に検証する。`termMethodTypes(sym.info)` で PolyType / MethodType / ExprType をたどって term-level MethodType 群を抽出し、`sym.paramSymss` と zip する。サポートされる形状は以下の条件をすべて満たす場合のみ。

- CPS clause が 1 つだけ。
- その clause がすべての term clause の最後。
- `mt.isContextualMethod` が true。
- Symbol 側 parameter が 1 個だけで、すべて `CpsTransform` 型。
- MethodType 側 parameter info が 1 個だけで、すべて `CpsTransform` 型。

上記を満たさない場合、または CPS clause が複数ある場合は unsupported として true を返す。

```scala
def cpsTransformParamR(sym: Symbol)(using Context): Option[Type] =
```

`sym.paramSymss.flatten` から最初の `CpsTransform[R]` parameter を探し、型引数先頭 `R` を返す。

```scala
def hasCpsTransformFunctionParam(sym: Symbol)(using Context): Boolean =
```

CPS context function 型の parameter を持つかを判定する。ただし非関数 `AppliedType` container は除外する。parameter type を `dealias.widen` し、`isCpsValType(p.info)` が true かつ `tp.isInstanceOf[AppliedType] && !defn.isFunctionType(tp)` でない場合に true。

```scala
def hasCpsValueContainerParam(sym: Symbol)(using Context): Boolean =
```

`List[CPS fn]` のような container 内 CPS function value 型 parameter を検出する。parameter type が `AppliedType`、かつ Scala function type ではなく、`isCpsValType(p.info)` が true なら該当。

```scala
def hasCpsValueContainerResult(sym: Symbol)(using Context): Boolean =
```

戻り値版。`sym.info.finalResultType.dealias.widen` が非関数 `AppliedType` で、元の final result type が `isCpsValType` なら true。

### CPS provider パラメータの3区分

スタブ生成対象か否かを判定する際、CPS provider としての形状は以下の3区分で扱われる。

| 区分 | 判定述語 | 意味 |
| --- | --- | --- |
| direct | `hasCpsTransformParam` | `CpsTransform[R]` 型パラメータを直接持つ（using パラメータ構文 Form 1） |
| poly / function | `hasCpsTransformFunctionParam` | CPS context function 型のパラメータを持つ。非関数 `AppliedType` は除外される |
| container | `hasCpsValueContainerParam` | `List[CPS fn]` のような非関数 `AppliedType` の container 内に CPS function 値を持つパラメータ |

`PolyType` は `isCpsValType` が `pt.resType` を再帰するため poly/function 区分に自然に含まれる。

### 型変換

```scala
def cpsTypeToCc(t: Type)(using Context): Type =
```

`CpsTransform[R] ?=> A` から `ControlContext[A, R]` を構築する。`A` は `t.dealias.argInfos.last`、`R` は `cpsFunctionR(t).get`。呼び出し側は引数が CPS context function 型であることを保証する必要がある。

### エラー診断メッセージ

```
"unsupported direct CpsTransform parameter shape"
```

```
"inline CPS providers are not supported; move the CPS provider body to a non-inline def"
```

これらは `private[plugin]` な定数として定義される。

## 型操作（CpsTypeOps）

```scala
trait CpsTypeOps extends CpsSymbols:
```

CPS 値型の再帰的検出、保存禁止の直接 CPS context function leaf 検出、CPS 値型・メソッド型の `ControlContext` 変換、`ControlContext` 型判定を実装するトレイト。選択的 CPS 変換の型レベル中核。

### isFunctionType（private helper）

`tpe.dealias.typeSymbol.fullName.toString` が `scala.Function` で始まるか、`scala.runtime.FunctionXXL` と一致すれば function type とみなす。

### hasDirectCpsContextFunctionStorageLeaf

```scala
def hasDirectCpsContextFunctionStorageLeaf(tpe: Type)(using Context): Boolean =
```

保存値として禁止する直接 CPS context function leaf を検出する。`dealias.widen` 後に以下の規則で再帰的に判定する。

- 型自体が `CpsTransform[R] ?=> A` → true。
- `RefinedType` の場合:
  - refined name が `apply` で refined info に direct CPS leaf があれば true。
  - そうでなければ parent を調べる。
- `PolyType` の場合: result type を調べる（polymorphic value だけでは delayed provider 境界とみなさない）。
- `MethodType` の場合: false。通常値引数を取る provider は遅延 provider として扱うため、method 境界より下には潜らない。
- `ExprType` の場合: result type を調べる。
- 非関数 `AppliedType` の場合: tycon symbol が class で opaque でなく、型引数のどれかに direct CPS leaf があれば true。
- その他: false。

### isCpsValType

```scala
def isCpsValType(tpe: Type)(using Context): Boolean =
```

CPS 値型を検出する。まず `isControlContextType(t)` を除外する（ControlContext 自体は CPS value として再変換しない）。残りで次のいずれかを満たせば true。

- 型自体が `CpsTransform[R] ?=> A`。
- `RefinedType` で、`apply` refined info または parent が CPS 値型。
- `PolyType` の result type が CPS 値型。
- `MethodType` の parameter info のどれか、または result type が CPS 値型。
- `ExprType` の result type が CPS 値型。
- `cpsApplyMember(t).nonEmpty`（`apply` member の型が CPS 値型）。
- function type で、型引数のどれかが CPS 値型。
- 非関数 `AppliedType` で、tycon symbol が class、opaque ではなく、型引数のどれかが CPS 値型。

**冒頭 `!isControlContextType(t)` ガードの意義**: `ControlContext` は `apply` メンバーを持つため、ガードなしでは `cpsApplyMember(t).nonEmpty` の分岐が `ControlContext` に対して true を返し、その `apply` の型に対して `isCpsValType` が再帰的に呼び出される。これを繰り返すと StackOverflow になる。先頭での除外によって無限再帰を防いでいる。

### transformCpsValueType

```scala
def transformCpsValueType(tpe: Type)(using Context): Type =
```

値型中の CPS 型を再帰的に `ControlContext` へ変換する。

- 型自体が `CpsTransform[R] ?=> A` → `ControlContext[A, R]`。
- `RefinedType` かつ refined name が `apply` で refined info が CPS 値型 → parent を維持し、refined info を `transformCpsMethodType(..., directMarkerAsExpr = false)` で変換。
- その他の `RefinedType` → parent を `transformCpsValueType` で変換し、refined info は維持。
- `PolyType` → param names / infos を維持し、result type を substitution 後に変換。
- `MethodType` → parameter info のうち CPS 値型だけ `transformCpsValueType` し、result type も substitution 後に変換。
- `ExprType` → result type を変換。
- function type で型引数に CPS 値型があれば、`AppliedType` の args のうち CPS 該当部分を変換。
- 非関数 `AppliedType` で class かつ non-opaque、型引数に CPS 値型があれば該当 args を変換。
- それ以外 → 元の `tpe` を返す。

### transformCpsMethodType

```scala
def transformCpsMethodType(tpe: Type)(using Context): Type =
```

public wrapper。`directMarkerAsExpr = true` で private 実装へ渡す。

private `transformCpsMethodType(tpe, directMarkerAsExpr)` の変換規則：

- `PolyType`: param names / infos を維持し、result type を substitution 後に再帰変換。
- `MethodType`:
  - `CpsTransform` parameter を除去した `keptParams` を作る。
  - 最初の direct `CpsTransform[R]` parameter から `directCpsR` を取り出す。
  - result type を substitution し、`directMarkerAsExpr = false` で再帰変換。
  - direct marker があり、変換済み result が `ControlContext` ではなく、元 result type も CPS 値型でない場合、`ControlContext[transformedResult, R]` で包む。
  - parameter が非空で、すべて direct marker として除去される場合: MethodType を消して result だけ返す。このとき direct marker があり、外側呼び出しで `directMarkerAsExpr` が true なら `ExprType(result)` にする。
  - 残す parameter がある場合: 残り parameter のうち CPS 値型を `transformCpsValueType` し、result も変換した `MethodType` を作る。
- `ExprType`: result type を `directMarkerAsExpr = false` で変換し、`ExprType` に戻す。
- CPS 値型 → `transformCpsValueType`。
- CPS context function 型 → `cpsTypeToCc`。
- その他 → 元型。

### isControlContextType

```scala
def isControlContextType(tpe: Type)(using Context): Boolean =
```

`tpe.widen.dealias.typeSymbol == controlContextClass` で判定する。

## CPS適格性判定（CpsEligibility）

```scala
trait CpsEligibility:
  self: CpsSymbols & CpsTypeOps & LocalCpsStorageOps =>
```

CPS 変換スタブ生成対象、未サポート形状、ローカル・メンバー CPS 値の適格性を判定するトレイト。型判定の実態は `CpsSymbols` / `CpsTypeOps` / `LocalCpsStorageOps` に委譲し、「どの Symbol を変換・拒否・診断対象にするか」の入口になる。

### needsTransformedStub

```scala
def needsTransformedStub(sym: Symbol)(using Context): Boolean =
```

メソッドに対する `$transformed` スタブ生成要否を判定する。`Flags.Method` を持ち、以下のすべてを満たさないこと（除外条件）：

- アクセサ
- inline
- constructor
- `$anonfun`
- 名前が既に `$transformed` で終わる

上記除外に該当しないうえで、次のいずれかを満たすと対象：

- `CpsTransform[R]` 型パラメータを持つ（`hasCpsTransformParam`）。
- CPS context function 型パラメータを持つ（`hasCpsTransformFunctionParam`）。
- 戻り値が CPS 値型で、かつ container 内 CPS 戻り値ではない（`isCpsValType` かつ `!hasCpsValueContainerResult`）。
- 戻り値が container 内 CPS function value 型（`hasCpsValueContainerResult`）。
- パラメータが container 内 CPS function value 型（`hasCpsValueContainerParam`）。

**owner ガードについて**: `needsTransformedStub` 自体は owner ガードを持たない。function param / container param ともに owner による絞り込みは行われない。class member への対応可否による owner ガードは `isMemberStoredCpsVal` / `needsTransformedMemberValStub` 側で行われる。

### isUnsupportedInlineCpsProvider

```scala
def isUnsupportedInlineCpsProvider(sym: Symbol)(using Context): Boolean =
```

inline メソッド版の同等判定。constructor と既存 `$transformed` は除外するが、inline で CPS provider 条件を満たすものを未サポートとして検出する。

### isTransformedStub

```scala
def isTransformedStub(sym: Symbol)(using Context): Boolean =
```

名前末尾が `$transformed` かだけを見る。

### メンバー CPS 値の分類

```scala
def isMemberStoredCpsVal(sym: Symbol)(using Context): Boolean =
```

class member として保存される CPS 値を検出する。条件は以下をすべて満たすこと：

- Symbol が存在する。
- method / param / param accessor ではない。
- owner が class、または constructor でその owner が class。
- owner が trait ではない。
- owner が package ではない。
- 名前が `$transformed` で終わらない。
- deferred ではない。
- 直接の CPS context function storage leaf ではない（`!hasDirectCpsContextFunctionStorageLeaf(sym.info)`）。
- `isCpsValType(sym.info)` が true。

```scala
def isStrictMemberCpsVal(sym: Symbol)(using Context): Boolean =
def isLazyMemberCpsVal(sym: Symbol)(using Context): Boolean =
def isMutableMemberCpsVal(sym: Symbol)(using Context): Boolean =
```

それぞれ member stored CPS val かつ lazy でも mutable でもない / lazy / mutable を分類する。

```scala
def needsTransformedMemberValStub(sym: Symbol)(using Context): Boolean =
```

strict / lazy / mutable のいずれかの member CPS val なら true。実質的に `isMemberStoredCpsVal` の分類結果をまとめる。

### 未サポート形状の判定

```scala
def isUnsupportedDirectCpsContextFunctionStorage(sym: Symbol)(using Context): Boolean =
```

直接 CPS context function を保存する named storage を拒否対象にする。method / param / `$transformed` / deferred / package owner を除外し、`hasDirectCpsContextFunctionStorageLeaf(sym.info)` が true なら対象。

```scala
def isUnsupportedNamedCpsVal(sym: Symbol)(using Context): Boolean =
```

named val の末尾 CPS 値のうち、現在サポートされる local/member storage や direct context-function storage 診断に分類されないものを未サポートにする。method ではなく、direct CPS context function storage ではなく、local stored CPS val でも member strict/lazy/mutable CPS val でもなく、`$transformed` でもなく、`isCpsValType(sym.info)` が true のもの。

## ローカルCPS値の格納規則（LocalCpsStorageOps）

```scala
trait LocalCpsStorageOps:
  self: CpsSymbols & CpsTypeOps =>
```

ローカル `val` / `lazy val` / `var` に保存される CPS 値の分類、保存 RHS から即時評価 prelude と保存される value shell の分離、prelude 内で即時 CPS consumption が起きるかの検出を提供するトレイト。

### ローカル CPS 値の分類

```scala
def isLocalStoredCpsVal(sym: Symbol)(using Context): Boolean =
def isLocalCpsVal(sym: Symbol)(using Context): Boolean =
```

`isLocalCpsVal` は `isLocalStoredCpsVal` の別名。method / block 直下の local storage で末尾が CPS 値へ到達する Symbol を検出する。条件は以下をすべて満たすこと：

- Symbol が存在する。
- method ではない。
- param ではない。
- 名前が `$transformed` で終わらない。
- owner が class ではない。
- owner が package ではない。
- `hasDirectCpsContextFunctionStorageLeaf(sym.info)` が false。
- `isCpsValType(sym.info)` が true。

```scala
def isLocalMutableCpsVal(sym: Symbol)(using Context): Boolean =
def isLocalLazyCpsVal(sym: Symbol)(using Context): Boolean =
def isStrictLocalCpsVal(sym: Symbol)(using Context): Boolean =
```

それぞれ local stored CPS val かつ mutable / lazy / lazy でも mutable でもないものを分類する。

### RHS の分離（splitStorageRhsPrelude）

```scala
def splitStorageRhsPrelude(rhs: Tree)(using Context): (List[Tree], Tree) = rhs match
```

ANF 後のローカル CPS storage RHS を、保存される CPS function value を作る前に即時評価される prelude と、保存される value shell に分離する。

- `Typed(expr, tpt)`: 内側 `expr` を分離し、shell 側を同じ `Typed` で包み直す。
- `Block(stats, closure: Closure)`:
  - closure の method symbol を `closure.meth.symbol` として取る。
  - stats 内の `DefDef` のうち symbol が closure method と一致する index を探す。
  - 見つかれば、その DefDef より前を prelude、DefDef 以降を shell stats とし、shell は `Block(shellStats, closure)`。
  - 見つからなければ stats 全体を prelude、closure を shell にする。
- `Block(stats, expr)`:
  - block stats はすべて prelude。
  - expr を再帰的に分離し、nested prelude も prelude に連結。
  - shell は `Block(Nil, nestedExpr)`。
- その他: prelude なし、rhs 自体が shell。

### 即時 CPS consumption の検出

```scala
def hasImmediateCpsConsumption(tree: Tree)(using Context): Boolean =
```

tree 内に即時 CPS consumption があるかを `TreeTraverser` で走査する。`found` が立ったら以後探索しない。`TypeTree`、`DefDef`、`Closure` には潜らない。その他の tree について `isImmediateCpsExpr(t)` が true なら検出、そうでなければ children を走査する。

```scala
def storageRhsPreludeHasImmediateCps(rhs: Tree)(using Context): Boolean =
```

`splitStorageRhsPrelude(rhs)._1` の prelude 各 tree に `hasImmediateCpsConsumption` を適用し、どれか true なら true。

`isImmediateCpsExpr`（private helper）: `Apply(_, args)` の args に `isCpsTransformType(a.tpe)` を満たすものがあれば即時 CPS expression とみなす。`Typed(expr, _)` は内側へ潜る。その他は false。

## 判定述語対応表

型の形状と各述語の true/false の対応。`CpsSymbols` / `CpsTypeOps` / `CpsEligibility` に分散する述語の横断的な整理。

| 型の形状 | `isControlContextType` | `isCpsValType` | `hasCpsTransformParam`（sym） | `hasCpsTransformFunctionParam`（sym） | `hasCpsValueContainerParam`（sym） | `needsTransformedStub`（sym） |
|---------|----------------------|----------------|------------------------------|---------------------------------------|-----------------------------------|-----------------------------|
| `ControlContext[A, R]` | true | **false**（先頭ガード） | — | — | — | — |
| `CpsTransform[R] ?=> A`（param） | false | true | **true** | true | false | true |
| `A => (CpsTransform[R] ?=> B)`（param） | false | true | false | **true** | false | true |
| `List[CpsTransform[R] ?=> A]`（param） | false | true | false | **false**（非関数 AppliedType を除外） | **true** | true |
| `PolyType` の resType が CPS | false | true | false | true | — | true |
| 通常型 | false | false | false | false | false | false |

`hasCpsTransformParam`・`hasCpsTransformFunctionParam`・`hasCpsValueContainerParam`・`hasCpsValueContainerResult` は `CpsSymbols` の述語で Symbol 全体を見る。`isCpsValType`・`isControlContextType` は `CpsTypeOps` の述語で Type を見る。`needsTransformedStub`・`isMemberStoredCpsVal` は `CpsEligibility` の述語で Symbol 全体を見る。

## 不変条件・前提

- `continuations.CpsTransform`、`continuations.ControlContext`、`continuations.CpsSym`、`continuations.shift`、`continuations.shift$transformed`、`continuations.shiftUnitR`、`continuations.reset`、`continuations.reset$transformed` は classpath 上に存在する必要がある。
- `$transformed` suffix は生成済みスタブの識別子として扱う。重複生成を防ぐための除外条件に使われる。
- inline CPS provider は未サポート。
- direct `CpsTransform` parameter は「最後の contextual term clause に単一 marker parameter」としてだけサポートされる。
- `CpsTransform` marker parameter は変換後メソッド型から除去される。
- direct marker parameter だけの clause は、必要なら nullary expression-like な `ExprType` に畳まれる。
- 既に `ControlContext` の型は再度 CPS 値として扱わない。
- trait member と package owner は member stored CPS val から除外される。
- direct CPS context function storage は通常の stored CPS val とは別の未サポート分類として扱う。
- member stored CPS val は strict / lazy / mutable のいずれかに分類される。
- local CPS storage は class owner / package owner ではない。
- closure RHS では、closure method に対応する `DefDef` 以降が保存される value shell で、それより前が即時評価 prelude。
- `DefDef` と `Closure` の内部は即時 consumption 探索対象から除外される（保存される関数本体内の CPS 使用を prelude の即時実行と混同しないため）。
- `Apply` に `CpsTransform` 型引数が渡されている形を即時 CPS consumption とみなす。
- opaque class の container 型引数には潜らない。
- `cpsTypeToCc` は `cpsFunctionR(t).get` を使うため、呼び出し側は CPS context function 型であることを保証する必要がある。

## エラー診断（全文引用）

`CpsSymbols` 内で `private[plugin]` 定数として定義：

```
"unsupported direct CpsTransform parameter shape"
```

定数名: `UnsupportedDirectCpsTransformParamMessage`。`hasUnsupportedDirectCpsTransformParam` が不正形状を検出したとき、`collectTransformedPlans`（LocalTransformRegistryOps）および `SelectiveCPSStubPhase.transformTemplate` が `report.error(UnsupportedDirectCpsTransformParamMessage, ...)` を発行する。Form 1（using パラメータ構文）のうち、末尾 contextual clause に単一 `CpsTransform` マーカーを持たない形状が対象。

```
"inline CPS providers are not supported; move the CPS provider body to a non-inline def"
```

`LocalCpsStorageOps` 内で `final protected val` として定義：

```
"local CPS function value cannot be used as an ordinary runtime value; invoke it under reset or pass it to a CPS-transformed consumer"
```

`CpsTypeOps`・`CpsEligibility` にはエラー診断メッセージ定数はない。

## 関連文書

- [00-overview.md](00-overview.md) — プラグイン全体の概要
- [02-stub-phase.md](02-stub-phase.md) — `SelectiveCPSStubPhase` の詳細（`runsAfter`/`runsBefore` を含む）
- [03-anf-phase.md](03-anf-phase.md) — `SelectiveANFTransform` の詳細
- [04-cps-phase.md](04-cps-phase.md) — `SelectiveCPSTransform` の詳細
