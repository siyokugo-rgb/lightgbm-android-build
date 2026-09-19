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

availability / publication-latency 契約は本ドキュメントの
「LIVE_CAPTURED_OBSERVED availability contract」を正とする。

取得経路・capture実装が未整備な間は
production feature にしない。

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

### candidate_status

draft mapping 上の候補状態。production selection ではない。

| Status | Meaning |
| --- | --- |
| `CANDIDATE` | history 確認済みで候補として維持可能 |
| `EXCLUDED` | 根拠をもって候補から除外（`exclusion_reason` 必須） |
| `NEEDS_VERIFICATION` | 公式 metadata だけでは判断不足 |
| `NEEDS_HISTORY_REVIEW` | history 精査未完了（Step C 完了後は原則 0） |

`EXCLUDED` の reason 例:

- `NO_REQUIRED_ELEMENTS`
- `NO_DATE_COVERAGE`
- `STATION_ID_NOT_APPLICABLE`
- `HISTORY_CONFLICT`

不採用候補は JSON から削除せず、`EXCLUDED` + reason として残すことを優先する。

### element_profile

候補の要素充足分類。`selected station` ではない。

| Profile | Meaning |
| --- | --- |
| `FULL_ELEMENT_CANDIDATE` | temperature / humidity / pressure / precipitation / wind を 1 station で充足可能 |
| `PARTIAL_ELEMENT_CANDIDATE` | 一部要素のみ |
| `PRECIP_ONLY_CANDIDATE` | 降水のみ |

1 station で必要要素を満たせない venue period は
`ELEMENT_COVERAGE_GAP` として記録する。
multi-station fusion は後続 Gate で設計する。

### 「最寄り」を採用根拠にしない

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

## LIVE_CAPTURED_OBSERVED availability contract

Step D 監査で固定する。
本節は downloader / parser / archive 実装を許可しない。
取得契約と PIT evidence だけを定義する。

### Design principle

禁止する推測:

- 「10分観測だから10分後には利用可能」
- 「15:00観測だから15:10からPIT-safe」
- 「過去データ検索に載っているから当時も同時刻に利用できた」
- JMBSC 配信仕様の「観測後3分/5分/9分」を
  JMA public HP availability の保証値として流用すること

LIVE_CAPTURED_OBSERVED の availability は原則:

実際に取得・保存した証拠

で決める。

固定 publication latency は
capture scheduling の参考にはできるが、
PIT eligibility そのものの根拠にしない。

sample latency ≠ guaranteed maximum latency。

### Acquisition source classification

| Class | Meaning |
| --- | --- |
| `REFERENCE_ONLY` | retrospective / historical final 用途。live PIT capture に使わない |
| `DOCUMENTED_PIT_CAPTURE_SOURCE` | 公式に機械取得手順が文書化された public source。要素・更新周期は文書どおりに限定 |
| `PIT_CAPTURE_CANDIDATE` | live capture 候補だが、要素不足・更新周期・契約条件の追加確認が必要 |
| `OFFICIAL_BUT_UNDOCUMENTED_INTERFACE` | 公式 domain 上の resource だが public API 契約ではない |
| `REQUIRES_JMBSC` | 気象業務支援センター等の契約配信が必要 |
| `NOT_SUITABLE` | LIVE_CAPTURED_OBSERVED に不適 |

JMA 公式 domain 上に存在するだけでは
documented production API とはしない。
hidden / undocumented resource を「公式API」と呼ばない。

### Source A — historical final (`REFERENCE_ONLY`)

対象:

- 過去の気象データ検索
  `https://www.data.jma.go.jp/stats/etrn/`
- 過去の気象データ・ダウンロード
  `https://www.data.jma.go.jp/risk/obsdl/`

公式更新時刻（概要）:

- 10分ごと～日ごとの値: 概ね毎日1時頃
  （`update_k.html`）

役割:

`HISTORICAL_FINAL_OBSERVED` / `NOT_PIT_ELIGIBLE`

後日修正されうる。
live capture source として自動採用しない。

