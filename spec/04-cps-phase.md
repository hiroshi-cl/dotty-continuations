# 04 CPS フェーズ実装仕様

## 対象ソースファイル一覧

```
plugin/src/main/scala/continuations/plugin/phase/cps/SelectiveCPSTransform.scala
plugin/src/main/scala/continuations/plugin/phase/cps/TransformedImplBuilder.scala
plugin/src/main/scala/continuations/plugin/phase/cps/LocalTransformRegistry.scala
plugin/src/main/scala/continuations/plugin/phase/cps/CpsBodyTransform.scala
plugin/src/main/scala/continuations/plugin/phase/cps/CallsiteRewrite.scala
plugin/src/main/scala/continuations/plugin/phase/cps/LocalValueRewrite.scala
```

---

## 概要

CPS フェーズは `SelectiveANFTransform` 後、`elimByName` 前に走る選択的 CPS 変換の本体フェーズである。ANF フェーズによってすでに A-normal form に変換された構文木を入力とし、`shift`/`reset` の呼び出しを含む関数本体を `ControlContext[A, R]` モナド連鎖へ変換する。

フェーズは 1 つの `PluginPhase` 実装 `SelectiveCPSTransform` と 5 つの trait mixin で構成される。

| コンポーネント | 責務の概要 |
|---|---|
| `SelectiveCPSTransform` | フェーズ本体。`PluginPhase` フックを実装し、全体の駆動と書き換えの起点を担う |
| `LocalTransformRegistry` (`LocalTransformRegistryOps`) | 局所変換計画（def・val・poly apply）の登録・検索・ライフサイクル管理 |
| `TransformedImplBuilder` (`TransformedImplBuilderOps`) | 元 `DefDef` と transformed symbol から変換後 `DefDef` 実装を構築する |
| `CpsBodyTransform` (`CpsBodyTransformOps`) | 関数本体の CPS 変換コアロジック。モナド連鎖の構築を担う |
| `CallsiteRewrite` (`CallsiteRewriteOps`) | 呼び出し側のローカルメソッド・ローカル val・CPS 引数を型情報のみで変換済みシンボルへ差し替える |
| `LocalValueRewrite` (`LocalValueRewriteOps`) | ローカル val の RHS を変換後の型・シンボル参照へ正規化する |

クラス宣言:

```scala
class SelectiveCPSTransform
    extends PluginPhase
    with CPSUtils
    with LocalTransformRegistryOps
    with LocalValueRewriteOps
    with TransformedImplBuilderOps
    with CpsBodyTransformOps
    with CallsiteRewriteOps:
  val phaseName = SelectiveCPSTransform.name
  override val runsAfter: Set[String] = Set(SelectiveANFTransform.name)
  override val runsBefore: Set[String] = Set("elimByName")
  override def relaxedTypingInGroup: Boolean = true
```

`relaxedTypingInGroup = true` により、このフェーズグループ内では型付け制約を緩め、元型と変換後型が一時的に混在する構文木を扱う。

コンパニオン:

```scala
object SelectiveCPSTransform:
  val name = "selectivecps"
  val StorageTmpName = new UniqueNameKind("$cpsStorageTmp")
```

---

## フェーズ駆動と変換実装メソッド生成

### SelectiveCPSTransform のフェーズフック

#### `prepareForUnit`

ユニット処理の前に以下を行う。

1. `clearLocalTransformState()` を呼び、前ユニットの registry 状態をすべて消去する。
2. `collectTransformedPlans(tree)` を呼び、このユニット内の変換対象 `DefDef` / `ValDef` の計画を事前収集する。

#### `transformDefDef`

- synthetic な transformed stub かつ concrete な `DefDef` なら、局所計画 `lookupTransformedLocalPlan` を優先し、なければ `findOriginalDefDef` で元定義を探す。
- 元定義が見つかれば `mkTransformedImpl(orig, transformedSym)` で本体を生成する。
- 元の関数が transformed stub を必要とし、deferred でなければ `replaceOriginalBody(tree)` で元本体を `Predef.undefined` 相当に置換する。
- RHS の末尾が raw local CPS value のまま残っていれば `reportLocalCpsRuntimeUse` を出す。

#### `transformStats`

- まず `localDefHelpers` として、局所 transformed stub が必要な `DefDef` について `registerLocalTransform` 済みの plan から transformed 実装を生成し、stats 先頭側へ追加する。
- 次に `rewrittenTrees` を作る。
  - local CPS `ValDef` は `emitLocalCpsValPair` で元値ではなく transformed 値を放出する。
  - mutable local CPS val の storage RHS prelude に即時消費 CPS がある場合は、一時 val 経由 `emitMutableLocalCpsValThroughTmp` にする。
  - lazy local CPS val で同条件ならエラーを出しつつ `emitLocalCpsValPair` する。
  - direct CPS transform param を持つ局所 transformed stub は、生成済み helper と重複しないよう元 stat 側を `Nil` にする。
  - raw CPS value tail は診断して元 tree を残す。

#### `transformTemplate`

- strict / mutable member CPS val は、元フィールドを `null` RHS の stub に置換し、transformed symbol 側の `ValDef` を追加する。
- lazy member CPS val も同様だが、initializer prelude に即時 CPS 消費がないこと、stripped RHS が prelude 定義 symbol を capture しないことを診断する。
- synthetic transformed `ValDef` stub は削除する。
- concrete synthetic transformed `DefDef` stub は、元 `DefDef` があれば `mkTransformedImpl` で実装に置換する。

#### `transformApply`

- `ControlContext.apply(cpsTransform)` 形は `qual` 自体へ畳む。
- CPS transform function を CPS transform 値に apply している形は `transformCpsExpr(qual, Map.empty)` に変換する。
- 引数に CPS val がなければ何もしない。
- 呼び出し結果が CPS val 型なら何もしない。
- 呼び先関数に transformed symbol があれば、CPS val 引数だけ `transformCpsCallSiteArg` し、関数部を `rewriteFunToTransformed` で置換して `Apply` を作り直す。
- transformed symbol がないのに raw local CPS value 引数があれば実行時使用として診断する。

#### `transformAssign`

- local mutable CPS val への代入は、元 val plan から transformed symbol への代入に変える。
- RHS prelude に即時 CPS 消費があれば `mkStorageTmpVal` で一時 val を作り、その transformed ペアを emit したあと transformed var へ代入する。
- そうでなければ `splitStorageRhsPrelude` で prelude と本体を分け、prelude 中の local CPS val も `emitStoragePrefix` で transformed 化してから代入する。
- member mutable CPS val への代入では、receiver が `this` または stable ident なら直接 transformed member を選択する。
- receiver が一般式なら synthetic `$cpsStorageTmp` に hoist し、評価回数を 1 回に固定してから transformed member に代入する。

