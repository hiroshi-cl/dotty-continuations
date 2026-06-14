# 06 プロジェクトアーキテクチャ概要

## 1. プロジェクト概要

dotty-continuations は、Scala 3（dotty）向けのコンパイラプラグインである。`shift` / `reset` による選択的 CPS（Continuation Passing Style）変換を Scala 3 に追加する。

ユーザは `reset { ... }` ブロックと `shift[A, R] { k => ... }` を直接スタイルで記述する。コンパイラプラグインがコンパイル時に対象コードを選択的に CPS 形式へ変換し、ランタイムは `ControlContext[A, R]` を使って継続を表現する。プラグインが適用されないコードは変換されない（選択的変換）。

## 2. モジュール構成

| sbt lazy val | ディレクトリ | 役割 |
|---|---|---|
| `library` | `library/` | 公開 API ライブラリ（`shift`・`reset`・`ControlContext`・`CpsTransform`・`CpsSym`） |
| `plugin` | `plugin/` | dotty-continuations コンパイラプラグイン本体（3フェーズの CPS 変換実装） |
| `integrationTests` | `integration-tests/` | プラグイン適用ありの統合テスト（正常系） |
| `integrationTestsNeg` | `integration-tests-neg/` | negative compilation tests（コンパイルエラーを期待するテスト） |
| `reifyReflect` | `sandbox/reify-reflect/core/` | reify/reflect モナド抽象の core（`CpsMonad` + 標準インスタンス） |
| `reifyReflectCats` | `sandbox/reify-reflect/cats/` | Cats バックエンドアダプタ |
| `reifyReflectScalaz` | `sandbox/reify-reflect/scalaz/` | Scalaz バックエンドアダプタ |
| `reifyReflectZio` | `sandbox/reify-reflect/zio/` | ZIO バックエンドアダプタ |
| `shiftReset` | `sandbox/shift-reset/` | shift/reset 利用例（abort/around/suspend パターンデモ） |
| `root` | `.` | 集約プロジェクト（publish しない） |

## 3. モジュール依存グラフ

`dependsOn` 関係のみを示す（外部ライブラリ依存は § 2 を参照）。

```
library
├─ plugin
├─ integrationTests
├─ integrationTestsNeg
├─ reifyReflect
│  ├─ reifyReflectCats
│  ├─ reifyReflectScalaz
│  └─ reifyReflectZio
└─ shiftReset
```

- `library` はプロジェクト内の他モジュールに依存しない。
- `plugin` は `library` に `dependsOn` するが、`scala3-compiler` は `provided` スコープで取得する。
- `reifyReflect` 配下の各バックエンド（cats/scalaz/zio）は `reifyReflect`（core）にのみ `dependsOn` し、`library` への直接依存はない。

`root` は集約プロジェクトであり、以下を `aggregate` する（`integrationTestsNeg` は `aggregate` 対象外だが `negTest` タスク経由で呼び出される）。

```
root.aggregate:
  library, plugin, integrationTests,
  reifyReflect, reifyReflectCats, reifyReflectScalaz, reifyReflectZio,
  shiftReset
```

## 4. コンパイラプラグインの配線

Scala 3 コンパイラではプラグイン jar を `-Xplugin:<path>` という `scalacOptions` エントリで渡す。このプロジェクトでは `addCompilerPlugin` は使われていない。プラグインを適用するモジュールは `plugin / Compile / packageBin` で自前プラグイン jar をビルドし、その絶対パスを `-Xplugin:` に渡す。

```scala
scalacOptions ++= {
  val jar = (plugin / Compile / packageBin).value
  Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
}
```

`-J-Dts=${jar.lastModified()}` は jar 更新時刻を JVM システムプロパティとして渡すことでコンパイルキャッシュを無効化するためのものと解釈される。

この `scalacOptions` 配線が設定されているモジュール:

- `integrationTests`
- `reifyReflect`
- `reifyReflectCats`
- `reifyReflectScalaz`
- `reifyReflectZio`
- `shiftReset`

`integrationTestsNeg` は `-Xplugin:` ではなく、テスト実行 JVM に以下のシステムプロパティを渡す形でプラグイン jar を供給する。

```
-Dneg.pluginJar=<絶対パス>
-Dneg.libraryClasspath=<ライブラリクラスパス>
-Dneg.testClasspath=<テストクラスパス>
-Dneg.fixturesDir=<テストフィクスチャディレクトリ>
```

これらのプロパティ（プラグイン jar・ライブラリクラスパス・テストクラスパス・フィクスチャディレクトリ）を受け取ったテストが、フィクスチャをプラグイン適用下でコンパイルし、期待されるコンパイルエラーが出ることを検証する。`integrationTestsNeg` は `root.aggregate(...)` には含まれず、`root` の `negTest` タスクから呼ばれる。

## 5. コンパイルパイプライン概観

