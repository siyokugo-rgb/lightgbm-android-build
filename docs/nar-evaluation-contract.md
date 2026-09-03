# NAR Evaluation Contract

- 文書状態: 正本
- version: 1
- 親仕様: `docs/product-goal-roadmap.md`
- 対象: R2 Evaluation Contract Freeze

# 1. Purpose

モデル・特徴量・閾値を、
LOCKED TEST / OOTの結果へ適合させることを防止する。

評価方法、データ期間、モデル選択規則、
アクセス境界、再現性記録を事前に固定する。

本文書と旧評価計画が矛盾する場合は、
`docs/product-goal-roadmap.md` と本文書を優先する。

# 2. Evaluation data states

## DEVELOPMENT

2021-01 ～ 2024-12

特徴量開発、モデル選択、hyperparameter選択、
early stopping、ablationに使用可能。

## LOCKED TEST

2025-01 ～ 2025-12

過去V2で2025性能指標を既に確認済みのため、
完全未知blind holdoutとは呼ばない。

ただし今後の、

- feature selection
- model selection
- hyperparameter selection
- threshold selection
- training window selection

には使用禁止。

R12までcandidate modelの評価に使用しない。

## OOT-1

2026-01 ～ 2026-07

candidate modelの性能評価にはR12まで使用しない。

現行baseline configには、
row件数およびpositive/negative集計が既に存在するため、
「情報を一切見ていない完全blind」とは呼ばない。

ただしcandidate prediction、
log loss、AUC、Brier、race metrics等は
R12まで評価しない。

## PROSPECTIVE SHADOW

R17以降に取得する将来実開催データ。

最も強い前向き評価証拠として扱う。

# 3. Meaning of opened = 0

trainerが表示する、

- test months opened = 0
- out_of_time months opened = 0

は、そのtrainer processが
test/OOT monthly model filesを開いていないことを意味する。

プロジェクト全体として
期間に関する一切の情報が未知であることを意味しない。

# 4. Development folds

ランダム分割は禁止する。

rolling-origin方式を使用する。

Fold 1:
- train: 2021-01 ～ 2021-12
- validation: 2022-01 ～ 2022-12

Fold 2:
- train: 2021-01 ～ 2022-12
- validation: 2023-01 ～ 2023-12

Fold 3:
- train: 2021-01 ～ 2023-12
- validation: 2024-01 ～ 2024-12

時系列順序を逆転させない。

# 5. Primary metric

baseline9およびlabel_win段階のPrimary metricは、

`binary log loss`

とする。

LightGBM early stoppingでも
`binary_logloss` をfirst metricとして使用する。

補助指標:

- Brier score
- ROC AUC
- calibration
- race top-1
- race top-2
- race top-3
- race probability sum

補助指標だけを理由に、
Primary metricが悪化したcandidateを自動採用しない。

# 6. Development OOF score

feature/model candidateの比較には、
3foldのvalidation predictionを連結した
out-of-fold predictionを使用する。

Primary development scoreは、
連結OOF predictionに対するbinary log lossとする。

各foldのmetricも必ず個別保存する。

# 7. Development champion selection rule

current championとcandidateは
同一fold・同一target・同一評価コードで比較する。

race内の相関を考慮するため、
race_idをresampling unitとした
paired bootstrapを使用する。

設定:

- resamples: 10000
- seed: 20260825
- confidence interval: 95%
- interval method: percentile
- statistic: candidate log loss - champion log loss

development championへの昇格条件:

1. candidate OOF log loss < champion OOF log loss
2. 95% CI upper bound < 0
3. PIT / schema / reproducibility違反が0件

条件を満たさないcandidateは、
実験結果として保存してよいがchampionへ昇格しない。

ここでいうchampion昇格は、
DEVELOPMENT期間内の実験上の比較基準更新のみを意味する。

production modelへの自動昇格を意味しない。
production昇格は別Gateのevaluation / shadow / approvalを必要とする。
# 8. Feature ablation

特徴量は可能な限りgroup単位で追加する。

例:

- horse history
- jockey history
- trainer history
- race context
- weather
- course-weather

同時に大量の変更を入れて、
改善原因を追跡不能にしない。

feature追加・削除の各実験で、

- feature set
- config
- git commit
- dataset checkpoint
- transform hash
- seed
- runtime
- fold metrics

を保存する。

# 9. Hyperparameter selection

hyperparameter tuningもDEVELOPMENT期間だけで行う。

探索範囲は実験開始前に設定またはconfigへ記録する。

2025 LOCKED TEST / OOT-1を見て
探索範囲やparameterを変更しない。

# 10. R3 baseline9 meaning

R3でFreezeするbaseline9は、

- train: 2021-2023
- validation: 2024
- primary metric: binary_logloss

で選択されたdevelopment baselineとする。

R3 baseline9そのものは
2025/OOTを開かない。

R3で確認する再現性は、
同一入力・同一config・同一runtimeから
同一artifactを生成できることを意味する。

# 11. Locked-test model

R12で2025 LOCKED TESTを評価するモデルは、
LOCKED TESTを開く前に完全Freezeする。

Freeze対象:

- feature set
- transform
- category dictionary
- model architecture
- hyperparameters
- best/fixed iteration
- calibration
- prediction conversion
- relevant thresholds
- git commit
- config hashes

NAR V3では、
2025 LOCKED TESTを開く前に、
DEVELOPMENT期間だけを使用して
最終評価artifactを作成する。

手順:

1. 2021-2024のwalk-forwardで、
   feature、hyperparameter、iteration、
   calibration、threshold等を確定する。
2. それらを完全Freezeする。
3. 2021-01～2024-12全体をtraining dataとしてfinal-fitする。
4. final-fitではLOCKED TEST/OOTを、
   early stopping、model selection、
   calibration、threshold selectionに使用しない。