#### `transformPackageDef`

最終検査パスとして package 全体を traverse する。

- CPS transform function result または transformed stub が必要な `DefDef` の RHS は検査しない。
- local CPS val、対応済み member CPS val はスキップする。
- 残存した対応済み member CPS val 参照は `"CPS value not transformed"` を報告する。
- CPS transform function 型の式が残っていれば `"CPS expression not transformed"` を報告する。

---

### LocalTransformRegistry

#### レジストリのデータ構造

```scala
protected val localPlansByOriginal: mutable.HashMap[Symbol, LocalTransformPlan]
protected val localPlansByTransformed: mutable.HashMap[Symbol, LocalTransformPlan]
protected val originalDefsByTransformed: mutable.HashMap[Symbol, DefDef]
protected val localValPlansByOriginal: mutable.HashMap[Symbol, LocalValTransformPlan]
protected val localValPlansByTransformed: mutable.HashMap[Symbol, LocalValTransformPlan]
protected val localValDefsBySymbol: mutable.HashMap[Symbol, ValDef]
protected val polyApplyPlansByClass: mutable.HashMap[Symbol, (DefDef, TermSymbol)]
```

主要データ型:

```scala
protected case class LocalTransformPlan(originalDef: DefDef, transformedSym: TermSymbol)
protected case class LocalValTransformPlan(
    originalVal: ValDef,
    transformedSym: TermSymbol,
    polyApplyPlan: Option[(DefDef, TermSymbol)]
  )
protected case class ParamRemap(
    substFrom: List[Symbol],
    substTo: List[Symbol],
    cpsMapping: Map[Symbol, Symbol],
    containerCpsMapping: Map[Symbol, Symbol] = Map.empty
  ):
  def effectiveMapping: Map[Symbol, Symbol] = ...
```

`ParamRemap.effectiveMapping` は `cpsMapping`、その値自身への identity mapping、`containerCpsMapping`、その値自身への identity mapping の和。

#### レジストリのライフサイクル

- `prepareForUnit` から `clearLocalTransformState()` が呼ばれ、すべての mutable map を unit ごとに clear する。
- 同じく `prepareForUnit` から `collectTransformedPlans(tree)` が呼ばれる。
- `transformStats`、`transformDefDef`、`transformTemplate`、`transformAssign` がこの registry を lookup して変換を進める。
- registry は phase instance 内 mutable state だが、ユニット境界で必ず破棄される設計。

#### `collectTransformedPlans`

- `DefDef` が `needsTransformedStub` かつ transformed stub 自体でなければ対象。
- unsupported direct CPS transform param があれば `UnsupportedDirectCpsTransformParamMessage` を報告する。
- local transformed stub が必要なら `registerLocalTransform(dd)` し、plan の transformed symbol から元 `DefDef` を `originalDefsByTransformed` に登録する。
- member / package 所有の transformed stub については、owner class の decl から `name + "$transformed"` の symbol を探し、存在すれば元定義対応を登録する。
- `ValDef` は local なら `localValDefsBySymbol` に記録し、CPS val なら `registerLocalValTransform(vd)` する。

#### `registerLocalTransform`

- 元 local def symbol ごとに一度だけ plan を作る。
- transformed symbol は `newSymbol` で owner を元 symbol owner にし、名前は `localTransformedName(origSym)`。
- flags は `origSym.flags &~ (Flags.Deferred | Flags.GivenOrImplicit) | Flags.Synthetic`。
- 型は `transformCpsMethodType(origSym.info)`。
- 作成後 `.asTerm.entered` で scope に入れる。

local transformed name の形式: `s"${origSym.name}$$transformed$$${origSym.id}".toTermName`（local symbol id を含めて衝突を避ける）。

**再帰的 CPS メソッドの事前生成**: `collectTransformedPlans` が `prepareForUnit` でユニット全体を先行走査し、変換シンボルを `registerLocalTransform` で生成・`.entered` してから本変換を進める。これにより、再帰呼び出し先の transformed symbol が本体変換時にすでに scope に存在することが保証される。

#### `registerLocalValTransform`

- 元 local val symbol ごとに一度だけ plan を作る。
- RHS 内に poly apply def があれば `findPolyApplyDef(originalVal.rhs)` で探し、`apply$transformed` synthetic method symbol を `transformCpsMethodType(applyDef.symbol.info)` で作る。
- poly apply plan は `polyApplyPlansByClass(applyDef.symbol.owner)` に登録される。
- transformed val symbol の flags は `origSym.flags | Flags.Synthetic`。
- 型は `transformCpsValueType(origSym.info)`。

#### `emitLocalCpsValPair`

- local val definition を `localValDefsBySymbol` に記録する。
- plan がなければその場で `registerLocalValTransform` する。
- strict local CPS val または mutable local CPS val は `splitStorageRhsPrelude` で prelude を hoist し、stripped RHS を格納対象にする。
- lazy local CPS val は `mkLocalLazyValTransformed`、それ以外は `mkLocalValTransformed` を使う。
- 出力は `prefix ++ List(transformed)`。元の CPS val 自体は runtime storage として emit しない。

#### `mkLocalValTransformedFromRhs`

- RHS を `TreeTypeMap(oldOwners = List(orig.symbol), newOwners = List(transformedSym))` で rehome する。
- `ctx.withOwner(transformedSym)` の下で `rewriteLocalValRhsToTransformed(rehomedRhs, orig.symbol.info, transformedSym.info)` を適用する。

#### `mkLocalLazyValTransformed`

- `splitStorageRhsPrelude` で prelude と stripped RHS を分ける。
- `referencedSymbols(strippedRhs).intersect(definedSymbols(prefix))` が非空なら lazy val capture エラー。
- 現実装では prefix の有無にかかわらず `mkLocalValTransformed(orig, transformedSym)` を返す。

#### `replaceOriginalBody`

- 元 def の final result が CPS transform function 型なら、RHS が `Block(List(anonfun: DefDef), _: Closure)` の closure block 形である場合は anonymous function の RHS だけ `Predef.undefined` に置換して closure block 形を保つ。これは Erasure フェーズの `skipContextClosures` が `Block + Closure` 形を期待するため、クロージャ形を維持することで Erasure との整合を確保するためである。それ以外は RHS 全体を `Predef.undefined.ensureConforms(dd.tpe.widen)` にする。
- final result が CPS val 型なら元 def をそのまま返す。
- それ以外は RHS 全体を `Predef.undefined` にする。

