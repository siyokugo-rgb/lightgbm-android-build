# 競馬予想 Android アプリ Product Goal & Roadmap

- 文書状態: 正本
- 基準日: 2026-09-02
- 対象: NAR地方競馬版 → JRA統合版
- 本文書の目的:
  - 完成地点を固定する
  - 開発順序をGateで固定する
  - PIT・評価・Weather・Odds・Betting・Budgetの上位方針を固定する
  - 進捗を「何%」ではなくGate PASS/NOT PASSで判断する

---

# 1. Source of Truth

実装状態の判断優先順位は原則として以下とする。

1. 現在の実コード
2. 現在のテスト・実行結果
3. 明示的に決定された仕様
4. 過去の会話・履歴資料
5. 推測

本文書はProduct GoalとRoadmapの最上位仕様とする。

詳細仕様は以下を参照する。

- `docs/nar-model-contract.md`
- `docs/nar-pit-contract.md`
- `docs/nar-evaluation-contract.md`
- `docs/nar-v3-plan.md`
- `config/nar-v3-features.json`
- `config/nar-v3-transform.json`
- `config/nar-v3-win-baseline9.json`
- `config/nar-v3-venue-coordinates.json`

本文書と旧ロードマップが矛盾する場合は、本文書を優先する。

---

# 2. GOAL-NAR

地方競馬版の完成条件は以下とする。

発走前のPIT-safeなNAR情報、外部気象、発走前オッズを利用して、
レースの着順確率を推定する。

そこから各馬券の的中確率と期待値を計算し、

- BUY / SKIP
- 馬券種
- 買い目
- 指定予算内の推奨購入額

までAndroidに表示する。

レース終了後は、

- RaceOutcome
- Payout
- 的中
- 収支
- 回収率

を予測Snapshotと照合し、
監査済み履歴Datasetへ日次反映できること。

本番モデルは日次結果取得と同時に自動交換しない。

R18 AcceptanceをPASSした時点を
「地方競馬版完成」とする。

---

# 3. GOAL-FINAL

NAR完成後にJRA固有の、

- Data Source
- PIT Contract
- Dataset
- Features
- Prediction Model
- Weather
- Odds

を追加する。

最終的にNAR/JRA双方を、

- 共通Android
- Betting
- Budget
- Snapshot
- Result Evaluation
- Model Management

から扱える状態を完成とする。

R23 AcceptanceをPASSした時点を
「NAR + JRA統合版完成」とする。

---

# 4. 予測時点

推論Engineは任意の `prediction_as_of` を扱える構造を維持する。

NAR Product v1の標準予測時点は、

`scheduled_start - 60 minutes`

すなわちT-60とする。

NAR v1ではT-30/T-10等の自動再予測は必須要件としない。

ただしT-60後に、

- 出走取消
- 競走除外
- 開催・レース取止め
- 入力整合性破壊

を検出した場合は、既存Recommendationを
`STALE` または `VOID` として扱い、
古い買い目を有効な推薦として継続表示しない。

---

# 5. PIT / Domain Contract

以下を別Domainとして扱う。

- InputSnapshot
- PredictionSnapshot
- OddsSnapshot
- RecommendationSnapshot
- RaceOutcome
- Payout

結果情報をPredictionSnapshotへ追記しない。

HorseEntryとHorseOutcome/Payoutを混在させない。

未知・未分類featureはfail-closedとする。

派生特徴量のavailabilityは、

「入力元すべてのavailabilityのうち最も遅い時刻」

を継承する。

production featureはtraining/serving双方で利用許可されていることを必須とする。

---

# 6. Weather

Weather Data Layerは完成必須要件とする。

ただし個々の気象特徴量の採否は評価結果で決定する。

Weatherは以下に分離する。

## ObservedWeather

`prediction_as_of` までに実際に観測済みの気象。

## ForecastWeather

`prediction_as_of` 以前に発行された、
発走予定時刻向けの予報。