5. final-fit artifactと関連hashをFreezeする。
6. その後初めて2025 LOCKED TESTを開く。

boosting iterationは、
LOCKED TESTを開く前に
DEVELOPMENT期間だけから確定した固定値を使用する。

baseline9については、
R3で確定したbest_iteration = 55を使用し、
2021-01～2024-12全体で
55 rounds固定のfinal-fitを行う。

R3で保存した、
train 2021-2023 / validation 2024の
baseline9 artifactは再現性checkpointとして保持し、
上書きしない。

calibrationを使用するモデルでは、
final-fit training data自身への
in-sample predictionを使用して
calibratorを学習しない。

calibrationの方式とcalibratorは、
DEVELOPMENT期間のOOF predictionだけを使用して確定し、
LOCKED TESTを開く前にFreezeする。

2025を見た後のrefitは禁止する。
# 12. Baseline comparator

2025 LOCKED TEST上の比較対象baselineは、
R3でFreezeされたbaseline9の、

- feature set
- transform
- category dictionary
- model architecture
- hyperparameters
- best_iteration = 55

を変更せず継承し、
Section 11のfinal-fit protocolに従って作成する。

具体的には、
2021-01～2024-12全体をtraining dataとして、
55 rounds固定で学習した
baseline9 locked-test artifactを比較対象とする。

R3で保存した、
train 2021-2023 / validation 2024の
baseline9 artifactは再現性checkpointとして保持する。

このR3 checkpoint artifact自体を、
2025 LOCKED TEST上の直接の比較artifactには使用しない。

baseline9 locked-test artifactも、
2025 LOCKED TESTを開く前にFreezeする。

final modelとbaseline9の双方について、
LOCKED TESTを見た後の再学習、
hyperparameter変更、
iteration変更、
calibration変更、
threshold変更を禁止する。

candidate/final modelの評価結果を見た後で、
baseline9だけを有利または不利になるよう
再学習・再調整しない。
# 13. LOCKED TEST acceptance

Prediction Systemについて、

`final log loss < baseline9 log loss`

を必須とする。

さらにrace-level paired bootstrapで、

`final log loss - baseline9 log loss`

の95% CI upper boundが0以下であることを要求する。

bootstrap:

- resamples: 10000
- seed: 20260825
- confidence interval: 95%
- interval method: percentile
- resampling unit: race_id
- statistic: final log loss - baseline9 log loss

resampling protocol:

- finalとbaseline9は同一のrace_id集合を持つこと
- 同一race内のentry_id集合とlabelが完全一致すること
- race_id、entry_id、labelに不一致・欠落・重複があれば評価を開始せずFAILする
- race_idを復元抽出単位としてreplacementありでresamplingする
- 抽出されたraceに属する全entryを、そのraceの抽出回数を含めて復元する
- 各resampleについて、全復元entryを対象にglobal binary log lossをfinalとbaseline9でそれぞれ計算する
- 各resampleのstatisticは final log loss - baseline9 log loss とする
- 10000個のstatisticからpercentile法で95% CIを計算する
- 2.5 percentileをlower bound、97.5 percentileをupper boundとする
- CI upper bound <= 0 をLOCKED TEST acceptance条件とする

raceごとのlog lossを単純平均する方式は、
このPrimary metricおよびbootstrap statisticには使用しない。
# 14. OOT-1 acceptance

OOT-1では、

`final log loss <= baseline9 log loss * 1.01`

をproduction候補条件とする。

OOT評価前に、
feature/model/thresholdを変更しない。

原則としてLOCKED TESTで使用した
同一Freeze artifactを評価する。

別のproduction-refit protocolを使用する場合は、
OOTを開く前に別途Gitで固定する。

# 15. Betting evaluation

historical T-60 OddsSnapshotが存在し、
PITを証明できる場合のみ、

Prediction
+
Bet Strategy
+
Budget

をhistorical locked evaluationする。

T-60 historical oddsが存在しない場合、

- Prediction: LOCKED TEST / OOT-1
- Betting: prospective shadow

で評価する。

Payoutや最終確定Oddsを
T-60 OddsSnapshotの代替に使用しない。

# 16. Access boundary

development trainerは、

- DEVELOPMENT以外のmonthly model dataを開かない
- LOCKED TEST/OOT pathをselection codeへ渡さない

ことを基本とする。

LOCKED TEST/OOT評価は、
selection/training codeとは別の明示的evaluation pathで実施する。

評価時にはaccess logを残す。

# 17. Reproducibility record

各正式実験で最低限保存する。

- git commit
- dataset checkpoint SHA-256
- transform checkpoint SHA-256
- feature order SHA-256
- category dictionary SHA-256
- config SHA-256
- trainer/evaluator SHA-256
- runtime versions
- random seeds
- fold definitions
- metrics
- model SHA-256

# 18. Security / fail-closed

以下の場合は評価を停止する。

- split mismatch
- source month mismatch
- checkpoint mismatch
- SHA mismatch
- unknown schema
- target形式異常
- duplicate entry_id
- train/validation overlap
- locked periodへの不正アクセス

不明値を推測補完して評価を継続しない。

# 19. R2 PASS condition

以下を満たした時点でR2 PASSとする。

1. 本文書をGitで正本化
2. `product-goal-roadmap.md` から本文書を参照
3. 2025をLOCKED TESTとして固定
4. 2026-01～07をOOT-1として固定
5. DEVELOPMENT foldを固定
6. Primary metricを固定
7. champion promotion ruleを固定
8. locked/OOT access ruleを固定
9. 既存trainerがLOCKED/OOTを開かないことを確認

R2 PASS後、
これらの評価規則を変更する場合は、
理由・影響・変更前後をGit履歴に残す。
