# TreeTypeMap

## 1. 概要

`TreeTypeMap` は、既存の `Tree` を別 owner のスコープへ移動しつつ、必要なら参照先 symbol も差し替えるための API である。

Scala 3 コンパイラプラグインで継続渡しスタイル変換（CPS 変換）を実装する場合、次のような場面で特に重要になる。

- 既存の `$anonfun.rhs` を別の `DefDef` の body に移す
- `ValDef.rhs` や `map`/`flatMap` の body を、新しいラムダ owner の下へ持ち込む

`Tree.subst` は参照の付け替えしか行わない。
`DefDef` や `ValDef` 自体の `symbol.owner` は変わらない。
そのため、ネストしたラムダやローカル定義を含む tree を別スコープへ移すと、`LambdaLift` が owner chain を辿れずに壊れる。

典型的な症状:

- `NoSuchElementException: key not found: method $anonfun`
- `Could not find proxy for x: Int`

## 2. 問題: `Tree.subst` だけでは壊れる

### 2.1 壊れる最小イメージ

```scala
def m0(x: Int)(using CpsTransform[Int]): Int =
  shift(k => k(k(x)))
```

ANF/CPS 変換後、`m0` から `m0$transformed` を作る場面では、もともと `m0` の中にいた `$anonfun` を `m0$transformed` の body に移す必要がある。

`Tree.subst` だけで済ませると、参照 `x` は新パラメータへ差し替えられても、`k => k(k(x))` を表す `$anonfun` 自体の owner は元の `m0` のまま残る。

変換前:

```
DefDef m0 owner=Test
  Block
    DefDef $anonfun owner=m0
      DefDef $anonfun$1 owner=$anonfun
        Apply(k, Apply(k, x))
    Closure($anonfun)
```

`Tree.subst` だけで `m0$transformed` に持ち込んだ後:

```
DefDef m0$transformed owner=Test
  Block
    Apply(reset$transformed,
      Block
        DefDef $anonfun owner=m0
          DefDef $anonfun$1 owner=$anonfun
            Apply(k, Apply(k, x$transformed))
        Closure($anonfun)
    )
```

`m0$transformed` の内部にいるはずの `$anonfun` が、owner だけ `m0` を指している。
`LambdaLift` は「現在のスコープから見える owner chain」に沿って proxy を探すため、`m0$transformed` 側から `m0` owner の `$anonfun` を正常に再配置できず失敗する。

### 2.2 owner だけ古い半壊れ状態

`Tree.subst` の典型的な壊れ方は次の通り。

```scala
// 参照だけ差し替わる
val effectiveRhs = orig.rhs match
  case Block(List(anonfun: DefDef), _: Closure) =>
    anonfun.rhs.subst(origParams, newParams)
  case _ =>
    orig.rhs
```

この実装では `Ident(x)` は差し替わるが、`anonfun.rhs` の中にあるローカル `DefDef` やネストラムダの owner は古い symbol のまま残る。

## 3. 原因: owner の再配置と参照置換は別問題

`TreeTypeMap` の基本形はこれである。

```scala
new TreeTypeMap(
  oldOwners = List(oldOwner),
  newOwners = List(newOwner),
  substFrom = List(oldSym1, oldSym2),
  substTo = List(newSym1, newSym2)
).transform(tree)
```

重要なのは次の 2 点。

- `oldOwners/newOwners` で tree 内の local symbol の owner を更新する
- `substFrom/substTo` で参照先 symbol を新しいものへ置き換える

つまり `Tree.subst` の不足分である「local symbol の再 home」を同時に行う。

## 4. 解決策: `TreeTypeMap` で owner と参照を一緒に更新する

同じ `m0` の例で見ると、`TreeTypeMap` を使った後の構造はこうなる。

```
DefDef m0$transformed owner=Test
  params: x$transformed, ctx
  rhs:
    DefDef $anonfun owner=m0$transformed
      DefDef $anonfun$1 owner=$anonfun
        Apply(k, Apply(k, x$transformed))
```

重要なのは、`$anonfun` 以下の local symbol が `m0$transformed` 側の owner chain に乗り直すことだ。
これで `LambdaLift` は `m0$transformed` を起点に proxy を辿れる。

## 5. 実装パターン

### 5.1 既存 closure body を変換済みメソッドへ移す

