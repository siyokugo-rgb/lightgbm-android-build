# NAR ObservedWeather Contract (JMA)

## Purpose

ObservedWeather（JMA観測）の role / PIT / quality / station-history 契約を固定する。

本契約は ForecastWeather（Open-Meteo）とは別 Domain である。
観測値を予報の代用・欠損補完に使わない。

詳細な venue→station **候補** mapping は:

`config/nar-jma-observed-station-candidates.json`

を正とする。本ドキュメントへ同一内容を複製しない。

---

## Domain separation

| Domain | Source | Role |
| --- | --- | --- |
| ForecastWeather | Open-Meteo 等 | `prediction_as_of` 以前に発行された予報 |
| ObservedWeather | JMA | 観測済み気象 |

ForecastWeather 契約・archive・feature 経路と ObservedWeather を混在させない。

---

## Observed roles

### HISTORICAL_FINAL_OBSERVED

意味:

現在 JMA の過去気象データ検索等から取得できる、
過去確定・修正反映後の観測値。

許可用途:

- retrospective analysis
- truth / reference
- coverage audit
- station mapping validation
- ForecastWeather 評価（観測側の参照値として）

禁止:

- historical race prediction feature としての PIT-safe 扱い
- historical Forecast の代用品
- Forecast 欠損補完

PIT eligibility:

`NOT_PIT_ELIGIBLE`

現行の historical download はこの role に属する。

---

### LIVE_CAPTURED_OBSERVED

意味:

今後、実際の `prediction_as_of` 時点までに取得し、
`downloaded_at` 等とともに immutable 保存した観測。

PIT eligibility:

`CANDIDATE`

availability contract 確定前は production feature にしない。

---

### HISTORICAL_PIT_OBSERVED

意味:

当時の配信状態 / vintage を証明可能な archive。

現状:

`UNAVAILABLE` / `NOT_CONFIRMED`

過去 vintage archive は未確認。
`HISTORICAL_FINAL_OBSERVED` から再構築しない。

---

## Time semantics

Observed 値について、次を別概念として扱う。

| Concept | Meaning |
| --- | --- |
| observation timestamp | 観測時刻そのもの |
| observation interval / end | 集計区間または区間終端 |
| downloaded_at | 当該値が取得・保存された時刻 |
| revision / download vintage | 修正版・配信版を識別する証拠 |

禁止:

`observation time <= prediction_as_of`
だけで自動的に PIT-safe と判断すること。

availability / publication evidence が必要である。

race scheduled start より後の観測は、
当該 prediction feature に使わない。

---

## Quality / status codes

Step A 監査で確認した JMA raw quality code を固定する。

| Raw code | Enum | JMA 原義 |
| --- | --- | --- |
| 8 | `NORMAL` | 正常 |
| 5 | `SEMI_NORMAL` | 準正常 |
| 4 | `INSUFFICIENT_DATA` | 資料不足 |
| 2 | `QUESTIONABLE` | 疑問 |
| 1 | `MISSING` | 欠測 |
| 0 | `NOT_APPLICABLE` | 対象外 |

契約:

- raw code は必ず保持する
- `SEMI_NORMAL` (5) を `NORMAL` (8) と同一扱いしない
- blank / missing / `0` による値補完を禁止する

今回の固定範囲外:

どの code を model feature として使用可能かは決めない。

---

## Correction / revision

`HISTORICAL_FINAL_OBSERVED` は、
取得時点の JMA 確定・修正版である。

同一 observation timestamp でも、
後から revision された値が返りうる。

方針:

- revision / download vintage を記録する
- final historical を当時配信値として扱わない
- final historical から `HISTORICAL_PIT_OBSERVED` を捏造しない

---

## Uniformity / discontinuity / station history

station metadata に関して次を無視しない。

- station relocation（移転）
- 統計切断
- 均質番号
- 観測要素変更

禁止:

current station coordinate を 1998年まで遡及適用すること。