---

### TransformedImplBuilder

#### `mkTransformedImpl`

`mkTransformedImpl(orig: DefDef, transformedSym: TermSymbol)` は `tpd.DefDef(transformedSym, paramss => ...)` で transformed symbol を持つ新 `DefDef` を作る。

処理手順:

1. body 生成時の owner は `ctx.withOwner(transformedSym)` に切り替える。
2. `buildParamRemap(orig.symbol.paramSymss, paramss)` で、元パラメータ symbol と新パラメータ symbol を zip する。
3. pure パラメータは `TreeTypeMap` の `substFrom/substTo` で置換する。
4. CPS val パラメータは `cpsMapping` に入れ、container-of-CPS applied 型のパラメータは `containerCpsMapping` に**別フィールドで**保持する。`containerPairs` を `substFrom/substTo` に含めると不正再型付けで `AssertionError` になるため、pure pairs のみ subst に入れる。
5. `effectiveMapping` を構成する。
6. RHS 内の alias を `remapParamMappingForTree` で補正し、同名同型で owner が変わった symbol も mapping に加える。

body 変換の分岐（以下を上から順に評価し、最初に該当したものを採用する）:

1. 元戻り値が CPS val 型かつ CPS transform function 型: 戻り値の `R` 型を `getRType` で求め、`transBody(effectiveRhs, rhsParamMapping, rType)` を生成する。この際 RHS が `Block(List(anonfun: DefDef), _: Closure)` の場合、anonymous function の RHS を `TreeTypeMap` で rehome して直接使う。
2. 元戻り値が CPS val 型だが CPS transform function 型でない: `remapPureRhs` 後に `rewriteLocalValRhsToTransformed(effectiveRhs, originalFinalResultType, transformedFinalResultType, rhsParamMapping)` を使う。
3. direct CPS transform parameter の R 型がある: `transBody(effectiveRhs, rhsParamMapping, directCpsParamR.get)` を使う。
4. CPS param または container CPS param があるが戻り値自体は CPS でない: `transPureBody(effectiveRhs, rhsParamMapping)` を使う。
5. 上記のどれにも該当しない: `orig.rhs` をそのまま使う。

#### `getRType`

- transformed method の final result が `ControlContext` 型なら、その第 2 型引数 `argInfos(1)` を R とする。
- そうでなければ、元パラメータから CPS transform function 型を探し、`cpsFunctionR` を使う。
- 見つからなければ `NothingType`。

#### `remapParamMappingForTree`

RHS を traverse し、元 mapping の key と同名の symbol を見つける。以下の条件を満たす場合に alias として対応付ける。

- mapping そのものでも alias 登録済みでもなく、shadowed name でもない。
- 元 symbol と同名同型（`origSym.info =:= id.symbol.info`）である。

traverse 規則:

- `Block`: stats を順に見て、`ValDef`/`DefDef` の RHS を先に traverse してからその名前を shadowed に追加する。
- `DefDef`: def 名と parameter 名を shadowed にして RHS を traverse する。
- `CaseDef`: pattern bind 名を shadowed に加えて guard/body を traverse する。

最終 mapping は `pm ++ aliases ++ pm.valuesIterator.map(sym => sym -> sym)`。

---

## 本体の CPS 変換規則（CpsBodyTransform）

このコンポーネントは `SelectiveCPSTransform` への自型制約 `self: SelectiveCPSTransform =>` を持つ。

パラメータの意味:

- `pm: Map[Symbol, Symbol]`: CPS 値のシンボルから変換済みシンボルへのマッピング。
- `rType: Type`: `ControlContext[A, R]` の型引数 `R`（`shiftUnitR` 呼び出し時に使用）。

### エントリーポイント: `transBody`

```
transBody(rhs, pm, rType):
  Block(stmts, expr) → transBlock(stmts, expr, pm, rType)
  _                  → transTailValue(rhs, pm, rType)
```

### ブロック変換: `transBlock`

逐次実行文のリストを再帰的に処理し、モナド連鎖（`flatMap`/`map`）を構築する。

| パターン | 処理 |
|---|---|
| `Nil`（空） | `transTailValue(expr, pm, rType)` を返す |
| `vd: ValDef` かつ `isCpsSymAnnotated(vd.symbol)` | CPS 変換済みの RHS を `transformCpsRhs` で取得し、`rehomeFromValDefOwner` で ValDef owner から変換後 owner へ付け替え。残りの本体 `bodyExpr` を再帰取得。`liftedPureBody` で純粋か判定し、純粋なら `mkMap`、CC 型なら `mkFlatMap` を選択 |
| `vd: ValDef`（アノテーションなし） | `transformCpsRhs(vd.rhs)` が CC 型なら `rehomeFromValDefOwner` で owner 付け替え後に `mkMap`/`mkFlatMap` を選択。CC 型でなければ `transformCpsExpr(vd, pm)` を適用して残りを `transBlock` で続行 |
| その他の文 | `transformCpsExpr(stm, pm)` を適用して `Block(List(stm1), body)` を返す |

`liftedPureBody` による最適化: `shiftUnitR(e)` という形の木を検出した場合、`e` を純粋な本体として取り出す（`flatMap` + `shiftUnitR` を `map` に昇格する）。

```
liftedPureBody:
  Apply(TypeApply(fun, _), List(expr)) if fun.symbol == shiftUnitRMethod → Some(expr)
  Apply(fun, List(expr))               if fun.symbol == shiftUnitRMethod → Some(expr)
  _                                    → None
```

### 末尾値変換: `transTailValue`

```
transTailValue(expr, pm, rType):
  t = transformCpsRhs(expr, pm, rType)
  if isControlContextType(t.tpe.widen) → t
  else:
    t2 = transformCpsExpr(t, pm)
    if isControlContextType(t2.tpe.widen) → t2
    else → mkShiftUnitR(t2, rType)
```

CC 型でない値は `shiftUnitR(expr)` で包む（純粋な値を CC モナドに lift する）。

### CPS 右辺変換: `transformCpsRhs`

CPS 変換が必要な式を形に応じて変換する。以下のパターンを上から順に評価する。