以下は CPS 変換プラグインにおける、元のメソッド本体を変換済みメソッドへ移す実装例である。
`mkTransformedImpl` は、元の `DefDef` から変換後 `DefDef` の body を組み立てる関数である。

```scala
private[plugin] def mkTransformedImpl(orig: DefDef, transformedSym: TermSymbol)(using ctx: Context): DefDef =
  tpd.DefDef(transformedSym, paramss =>
    given Context = ctx.withOwner(transformedSym)
    val paramRemap = buildParamRemap(orig.symbol.paramSymss, paramss)
    val hasCpsParam = paramRemap.cpsMapping.nonEmpty
    val hasCpsResult = isCpsTransformFunctionType(orig.symbol.info.finalResultType)
    if hasCpsResult then
      val rType = getRType(orig, transformedSym)
      val effectiveRhs = orig.rhs match
        case Block(List(anonfun: DefDef), _: Closure) =>
          new TreeTypeMap(
            oldOwners = List(anonfun.symbol),
            newOwners = List(transformedSym),
            substFrom = paramRemap.substFrom,
            substTo = paramRemap.substTo
          ).transform(anonfun.rhs)
        case _ => orig.rhs
      val effectiveParamMapping = remapParamMappingForTree(effectiveRhs, paramRemap.effectiveMapping)
      transBody(effectiveRhs, effectiveParamMapping, rType)
    else if hasCpsParam then
      val effectiveRhs = remapPureRhs(orig, transformedSym, paramRemap)
      val effectiveParamMapping = remapParamMappingForTree(effectiveRhs, paramRemap.effectiveMapping)
      transPureBody(effectiveRhs, effectiveParamMapping)
    else
      orig.rhs
  )
```

ポイント:

- `anonfun.rhs` をそのまま持ち込まず、`oldOwners = List(anonfun.symbol)` を指定して `transformedSym` 配下へ移す
- original/transformed parameter の対応は `buildParamRemap` で先に明示的に作る
- 純粋パラメータだけを `TreeTypeMap.substFrom/substTo` で差し替える
- CPS パラメータは `TreeTypeMap` では置換せず、CPS rewrite 用 mapping として渡す
- `remapParamMappingForTree` は、rehome で生じた parameter 由来コピーを補助的に拾うためだけに使う

### 5.2 `reset { ... }` の closure body を呼び出し側へ戻す

以下は CPS 変換プラグインにおける、`reset { ... }` 内の closure body を現在 owner に移し直す実装例である。
`transformCpsReset` は、`reset` 呼び出しを検出して内部 body を再配置しながら CPS 変換する関数である。

```scala
private[plugin] def transformCpsReset(tree: Tree, pm: Map[Symbol, Symbol])(using Context): Tree = tree match
  case Apply(fun, List(Block(List(dd: DefDef), _: Closure))) if fun.symbol == resetSymbol =>
    val rType = dd.symbol.info.finalResultType
    val cleanRhs = new TreeTypeMap(
      oldOwners = List(dd.symbol),
      newOwners = List(summon[Context].owner)
    ).transform(dd.rhs)
    val resetParamMapping = remapParamMappingForTree(cleanRhs, pm)
    val body1 = transBody(cleanRhs, resetParamMapping, rType)
    val aType = body1.tpe.widen.argInfos.headOption.getOrElse(rType)
    ref(resetTransformedMethod).appliedToType(aType).appliedTo(body1)
  case _ => tree
```

`reset { ... }` の closure body も、呼び出し側スコープへ持ち込む時点で owner を更新する必要がある。
参照置換だけで済ませると、`reset` 内のローカル定義が外側スコープに混入した瞬間に owner chain がずれる。

### 5.3 CPS 引数のみメソッドの再帰変換

以下は CPS 変換プラグインにおける、戻り値は pure だが CPS 引数を取るメソッドを処理する実装例である。
`transPureBody` は、そのような body を再帰的に変換する関数である。