T-60予測に発走時刻の未来実測を混入させない。

Historical Forecastを使用する場合は、
当時発行されたforecast vintageを復元可能なSourceのみ使用する。

最低限の特徴候補:

- temperature
- humidity
- pressure
- precipitation
- wind_speed
- wind_direction
- wind_gust
- 1h cumulative precipitation
- 3h cumulative precipitation
- 6h cumulative precipitation
- 12h cumulative precipitation
- 24h cumulative precipitation

競馬場情報と組み合わせ、

- headwind_component
- tailwind_component
- crosswind_component

等を生成する。

Weather取得失敗を0補完しない。

fallbackを使う場合は、
事前検証済みWeather-less modelのみ許可する。

旧 `docs/nar-model-contract.md` の
「V1ではWeatherを使用しない」という段階的開発方針は、
GOAL-NARの完成条件については本文書が上位となる。
NAR完成版ではWeather Data Layerを必須とする。

旧 `docs/nar-v3-plan.md` の
「発走時刻に対応する過去実測気象を結合する」案は、
T-60 PIT条件を満たす場合に限り利用可能とする。
発走後に確定した未来実測をT-60特徴量として使用しない。

過去のforecast vintageを復元できない場合、
未来実測で代用しない。
ForecastWeatherはlive取得時から保存を開始して将来学習用に蓄積し、
現時点のhistorical trainingではPIT証拠のあるWeatherだけを使用する。

---

# 7. Prediction Architecture

現在の `label_win` baseline9は開発基準モデルであり、
最終Prediction Systemそのものではない。

最終系ではレース単位の着順分布を基礎予測として扱い、
そこから馬券に必要な的中確率を導出する。

正式対象馬券種:

- 単勝
- 複勝
- 枠複
- 枠単
- 馬複
- 馬単
- ワイド
- 3連複
- 3連単

実際に当該レースで発売される券種だけ生成する。

券種ごとに無条件で別モデルを乱立させない。

---

# 8. Odds / Betting

PredictionとBettingを分離する。

処理責務:

Prediction System
→ Bet Probability Engine
→ OddsSnapshot
→ EV Engine
→ Bet Strategy
→ Budget Engine

使用できるOddsは、

`odds.observed_at <= prediction_as_of`

を満たす最新発走前OddsSnapshotのみとする。

Payoutや最終確定オッズを
過去T-60時点オッズの代替として使用しない。

複勝・ワイド等のレンジオッズで安全側評価が必要な場合は、
原則として下限側を使用する。

---

# 9. BUY / SKIP

全レース購入を禁止する。

BUY候補は最低限、

- 入力Data PASS
- PIT PASS
- Model PASS
- Odds PASS
- EV >= EV_MARGIN

を満たすものだけとする。

EV_MARGIN候補は、

- 0%
- 5%
- 10%
- 15%
- 20%

に限定して開発データまたはshadowデータで選択する。

2025 LOCKED TESTを見た後にEV_MARGINを変更しない。

歴史的T-60 Oddsが存在しない場合、
initial live shadow値は10%を使用する。

---

# 10. Budget

ユーザー指定の日次予算を最大使用可能額として扱う。

予算を必ず使い切る仕様にはしない。

基本制約:

- 最低購入単位: 100円
- 1レース最大: 日次予算の20%
- 1買い目最大: 日次予算の5%
- 未使用予算を残してよい
- 負け追い増し禁止
- マーチンゲール禁止

ユーザーが1レース上限を明示した場合は、
ユーザー上限とシステム上限の小さい方を採用する。

NAR v1は買い目・推奨購入額の提示までを対象とする。

馬券の自動購入はNAR v1スコープ外とする。

---

# 11. Evaluation Contract

2021-2024を開発期間とする。

基本walk-forward:

- Fold 1: 2021 → 2022
- Fold 2: 2021-2022 → 2023
- Fold 3: 2021-2023 → 2024

