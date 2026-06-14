# scala-continuations (Scala 2) SelectiveANFTransform / SelectiveCPSTransform ロジック整理

`~/github/scala-continuations` のロジック整理。Scala 2 実装の参照用。

---

## 全体アーキテクチャ

```
ソースコード
  └─ Typer (Scala 2 フロントエンド)
       │  CPSAnnotationChecker が型付け中に @cps アノテーションを伝播させる
       │  → 式の型に @cpsParam[B,C] アノテーションが付与される
       ▼
Phase: selectiveanf (SelectiveANFTransform)
  │  対象: @cps アノテーション付き DefDef / Function / ValDef / Apply
  │  目的: CPS 式を val に抽出して後続フェーズを簡略化
  ▼
Phase: selectivecps (SelectiveCPSTransform + InfoTransform)
  │  対象: @cpsSym アノテーション付き ValDef のある Block
  │       + Try (CPS あり) + shift/shiftUnit/reify 呼び出し
  │  目的: CPS body を ControlContext[A,B,C] を返す式に書き換える
  │       + 全シンボルの型を transformCPSType で書き換える (InfoTransform)
  ▼
後続フェーズ
```

---

## ライブラリ型・ヘルパー

### アノテーション (CPSUtils)

| アノテーション | 役割 |
|---|---|
| `@cpsParam[B,C]` (`MarkerCPSTypes`) | 型レベル CPS マーカー。`A @cpsParam[B,C]` = "A を生成するが reset 後の型は C、継続型は B" |
| `@cps[A]` | `cpsParam[A,A]` の別名 |
| `@cpsSym` (`MarkerCPSSym`) | ANF フェーズが ValDef シンボルに付与。CPS 変換が必要な val を印す |
| `@cpsSynth` (`MarkerCPSSynth`) | ANF が合成した @cps アノテーションに付与。後で除去可能 |
| `@cpsPlus` (`MarkerCPSAdaptPlus`) | 型推論中の暫定マーカー (shiftUnit 挿入候補) |
| `@cpsMinus` (`MarkerCPSAdaptMinus`) | by-value 位置でのアノテーション除去マーカー |

`CPSInfo = Option[(Type, Type)]` — `Some((B, C))` = `@cpsParam[B,C]` の有無

### ControlContext[+A, -B, +C]

| メソッド | シグネチャ | 役割 |
|---|---|---|
| `fun` | `(A=>B, Exception=>B) => C` | 継続を受け取る関数 |
| `map[A1]` | `(A=>A1): ControlContext[A1,B,C]` | 純粋変換 |
| `flatMap[A1,B1,C1<:B]` | `(A=>ControlContext[A1,B1,C1]): ControlContext[A1,B,C1]` | CPS bind |
| `foreach` | `(f: A=>B): C` | reset 実行 |
| `flatMapCatch` | `(pf: PartialFunction[Exception, CC])` | try-catch |
| `mapFinally` | `(f: ()=>Unit)` | try-finally |

### ヘルパー関数

