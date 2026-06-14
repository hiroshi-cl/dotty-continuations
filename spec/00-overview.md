# 00 全体アーキテクチャ概観

## プロジェクト概要

dotty-continuations は Scala 3 向けのコンパイラプラグインで、`shift` / `reset` によるデリミテッド継続（delimited continuations）を選択的 CPS 変換（selective CPS transformation）によって実現する。

選択的 CPS 変換とは、プログラム全体を変換するのではなく、`reset` のスコープ内で `shift` を含む式だけを継続渡しスタイル（CPS: Continuation-Passing Style）に変換する手法である。ユーザは通常の直接スタイル（direct style）で `shift` / `reset` を記述し、コンパイラプラグインがそれを `ControlContext[A, R]` モナドによる CPS 表現へと変換する。変換後のコードはライブラリモジュールが提供するクラスと関数のみで動作する。

## モジュール構成

この仕様書（プラグイン内部）が中心的に扱うのは `library`（公開 API・ランタイム基盤）と `plugin`（変換エンジン）の2モジュールである。プロジェクト全体のモジュール構成（テスト・sandbox を含む）は [06-architecture.md](06-architecture.md) を参照。

`library` モジュールはランタイム基盤と公開 API を提供する。`ControlContext[+A, R]` は変換後の計算をランタイムで表現するクラスで、成功継続 `A => R` と例外継続 `Exception => R` を受け取って最終結果 `R` を返す関数を保持する。`map` / `flatMap` / `foreach` / `flatMapCatch` による合成が可能で、CPS 変換後の式を Scala の for-comprehension 風に連結するために使われる。`CpsTransform[R]` はコンパイル時の文脈マーカー型で、`shift` を呼べる場所を型レベルで制限する。`CpsSym` はアノテーション型で、変換対象シンボルにコンパイラプラグインが付与する。公開 API 関数 `shift` と `reset` の実装は `???` であり、未変換のまま実行されると `NotImplementedError` になる。変換後に実際に呼ばれるのは `shift$transformed` と `reset$transformed` である。

`plugin` モジュールはコンパイラプラグイン本体である。プラグインクラス `SelectiveCPSPlugin`（name: `"continuations"`）が `StandardPlugin` を継承し、3つのフェーズを Scala 3 コンパイラへ登録する。共通ユーティリティ trait `CPSUtils` は、シンボル解決（`CpsSymbols`）・型変換（`CpsTypeOps`）・CPS 適格性判定（`CpsEligibility`）・ローカル CPS 格納規則（`LocalCpsStorageOps`）を合成するファサードで、各フェーズの実装クラスが mixin する。

## フェーズパイプライン

フェーズは以下の順序で Scala 3 コンパイラパイプラインに挿入される。

```
posttyper → [selectivecpsstub] → pickler → ... → [selectiveanf] → [selectivecps] → elimByName
```

> Scala 3 コンパイラ標準フェーズの補足: `posttyper` は型付け後の後処理、`pickler` は TASTy 直列化、`firstTransform` は最初のバックエンド変換、`elimByName` は by-name 引数の展開を担う。角括弧内（`[selectivecpsstub]` 等）がこのプラグインが挿入するフェーズを表す。

`SelectiveCPSPlugin.initialize` が返すリスト順は `SelectiveCPSStubPhase`、`SelectiveANFTransform`、`SelectiveCPSTransform` の3つである。

### フェーズ 1: SelectiveCPSStubPhase（phaseName: `selectivecpsstub`）

```scala
runsAfter = Set("posttyper")
runsBefore = Set("pickler")
```

入力: 型付け済み（typed）の構文木。出力: 変換対象の `def` とメンバー `val` に対して `$transformed` という名前の suffix を持つスタブシンボルを同一テンプレート内に追加した構文木。

このフェーズはシンボル生成とシグネチャ（ABI）確定のみを行い、本体の CPS 変換は一切行わない。`pickler` より前に走ることで、生成した `$transformed` シンボルが pickle 対象に含まれ、別コンパイルユニットからも参照できるようになる。スタブ本体は non-deferred の場合 `Predef.undefined` を置き、後続フェーズが実装で置き換える。

### フェーズ 2: SelectiveANFTransform（phaseName: `selectiveanf`）

```scala
runsAfter = Set("pickler")
runsBefore = Set("firstTransform")
```