Primary metric:

`log loss`

補助指標:

- Brier score
- calibration
- ROC AUC
- race ranking
- top-k

特徴量・モデル・閾値の選択は開発期間だけで行う。

学習期間について直近3年・5年・10年・全期間等を比較する場合も、
2021-2024内のwalk-forwardだけで決定する。
2025 LOCKED TESTを見た後に学習期間を変更しない。

---

# 12. LOCKED TEST / OOT

2025-01〜2025-12:

`LOCKED TEST`

2025については過去のV2評価結果が既にプロジェクト内で確認済みであるため、
完全未知のblind holdoutとは呼ばない。
ただし今後のfeature/model/threshold選択には使用しない固定テスト期間とする。

2026-01〜2026-07:

`OOT-1`

とする。

2025/2026は最終仕様凍結前に開かない。

2025を開いた後に、

- feature
- model
- selection threshold

を変更した場合、2025はLOCKED TESTとしての独立性を失う。

---

# 13. Locked Test / OOT Evaluation

2025 LOCKED TESTでは、
final modelのlog lossが
同一対象データ上のbaseline9を下回ることを要求する。

さらにrace-level paired bootstrapにより、

`final - baseline`

のlog-loss差95% CI上限が0以下であることを要求する。

OOT-1では、

`final log loss <= baseline log loss * 1.01`

をproduction候補条件とする。

歴史的T-60 Oddsが存在する場合は、
Prediction + Betting + Budgetまでend-to-end blind evaluationする。

存在しない場合は、

- Prediction: 2025/OOTでblind評価
- Betting: live shadowで前向き評価

とする。

---

# 14. Daily Learning Loop

レース終了後、毎日以下を更新する。

RaceOutcome / Payout
→ Prediction照合
→ 的中・収支
→ 監査済み履歴
→ 学習可能Dataset

Datasetの日次更新とproduction model更新を分離する。

candidate model生成は原則週1回とする。

production昇格:

candidate
→ evaluation
→ shadow
→ production model比較
→ PASS
→ approval
→ production

自動即時昇格は禁止する。

---

# 15. Security

Securityは最後の独立工程ではなく、
全Gate共通受入条件とする。

必要に応じて以下を要求する。

- fail-closed
- schema validation
- SHA-256 verification
- path traversal防止
- input size制限
- secret非埋込み
- TLS
- timeout / retry制御
- audit log
- model package verification
- rollback capability

---

# 16. Shadow Acceptance

NAR正式完成前に最低、

- 30 calendar days
- 1,000 completed races

の両方を満たすshadow運用を行う。

Bet Strategy評価には最低200 BUY recommendationsを要求する。

以下は0件必須。

- PIT violation
- silent schema failure
- SHA bypass
- untraceable prediction

短期間の回収率100%超を
Engineering Completion条件とはしない。

利益・回収率はPractical Performanceとして継続評価する。

---

# 17. Gate Roadmap

## R0 Product Goal Contract
本書を固定する。

## R1 PIT / Feature / Domain Contract Freeze
既存PIT/Feature契約を正本化し、
Weather/Odds/Derived Featureまで拡張する。

## R2 Evaluation Contract Freeze
walk-forward、LOCKED TEST、OOT-1、評価基準を固定する。

## R3 baseline9 Freeze
primary-logloss版の完全再現性とcheckpointを固定する。

## R4 Live PIT Capture Foundation
後から復元できない可能性がある時系列情報を早期に保存開始する。

対象:
- ForecastWeather snapshot
- OddsSnapshot

このGateの目的はデータ収集開始であり、
未監査データを直ちにproduction featureへ採用することではない。

## R5 NAR Internal Feature Development
馬・騎手・調教師・過去走等をPIT-safeに拡張しablationする。

## R6 PIT-safe Weather Core
Observed/Forecast Weatherの取得・保存・監査を完成する。

