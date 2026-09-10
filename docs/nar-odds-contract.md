# NAR Odds Data Contract

## Purpose

NAR発走前オッズをPIT-safeかつ
race identityを誤結合せず取得・保存・解析するための契約を定義する。

上位契約:

- `docs/product-goal-roadmap.md`
- `docs/nar-pit-contract.md`

上位契約と矛盾する場合は上位契約を優先する。

---

## 1. Scope

初期対象はNAR公式
`OddsTanFuku` の以下とする。

- 単勝
- 複勝

他馬券種は別途実HTML監査後に追加する。

---

## 2. PIT

Predictionで利用可能なOddsSnapshotは、

`odds.observed_at <= prediction_as_of`

を必須とする。

`observed_at` は取得時のPIT evidenceから決定する。

HTML表示上の

`HH:mm 現在`

はsource-reported表示時刻であり、
`observed_at` の代替にしない。

後から取得した最終オッズを
過去T-60 Oddsの代替として使用しない。

---

## 3. Page state

確認済み表示:

- CURRENT: `単勝・複勝 オッズ （HH:mm 現在）`
- FINAL: `単勝・複勝 オッズ （最終）`

CURRENTのHH:mmは
source-reported timeとして保持可能だが、
単独でPIT availability evidenceとはしない。

未知のpage stateは既知状態へ推測変換せず
fail-closedとする。

---

## 4. odds_flg

実HTML監査で以下を確認した。

- `odds_flg=4`: 馬番順
- `odds_flg=5`: 人気順

これはOddsの時点指定ではなくsort orderである。

Parserはrow順序をhorse identityとして使用しない。

---

## 5. Race identity binding

HTTP 200とNAR OddsページらしいHTMLであることだけでは
対象レース一致の証拠として不足する。

requestの

- babaCode
- raceDate
- raceNo

とHTML自身のidentityを照合する。

最低限以下を検証する。

### RaceList anchor

`id="RaceList"` のhrefに含まれる

- `k_babaCode`
- `k_raceDate`
- `k_raceNo`

がrequestと完全一致すること。

query parameter順序へ依存しない。

HTML entityをdecodeしたうえでqueryを解釈する。

### Active race navigation

`raceNum active` の表示が
request raceNoと一致すること。

`class` 属性は空白区切りのtoken集合として判定し、
tokenの順序へ依存しない。

active race候補は一意であること。

### Active course navigation

`courseBtn active` を現在競馬場表示として取得する。

`class` 属性は空白区切りのtoken集合として判定し、
tokenの順序へ依存しない。

active course候補は一意であること。

### Race header

race headerから最低限

- race date
- venue display name
- race number
- scheduled start time

を抽出する。

race dateとrace numberはrequestと一致必須。

venue display nameはHTML内のactive course表示と
Unicode空白文字をすべて除去した比較用文字列で一致必須。

identityの欠損、不一致、複数候補、解析不能は
fail-closedとする。

navigation link件数の多数決でidentityを決めない。

---

## 6. TanFuku table structure

確認済みbody rowは14 cell。

- cell 1: 人気
- cell 2: 枠
- cell 3: 馬番
- cell 4: 馬名
- cell 5: 単勝オッズ
- cell 6: 複勝オッズ下限
- cell 7: 複勝オッズ上限
- cell 8: 性齢
- cell 9: 馬体重(増減)
- cell 10: 負担重量
- cell 11: 騎手(所属)
- cell 12: 所属
- cell 13: 調教師
- cell 14: 変更情報

headerは複勝がcolspan=2のため
bodyと同一cell数であることを要求しない。

Parserはhorse numberをrunner keyとして使用する。

---

## 7. Numeric Odds

単勝は単一値。

例:

`1.9`

複勝は下限・上限の2値。

例:

- lower raw: `1.1-`
- upper raw: `1.3`

lowerの末尾`-`は表示separatorとして処理する。

Parserが生成するOddsのDomain値は`BigDecimal`で保持する。

数値は正であること。

複勝は

`lower <= upper`

を必須とする。

不正数値、欠損組合せ、逆転rangeは
既知の正常Oddsへ推測補正しない。

---

## 8. Runner change state

確認済み:

### 出走取消

確認サンプルでは、

- win odds: empty
- place lower: empty
- place upper: empty
- change info: `出走取消`

で14 cell構造を維持した。

出走取消馬へOddsを生成しない。

### 騎手変更

確認サンプルでは通常Oddsが存在した。

したがって、

`changeInfo is not empty`

だけを理由に非出走扱いしてはならない。

### 未確認状態

競走除外、発売前その他の未確認表示を
既知状態へ推測変換しない。

未知のrunner change stateは
該当runnerのOddsを利用不可とし、
推測で既知状態へ変換しない。

race identity、page state、table structureの不明は
page全体をfail-closedとする。

---

## 9. Separation of concerns

Downloader:

- HTTPS取得
- origin/content-type/size等のtransport validation
- PIT acquisition evidence
- race identity validation

SnapshotStore:

- raw response bytes
- manifest
- hash
- append-only保存
- integrity verification

Parser:

- 保存済み/検証済みHTMLのOdds semantics解析

Prediction/Betting:

- PIT eligibility
- Odds availability
- EV計算

ParserはPayoutやRaceOutcomeを参照しない。

---

## 10. Fail-closed conditions

最低限以下はOddsとして使用しない。

- race identity不一致
- identity解析不能
- 未知page state
- table構造不一致
- duplicate horse number
- 不正horse number
- malformed Odds
- partial place range
- place lower > upper
- unknown stateを既知状態へ変換する必要がある場合
- Snapshot integrity verification failure

---

## 11. Verified evidence baseline

2026-09-08 門別3R historical page:

- active course: 門別
- active race: 3R
- page state: FINAL
- 出走取消rowを確認

2026-09-10 門別12R live page:

- active course: 門別
- active race: 12R
- race header: 2026年9月10日 門別 第12競走 20:35発走
- page state: `15:07 現在`
- 騎手変更rowに正常Oddsが存在

未確認表示は確認済み仕様として扱わない。