### Source B — JMA public「最新の気象データ」CSV
(`DOCUMENTED_PIT_CAPTURE_SOURCE` for published products only)

対象:

- 最新の気象データ
  `https://www.data.jma.go.jp/stats/mdrr/`
- CSV仕様
  `https://www.data.jma.go.jp/stats/data/mdrr/docs/csv_dl_readme.html`

確認済み:

- HTTPS / official host
- documented CSV URL（例: `pre_rct/alltable/pre1h00_rct.csv`）
- `text/csv`
- station_id / 現在時刻 / 値 / 品質情報を含む
- 更新時刻の公式説明あり（`update_n.html`）
  - 降水の状況: 10分ごと更新（観測から約30分後、と公式記載）
  - 風・気温・雪の状況: 毎時50分頃更新
  - 速報値であり修正されうる

限定:

- 公開CSVが提供する product（例: 降水量現在値、日最高/最低気温表、最大風速表）に限る
- 全要素の10分瞬時値セットを保証しない
- humidity / station_pressure / sea_level_pressure の
  即時フルセットは本CSV群だけでは充足しない

Step D 小規模実測（一時artifact、Git非追加）:

- 帯広 `20432` / 名古屋 `51106` / 船橋 `45106` / 江刺 `33781`
- documented precip CSV で値・品質・現在時刻を確認
- HTTP `Date` / `Last-Modified` を記録
- sample として product 現在時刻と `downloaded_at` の差を観察したが、
  これを `PUBLICATION_LAG` 定数として production 契約化しない

### Source C — JMBSC / 配信資料 (`REQUIRES_JMBSC`)

対象:

- 気象庁情報カタログ アメダス
- 配信資料に関する仕様 No.13301（BUFR 地域気象観測報）

確認済み:

- 約1300地点、10分毎
- 気温・降水・風・日照・積雪等に加え、
  官署等では気圧・湿度等を含む
- 通常報: 毎10分の3分後（第1報）/ 5分後（第2報）
- 遅延報: 9分後、修正報: 9分30秒後、等

これは有料/契約を要する配信経路であり、
JMA public HP の publication timing 証明として流用しない。

### Source D — bosai UI / undocumented resources
(`OFFICIAL_BUT_UNDOCUMENTED_INTERFACE`)

例:

- `https://www.jma.go.jp/bosai/map.html`（UI）
- official domain 上で応答する未カタログ resource

production acquisition contract として採用しない。
将来採用するなら別Gateで documented 化または明示例外承認が必要。

### Time fields for LIVE capture

| Field | Meaning |
| --- | --- |
| `observation_end_at` | 観測/集計区間の終端。product 定義に従う |
| `observation_timestamp` | product が示す観測時刻。availability 証拠ではない |
| `downloaded_at` | response body 取得完了時刻 |
| `server_date_at` | HTTP `Date` がUTCとしてパース可能な場合のみ記録 |
| `publication_hint_at` | `Last-Modified` 等。参考情報。単独では PIT 根拠にしない |
| `pit_evidence_at` | 当該 raw snapshot を実際に利用可能だったことを示す時刻 |
| `raw_snapshot_sha256` | raw body の整合性証拠 |

### `pit_evidence_at`

原則:

`pit_evidence_at = downloaded_at`

HTTP `Date` が存在し UTC としてパース可能な場合は、
Odds / ForecastWeather と同様に:

`pit_evidence_at = max(downloaded_at, server_date_at)`

としてよい。

禁止:

- product の「現在時刻」や観測時刻だけで `pit_evidence_at` を決めること
- 固定 publication lag を加算して `pit_evidence_at` を捏造すること
- historical final の掲載時刻を live availability の代替にすること

### Aggregation / timestamp semantics

要素ごとに timestamp 意味を混同しない。

| Element | Public latest CSV (documented) | Notes |
| --- | --- | --- |
| precipitation | product依存（例: 1時間降水量の現在値） | 10分更新の公式記載あり。区間定義は product 文書に従う |
| temperature | 日最高/最低などの表が中心 | 10分瞬時気温のフル提供とは限らない |
| wind | 日最大風速などの表が中心 | 同上 |
| humidity | UNKNOWN / 本CSV群では未確認 | JMBSC官署報など別経路 |
| pressure | UNKNOWN / 本CSV群では未確認 | 同上 |