| 入力パターン | 変換規則 |
|---|---|
| `Apply(Select(id: Ident, _), _)` かつ `pm.contains(id.symbol)` | `ref(pm(id.symbol))` へ置換 |
| `Apply(id: Ident, _)` かつ `pm.contains(id.symbol)` | `ref(pm(id.symbol))` へ置換 |
| `app: Apply` かつ `isCpsTransformFunctionType(app.tpe)` | `transformCpsExpr(app, pm)` |
| `Apply(Select(qual, _), _)` かつ `isCpsTransformFunctionType(qual.tpe)` | `transformCpsExpr(qual, pm)` |
| `app: Apply` かつ引数に `isCpsTransformType(a.tpe)` が存在 | `transformCpsExpr(app, pm)` |
| `Typed(expr, tpt)` | `transformCpsRhs(expr, ...)` を再帰。CC 型になれば `expr1` のみ返す |
| `Block(stmts, expr)` | `transBlock(stmts, expr, pm, rType)` へ委譲 |
| `If(cond, thenp, elsep)` | 下記「if 式の変換規則」参照 |
| `Match(sel, cases)` | 下記「match 式の変換規則」参照 |
| `Try(body, cases, finalizer)` | 下記「try 式の変換規則」参照 |
| `app: Apply` かつ CC コンテナパラメータを持つクラスメンバーへの呼び出し | `transformCpsExpr` を試み、失敗時にエラー報告 |
| その他 | `tree` をそのまま返す |

### `if` 式の変換規則

```
If(cond, thenp, elsep)
  → If(rewritePurePosition(cond, pm),
       transTailValue(thenp, pm, rType),
       transTailValue(elsep, pm, rType))
```

条件式は純粋位置（`rewritePurePosition`）として扱い、両ブランチを末尾値変換する。

### `match` 式の変換規則

```
Match(sel, cases)
  → Match(rewritePurePosition(sel, pm),
           cases.map { cd =>
             CaseDef(cd.pat, rewritePurePosition(cd.guard, pm),
                     transTailValue(cd.body, pm, rType))
           })
```

セレクタとガードは純粋位置、各 case の本体は末尾値変換。

### `try` 式の変換規則

finalizer が空の場合（`finally` ブロックなし）:

```
Try(body, cases, EmptyTree)
  → mkFlatMapCatch(
      Try(transTailValue(body, pm, rType),
          cases.map { cd => CaseDef(cd.pat, rewritePurePosition(cd.guard), transTailValue(cd.body)) },
          EmptyTree),
      transformedCases)
```

`mkFlatMapCatch` は `controlContext.flatMapCatch` を呼び出し、ハンドラとして `ex => Match(ex, transformedCases :+ defaultCase)` のラムダを渡す。デフォルト case は `_ => throw ex`。

finalizer がある場合: `mkFlatMapCatch` を適用せず、`Try` をそのまま返す（`finally` は副作用を伴うため CC モナド連鎖の外に置く）。

### `shiftUnitR` による純粋値の lift

```
mkShiftUnitR(expr, rType):
  val A = expr.tpe.widen
  ref(shiftUnitRMethod).appliedToTypes(List(A, rType)).appliedTo(expr)
  // → shiftUnitR[A, R](expr)
```

### `flatMap`/`map` の構築

`mkFlatMap(cc, vSym, body)`: `cc.flatMap[A1] { vSym => body }` を生成する。`A1 = body.tpe.widen.argInfos(0)` で CC の型引数を取得し、`TreeTypeMap` で `vSym` を新ラムダパラメータで置換して owner を正しく付け替える。

`mkMap(cc, vSym, body)`: `cc.map[A1] { vSym => body }` を生成する。`A1 = body.tpe.widen` で返り値型を取得する。

`rehomeFromValDefOwner(tree, oldOwner)`: `ValDef` の owner から変換後の owner へ所有者を付け替える（`TreeTypeMap` ラッパー）。

### 純粋変換モード: `transPureBody`

CPS 変換が不要な箇所に使う軽量パス。シンボルマッピングの反映と reset/shift の書き換えのみ行う。

| 入力パターン | 処理 |
|---|---|
| `Block(stmts, expr)` | `rewritePureBlockStats(stmts, pm)` で文を書き換え → `transPureBody(expr, bodyMapping)` で末尾式を再帰 |
| `If(cond, thenp, elsep)` | cond/thenp/elsep を `rewritePurePosition`/`transPureBody` で再帰 |
| `Match(sel, cases)` | sel を `rewritePurePosition`、各 case を `transPureBody` で再帰 |
| `Try(body, cases, finalizer)` | body/cases を `transPureBody`、finalizer を `rewritePurePosition` で再帰 |
| その他 | `transformCpsExpr` を適用 |

`rewritePureBlockStats`: ブロック文リストを走査し、`isCpsValType` かつ不変（`!Mutable && !Lazy`）な `ValDef` を見つけた場合、`normalizeCpsValuePosition` でシンボルのエイリアスが取れればマッピングに追加（実際の `ValDef` を省く）。そうでなければ `transformCpsExpr(rewriteLocalValRhsToTransformed(...), pm)` で変換した結果を出力リストに追加する。

### ループ

ループ構文の変換規則は存在しない。`CpsBodyTransformOps` の `transformCpsRhs` / `transBlock` / `transTailValue` / `transPureBody` に `WhileDo` / `Labeled` のケースはない。ループ内での shift 使用は ANF フェーズで診断・拒否される（[03-anf-phase.md](03-anf-phase.md) 参照）。

---

## 呼び出し側書き換え（CallsiteRewrite）

`CallsiteRewriteOps` はフェーズの自型制約 `self: SelectiveCPSTransform =>` を持つ。

### `transformCpsExpr` — 呼び出し側書き換えの中心入口

以下のパターンを上から順に評価する（先に成立した規則を採用する）。

#### `pm` 起点の呼び出しチェーン

`rewriteMappedParamCallChain(qual, pm)` が `Some` を返す場合、その結果を採用する。`pm` に `x -> x$transformed` があるなら、`x` だけでなく `x.foo[T](arg)` のようなチェーンでも起点の `x` を `x$transformed` に差し替える。

引数処理:

- `isCpsTransformType(arg.tpe)` の引数は削除する。
- `isCpsValType(arg.tpe)` の引数は `transformCpsCallSiteArg(arg, pm)` で変換する。
- 引数が空になった場合は `stripNullaryApplyToNonMethod(newFun, newFun.symbol)` を通す。

#### ローカル val 変換計画を起点にする呼び出しチェーン

`rewriteLocalValCallChainToTransformed(qual, pm)` が `Some` を返す場合、その結果を採用する。

- 呼び出し文脈かつ `polyApplyPlan` がある場合: `ref(plan.transformedSym).select(transformedApplySym)` へ置換。
- それ以外: `ref(plan.transformedSym)` へ置換。

#### 型適用された識別子