分離して扱う:

- station identity（観測所番号などの識別）
- station metadata version / effective period（座標・標高・要素・均質の有効期間）

根拠として公式 metadata を用いる。

例:

- `ame_master`（現行地点一覧）
- `amdmaster.index4`（アメダス地点情報履歴）
- `smaster.index`（地上気象観測地点情報履歴）
- `discnt_sfc.csv`（統計切断情報）

全履歴の完全実装は本 Step の必須ではない。
不確実な候補は `NEEDS_HISTORY_REVIEW` とする。

---

## Station candidate mapping

`config/nar-jma-observed-station-candidates.json` は:

- `status = DRAFT_CANDIDATES_ONLY`
- `pit_eligibility = NOT_PIT_ELIGIBLE`

の **draft candidate** である。

production station 選択ではない。
`selected=true` 等を設定しない。

候補 station mapping が存在しても、
historical final が PIT-eligible になることはない。

### Mapping structure

禁止:

`venue → selected_station` だけの単純構造。

理由:

観測所種別により temperature / humidity / pressure / wind / precipitation
の提供要素が異なる。

候補ごとに最低限保持する:

- `station_id`（文字列。先頭 0 を失わない）
- `station_name`
- `station_type`
- `from` / `to`
- `latitude` / `longitude`
- `elevation_m`（取得可能な場合）
- `distance_km`
- `available_elements`
- `source` / evidence
- `candidate_status`
- `rationale`

### available_elements

少なくとも次を候補要素とする。

- temperature
- relative_humidity
- station_pressure
- sea_level_pressure
- precipitation
- wind_speed
- wind_direction

値は 3 状態:

- `AVAILABLE` — JMA source で利用可能と確認済み
- `UNAVAILABLE` — 利用不可と確認済み
- `UNKNOWN` — 未確認（`false` / `UNAVAILABLE` と同義にしない）

### 「最寄り」は採用根拠にしない

`distance_km` は ranking 情報にすぎない。

最終 station 選択には次が必要である（今回は行わない）:

- distance
- element availability
- station type
- historical coverage
- relocation / history
- statistical discontinuity

### Venue coverage

`config/nar-v3-venue-coordinates.json` の venue key と完全一致させる。

15 場すべてに candidate entry を持つ。
確証不足なら `candidate_status = NEEDS_VERIFICATION` とする。
推測 station ID を埋め込まない。

### Nagoya date split

名古屋は venue 側の date split を維持する。

| Period | Venue coordinates |
| --- | --- |
| ～2022-03-11 | 旧・名古屋競馬場（名古屋市港区） |
| 2022-03-12～2022-04-07 | venue mapping なし（gap。補完しない） |
| 2022-04-08～ | 現・名古屋競馬場（弥富市） |

旧会場と新会場を同一 station candidate set と決め打ちしない。

---

## Official sources only

記録してよい source は公式 JMA に限る。

例:

- 過去の気象データ検索（historical observation download）
- 観測概要 / metadata 配布ページ
- `ame_master`
- `amdmaster.index4`
- `smaster.index`
- `discnt_sfc.csv`

禁止:

- secret / credential / Cookie / session
- PC 固有 local path
- hidden / undocumented endpoint を production source として記録
- raw downloaded observation time-series を Git へ追加

---

## Out of scope (this Step)

本契約 Step では次を行わない。

- JMA downloader / scraping / parser
- archive store / live capture
- 1998～現在の一括取得
- cumulative precipitation 実装
- model feature 追加 / training
- Forecast / Observed merge
- Android UI
- production station 最終選択
- R7

---

## Related documents

- `docs/product-goal-roadmap.md` — Weather Domain 概要
- `docs/nar-pit-contract.md` — 全体 PIT 契約
- `config/nar-v3-venue-coordinates.json` — venue 座標 SoT
- `config/nar-jma-observed-station-candidates.json` — draft station candidates