| 関数 | 変換後シンボル | 役割 |
|---|---|---|
| `shift[A,B,C](fun)` | `MethShift` → `MethShiftR` | shift (ソース → ランタイム) |
| `shiftUnit[A,B,C](x)` | `MethShiftUnit` → `MethShiftUnitR` | pure lift |
| `shiftUnit0[A,B](x)` | `MethShiftUnit0` → `MethShiftUnitR` | pure lift (B=C 専用) |
| `reify[A,B,C](ctx)` | `MethReify` → `MethReifyR` | A @cpsParam → ControlContext |
| `reset[A,C](ctx)` | (変換なし、ControlContext#foreach に展開) | リセット |
| `shiftUnitR[A,B](x)` | ランタイム | ControlContext のコンストラクタ |
| `shiftR[A,B,C](fun)` | ランタイム | ControlContext のコンストラクタ |

---

## Phase 0 (型付け中): CPSAnnotationChecker

Typer のプラグインとして動作し、型推論中に @cps アノテーションを式ツリーに伝播させる。

### pluginsTyped — match ケース

型付けされた式の型を修正して @cps アノテーションを伝播する。

| ケース | 処理 |
|---|---|
| `Apply(fun @ Select(qual,name), args)` | `transChildrenInOrder(qual :: args)` |
| `Apply(TypeApply(fun @ Select(qual,name), targs), args)` | `transChildrenInOrder(qual :: args)` |
| `TypeApply(fun @ Select(qual,name), args)` | `transChildrenInOrder(List(qual, fun))` |
| `Apply(fun, args)` | `transChildrenInOrder(fun :: args)` |
| `TypeApply(fun, args)` | `transChildrenInOrder(List(fun))` |
| `Select(qual, name)` | qual が @cps なら子から伝播。MethodType 等はスキップ |
| `If(cond, thenp, elsep)` | cond は inline、thenp/elsep は by-name として伝播 |
| `Match(select, cases)` | select は inline、cases body は by-name として伝播 |
| `Try(block, catches, finalizer)` | block・catches は by-name。finalizer は除外。`atp0 =:= atp1` のみ許可 |
| `Block(stms, expr)` | stms の ValDef.rhs / Assign.rhs / その他を inspect |
| `ValDef(...)` | シンボルから @cps を除去 (ValDef シンボルには付けない) |
| `_` | tpe をそのまま返す |

### canAdaptAnnotations / adaptAnnotations

- `typingExprNotValue` かつアノテーション不足 → `@plus @cps` を追加 (shiftUnit 挿入候補)
- `typingExprByValue` かつ @cps あり → `@minus` を追加 (by-value 渡し時に除去)
- `inReturnExpr` かつアノテーション不足 → `@plus` を追加

---

## Phase 1: SelectiveANFTransform

### ANFTransformer.transform — トップレベル match ケース

| ケース | 処理 | 備考 |
|---|---|---|
| `DefDef(_, name, _, _, tpt, rhs)` | `transExpr(rhs, None, getExternalAnswerTypeAnn(tpt.tpe))` | cpsParamTypes が戻り型にある場合のみ ANF 化 |
| `Function(vparams, body)` | body の型から ext を取り出し、純粋 Match は special-case で変換 | 下記参照 |
| `ValDef(mods, name, tpt, rhs)` | `transExpr(rhs, None, None)` | @cps 付き ValDef はエラー |
| `TypeTree()` | `super.transform` | cpsAllowed を迂回 |
| `Apply(_, _)` | `transExpr(tree, None, None)` | reset { ... } を object コンストラクタ内で許可する特殊ケース |
| `_` | `hasAnswerTypeAnn` があれば `cpsAllowed` チェック後 `super.transform` | |

**Function の特殊ケース (pure body + 外部 @cps 期待):**

| ケース | 処理 |
|---|---|
| `Match(selector, cases)` | 各 case body を `transExpr(body, None, ext)` |
| `Block(List(selDef:ValDef), Match(...))` | virtpatmat switch パターン |
| `Block(matchStats@((selDef:ValDef)::cases), matchEnd)` where all have synth case symbol | virtpatmat パターン |
| `Block(List(selDef0:ValDef), Block(matchStats, matchEnd))` | virtpatmat with separate scrut |
| `_` | `transExpr(body, None, ext)` |

### transValue — match ケース

`(stmts, expr, spc: CPSInfo)` を返す。spc は stmts の後・expr の前の CPS 状態。

| ケース | 処理 | 備考 |
|---|---|---|
| `Block(stms, expr)` | `transBlock(stms, expr, cpsA2, cpsR2)` で再帰 | (Nil, block, cpsA) を返す |
| `If(cond, thenp, elsep)` | cond を `transInlineValue`、両分岐を `transExpr` | hasSynthMarker で spc 伝播を制御 |
| `Match(selector, cases)` | selector を `transInlineValue`、各 case body を `transExpr` | hasSynthMarker で制御 |
| `LabelDef(name, params, rhs)` | @cps あり → DefDef に変換して stmts に追加 | while / パターンマッチのラベル |
| `Try(block, catches, finalizer)` | `transExpr(block, cpsA, cpsR)`、各 catch を変換 | finalizer は `(None, None)` (CPS 不可) |
| `Assign(lhs, rhs)` | rhs のみ `transInlineValue` | lhs は変換しない |
| `Return(expr)` | `isAnyParentImpure` ならエラー。`transInlineValue(expr)` | |
| `Throw(expr)` | `transInlineValue(expr)` | |
| `Typed(expr, tpt)` | `transInlineValue(expr)`、tpt から @cps 除去 | |
| `TypeApply(fun, args)` | `transInlineValue(fun)` | |
| `Select(qual, name)` | `transInlineValue(qual)` | |
| `Apply(fun, args)` | `transInlineValue(fun)`、`transArgList(fun, args, funSpc)` | by-name 引数は別処理 |
| `_` | `cpsAllowed = true`、`(Nil, transform(tree), cpsA)` | |

### transTailValue

`transValue` を呼んで spc と cpsR を照合し、必要なら `shiftUnit` 挿入 / エラー報告。

- `cpsR.isDefined && !bot.isDefined` → `shiftUnit[A,B,C](expr)` を挿入
- `!cpsR.isDefined && bot.isDefined` → エラー "found cps expression in non-cps position"
- 両方一致 → そのまま

### transInlineValue

`transValue(tree, cpsA, None)` を呼び、結果に @cps アノテーションがあれば:
- fresh `val $tmpN` を作成 (`@cpsSym` アノテーション付き)
- `(stmts :+ ValDef(tmp, expr), Ident(tmp), linearize(spc, spcVal))`

### transInlineStm (ステートメント位置)

| ケース | 処理 |
|---|---|
| `ValDef(mods, name, tpt, rhs)` | `atOwner(sym) { transValue(rhs, cpsA, None) }` で rhs 変換。結果に @cps があれば `@cpsSym` を sym に付与 |
| `_` | `transInlineValue(stm, cpsA)` |

### transBlock

再帰的に stats を `transInlineStm` で処理、expr を `transTailValue` で処理。

**LabelDef 特殊処理 (virtpatmat):**
`anfStats` が全て定義でない (または synth case symbol) で DefDef が含まれる場合、
DefDef を逆順に並べ直して先頭 DefDef を呼び出すコードを生成。

### RemoveTailReturnsTransformer

CPS 戻り型を持つメソッドの末尾 `Return(expr)` を `expr` に置き換えるプリパス。

match ケース:

| ケース | 変換 |
|---|---|
| `Block(stms, Return(expr))` | `Block(stms, expr)` |
| `Block(stms, expr)` | `Block(stms, transform(expr))` |
| `If(cond, Return(t), Return(e))` / 片方のみ | 両方 transform |
| `Try(block, catches, finalizer)` | 各部 transform |
| `CaseDef(pat, guard, Return(expr))` | `CaseDef(pat, guard, expr)` |
| `CaseDef(pat, guard, body)` | body transform |
| `Return(_)` (非末尾) | エラー "return expressions in CPS code must be in tail position" |
| `_` | `super.transform` |

---

## Phase 2: SelectiveCPSTransform

### InfoTransform: transformCPSType

全シンボルの型を再帰的に書き換える。

| ケース | 変換 |
|---|---|
| `PolyType(params, res)` | res を再帰変換 |
| `NullaryMethodType(res)` | res を再帰変換 |
| `MethodType(params, res)` | res を再帰変換 |
| `TypeRef(pre, sym, args)` | args を再帰変換 |
| `_` with `getExternalAnswerTypeAnn` → `Some((res, outer))` | `ControlContext[removeAllCPS(tp), res, outer]` に変換 |
| `_` | `removeAllCPSAnnotations(tp)` |

### mainTransform — match ケース

| ケース | 処理 | 備考 |
|---|---|---|
| `Apply(TypeApply(fun, targs), args)` where `fun.symbol == MethShift` | `shiftR` に置き換え | shift → shiftR |
| `Apply(TypeApply(fun, targs), args)` where `fun.symbol == MethShiftUnit` | `shiftUnitR` に置き換え | shiftUnit → shiftUnitR |
| `Apply(TypeApply(fun, targs), args)` where `fun.symbol == MethReify` | `reifyR` に置き換え | reify → reifyR |
| `Try(block, catches, finalizer)` | 下記参照 | |
| `Block(stms, expr)` | `transBlock(stms, expr)` | |
| `_` | `super.transform(tree)` | |

**Try の変換 (CPS あり):**
1. `block1 = transform(block)` → `(stms, expr1)` に分解
2. `targettp = transformCPSType(tree.tpe)` (= ControlContext 型)
3. catches を PartialFunction に変換: `val $catches = { e => e match { case ... } }`
4. `expr2 = expr1.flatMapCatch($catches)` を生成
5. 実際の catch を `if ($catches.isDefinedAt($ex)) $catches($ex) else throw $ex` に変換
6. finalizer は変換するが `mapFinally` は **現在無効化** (コメントアウト)

**Try の変換 (CPS なし):** そのまま再帰変換

### transBlock — match ケース

```
stms match
  case Nil → transform(expr)

  case vd @ ValDef(mods, name, tpt, rhs) :: rest
    if vd.symbol.hasAnnotation(MarkerCPSSym) →
      rhs を transform → rhs1 (= ControlContext 型の式)
      transBlock(rest, expr) → (bodyStms, bodyExpr)
      変換:
        specialCaseTrivial 判定:
          bodyExpr が Apply(fun, _) かつ Context 型かつ currentMethod == fun.symbol
          → テールコール最適化: if (rhs1.isTrivial) { val v = rhs1.getTrivialValue; ... } else { rhs1.flatMap { v => ... } }
        それ以外:
          body が Context 型なら → rhs1.flatMap { v => ... }
          そうでなければ         → rhs1.map { v => ... }

  case _ :: rest →
    transform(stm) :: transBlock(rest, expr)
```

---

## 既知の問題・制限

| # | 場所 | 問題 |
|---|---|---|
| 1 | `Try` (CPSTransform) | `mapFinally` は無効化。finally ブロックが通常パスでも実行されてしまうバグがある (コメント参照) |
| 2 | `LabelDef` (ANFTransform) | "utterly broken" コメントあり。while/パターンマッチの LabelDef が複数あるとき連鎖変換が不正確 |
| 3 | `transBlock` (CPSTransform) | `TreeSymSubstituter + ChangeOwnerTraverser` が複数回走り指数時間になる可能性がある |
| 4 | `transValue` の `_` | `cpsAllowed = true` で黙過されるノードが多い |
| 5 | `Try` (AnnotationChecker) | `atp0 =:= atp1` しか許可しない (answer type modification 不可) |
| 6 | `adaptBoundsToAnnotations` | 関数型・by-name 型のみ処理。その他の型パラメータ境界は処理しない |
| 7 | `transArgList` (ANFTransform) | by-name 引数の扱いが複雑。`overshoot` (過剰な引数) のケースで NoType が使われる |