```
TypeApply(id: Ident, targs)
→ TypeApply(ref(tSym.asTerm), targs)  (lookupTransformedSym が存在する場合)
→ qual                                 (存在しない場合)
```

#### 型適用された関数への通常適用

```
Apply(TypeApply(fun, targs), args)
```

まず `transformedTupleElementAlias(qual, pm)` を試す。失敗したら `lookupTransformedSym(fun.symbol)` を見る。変換済みシンボルがあれば:

```
Apply(
  rewriteFunToTransformed(TypeApply(fun, targs), tSym),
  args.map(a => if isCpsValType(a.tpe) then transformCpsCallSiteArg(a, pm)
                else rehomeClosureArgToCurrentOwner(a))
)
```

型引数は維持し、関数部だけ変換済みシンボルへ書き換える。CPS 値型でない引数には `rehomeClosureArgToCurrentOwner` を適用する（クロージャ形の引数の owner を現在 owner へ移す）。`shift[A, R](f)` → `shift$transformed[A, R](f)` の書き換えもこのパスで処理される。

#### `pm` にある識別子を qualifier にした Select/Apply

```
Apply(Select(id: Ident, _), _) if pm.contains(id.symbol)
→ ref(pm(id.symbol).asTerm)
```

select/apply の構造を再構築せず、変換済みパラメータ参照そのものに置換する。

#### `pm` にある識別子

```
id: Ident if pm.contains(id.symbol)
→ ref(pm(id.symbol).asTerm)
```

#### 通常の識別子

```
id: Ident
→ ref(tSym.asTerm)  (lookupTransformedSym が存在する場合)
→ qual              (存在しない場合)
```

#### 一般の `Apply(fun, args)`

まず `transformedTupleElementAlias(qual, pm)` を試す。失敗したら以下の条件を判定する:

- 引数に CPS transform 型がある、または
- 引数に CPS 値型があり、かつ関数がローカル変換計画に載っていない

場合に `rewriteDirectCpsUsingCall(fun, args, pm)` を試す。その後、以下を順に試し、最初の成功を採用する:

1. `rewriteDirectCpsUsingCall`
2. `rewriteLocalMethodCallChainToTransformed`
3. `rewriteMethodCallToTransformed`
4. `normalizeCpsValuePosition`

すべて失敗したら元の `qual` を返す。

#### `Select`

以下を順に試す:

1. `transformedTupleElementSelect(sel, pm)`
2. `normalizeCpsValuePosition(sel, pm)`

失敗したら元の `qual` を返す。

### `rewriteDirectCpsUsingCall`

CPS using 引数を持つ直接呼び出しを、変換済みメソッドへ書き換える。

1. `lookupTransformedSym(fun.symbol)` を取得。存在しなければ `None`。
2. 存在すれば `rewriteFunToTransformed(fun, tSym)` で関数部を変換。
3. 引数を処理: CPS transform 型は削除、CPS 値型は `transformCpsCallSiteArg`、それ以外は維持。
4. 新引数が空なら `newFun`、空でなければ `Apply(newFun, newArgs)` を返す。

`Option.when(tSym.exists)` を使うため、変換済みシンボルが存在する場合だけ `Some` になる。

### `rewriteMethodCallToTransformed`

通常メソッド呼び出しを変換済みメソッドへ差し替える（CPS transform 型引数は削除しない）。

1. `lookupTransformedSym(fun.symbol)` を取得。存在しなければ `None`。
2. 存在すれば `cpy.Apply(tree.asInstanceOf[Apply])` で元 apply をベースに再構築。
3. 関数部は `rewriteFunToTransformed(fun, tSym)`、引数は CPS 値型のみ `transformCpsCallSiteArg`、それ以外は維持。
4. 元の `args` が空なら `stripNullaryApplyToNonMethod(applied, tSym)` を通す。

### `rewriteLocalMethodCallChainToTransformed`

ローカルメソッドの変換計画を使って呼び出しチェーンを変換済みメソッドへ置換する。

内部 `rewrite` の規則:

- `Ident`: `lookupOriginalLocalPlan(id.symbol)` があれば `ref(plan.transformedSym)`。
- `Select`: qualifier を再帰変換できれば select を再構築。
- `TypeApply`: 関数部を再帰変換できれば型引数を維持。
- `Apply`: 関数部を再帰変換できれば apply を再構築。引数は CPS 値型のみ `transformCpsCallSiteArg`。引数が空なら `stripNullaryApplyToNonMethod(applied, newFun.symbol)`。

### `rewriteLocalValCallChainToTransformed`

ローカル val の変換計画を使って val 参照またはその呼び出しチェーンを変換済み val へ置換する。

`baseRef(plan, invoked)` の規則:

- `invoked == true` かつ `plan.polyApplyPlan` が `Some((_, transformedApplySym))`: `ref(plan.transformedSym).select(transformedApplySym)`。
- それ以外: `ref(plan.transformedSym)`。

### `rewriteFunToTransformed`

関数位置の木を `transformedSym` を参照する形へ変換する。

- `Apply(prefix, args)`: prefix を再帰変換し、同じ引数で `Apply`。
- `TypeApply(prefix, targs)`: prefix を再帰変換し、同じ型引数で `TypeApply`。
- `Select(qual, _)`: selector 名は使わず、`qual.select(transformedSym)`（qualifier を保ちつつ selector を `$transformed` シンボルへ差し替える）。
- その他: `ref(transformedSym.asTerm)`。

### `stripNullaryApplyToNonMethod`

```scala
case Apply(prefix, Nil) if !transformedSym.info.isInstanceOf[MethodType] =>
  prefix
```

変換済みシンボルが `MethodType` でない場合、空引数 apply を剥がす。変換後に関数ではなく値を参照する形になった場合に、不要な `()` 適用を残さないための補正。

### `lookupTransformedSym`

元シンボルから変換済みシンボルを探す。優先順位:

1. `sym.exists` が false なら `NoSymbol`。
2. `lookupAnyLocalPlan(sym)` があれば、その `transformedSym`。
3. `lookupLocalValPlan(sym)` があれば、その `transformedSym`。
4. 名前に `"$transformed"` を付けた term 名を作り、`ownerSym.info.decl(tname).symbol` を返す（owner の補正あり: `sym.owner.isConstructor && sym.owner.owner.exists` なら `sym.owner.owner`）。

### `transformCpsCallSiteArg`

CPS 値または CPS 変換関数型の引数を、呼び出し側で渡せる形へ変換する。

