# 02 SelectiveCPSStubPhase 実装仕様

## 対象ソースファイル

- `plugin/src/main/scala/continuations/plugin/phase/stub/SelectiveCPSStubPhase.scala`
- `plugin/src/main/scala/continuations/plugin/CPSUtils.scala`（`CPSUtils` trait の定義のみ。型・判定ロジックは sub-trait に委譲）

## 概要

`SelectiveCPSStubPhase` は選択的CPS変換パイプラインの第1フェーズ。元の `def` およびメンバ `val` に対応する `$transformed` スタブを同一テンプレート内へ生成する。本体のCPS変換は行わない。後続フェーズ（`SelectiveANFTransform`, `SelectiveCPSTransform`）が参照できる ABI とシンボルを、pickling より前に確定させることが目的。

### フェーズ順序

```
posttyper → [selectivecpsstub] → pickler → ... → [selectiveanf] → [selectivecps]
```

```scala
override val runsAfter: Set[String] = Set("posttyper")
override val runsBefore: Set[String] = Set("pickler")
```

typed 後・pickling 前に走るため、`$transformed` シンボルは pickle 対象に含まれる。

プラグイン全体のフェーズ登録順（`SelectiveCPSPlugin.initialize` より）:

```scala
List(new SelectiveCPSStubPhase(), new SelectiveANFTransform(), new SelectiveCPSTransform())
```

### クラス宣言

```scala
class SelectiveCPSStubPhase extends PluginPhase with CPSUtils:
  val phaseName = SelectiveCPSStubPhase.name

object SelectiveCPSStubPhase:
  val name = "selectivecpsstub"
```

## 生成されるスタブ/シンボルのシグネチャ規則・ABI

### 名前規則

元シンボル名に文字列 `"$transformed"` を連結した `TermName` をスタブ名とする。

```scala
val stubName = (origSym.name.toString + "$transformed").toTermName
```

### `DefDef` スタブ（メソッド用）

| 項目 | 値 |
|---|---|
| 名前 | `origSym.name + "$transformed"` |
| 型 | `transformCpsMethodType(origSym.info)` |
| owner | `origSym.owner` |
| flags（deferred の場合） | `origSym.flags &~ GivenOrImplicit \| Synthetic`（`Deferred` 維持） |
| flags（non-deferred の場合） | `origSym.flags &~ (Deferred \| GivenOrImplicit) \| Synthetic` |
| 本体（deferred） | `EmptyTree` |
| 本体（non-deferred） | `ref(defn.Predef_undefined)` |

```scala
val stubFlags =
  if origSym.is(Flags.Deferred) then origSym.flags &~ Flags.GivenOrImplicit | Flags.Synthetic
  else origSym.flags &~ (Flags.Deferred | Flags.GivenOrImplicit) | Flags.Synthetic
```

シンボル生成:

```scala
newSymbol(origSym.owner, stubName, stubFlags, stubType, coord = origSym.coord).asTerm.entered
```

### メンバ `ValDef` スタブ（フィールド用）

| 項目 | 値 |
|---|---|
| 名前 | `origSym.name + "$transformed"` |
| 型 | `transformCpsValueType(origSym.info)` |
| owner | `transformedOwner(origSym)` |
| flags | `origSym.flags &~ GivenOrImplicit \| Synthetic` |
| 本体 | `ref(defn.Predef_undefined)` |

```scala
val stubFlags = origSym.flags &~ Flags.GivenOrImplicit | Flags.Synthetic
val stub = tpd.ValDef(stubSym, ref(defn.Predef_undefined))
```

### mutable メンバ CPS val の setter スタブ

`isMutableMemberCpsVal(origSym)` が true の場合、追加で setter を生成する。

| 項目 | 値 |
|---|---|
| 名前 | `stubName.setterName` |
| 型 | `MethodType(List("x".toTermName))(_ => List(stubType), _ => defn.UnitType)` |
| flags | `(stubFlags &~ Mutable) \| Method \| Accessor` |
| 本体 | `tpd.Literal(Constant(()))` |

```scala
val setterType = MethodType(List("x".toTermName))(_ => List(stubType), _ => defn.UnitType)
val setterFlags = (stubFlags &~ Flags.Mutable) | Flags.Method | Flags.Accessor
```

setter 本体は実行用ではない。CPS phase は代入を direct field `Assign` に書き換えるため、setter は ABI・accessor 形状を pickling と後続コンパイラフェーズ向けに保つためだけに存在する。

### member val ABI の設計根拠

member val の `$transformed` は "public synthetic def + private field" のアクセサ方式ではなく、**val/var フィールドそのものとして** 生成する。これにより評価セマンティクスを単純に保てる。

