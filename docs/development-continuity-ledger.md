# 開発継続台帳・実行手順（正本）

- **状態:** 正本（進捗・次作業・証跡の入口）
- **基準日:** 2026-10-18 JST
- **対象:** NAR 地方競馬 Android アプリ。JRA は NAR 完成後の別段階。
- **上位ゴール:** `docs/product-goal-roadmap.md`
- **状態語:** `COMPLETE` / `VERIFY FIRST` / `FIX FIRST` / `REDESIGN` / `BLOCKED`
- **重要:** 本書は実装や検証そのものを完了扱いにしない。記録の更新だけで Gate を通過させない。

## 1. なぜこの台帳を置くか

同じ調査・同じ未解決点を会話ごとにやり直さないため、開始地点、確定事項、未解決条件、証拠、次の一手をここに固定する。
現在の実装状態は GitHub の指定 ref を最優先とし、会話・Drive・PDF は仕様意図と過去の証拠に使う。記憶や要約だけで現在のコード状態を断定しない。

## 2. 最新に優先する確定事項

1. **shadow 検証は完成後に実アプリを使いながら行う。** NAR 完成の受入前提・工程ゲートにはしない。旧文書の「R17 Shadow Acceptance」「R18 前の 30 日/1,000 レース/200 BUY」は旧仕様として残すが、ここで上書きする。
2. **オッズは予測・判断には使用しない。表示・保存は可。** 自動投票は仕様外。旧 Product Goal の EV / BUY-SKIP / オッズ依存予算は、矛盾解消まで production 仕様に採用しない。
3. **独自 NAR 馬 ID は公式 ID 調達を待つ先決条件ではない。** 候補 ID と「同一馬である証明」を分離する。候補を本番 feature へ接続するには個別の PIT・coverage・衝突隔離の受入が必要。
4. **PIT / 漏洩防止が性能より優先。** HorseOutcome / Payout を予測 feature に渡さない。予測時点より後の値、結果 CSV の後知恵、未来実測 Weather を使わない。
5. **固定分割:** train 2021--2023、validation 2024、モデル固定後に test 2025、OOT 2026。test/OOT を見て変更したら独立評価として失効を記録する。
6. **作業順は 16 ステップを守る。** 現工程が COMPLETE でない限り後工程を実装開始しない。

## 3. 開発ゴール

### NAR 完成

PIT-safe な発走前 Input Snapshot を使い、着順確率を Android で再現可能に推論・表示し、結果を別の append-only 記録として照合・評価できること。
Python/Android の feature name、順序、category ID、欠損・数値変換、prediction は許容差内で一致し、schema/SHA 不一致では fail-closed する。
自動投票は含まない。オッズは保存・表示してよいが予測、BUY/SKIP、推奨額の判断入力にはしない。

### 最終ゴール

NAR を完成・固定後、JRA 専用の Data/PIT/Dataset/Feature/Model を別契約で追加し、共通 Android へ統合する。NAR 候補 ID と JRA 識別子を直接同一視しない。

## 4. 正式ロードマップと現在判定

| # | 工程 | 状態 | 根拠と次の完了条件 |
|---:|---|---|---|
| 1 | PIT 基盤 | COMPLETE（既存範囲） | `docs/nar-pit-contract.md` と既存 guard。ただし新 input は都度 PIT 監査。 |
| 2 | V3 Dataset | COMPLETE（baseline 範囲） | baseline9 checkpoint。新 feature は別 candidate として再検証。 |
| 3 | V3 Transform | COMPLETE（baseline 範囲） | baseline9 input hashes。 |
| 4 | baseline trainer | COMPLETE | baseline9 checkpoint の trainer hash。 |
| 5 | baseline preflight | COMPLETE | baseline9 freeze evidence。 |
| 6 | baseline 学習 | COMPLETE | baseline9, best iteration 55。 |
| 7 | test / OOT | NOT STARTED | 2025/2026 を feature 選択に使わない。 |
| 8 | NAR 内部特徴量拡張 | VERIFY FIRST | sex1 は DEVELOPMENT_ELIGIBLE だが production 未接続。過去走4特徴量は下記 BLOCKED。 |
| 9 | 外部気象データ基盤 | VERIFY FIRST | Weather の保存/reader 実装はあるが feature production 採用・end-to-end 受入は未完。 |
| 10 | 競馬場・物理特徴量 | NOT STARTED | course × weather を PIT-safe な candidate として評価する前に #9 を完了。 |
| 11 | 最終モデル選択 | NOT STARTED | #8--#10 と calibration/順位分布の受入後。 |
| 12 | Android feature parity | VERIFY FIRST | 既存 parity 実績と、新 production package に対する再実証を分離。 |
| 13 | Android 本番推論 | NOT STARTED | #11/#12 後。 |
| 14 | 当日自動取得 | VERIFY FIRST | capture 実装と運用受入を分離。 |
| 15 | 結果評価・更新基盤 | NOT STARTED | immutable prediction と outcome 分離を end-to-end 受入。 |
| 16 | 完成版 | NOT STARTED | 1--15 の受入完了後。shadow は完成後の実運用検証。 |