1. `argType = arg.tpe.widen`。
2. `isCpsTransformFunctionType(argType)` が true なら:
   - 引数が `Block(List(dd: DefDef), _: Closure)` の場合: `rType = dd.symbol.info.finalResultType`、owner を現在 owner へ移し、`transBody(cleanRhs, pm, rType)` を返す。
   - closure block 形でなければ `transformCpsExpr(arg, pm)`。
3. CPS 変換関数型でなければ: `rewriteLocalValRhsToTransformed(arg, argType, transformCpsValueType(argType), pm)`。

### CPS transform 型引数の削除有無

| 書き換え関数 | CPS transform 型引数を削除するか |
|---|---|
| `rewriteMappedParamCallChain` | 削除する |
| `rewriteDirectCpsUsingCall` | 削除する |
| `rewriteMethodCallToTransformed` | 削除しない |
| `rewriteLocalValCallChainToTransformed` | 削除しない |
| `rewriteLocalMethodCallChainToTransformed` | 削除しない |
| `Apply(TypeApply(fun, targs), args)` の直接分岐 | 削除しない |

---

## ローカル値の書き換え（LocalValueRewrite）

`LocalValueRewriteOps` はフェーズの自型制約 `self: SelectiveCPSTransform =>` を持つ。

このコンポーネントの役割は「ローカル値定義そのものの構造変更」ではなく、「ローカル値 RHS 内の CPS 参照・CPS 値位置・CPS コンテナ要素を変換後表現へ寄せること」である。`ValDef` のシンボルやフラグはこのファイルでは作り直さない。

### `rewriteLocalValRhsToTransformed` — 中心関数

入力 AST `tree` を元型 `originalType` と CPS 変換後型 `transformedType` に従って変換する。

最初に `originalType.dealias.widen` を見る。これが CPS 変換関数型なら、RHS 全体を `transformCpsCallSiteArg(tree)` に渡す。

型・パターン別変換規則:

**`Ident`**:

- `pm` にシンボルがある `Ident` は対応先シンボルへの `ref` に置換。
- `pm` にない `Ident` は `lookupTransformedSym(id.symbol)` を探し、存在すれば変換後シンボルへの `ref` に置換。
- `Apply(Select(id: Ident, _), _)` と `Apply(id: Ident, _)` も、関数位置の `id.symbol` が `pm` にあれば対応先 `ref` に畳む。

**`Apply`**:

- まず `transformedTupleElementAlias(app, pm)` を試す。
- `app.tpe.widen` が CPS 変換関数型なら `transformCpsExpr(app, pm)` で式全体を変換。
- 戻り型が CPS 値型かつ CPS 適用型を含むコンテナなら、`normalizeCpsValuePosition(app, pm)` を試し、できなければ `rewriteContainerApplyArgs(app, pm)` に進む。
- 戻り型が CPS 値型なら `transformCpsExpr(app, pm)`。
- それ以外では `normalizeCpsValuePosition(app, pm)` を試し、できなければ関数部と引数を再帰的に書き換えた `Apply` を作る。

**`Select`**:

- `transformedTupleElementSelect(sel, pm)` を先に試す。
- 次に `normalizeCpsValuePosition(sel, pm)` を試す。
- それでも処理できない場合、qualifier を再帰変換して `Select` を再構築。
- ただし、選択対象が「CPS 値を含むコンテナ型を返すクラスメンバー `def`」で変換後シンボルが見つからない場合はエラーを報告。

**`TypeApply`**: タプル要素 alias、CPS 値位置の正規化を試した後、関数部だけを再帰変換して型引数は維持。

**`Typed`**: 式本体を同じ `originalType`/`transformedType` で再帰変換し、型注釈 `tpt` は維持。

**`Inlined`**: `call`、`bindings`、`expansion` をそれぞれ再帰変換。`expansion` は元の期待型を引き継ぐ。

**`Block(stats, closure: Closure) if isCpsValType(widenedOriginal)`**: ローカル関数クロージャ値を CPS 変換する特別規則。

- `stats` 内に `closure.meth.symbol` と同じ `DefDef` があれば、`mkTransformedLocalValClosure` で変換済みローカル関数定義と、それを指す `Closure` を作る。
- 対応する `DefDef` がなくても `lookupTransformedSym(closure.meth.symbol)` が見つかれば、空 `stats` の `Block` として変換済みメソッド参照の `Closure` に置換。
- どちらもなければ元の tree を維持。

**`ValDef`**: RHS のみを次の形で変換する。シンボル・名前・フラグ・型木などは `cpy.ValDef(vd)(rhs = newRhs)` により維持。

```scala
rewriteLocalValRhsToTransformed(vd.rhs, vd.symbol.info, transformCpsValueType(vd.symbol.info), pm)
```

**`If`**: 条件を通常位置として変換し、then/else は元の期待型で変換。

**`Match`**: selector について `tupleRhsForSelector(sel)` を調べる。selector がローカル値なら、その RHS を使ってパターン束縛の alias 展開を試みる。各 case では `patternBindPaths(cd.pat)` で `Bind` シンボルからタプル内 path への対応を作り、case body 内の束縛参照を `replacePatternAliases` で変換済みタプル要素へ差し替えてから、通常の RHS 変換をかける。

**`TypeDef`**: ローカルクラスの synthetic apply 追加用。`polyApplyPlansByClass` に該当クラスシンボルがある場合、`mkTransformedImpl(applyDef, transformedApplySym)` で変換済み apply 実装を作り、`Template` body の末尾に追加。

**`Try`**: body と各 case body を元の期待型で変換し、guard と finalizer は通常位置として変換。

**`Block(stats, expr)`（通常）**: stats を通常位置、expr を元の期待型で変換。

**`SeqLiteral`**: 要素型や要素値が CPS 値型なら変換後型へ置換。varargs 呼び出しの引数は typer によって `SeqLiteral` でラップされるため、CPS 値を varargs として渡す場合にこのケースに至る。要素型 `elemtpt` が CPS 値型なら `TypeTree(transformCpsValueType(...))` に変え、各要素も CPS 値なら `elem.tpe.widen`（元型）を明示して `rewriteLocalValRhsToTransformed(elem, elemTpe, transformCpsValueType(elemTpe), pm)` で再帰変換する。リテラル全体の型が CPS 値型なら `.withType(transformCpsValueType(sl.tpe.widen))` を付ける。

### `mkTransformedLocalValClosure`

元のローカル `DefDef` から新しい synthetic なメソッドシンボルを作る。

