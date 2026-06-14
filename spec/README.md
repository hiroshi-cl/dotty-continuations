# dotty-continuations 実装仕様書

dotty-continuations のコンパイラプラグイン・公開APIライブラリ・プロジェクト全体アーキテクチャ・sandbox ミニプロジェクトの実装仕様を、Scala ソースコードを情報源として記述した仕様書群です。

## 推奨される読む順序

下記順序での閲覧を推奨します：

1. **06-architecture.md** — プロジェクト全体概観から入る
2. **00-overview.md** — 3フェーズパイプラインの概要を理解する
3. **01-public-api.md** — 公開 API を確認する
4. **02-stub-phase.md** から **05-shared-infrastructure.md** — 各実装フェーズと基盤を深掘りする
5. **sandbox/** — 利用例で実践的な使い方を学ぶ

## 仕様書一覧

### コアドキュメント

[06-architecture.md](06-architecture.md) — プロジェクト全体のモジュール構成（library / plugin / integration-tests / sandbox）、依存グラフ、コンパイラプラグインの配線、3フェーズパイプライン概観、設計原則。

[00-overview.md](00-overview.md) — プラグイン内部のフェーズパイプライン概要。stub→ANF→CPS の3フェーズの役割分担、$transformed ABI、CPS適格性判定の流れ、設計原則をまとめる。

[01-public-api.md](01-public-api.md) — 公開APIライブラリ仕様。ControlContext / CpsSym / CpsTransform と shift・reset 等の package 関数のシグネチャと意味論。

[02-stub-phase.md](02-stub-phase.md) — stub フェーズ仕様。pickling 前に $transformed スタブ（def/val/setter）を生成する ABI 規則とアルゴリズム。

[03-anf-phase.md](03-anf-phase.md) — ANF フェーズ仕様。apply/if/match/try/block 等の構文形ごとの正規化変換規則と残留CPS式の診断。

[04-cps-phase.md](04-cps-phase.md) — CPS フェーズ仕様。本体のCPS変換規則、呼び出し側書き換え、ローカル値書き換えの3コンポーネントを統合的に記述。

[05-shared-infrastructure.md](05-shared-infrastructure.md) — プラグイン登録と共通基盤。CPS適格性判定・型操作・シンボル定義・ローカルCPS格納規則・ユーティリティ。

### sandbox ミニプロジェクト

[sandbox/shift-reset.md](sandbox/shift-reset.md) — shift/reset 利用例実装仕様。abort/around/suspend の4パターン（Escape / Resource / Tracing / Generator）で、継続の捕捉・破棄・包括・蓄積を実証。

[sandbox/reify-reflect.md](sandbox/reify-reflect.md) — reify/reflect モナド抽象で任意のモナド `M[_]` を直接スタイルで扱う仕様。CpsMonad トレイト、core の標準インスタンス（Id / List / Option / Reader）、cats / scalaz / zio バックエンド。

### 参照ドキュメント（reference/）

このリポジトリ外の知見・汎用リファレンス。spec 本体（00〜06）の補足として保持する。

[reference/TREETYPEMAP.md](reference/TREETYPEMAP.md) — `TreeTypeMap` の概念・利用パターン・よくあるミス・デバッグ方法の汎用技術リファレンス。要点は [04-cps-phase.md](04-cps-phase.md) にも統合済み。

[reference/REFERENCE_IMPL_DIFF.md](reference/REFERENCE_IMPL_DIFF.md) — Scala 2 版 scala-continuations 参照実装との差分一覧（型表現・answer type・ABI・フェーズ構成・try/finally・while・テスト戦略など）。

[reference/SCALA2_TRANSFORM_LOGIC_ONLY.md](reference/SCALA2_TRANSFORM_LOGIC_ONLY.md) — Scala 2 版の変換ロジックのアーキテクチャ・ライブラリ型・各フェーズ詳細。dotty-continuations の設計との対比用。