## 5. 完了済み・確認済みの証拠

- baseline9: `data-manifests/nar-v3-baseline9/checkpoint.json`（main の blob `b31fa38`）。2021--2023 train、2024 validation、test/OOT opened は 0、独立 2 runs の 5/5 artifact 一致。
- sex1 candidate: [PR #13](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/13) は merged。DEVELOPMENT OOF 46,003 races、candidate log loss 0.320499 vs baseline 0.321050、bootstrap 95% CI [-0.0006753, -0.0004293]。**production/Android/2025/OOT を意味しない**。
- Weather: `docs/nar-observed-weather-contract.md` は historical final observed を `NOT_PIT_ELIGIBLE` と明記し、live capture と feature authorization を分離している。
- 既知の実機証拠: 開発ページ10（2026-10-15相当の会話記録）では Xperia 1 VIII 隔離 QA で install/start、read-only Ledger audit、DIAG/BUT FORBIDDEN、画面遷移中操作を PASS と報告。これは production release、Activity 再生成、例外表示、原アプリ Ledger 不変性の全受入ではない。
- Drive: `keiba_android_consolidated_spec_review_v1_1_2026-10-15.docx`（Drive ID `1GsIL7tjjquGGg7dtvk4KOTThJkinR5QM`）は仕様・実装突合の補助資料。GitHub current ref を置換しない。

## 6. Phase08 / 過去走4特徴量

**状態: BLOCKED（外部データ全件監査待ち。文書更新で解除しない）**

引継ぎされた候補 ID 集計（2026-10-15 時点の作業記録）:
- 1998--2024、324 か月、4,574,037 行
- 候補割当 4,573,242、候補 164,934、曖昧 354、未識別 441

この数値は過去作業の証跡として保持するが、2026-10-18 に GitHub public ref 上で該当 commit `d7fc08b9f01e6b156a17065701aeb6c51ffb0f71` は解決できなかった。従って「現行 main に存在する」とは断定しない。到達可能な branch/PR/commit または artifact を回復して照合するまで、実装存在は **VERIFY FIRST** とする。

解除に必要な順序:
1. 324 monthly ZIP の実体、SHA/CRC、parser/version、行数を再現可能な ledger に固定する。
2. 候補キー・最古観測・競技区分を記録し、同名/血統矛盾/改名疑義/複数同日出走を自動結合せず隔離する。
3. train/validation feature を、その race の `prediction_as_of` 以前に証明できる履歴のみで build する。coverage 不足を 0 / 未出走へ偽装しない。
4. Python feature parity、candidate ablation、regression/PIT tests を PASS してからのみ production 接続を提案する。

## 7. 仕様矛盾台帳

| 領域 | 旧記述 | 最新優先 | 取扱い |
|---|---|---|---|
| shadow | Product Goal §16--17: R17 を完成前受入 | 完成後にアプリで実施 | 旧文を削除せず、本台帳の最新決定を優先。 |
| odds / BUY | Product Goal §8--10: odds→EV→BUY/SKIP→budget | odds は表示/保存のみ、予測・判断に不使用 | EV/BUY/budget を production 化しない。 |
| Weather | 旧 V1 の Weather 不使用 | Weather Data Layer は完成前に PIT-safe 基盤として必要。ただし model 採用は別 Gate | historical final observed を T-60 入力へ流用しない。 |
| 予測時点 | T-60 が既存標準 | 実装は任意 `prediction_as_of` を維持 | T-30/T-15 を勝手に完成条件へ追加しない。 |

## 8. 毎回の必須手順（開始から記録まで）

1. **開始前:** `main` と対象 branch の HEAD、`git status`、関連 Issue/PR、変更対象の現行コード・テスト・本台帳を確認する。作業開始記録には ref/SHA、対象 Gate、状態、禁止境界、成功条件を残す。
2. **設計/PIT:** feature/input ごとに source、available-at、prediction-as-of、禁止結果列、欠損・fallback、train/serving parity を表にする。
3. **実装:** 最小差分に限定する。既存の証跡、snapshot、raw archive、unrelated dirty worktree は削除・上書きしない。
4. **検証:** unit/regression、PIT negative tests、schema/SHA/fail-closed、必要な Android/CI を実行する。失敗・skip・未実行を PASS と数えない。
5. **記録:** 変更と同じ PR にこの台帳または対象 Gate の checkpoint を更新し、commit SHA、実行 command、環境、PASS/FAIL/SKIP 数、artifact hash、未解決条件、次タスクを保存する。
6. **終了判定:** COMPLETE / VERIFY FIRST / FIX FIRST / REDESIGN / BLOCKED のいずれかを明記する。CI 成功と final HEAD を確認後に限り「記録済み」とする。

## 9. 今回の記録

- 目的: 会話・Drive・GitHub の入口を一つにし、過去走4特徴量を根拠なく再着手/完了扱いしない。
- 変更範囲: 文書のみ。
- 今回 Phase08 を解除しない。NAR 完成や shadow 完了も主張しない。
- 次タスク: reachable な Phase08 branch/artifact と 324-month input manifest を確定し、§6 の解除条件 1 から実証する。