- 名前: `localTransformedName(dd.symbol.asTerm)`
- 型: `transformCpsMethodType(dd.symbol.info)`
- フラグ: 元フラグから `GivenOrImplicit` を落として `Synthetic` を付与
- 元 RHS は `TreeTypeMap` で owner とパラメータシンボルを新しいメソッド側へ rehome/substitute したうえで、`rewriteLocalValRhsToTransformed` により最終結果型を CPS 変換後型へ書き換える。
- 最後に、新 `DefDef` と新メソッド参照の `Closure` を含む `Block` を返す。

### `normalizeCpsValuePosition`

式の一部がすでに変換後シンボルで表せる場合に、CPS 値位置を局所的に正規化する。`Ident`、`Select`、`TypeApply`、`Apply` を対象にし、変換後シンボルが見つかる参照を差し替える。

- `Select(..., apply)` かつ qualifier が control context 型になった場合は apply 選択を剥がして qualifier 自体を返す。
- `Apply` では関数部が control context 型で、引数に CPS 変換型があり、関数部に raw local CPS value が含まれない場合は関数部だけに畳む。それ以外では CPS 値型の引数を `transformCpsCallSiteArg(arg, pm)` で変換してから `Apply` を再構築。
- 空引数 apply は `stripNullaryApplyToNonMethod` で不要な nullary apply を剥がす可能性がある。

### タプル要素の変換

安全性を重視した実装になっている。

- `_1`、`_2` などは `tupleSelectorIndex` で 0 始まり index に変換する。
- qualifier がローカル値なら `lookupLocalValDef(sel.qualifier.symbol)` で元 RHS を取り出す。
- `tupleElementAtPath` は tuple apply の引数を path で辿る。
- `Block(stats, expr)` 内の tuple 要素を取り出す場合、その要素が block 内定義シンボルを参照していないことを確認する。
- CPS 値要素を安全に取り出せる場合だけ、`stableTransformedTupleElement` で `pm` または `lookupTransformedSym` により変換済み stable 参照へ置換する。
- 安全に変換できない CPS 要素は診断対象となる。

---

## 型・シンボルの扱い

### CPS 型判定述語

| 述語 | 役割 |
|---|---|
| `isCpsValType(tpe)` | 型が CPS 値型（`CpsVal[A]` 等）か判定 |
| `isCpsTransformType(tpe)` | 型が CPS 変換型か判定 |
| `isCpsTransformFunctionType(tpe)` | 型が CPS 変換関数型か判定 |
| `isControlContextType(tpe)` | 型が `ControlContext[_, _]` か判定 |
| `isContainerOfCpsAppliedType(tpe)` | 型が CPS 適用型を含むコンテナ型か判定 |
| `isCpsSymAnnotated(sym)` | シンボルが `@cps` アノテーションを持つか判定 |

### CPS 型変換

| 関数 | 役割 |
|---|---|
| `transformCpsValueType(tpe)` | CPS 値型を対応する変換済み型（`ControlContext[A, R]`）に変換 |
| `transformCpsMethodType(info)` | CPS メソッド型を変換済みメソッド型に変換 |

### シンボル変換

- transformed symbol は `lookupTransformedSym` または local registry から取る。
- local transformed stub 判定: `needsTransformedStub(sym)` かつ transformed stub 自体ではなく、owner が class/package でない。
- member CPS val の元フィールドは `null.cast(vd.symbol.info)` で型を合わせる。
- storage assignment では、元型が CPS transform function 型で stripped RHS がそうでない場合だけ `transformCpsExpr` を使い、それ以外は `rewriteLocalValRhsToTransformed` を使う。
- receiver が非 stable な member 代入では synthetic symbol を `newSymbol(ctx.owner, StorageTmpName.fresh(), Flags.Synthetic, qual.tpe.widen, coord = qual.span)` で作る。

### TreeTypeMap の利用パターン

- pure parameter の置換: `substFrom/substTo` で symbol を置換。
- owner の付け替え: `oldOwners/newOwners` で owner chain を移す。
- anonymous closure rehome: `DefDef` owner から transformed method owner へ木を移す。
- `flatMap`/`map` のラムダパラメータ置換: `transBlock` 内でラムダパラメータシンボルを差し替える。

利用箇所ごとの引数の差異:

| 利用箇所 | oldOwners/newOwners | substFrom/substTo |
|---|---|---|
| `mkTransformedImpl` の closure rehome（`Block(List(anonfun), Closure)` ケース） | anonfun.symbol → transformedSym | paramRemap.substFrom / substTo（フル指定） |
| `remapPureRhs` | anonfun.symbol → transformedSym | substFrom / substTo（フル指定） |
| `transformCpsCallSiteArg`（`CpsTransformFunctionType` 引数の rehome） | dd.symbol → ctx.owner | 指定なし（省略） |
| `mkLocalValTransformedFromRhs` | orig.symbol → transformedSym | 指定なし（省略） |
| `mkFlatMap` / `mkMap` | ラムダパラメータ owner | substFrom / substTo でラムダパラメータを置換 |

**重要な使い分け**:

- `tpd.Lambda` は新しいラムダシンボルを自動管理するため、新規に作るラムダ枠には `tpd.Lambda` を使う。既存ツリーを別 owner へ移すときにのみ `TreeTypeMap` が必要。
- `transPureBody` は自身では `TreeTypeMap` を呼ばない。呼び出し元の `remapPureRhs` が `TreeTypeMap` でツリーを rehome してから `transPureBody` に渡す。
- `transformCpsCallSiteArg` 内の `CpsTransformFunctionType` 引数 rehome は `oldOwners/newOwners` のみで owner を移す。`substFrom/substTo` は持たない（パラメータ置換は `remapParamMappingForTree` が担う）。

### `pm: Map[Symbol, Symbol]` の伝播

- `transBlock` でアノテーション付き `ValDef` を処理するたびに新しいエントリが追加される。
- `rewritePureBlockStats` で CPS 値型の不変 `ValDef` を処理するたびに追加される。
- `mkFlatMap`/`mkMap` 内では `TreeTypeMap` でラムダパラメータへの置換も行われる。

---

## 不変条件・前提