```scala
private[plugin] def transPureBody(rhs: Tree, pm: Map[Symbol, Symbol])(using Context): Tree =
  rhs match
    case Block(stmts, expr) =>
      tpd.Block(stmts.map(transformCpsReset(_, pm)), transPureBody(expr, pm))
    case If(cond, thenp, elsep) =>
      tpd.If(cond, transPureBody(thenp, pm), transPureBody(elsep, pm))
    case Match(sel, cases) =>
      tpd.Match(sel, cases.map(cd => tpd.CaseDef(cd.pat, cd.guard, transPureBody(cd.body, pm))))
    case Try(body, cases, finalizer) =>
      tpd.Try(
        transPureBody(body, pm),
        cases.map(cd => tpd.CaseDef(cd.pat, cd.guard, transPureBody(cd.body, pm))),
        finalizer
      )
    case _ =>
      val resetTree = transformCpsReset(rhs, pm)
      if resetTree ne rhs then resetTree
      else transformCpsExpr(rhs, pm)
```

呼び出し側:

```scala
else if hasCpsParam then
  val paramRemap = buildParamRemap(orig.symbol.paramSymss, paramss)
  val effectiveRhs = remapPureRhs(orig, transformedSym, paramRemap)
  val effectiveParamMapping = remapParamMappingForTree(effectiveRhs, paramRemap.effectiveMapping)
  transPureBody(effectiveRhs, effectiveParamMapping)
```

この経路でも、再帰変換に入る前に owner と純粋パラメータの対応を張り替えておく必要がある。

## 6. 応用パターン: `map` / `flatMap` に既存 body を流し込む

### 6.1 参照置換だけでは不十分な例

以下は CPS 変換プラグインにおける、bind ラムダを生成する実装例である。
`mkMap` と `mkFlatMap` は、既存 body を新しいラムダ owner の下に入れて `map` / `flatMap` を構築する関数である。

```scala
private[plugin] def mkFlatMap(cc: Tree, vSym: TermSymbol, body: Tree)(using Context): Tree =
  val A1 = body.tpe.widen.argInfos(0)
  val flatMapSym = controlContextClass.requiredMethod("flatMap")
  val lambdaTpe = MethodType(List(vSym.name))(_ => List(vSym.info), _ => body.tpe.widen)
  val lambda = tpd.Lambda(lambdaTpe, params =>
    body.subst(List(vSym), List(params.head.symbol))
  )
  cc.select(flatMapSym).appliedToType(A1).appliedTo(lambda)

private[plugin] def mkMap(cc: Tree, vSym: TermSymbol, body: Tree)(using Context): Tree =
  val A1 = body.tpe.widen
  val mapSym = controlContextClass.requiredMethod("map")
  val lambdaTpe = MethodType(List(vSym.name))(_ => List(vSym.info), _ => A1)
  val lambda = tpd.Lambda(lambdaTpe, params =>
    body.subst(List(vSym), List(params.head.symbol))
  )
  cc.select(mapSym).appliedToType(A1).appliedTo(lambda)
```

この `body.subst(...)` は参照 `vSym` を bind parameter に替えるだけで、`body` 内の local definition の owner は元スコープのまま残る。

### 6.2 `TreeTypeMap` を使った安全な実装

以下は同じ目的を `TreeTypeMap` で実装した例である。

```scala
private[plugin] def mkFlatMap(cc: Tree, vSym: TermSymbol, body: Tree)(using Context): Tree =
  val A1 = body.tpe.widen.argInfos(0)
  val flatMapSym = controlContextClass.requiredMethod("flatMap")
  val enclosingOwner = summon[Context].owner
  val lambdaTpe = MethodType(List(vSym.name))(_ => List(vSym.info), _ => body.tpe.widen)
  val lambda = tpd.Lambda(lambdaTpe, params =>
    val paramSym = params.head.symbol
    new TreeTypeMap(
      oldOwners = List(enclosingOwner),
      newOwners = List(paramSym.owner),
      substFrom = List(vSym),
      substTo = List(paramSym)
    ).transform(body)
  )
  cc.select(flatMapSym).appliedToType(A1).appliedTo(lambda)

private[plugin] def mkMap(cc: Tree, vSym: TermSymbol, body: Tree)(using Context): Tree =
  val A1 = body.tpe.widen
  val mapSym = controlContextClass.requiredMethod("map")
  val enclosingOwner = summon[Context].owner
  val lambdaTpe = MethodType(List(vSym.name))(_ => List(vSym.info), _ => A1)
  val lambda = tpd.Lambda(lambdaTpe, params =>
    val paramSym = params.head.symbol
    new TreeTypeMap(
      oldOwners = List(enclosingOwner),
      newOwners = List(paramSym.owner),
      substFrom = List(vSym),
      substTo = List(paramSym)
    ).transform(body)
  )
  cc.select(mapSym).appliedToType(A1).appliedTo(lambda)
```