入力: pickle 済みの構文木。出力: `CpsTransform[R]` パラメータを持つ `DefDef` の本体が A 正規形（ANF）に変換された構文木、および不正な `shift` 使用の診断。

このフェーズは2段階で処理する。第1段階では `ANFBodyTransformer` が CPS 式を `@CpsSym` 付き synthetic `ValDef` に抽出し、後続の CPS 変換フェーズが扱いやすい形に正規化する。具体的には、引数位置・selector 位置・qualifier 位置（inline 位置）に現れる CPS 式を一時変数へ抜き出す。tail 位置の CPS 式は抽出しない。第2段階では `transformPackageDef` が残留 CPS 式の不正使用（`reset` の外での `shift` 使用、未対応の `while` / `finally` 内 CPS 式など）を診断してエラーを報告する。

### フェーズ 3: SelectiveCPSTransform（phaseName: `selectivecps`）

```scala
runsAfter = Set(SelectiveANFTransform.name)  // "selectiveanf"
runsBefore = Set("elimByName")
```

入力: ANF 化済みの構文木（`$transformed` スタブは ABI が確定済み）。出力: `shift` / `reset` を含む関数本体が `ControlContext[A, R]` モナド連鎖（`flatMap` / `map` / `foreach`）へ変換された構文木。

このフェーズが変換の本体である。`relaxedTypingInGroup = true` により、変換中は元型と変換後型が一時的に混在する構文木を扱う。内部は5つの trait mixin（`LocalTransformRegistryOps`（変換計画の登録・参照）、`TransformedImplBuilderOps`（`$transformed` 実装の組み立て）、`CpsBodyTransformOps`（本体の CPS 変換規則）、`CallsiteRewriteOps`（呼び出し側の書き換え）、`LocalValueRewriteOps`（ローカル値定義の書き換え））で責務を分担する。`$transformed` スタブ本体を実装で置き換え、元定義の本体は `Predef.undefined` で無効化する（ただし member CPS val の元 RHS は `Literal(null).cast(originalType)` で置換する）。変換完了後に最終検査パスを走らせ、変換漏れ（`"CPS value not transformed"`、`"CPS expression not transformed"`）を診断する。

## コンポーネント間の主要なデータフロー

### `$transformed` メソッドの ABI

`SelectiveCPSStubPhase` が確定させる `$transformed` シンボルの名前・型・フラグが後続フェーズへ渡す ABI である。名前は元シンボル名に `"$transformed"` を連結した `TermName`。型は `transformCpsMethodType(origSym.info)` で変換された型で、`CpsTransform[R]` marker parameter を除去し、最終結果型を `ControlContext[A, R]` に変換したものになる。フラグは `GivenOrImplicit` を除去して `Synthetic` を付与する。このシンボルは `pickler` 前に scope に `entered` されるため、別ユニットからも `decl("name$transformed")` で参照できる。

### `@CpsSym` アノテーションの流れ

ANF フェーズが CPS 式を抽出するとき、生成した synthetic `ValDef` のシンボルに `@CpsSym`（`continuations.CpsSym`）を付与する（`addCpsAnnotation`）。CPS フェーズはこのアノテーション（`isCpsSymAnnotated`）を手掛かりに `transBlock` 内で CPS 変換が必要な `ValDef` を識別し、`flatMap` / `map` 連鎖を構築する。

### CPS 適格性判定の利用フェーズ

`CPSUtils` を通じて提供される判定述語は複数のフェーズにまたがって共有される。`needsTransformedStub` はスタブ生成要否の判定で `SelectiveCPSStubPhase` と `SelectiveCPSTransform`（`collectTransformedPlans`）の両方から呼ばれる。`isCpsValType` は ANF フェーズの残留 CPS 式診断と CPS フェーズの型変換の両方で使われる。`isCpsTransformType` は ANF フェーズの `isCpsExpr` 判定と CPS フェーズの呼び出し側書き換えの両方で使われる。

### `LocalTransformRegistry` によるユニット内状態管理

`SelectiveCPSTransform` はユニット処理の開始時に `collectTransformedPlans` で変換対象の `DefDef` と `ValDef` の計画を事前収集し、`LocalTransformPlan` / `LocalValTransformPlan` として registry に保持する。各フェーズフック（`transformDefDef`、`transformStats`、`transformTemplate` 等）はこの registry を参照して変換を進める。状態はユニット境界で必ず `clearLocalTransformState` によりクリアされる。