- `SelectiveANFTransform` 後に実行される。入力の RHS は A-normal form であることを前提とする。
- transformed stub は事前にシンボル生成または収集済みで、`lookupTransformedSym`/registry で引ける前提。
- direct CPS パラメータの `pm` マッピング先シンボルは `isControlContextType` な型を持つ。effective mapping（`effectiveMapping`）には `containerCpsMapping` によるコンテナ型（`List[CPS function]` 等）の変換も含まれる。
- `rType` は `ControlContext[A, R]` の `R` 型引数であり、`shiftUnitR` の型適用に使われる。
- `transBlock` の再帰処理では `stmts` の先頭に `ValDef` が来ることが想定され、非 `ValDef` の文は `transformCpsExpr` のみ適用される。
- local lazy CPS val は initializer prelude の即時 CPS 消費、および prelude 定義値の capture を許さない。
- member lazy CPS val も同じく prelude capture を許さない。
- raw local CPS value は runtime value としてそのまま使ってはいけない。
- mutable member assignment の receiver は必要なら一時 val に hoist し、副作用評価回数を保つ。
- 各 local def/val plan は元 symbol ごとに一度だけ登録される。
- mutable map は unit ごとに clear される。
- transformed symbol は作成時に `.entered` 済みである。
- `orig.symbol.paramSymss.flatten` と `newParamss.flatten` は対応順序が一致する前提。
- `transformedSym.info.finalResultType` は変換後の戻り値型を正しく持つ前提。
- body 変換中は `ctx.owner == transformedSym` であること。
- タプル要素の最適化は、元 RHS を再評価せず安全に変換済み stable 参照へ到達できる場合だけ行う。
- `Block` 内のタプル要素を外へ抜く場合、その要素が block 内で定義されたシンボルに依存しないことが必要。
- `DefDef` は通常の再帰変換対象ではなく、ローカルクロージャや `polyApplyPlansByClass` 経由の特殊経路でのみ変換・追加される。
- `transformCpsCallSiteArg` がクロージャ本体を直接 CPS 変換するのは、`Block(List(dd: DefDef), _: Closure)` 形に限られる。

---

## エラー診断

以下のメッセージが各コンポーネントから `report.error` で発報される。

**SelectiveCPSTransform**（`transformPackageDef` の最終検査パス）:

```text
CPS value not transformed
CPS expression not transformed
```

**SelectiveCPSTransform**（`transformStats` / `transformDefDef`）:

`reportLocalCpsRuntimeUse` 経由で発報される。本文は `LocalCpsValueRuntimeMessage` に委譲されており、ソースファイル内に文字列は含まれない。

**LocalTransformRegistry**:

```text
local lazy val CPS storage RHS cannot capture initializer prelude values; use a strict val or def
```

`UnsupportedDirectCpsTransformParamMessage` および `LocalCpsValueRuntimeMessage` は定数参照のみで、文字列本文は対象ファイル内に存在しない。

**SelectiveCPSTransform**（`transformStats` の lazy CPS val 処理）:

```text
local lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def
```

**SelectiveCPSTransform**（`transformTemplate` の lazy member CPS val 処理）:

```text
member lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def
member lazy val CPS storage RHS cannot capture initializer prelude values; use a strict val or def
```

**CpsBodyTransform**（`transformCpsRhs` 内）:

```text
class member def with CPS container parameter is not yet supported in CPS context
```

発生条件: `Apply` の引数に `isContainerOfCpsAppliedType` な型が含まれており、かつ呼び出し先がクラスメンバーのとき、`transformCpsExpr` が変換できない場合。

**LocalValueRewrite**:

```text
class member def with CPS container return type is not yet supported in CPS context
```

発生箇所: `Select` 処理中。CPS 値を含むコンテナ型を返すクラスメンバー `def` で、変換後シンボルが見つからない場合。

```text
tuple CPS value element cannot be safely transformed without re-evaluating its RHS; bind it to a local val first
```

発生箇所: タプル RHS から CPS 値要素を安全に変換済み参照へ置換できない場合。

---

## End-to-End 変換例（3フェーズ跨ぎ）

以下に示す `example` メソッドを起点に、3フェーズを跨ぐ変換の流れを示す。

**変換前ソース**:

```scala
def example()(using CpsTransform[Int]): Int =
  val x = shift[Int, Int](k => k(1))
  val y = shift[Int, Int](k => k(2))
  x + y
```

**Phase 1（Stub フェーズ）後**:

```scala
def example()(using CpsTransform[Int]): Int =  // 元定義は変更なし
  val x = shift[Int, Int](k => k(1))
  val y = shift[Int, Int](k => k(2))
  x + y
def example$transformed(): ControlContext[Int, Int] = ???  // synthetic stub（body = Predef.undefined）
```

- `SelectiveCPSStubPhase` は sibling stub (`example$transformed`) を追加するだけで、元定義の RHS は変更しない。
- `example$transformed` は stub としてのみ生成され、pickler に TASTy として書かれる。

**Phase 2（ANF フェーズ）後**:

```scala
def example$transformed(): ControlContext[Int, Int] =
  val $cps1: ControlContext[Int, Int] = shift[Int, Int](k => k(1))  // @CpsSym
  val $cps2: ControlContext[Int, Int] = shift[Int, Int](k => k(2))  // @CpsSym
  $cps1 + $cps2  // tail 位置の CPS はホイストしない
```

- tail 位置（`val x = shift(...)` の `shift(...)` 自体）に来る CPS 式はホイストしない。
- tail 位置でない引数位置（`f(shift(...))` のような形）でのみ synthetic val へのホイストが行われる。
- ホイストされた `ValDef` には `@CpsSym` アノテーションが付く。

**Phase 3（CPS フェーズ）後**:

```scala
def example$transformed(): ControlContext[Int, Int] =
  shift$transformed[Int, Int](k => k(1)).flatMap[Int] { x =>
    shift$transformed[Int, Int](k => k(2)).map[Int] { y =>
      x + y
    }
  }
```

- `transBlock` が `@CpsSym` 付き `ValDef` のリストを再帰処理し、`mkFlatMap` + `mkMap` 連鎖を構築する。
- 末尾の `x + y` は `map` の本体として純粋値のまま残る（`shiftUnitR` で包まれた後、`liftedPureBody` 最適化で `map` に昇格）。
- `shift[A, R]` → `shift$transformed[A, R]` の書き換えは `transformCpsExpr` の `Apply(TypeApply(fun, targs), args)` パスが `lookupTransformedSym` で名前規則から変換済みシンボルを発見して処理する。
- `replaceOriginalBody` によって元定義 (`example`) の RHS は `Block(List(anonfun: DefDef), _: Closure)` 形を保ちつつ `Predef.undefined` に置換される（Erasure の `skipContextClosures` が Block+Closure 形を期待するため）。

---

## 関連文書

- [00-overview.md](00-overview.md) — プラグイン全体の概要
- [01-public-api.md](01-public-api.md) — 公開 API（shift/reset/ControlContext）の仕様
- [03-anf-phase.md](03-anf-phase.md) — CPS フェーズの前段にあたる ANF フェーズの仕様
- [05-shared-infrastructure.md](05-shared-infrastructure.md) — CPSUtils 等の共通基盤の仕様