`tpd.Lambda` は新しいラムダ symbol を作るが、既存 body を安全に持ち込む処理まではしてくれない。
既存 tree を返す側で `TreeTypeMap(...).transform(body)` をかける必要がある。

### 6.3 `ValDef.rhs` をラムダ owner に載せ替える

`ValDef.rhs` は一度 `ValDef` owner を親にして生成されるため、そのまま bind ラムダへ流すと owner がずれる。

以下は CPS 変換プラグインにおける、`ValDef.rhs` を現在 owner へ戻す実装例である。

```scala
private def rehomeFromValDefOwner(tree: Tree, oldOwner: Symbol)(using Context): Tree =
  new TreeTypeMap(
    oldOwners = List(oldOwner),
    newOwners = List(summon[Context].owner)
  ).transform(tree)
```

呼び出し側:

```scala
case (vd: ValDef) :: rest if isCpsSymAnnotated(vd.symbol) =>
  val vSym = vd.symbol.asTerm
  val rhs1 = rehomeFromValDefOwner(transformCpsRhs(vd.rhs, pm, rType), vSym)
  val bodyExpr = transBlock(rest, expr, pm, rType)
  liftedPureBody(bodyExpr) match
    case Some(pureBody) => mkMap(rhs1, vSym, pureBody)
    case None if isControlContextType(bodyExpr.tpe.widen) => mkFlatMap(rhs1, vSym, bodyExpr)
    case None => mkMap(rhs1, vSym, bodyExpr)
```

AST の変化:

```
変換前
Block owner=outer
  ValDef x owner=outer
    rhs:
      DefDef $anonfun owner=x
        ...
  body

rhs をそのまま map に渡すと
Lambda owner=map$lambda
  body:
    DefDef $anonfun owner=x
      ...

rehomeFromValDefOwner 後
Lambda owner=map$lambda
  body:
    DefDef $anonfun owner=map$lambda
      ...
```

## 7. `remapParamMappingForTree` が必要になる理由

`TreeTypeMap` によって `rhs` 内の symbol がコピーされると、もともとの `paramMapping: Map[Symbol, Symbol]` のキーが古い symbol を指したままになることがある。
そのため、変換後 tree と対応する parameter mapping を、変換呼び出し側が持っている一次情報から組み立て直す必要がある。

安全な方針は、`rhs` を scan して「同名同型だから同じ parameter だろう」と推測することではない。
caller は original method の parameter と transformed method の parameter を同じ順序で知っているので、その対応を先に明示的なペアとして作る。

以下は CPS 変換プラグインにおける、より安全な調整処理のイメージである。
pure parameter の対応を `TreeTypeMap.substFrom/substTo` に渡し、CPS parameter については後段の CPS rewrite 用 mapping に入れる。
こうすると nested lambda / local def / rebinding に同名同型の `body` が出ても、outer parameter の alias と誤認しない。

```scala
private case class ParamRemap(
  substFrom: List[Symbol],
  substTo: List[Symbol],
  cpsMapping: Map[Symbol, Symbol]
):
  def effectiveMapping: Map[Symbol, Symbol] =
    cpsMapping ++ cpsMapping.valuesIterator.map(sym => sym -> sym)

private def buildParamRemap(origParamss: List[List[Symbol]], newParamss: List[List[Tree]])(using Context): ParamRemap =
  val paired = origParamss.flatten.zip(newParamss.flatten.map(_.symbol))
  val (cpsPairs, purePairs) = paired.partition((origParam, _) => isCpsValType(origParam.info))
  ParamRemap(
    purePairs.map(_._1),
    purePairs.map(_._2),
    cpsPairs.toMap
  )
```

呼び出し側:

```scala
val paramRemap = buildParamRemap(orig.symbol.paramSymss, paramss)
val effectiveRhs = new TreeTypeMap(
  oldOwners = List(orig.symbol),
  newOwners = List(transformedSym),
  substFrom = paramRemap.substFrom,
  substTo = paramRemap.substTo
).transform(orig.rhs)
val effectiveParamMapping = remapParamMappingForTree(effectiveRhs, paramRemap.effectiveMapping)
transBody(effectiveRhs, effectiveParamMapping, rType)
```