プラグインのエントリポイントは `SelectiveCPSPlugin`（`dotty.tools.dotc.plugins.StandardPlugin` を継承）であり、プラグイン名 `"continuations"`、説明 `"applies selective CPS conversion (shift/reset)"` を持つ。`initialize` が以下の順序で3つの `PluginPhase` を返し、Scala 3 コンパイラのパイプラインに挿入される。

詳細は [05-shared-infrastructure.md](05-shared-infrastructure.md)（フェーズ挿入位置の `runsAfter`/`runsBefore`）および [00-overview.md](00-overview.md)（パイプライン全体像）を参照。

### フェーズ 1: SelectiveCPSStubPhase（stub フェーズ）

`reset { ... }` の本体を走査し、CPS 変換が必要な定義に対して `$transformed` サフィックスを持つスタブシンボルを生成する。後続フェーズが参照できる ABI（Application Binary Interface）を確定させる役割を担う。詳細は [02-stub-phase.md](02-stub-phase.md) を参照。

### フェーズ 2: SelectiveANFTransform（ANF フェーズ）

CPS 変換対象の式を ANF（A-Normal Form）に正規化する。複合式を単純な let 束縛の列に分解し、後続の CPS 変換が扱いやすい形に整える。構文形ごとの変換規則の詳細は [03-anf-phase.md](03-anf-phase.md) を参照。

### フェーズ 3: SelectiveCPSTransform（CPS フェーズ）

ANF 化された本体を実際の CPS 形式へ変換する。`shift` / `reset` 呼び出しを `shift$transformed` / `reset$transformed` および `ControlContext` を使った CPS 表現に書き換え、呼び出し側・ローカル値定義側も整合させる。詳細は [04-cps-phase.md](04-cps-phase.md) を参照。

## 6. レイヤ関係

```
[library]  ←dependsOn─  [plugin]
    │
    └─dependsOn─  [integrationTests]
    │
    └─dependsOn─  [shiftReset]          ←Xplugin─  [plugin jar]
    │
    └─dependsOn─  [reifyReflect]        ←Xplugin─  [plugin jar]
                      │
                      ├─dependsOn─  [reifyReflectCats]    ←Xplugin─  [plugin jar]
                      ├─dependsOn─  [reifyReflectScalaz]  ←Xplugin─  [plugin jar]
                      └─dependsOn─  [reifyReflectZio]     ←Xplugin─  [plugin jar]
```

`library` が公開 API（`shift`・`reset`・`ControlContext` 等）を提供する基盤レイヤである。`sandbox` 配下の各ミニプロジェクトはこの API を利用しつつ、コンパイル時にプラグイン jar を `-Xplugin:` で受け取ることで CPS 変換を適用される。

`reify-reflect` は `library` の shift/reset の上に薄いアダプタ層（`CpsMonad` 抽象と各バックエンド）を重ねる構造であり、`shift` / `reset` / `CpsTransform` は `library` から供給される。

## 7. 設計原則

- **型情報に基づく選択的変換**: CPS 変換の対象は構文的なシンボル名による分岐ではなく、型情報（`CpsTransform[R]` の given 有無等）によって判定される。変換が不要なコードにはプラグインが影響を与えない。
- **コンパイル時変換・ランタイム軽量**: `shift` / `reset` の実装は `???`（コンパイラに変換されることが前提）であり、変換後は `ControlContext` による CPS 表現のみが残る。
- **モジュール分離**: 公開 API（library）・変換エンジン（plugin）・サンプル/検証（sandbox, integration-tests）が明確に分離されており、利用者は `library` と `-Xplugin:` を配線するだけでよい。
- **プラグインオプション未使用**: `initialize(options: List[String])` は `options` を受け取るが、この実装では使用せず無視する（返すフェーズ順序は固定）。

## 8. 文書マップ

| ファイル | 内容 |
|---|---|
| [00-overview.md](00-overview.md) | プラグイン内部のフェーズパイプライン概要（stub→ANF→CPS） |
| [01-public-api.md](01-public-api.md) | 公開APIライブラリ（ControlContext / CpsSym / CpsTransform / shift・reset） |
| [02-stub-phase.md](02-stub-phase.md) | stub フェーズ（$transformed スタブ生成・ABI） |
| [03-anf-phase.md](03-anf-phase.md) | ANF フェーズ（構文形ごとの正規化変換規則） |
| [04-cps-phase.md](04-cps-phase.md) | CPS フェーズ（本体変換・呼び出し側書き換え・ローカル値書き換え） |
| [05-shared-infrastructure.md](05-shared-infrastructure.md) | プラグイン登録と共通基盤（適格性判定・型操作・シンボル・ローカル格納） |
| [sandbox/shift-reset.md](sandbox/shift-reset.md) | shift/reset 利用例（abort/around/suspend のデモ） |
| [sandbox/reify-reflect.md](sandbox/reify-reflect.md) | reify/reflect モナド抽象（core ＋ cats/scalaz/zio バックエンド） |