## 設計原則

### 型情報に基づく判定（中核）と synthetic 名の利用

CPS 変換対象の判定中核は型情報ベースである。`isCpsValType`、`isCpsTransformType`、`isCpsTransformFunctionType` などの型判定述語が主たる判断基準であり、変換フェーズの分岐はこれらに依拠する。

ただし synthetic シンボルに対しては名前文字列の参照も限定的に存在する。具体的には `CpsEligibility` 内での `$anonfun` / `$transformed` 付きシンボルの除外ガード、`SelectiveCPSTransform` 内での `"apply"` マッチ、`CallsiteRewrite` 内での `"$transformed"` 名生成がこれに該当する。これらは CPS 適格性・変換判定ではなく、synthetic シンボルの除外と命名のためである。「中核判定は型駆動／synthetic 名の除外・命名には文字列利用あり」という区別が実装の実態に即した表現である。

### pickling 前の ABI 確定と設計分離

`$transformed` シンボルとその型シグネチャを pickling より前（`SelectiveCPSStubPhase`）に確定させることには2つの目的がある。第1に、インクリメンタルコンパイルや別コンパイルユニットをまたぐ参照が成立する（シグネチャが TASTy に含まれるため）。第2に、TASTy が意味論的変換を表現する中間表現であるという Scala 3 の設計原則に従い、意味論的変換（pickler 前フェーズ）とバックエンド実装詳細（pickler 後フェーズ）を分離できる。

スタブ本体（`???`）を pickler 前に置き、CPS 変換済みの実装本体を pickler 後に生成するこの構造は、`@inline` メソッドがシグネチャと展開用情報を TASTy に持ち、実装は classfile に生成するパターンと類比できる。

### ANF 正規化による変換の簡略化

CPS 変換フェーズへの入力を A 正規形に限定することで、変換本体（`CpsBodyTransform`）が扱う構文パターンを削減し、正確な `flatMap` / `map` 連鎖の生成を可能にする。

### CPS 状態管理のユニット境界リセット

ANF フェーズは CPS 式を抽出した synthetic `ValDef` のシンボルに `@CpsSym` アノテーション（`continuations.CpsSym`）を付与することで状態を木構造属性として伝搬する。CPS フェーズ（`SelectiveCPSTransform`）は変換対象の `DefDef` / `ValDef` を `LocalTransformPlan` / `LocalValTransformPlan` として `mutable.HashMap` に登録し、`LocalTransformRegistryOps` を通じて管理する。この HashMap は `prepareForUnit` 内の `clearLocalTransformState()` によって CompilationUnit ごとに必ずリセットされ、フェーズをまたいで状態が持ち越されることはない。

### answer type modification の削除（B=C=R 固定）

scala-continuations の `@cpsParam[B,C]` は B≠C の answer type modification をサポートするが、このプロジェクトは B=C=R に固定している。これにより `ControlContext[A, R]` の型パラメータが2つになり、ユーザー向けの型注釈が単純化される。Scala の型システムでは B≠C の注釈が煩雑になるためこの削減は実質的な利益をもたらす。

### ランタイム表現の分離

変換後の計算は `ControlContext[A, R]` モナドとして `library` モジュールに閉じており、コンパイラプラグインはこの型を生成するだけでランタイム動作に関与しない。

## 文書構成

各論仕様は以下のファイルに記述されている。

- [01-public-api.md](01-public-api.md) — `ControlContext`・`CpsTransform`・`CpsSym`・`shift`/`reset` などライブラリ公開 API の仕様
- [02-stub-phase.md](02-stub-phase.md) — `SelectiveCPSStubPhase` の詳細仕様（スタブ ABI 規則・アルゴリズム・エラー診断）
- [03-anf-phase.md](03-anf-phase.md) — `SelectiveANFTransform` の詳細仕様（ANF 化変換規則・残留 CPS 式診断）
- [04-cps-phase.md](04-cps-phase.md) — `SelectiveCPSTransform` の詳細仕様（CPS 変換規則・呼び出し側書き換え・エラー診断）
- [05-shared-infrastructure.md](05-shared-infrastructure.md) — `SelectiveCPSPlugin`・`CPSUtils`・`CpsSymbols`・`CpsTypeOps`・`CpsEligibility`・`LocalCpsStorageOps` の仕様