`TreeTypeMap` の rehome 中や、さらに内側の `reset` closure を rehome する場面では、parameter 由来の symbol がコピーとして現れることがある。
その場合でも tree 全体を無条件に name/type で拾い直すのではなく、scope-aware に辿る。
nested `DefDef` や block-local `val`、pattern binder が宣言した同名 symbol は shadowing として扱い、その名前では alias 推測をしない。
一方、同じ nested body の中で shadow されていない別の outer parameter 由来コピーは、補助的に mapping へ加えてよい。

過去の実装では、変換後 tree を traverse して、`name` と `info` が一致する CPS symbol を alias とみなす方法を使っていた。
これは simple case では動くが、shadowing された同名同型 symbol も outer CPS parameter として拾ってしまう危険がある。
`TreeTypeMap` の役割は owner の rehome と必要な参照置換に限定し、parameter mapping は original/transformed parameter の明示的な対応から作るのが安全である。

## 8. `tpd.Lambda` との使い分け

`tpd.Lambda` 自体は新しいラムダ symbol を作るので、その場で組み立てる body なら通常は安全である。

```scala
val lambda = tpd.Lambda(lambdaTpe, params => ...)
```

ただし `params => ...` の中で既存 tree をそのまま返すと危ない。
既存 tree を別 owner に持ち込むなら、body 側で `TreeTypeMap` を使う。

整理すると次の通り。

- 新規に作るラムダ枠: `tpd.Lambda`
- 既存 tree を別 owner に持ち込む: `TreeTypeMap`

## 9. よくあるミス

| ミス | 何が起きるか | 対策 |
| --- | --- | --- |
| `body.subst(...)` だけで bind ラムダへ押し込む | 参照だけ更新され、local definition の owner が古いまま残る | `TreeTypeMap` で owner と参照を同時に更新する |
| `ValDef.rhs` を owner 移動せずに `mkMap` / `mkFlatMap` へ渡す | `ValDef` owner 配下の subtree が新ラムダ内部に混入する | `rehomeFromValDefOwner` のように現在 owner へ戻す |
| `TreeTypeMap` 後の `rhs` に対して古い `paramMapping` だけを使う | 変換後 tree に現れる symbol と mapping のキーがずれる | original/transformed parameter pairing から mapping を明示的に作り、必要な rehome コピーだけを scope-aware に補助 mapping へ加える |
| `tpd.Lambda` が body の owner まで直してくれると思い込む | 新規ラムダ symbol だけ作られ、既存 tree の owner は直らない | 既存 body 側で `TreeTypeMap` を適用する |

## 10. デバッグ方法

### 10.1 `-Xprint:lambdaLift` を見る

owner 崩れを疑うときは `lambdaLift` 出力を見るのが早い。

```scala
-Xprint:lambdaLift
```

確認ポイント:

- 問題の `$anonfun` がどの `DefDef` 配下に出力されているか
- 参照される local symbol の proxy がどの owner に生えているか
- ある `DefDef` の中にいるのに、owner が別メソッドのままの定義がないか

### 10.2 owner を直接出力する

もっとも手早いデバッグプリントはこれである。

```scala
report.inform(
  s"symbol=${tree.symbol.showFullName}, owner=${tree.symbol.owner.showFullName}",
  tree.srcPos
)
```

`DefDef`, `ValDef`, `Ident` を traverser で見ながら出すと、どこで owner が古いまま残っているか追跡しやすい。
必要なら `transformCpsReset`, `mkTransformedImpl`, `mkMap`, `mkFlatMap` の直前直後で比較するとよい。

## 11. Scala 2 で近いことを行う API

Scala 2 では似た処理を複数 API の組み合わせで行う。

```scala
// 参照置換
val body1 = (new TreeSymSubstituter(List(oldSym), List(newSym)))(body)

// owner 変更
val body2 = body1.changeOwner(currentOwner -> newOwner)

// owner を変えた状態での再構築
atOwner(sym) {
  transform(rhs)
}
```

Scala 3 の `TreeTypeMap` は、この「subst」と「owner の再 home」を一度に行う実用 API と捉えると分かりやすい。