- **immutable val**: stub フェーズでは `val foo$transformed: T = ???`（`Predef.undefined`）として配置。CPS フェーズが `tpd.ValDef(tSym.asTerm, transformedRhs)` で実際の変換済み値に差し替える。
- **mutable var**: stub フェーズで `var xs$transformed: T = ???` を生成し、加えて setter `def xs$transformed_=(x: T): Unit = ()` を `Flags.Accessor` 付きで生成する。CPS フェーズは `tpd.Assign` で `xs$transformed` フィールドに直接代入する。元の `xs` は `Literal(Constant(null)).cast(sym.info)` で null 化されるため、**元 `xs` は null のまま**・変換済み値は `xs$transformed` のみに存在するという分離初期化になる。

"public synthetic def + private field" 方式（標準アクセサ）と比較した場合、getter/setter の二重生成が不要で、後続フェーズの `transformValDef`/`transformDefDef` が field と accessor を個別に処理する複雑さを避けられる。

## アルゴリズム詳細

### エントリポイント

```scala
override def transformTemplate(tree: Template)(using ctx: Context): Tree
```

処理単位は `Template`。2段階で処理する。

### 第1段階: メンバ `ValDef` スタブの挿入

`tree.body.flatMap` でテンプレート本文を走査し、`needsTransformedMemberValStub(vd.symbol)` に合致する `ValDef` を処理する。

- 元シンボル: `vd.symbol.asTerm`
- 同一テンプレート内でスタブ生成済みなら重複生成しない
- 既存 `$transformed` シンボルがある場合も生成しない
- 生成したスタブは元 `ValDef` の直後に挿入する（`flatMap` による展開）
- `isMutableMemberCpsVal(origSym)` が true なら setter も同位置に追加する

### 第2段階: `DefDef` スタブの収集

`tree.body.flatMap` で `needsTransformedStub(dd.symbol)` に合致する `DefDef` を走査する。

- `hasUnsupportedDirectCpsTransformParam(dd.symbol)` が true ならエラーを報告しスタブを生成しない
- 既存 `$transformed` がなければ `mkTransformedStub(dd)` を呼んでスタブを生成する
- 収集した `stubs` をテンプレート本文末尾に追加する

### テンプレート返却規則

変更がなければ元 tree をそのまま返す（コピー不要最適化）。

```scala
if !hasInlineStubs && stubs.isEmpty then tree
else cpy.Template(tree)(body = bodyWithMemberValStubs ++ stubs)
```

### `mkTransformedStub` の詳細

```scala
private[plugin] def mkTransformedStub(dd: DefDef)(using ctx: Context): DefDef
```

1. `dd.symbol.asTerm` から `origSym` を取得
2. `stubName` = `(origSym.name.toString + "$transformed").toTermName`
3. `stubType` = `transformCpsMethodType(origSym.info)`
4. `stubFlags` を `origSym.is(Flags.Deferred)` で分岐して計算（上記 ABI 規則参照）
5. `newSymbol(origSym.owner, stubName, stubFlags, stubType, coord = origSym.coord).asTerm.entered` でシンボル生成・scope enter
6. `deferred` なら `tpd.DefDef(stubSym, _ => EmptyTree)`、`non-deferred` なら `tpd.DefDef(stubSym, _ => ref(defn.Predef_undefined))`

### 既存 `$transformed` の探索

```scala
private[plugin] def hasExistingTransformed(origSym: TermSymbol)(using Context): Option[Symbol]
private[plugin] def validateExistingTransformed(origSym: TermSymbol)(using Context): Boolean
```

探索は `transformedOwner(origSym).info.decl(stubName).symbol` で行う。

## 型・シンボルの扱い

型変換はこのファイル内では実装されず `CPSUtils` 由来メソッドに委譲する。

| メソッド | 用途 |
|---|---|
| `transformCpsValueType(origSym.info)` | メンバ val の変換後型 |
| `transformCpsMethodType(origSym.info)` | メソッドの変換後型 |

シンボル生成は常に `newSymbol(...).asTerm.entered` で行い、生成した `$transformed` sibling を owner の scope に enter する。

### `transformedOwner` の定義

メンバ `ValDef` のスタブ owner は `transformedOwner(origSym)` で決まる。

```scala
private def transformedOwner(origSym: TermSymbol)(using Context): Symbol =
  if origSym.owner.isConstructor && origSym.owner.owner.exists then origSym.owner.owner
  else origSym.owner
```