## R7 Course × Weather Feature Layer
雨量・風・競馬場物理情報等を特徴量化する。

## R8 RaceOrder Prediction System
着順分布・calibration・券種確率変換を固定する。

確率出力は少なくとも、
- 0以上1以下
- race内確率整合性
- 順序付き/順不同事象の意味整合性
- 同着を一意順位へ捏造しない
ことを満たす。

## R9 PIT-safe Odds / Bet Probability Core
T-60時点OddsSnapshotを取得・保存・監査し、
馬券的中確率との結合を行う。

## R10 EV / BUY-SKIP Strategy
確率・OddsからEVを計算し、
BUY/SKIP・券種・買い目を再現可能に生成する。

## R11 Budget Engine
100円単位で予算制約内に配分する。

## R12 2025 LOCKED TEST + 2026-01〜07 OOT-1
仕様凍結後に評価する。
2025は固定テスト、OOT-1は追加out-of-time評価として扱う。

## R13 Immutable Snapshot / Audit
Input/Prediction/Odds/Recommendation/Outcomeを分離保存する。

## R14 Android Prediction + Recommendation Parity
Python/Android間でfeature、prediction、recommendationを一致させる。

## R15 Live Automation
当日取得からRecommendationまで自動化する。

## R16 Daily Result / Dataset / Candidate Loop
日次結果反映とcandidate model更新系を完成する。

## R17 Shadow Operation
30日 AND 1000完了レース、
200 BUY recommendations以上を検証する。

## R18 NAR Acceptance
全受入条件PASS。

ここを地方競馬版完成とする。

## R19 JRA PIT / Dataset Core
JRA固有Data/PIT/Datasetを構築する。

## R20 JRA Prediction System
JRA特徴量・Prediction Systemを構築する。

## R21 JRA Weather / Odds / Betting
JRA向け実戦レイヤーを接続する。

## R22 JRA Shadow + NAR/JRA Integration
JRA前向き検証を行い、
共通Android・運用・結果管理へ統合する。

## R23 Final Acceptance
NAR + JRA統合版の最終受入試験。

ここを最終完成とする。

---

# 18. 現在位置

現在のSource of Truth:

- Product Goal初回固定commit: `90257a0`
- PIT-safe Dataset構築済み
- Dataset checkpoint監査済み
- PIT-safe Transform構築済み
- Transform checkpoint監査済み
- baseline trainer実装済み
- trainer静的テストPASS
- train/validation preflight PASS
- 2025 TEST opened = 0
- OOT opened = 0
- primary metric = binary_logloss
- best_iteration = 55
- baseline model SHA-256:
  `92aa2f321289618bce3a4feff458df32052d3b67659ff35f83fa5bb8924014db`

R3で残っている主要作業:

primary-logloss版を別出力へ再学習し、
5成果物のSHA-256完全一致を確認してbaseline9をFreezeする。

---

# 19. 直近の作業順

1. 本文書をGitで固定する。
2. 既存契約との矛盾を監査する。
3. R2 Evaluation Contractを固定する。
4. primary-logloss版の5成果物再現性確認を完了する。
5. R3 PASS判定を行う。
6. R4 Live PIT Capture Foundationを開始する。
7. R5 NAR Internal Feature Developmentへ進む。

---

# 20. 現在禁止する作業

以下は現段階では実施しない。

- 2025 LOCKED TESTを見る
- 2026-01〜07 OOT-1を見る
- Weather未来実測をT-60特徴量へ混ぜる
- Payoutを過去T-60 Oddsの代替にする
- Bet Strategy確定前にAndroid UIを作り込む
- Dataset日次更新とproduction model自動交換を一体化する

---

# 21. 完成地点

`R18 PASS = 地方競馬版完成`

`R23 PASS = NAR + JRA統合版完成`

今後の進捗は、
機能数や主観的なパーセントではなく、
GateのPASS/IN PROGRESS/NOT STARTEDで判断する。