不明な意味は `UNKNOWN` のまま止め、推測で確定しない。

### Prediction eligibility (T-60)

NAR v1 標準:

`prediction_as_of = scheduled_start - 60 minutes`

LIVE_CAPTURED_OBSERVED を当該 prediction に使う最低条件:

1. `snapshot.pit_evidence_at <= prediction_as_of`
2. `observation_end_at <= prediction_as_of`
3. snapshot integrity PASS（raw SHA 等）
4. station / date mapping が有効
5. 必要要素が snapshot 内に実在
6. 未来観測を含めない

例:

- race scheduled start = 16:00
- `prediction_as_of` = 15:00
- observation product time = 15:00
- `pit_evidence_at` = 15:10

→ T-60 prediction には不適格。
observation timestamp が 15:00 でも、
15:10 に初めて取得した snapshot を遡及使用しない。

quality code の feature 採用可否は別Gate。
今回 `8` のみ / `5` も利用等は決めない。

### Late / missing

予定の `prediction_as_of` までに値が取得できない場合:

- 0補完禁止
- 後から取得した値で過去 prediction を埋めない
- 当該 prediction では `MISSING` / `UNAVAILABLE`

後で取得できた値は:

- retrospective truth / reference
- または後続 prediction 用の新しい snapshot

として扱えるが、
過去 snapshot を書き換えない。

### Correction / revision

LIVE capture 後に JMA が値を修正した場合:

- 既存 raw snapshot を上書きしない
- revision は新しい capture / vintage として保存する
- 過去 prediction は当時利用した snapshot SHA を維持
- 最新修正版へ差し替えて過去 prediction を再解釈しない

### Capture scheduling (design only)

publication latency の公式記載や sample は:

「何分おきに capture を試すか」

の参考にできる。

例:

- documented precip CSV が10分更新なら、10分単位の取得試行を検討

ただし:

`capture interval ≠ PIT availability`

本Stepでは scheduler / retry / WorkManager を実装しない。

### Relation to station candidates

Step C の 55 candidates は
history / element 観点の draft である。

本 availability 契約が完成しても
production station は選択しない。

水沢 pressure gap も本Stepでは解決しない。

### HISTORICAL_FINAL_OBSERVED unchanged

本契約によっても:

`HISTORICAL_FINAL_OBSERVED = NOT_PIT_ELIGIBLE`

を変更しない。
live availability が整備されても、
1998～現在の historical final を PIT-safe に昇格させない。

---

## Official sources only

記録してよい source は公式 JMA に限る。

例:

- 過去の気象データ検索 / ダウンロード（historical final）
- 最新の気象データ CSV（documented public latest products）
- 気象庁情報カタログ / 配信仕様（JMBSC経路の文書）
- 観測概要 / metadata 配布ページ
- `ame_master`
- `amdmaster.index4`
- `smaster.index`
- `discnt_sfc.csv`

禁止:

- secret / credential / Cookie / session
- PC 固有 local path
- hidden / undocumented endpoint を production source として採用
- raw downloaded observation time-series を Git へ追加
- 公式 domain 上の未文書 resource を「公式API」と呼ぶこと

---

## Out of scope (Step D)

本 availability 契約 Step では次を行わない。

- JMA downloader / scraping / parser 実装
- undocumented API client 実装
- archive store / live capture daemon
- WorkManager 等の scheduler
- 1998～現在の一括取得
- cumulative precipitation 実装
- 水沢 multi-station fusion
- production station 最終選択
- model feature 追加 / training
- Forecast / Observed merge
- Android UI
- R7

---

## Related documents

- `docs/product-goal-roadmap.md` — Weather Domain 概要
- `docs/nar-pit-contract.md` — 全体 PIT 契約（Odds の observed_at と整合）
- `config/nar-v3-venue-coordinates.json` — venue 座標 SoT
- `config/nar-jma-observed-station-candidates.json` — draft station candidates