コンストラクタが owner になっているフィールドでも、変換後 sibling を enclosing class 側に enter するための防御的処理。`DefDef` スタブの owner は常に `origSym.owner`（この分岐なし）。

**通常経路での動作**: `SelectiveCPSStubPhase` が走る時点（posttyper 後・Constructors フェーズ前）では、メンバ `val` のシンボルは class 所有のまま。コンストラクタが owner に移るのは後続の Constructors フェーズであるため、stub/ANF 経路では `origSym.owner` が直接 class を指す。`isConstructor` 分岐は正常経路では実行されない防御コード。`lookupTransformedSym`（`CallsiteRewriteOps`）内にも同趣旨のコメントが存在する。

### CPS フェーズでの元 val 本体の null 化

`SelectiveCPSTransform.transformTemplate` は、変換済み CPS val に対して元 val の RHS を null で上書きする。実装パターン:

```scala
val nullRhs = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(null)).cast(vd.symbol.info)
```

`Any_asInstanceOf` を `Select` に渡す方式は使わない。`Literal(Constant(null)).cast(sym.info)` を直接使う。strict/mutable/lazy member CPS val の3ケースすべてでこのパターンが統一されている。

### 変換後テンプレート構造

stub フェーズ後のテンプレート本文は以下の順序になる。

```
[元メンバーが並ぶ位置]
  val foo: CpsType = <元の本体>          // member CPS val（元）
  val foo$transformed: T = ???           // foo の $transformed val スタブ ← 元 val の直後に挿入
  var bar: CpsType = <元の本体>          // mutable member CPS val（元）
  var bar$transformed: T = ???           // bar の $transformed val スタブ ← 元 val の直後に挿入
  def bar$transformed_=(x: T): Unit = () // setter スタブ                  ← 同位置に続けて挿入
  <その他の非 CPS メンバー>
  def compute$transformed(...): T = ???  // DefDef スタブ ← テンプレート末尾に追加
  ...
```

`flatMap` で `List(vd, stub)` を返すため、`val` スタブは元 `ValDef` の**直後**に挿入される（`bodyWithMemberValStubs` の中）。`def` スタブのみ末尾に追加（`bodyWithMemberValStubs ++ stubs`）。

## 不変条件・前提

- `$transformed` 名は必ず元 term 名文字列 + `"$transformed"`
- 手書き（非 `Synthetic`）の `$transformed` 定義は許可しない
- 既存 `Synthetic` の `$transformed` があれば再生成しない
- メンバ val スタブは元 `ValDef` の直後に挿入する
- method スタブはテンプレート本文末尾に追加する
- stub 本体は実行用ではなく、型・シンボル・ABI を後続フェーズへ渡すためのもの
- `GivenOrImplicit` は transformed stub から必ず除去する
- mutable transformed member val では setter symbol も ABI として存在する
- `pickler` より前に生成されることが前提（pickle 対象に含める意図）
- typer 後に `entered` で追加した `$transformed` シンボルは override chain に組み込まれない（`accessor` フラグを持つ setter は除外されるが、val/def スタブは override chain への登録が行われない）。ただし concrete/abstract trait・`this`/`super`・別コンパイル TASTy 経由を含む継承パターンは `InheritanceCpsMethodSuite` でカバーされている

## エラー診断

### `manual $transformed definition is not supported`

```text
manual $transformed definition is not supported
```

発生条件:

- `hasExistingTransformed(origSym)` が `Some(sym)` を返す
- かつ `!sym.is(Flags.Synthetic)`

報告位置: `sym.srcPos`

### `UnsupportedDirectCpsTransformParamMessage`

```scala
report.error(UnsupportedDirectCpsTransformParamMessage, dd.srcPos)
```

発生条件: `hasUnsupportedDirectCpsTransformParam(dd.symbol)` が true の場合。

報告位置: `dd.srcPos`

メッセージ本体は `CpsSymbols.scala` に `private[plugin] val UnsupportedDirectCpsTransformParamMessage` として定義されている。全文は [05-shared-infrastructure.md](05-shared-infrastructure.md) のエラー診断節を参照。

## 関連文書

- [00-overview.md](00-overview.md) — プラグイン全体アーキテクチャ
- [01-public-api.md](01-public-api.md) — `shift` / `reset` 公開 API
- [05-shared-infrastructure.md](05-shared-infrastructure.md) — `CPSUtils` / `CpsTypeOps` / `CpsEligibility` 等の共有インフラ
- [03-anf-phase.md](03-anf-phase.md) — 第2フェーズ SelectiveANFTransform
- [04-cps-phase.md](04-cps-phase.md) — 第3フェーズ SelectiveCPSTransform
