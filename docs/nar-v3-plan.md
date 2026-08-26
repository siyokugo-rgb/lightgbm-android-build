# NAR V3 Plan

## Baseline

NAR V2 (`nar-v2-win-no-identity4`) をV3の基準モデルとする。

- train: 2021-2023
- validation: 2024
- test: 2025
- V2 features: 64
- V2 test log loss: 0.2829324384590122
- V2 test AUC: 0.7506701273729478
- V2 test top1: 0.32157538787959156
- V2 test top3: 0.6456040312955842

2025 test はV3の特徴選択には使用しない。
特徴候補は2024 validationで選択し、最終候補確定後に2025 testを使用する。

## NAR pre-race feature candidates

発走前に取得可能かを確認してから使用する。

- 天候
- 馬場
- 頭数
- レース番号
- 発走時刻
- 馬体重
- 馬体重増減

## Open-Meteo weather integration

V3ではOpen-Meteoによる外部気象データの導入を検証する。

### Historical training data

過去レースについて、競馬場の位置と発走時刻に対応する
Open-Meteoの過去気象データを取得して学習データへ結合する。

候補:

- temperature_2m
- precipitation
- weather_code
- wind_speed_10m
- wind_direction_10m
- wind_gusts_10m

### Derived wind features

競馬場・コース方向を定義できる場合は以下も検証する。

- headwind_component
- crosswind_component

単純な風向・風速だけでなく、
向かい風・追い風・横風として特徴化する。

### Android production prediction

本番予測では、Open-Meteoの予報または利用可能な発走前気象データを取得し、
学習時と同じ定義で特徴量を生成する。

## Leakage rules

当該レースの発走後にしか判明しない情報は入力特徴に使用しない。

使用禁止例:

- 着順
- タイム
- 着差
- 当該レース上がり
- 当該レースハロンタイム
- 当該レースコーナー通過順

気象データも各レースの発走時点で利用可能な情報だけを使用する。

## Development order

1. このV3計画をGitに固定
2. Open-Meteo小規模取得試験
3. NAR発走前候補列のcoverage確認
4. 過去気象とNARレースの時刻結合を検証
5. V3用特徴生成処理を作成
6. 特徴群ごとに2024 validation ablation
7. 採用特徴を確定
8. 2025 testを最終評価
9. 正式V3モデルを再現可能な形で保存
10. AndroidへV3を移植
11. Android parity確認
12. 当日実予測確認
