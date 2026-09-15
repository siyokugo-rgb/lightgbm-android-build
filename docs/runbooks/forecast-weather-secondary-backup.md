# ForecastWeather secondary Backup / Restore (home PC)

会社では OAuth / rclone 実認証・実 Drive アクセスを行わない。
この runbook は、自宅 PC で認証完了直後に曖昧さなく実行するための手順書。

R4 Weather を COMPLETE にするには、本手順の backup → check → restore →
`verifyRestoredArchive` までがすべて PASS であること。
二次 Backup 作成だけで PASS にしない。

## Drive 契約（固定）

- Drive ROOT_FOLDER_ID（固定・必須）:
  `1Qz1QAX58jrekp80kyH5jrRmHaggH2iJb`
- remote 相対パス（固定）: `weather/forecast/`
- 「競馬」等の名前検索は禁止。ROOT_FOLDER_ID 以外で folder を解決しない。

rclone remote は、上記 ROOT_FOLDER_ID を root とする Google Drive remote を使う。
remote 名そのものは環境ごとに決めてよい（例: `gdrive`）。パスは付けない。

## 環境変数一覧（値は書かない）

必須:

| 変数 | 用途 |
| --- | --- |
| `KEIBA_NAR_WEATHER_ARCHIVE_ROOT` | 一次 Archive root（絶対パス・Git tree 外） |
| `KEIBA_NAR_WEATHER_RESTORE_ROOT` | 検証用 restore root（一次とは別・空・Git tree 外） |
| `KEIBA_WEATHER_RCLONE_REMOTE` | rclone remote 名のみ（コロン/パス禁止） |
| `KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID` | 上記固定 ROOT_FOLDER_ID と完全一致必須 |

任意:

| 変数 | 用途 |
| --- | --- |
| `KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH` | 省略時 `weather/forecast` |
| `RCLONE_BIN` | rclone 実行ファイル（synthetic テスト用にも使用） |
| `RCLONE_CONFIG` | rclone 設定ファイルパス（値をログしない） |

integrity verify（Gradle）:

| 変数 | 用途 |
| --- | --- |
| `KEIBA_WEATHER_RESTORE_VERIFY_TEST` | `1` のときだけ `verifyRestoredArchive` 実行 |
| `KEIBA_NAR_WEATHER_RESTORE_ROOT` | 復元先（上と同じ） |

一次 capture（参考・本 Backup 手順とは別）:

| 変数 | 用途 |
| --- | --- |
| `KEIBA_LIVE_WEATHER_ARCHIVE_TEST` | `1` で live capture opt-in |
| `KEIBA_NAR_WEATHER_ARCHIVE_ROOT` | 一次 Archive |

秘密情報（`CLIENT_SECRET` / refresh token / `Authorization` / Cookie 等）を
環境変数に載せる場合でも、本スクリプトは値を表示しない。
コード・ログ・commit への credential 混入は禁止。

## 削除してよいもの / 削除禁止

削除してよい（一時）:

- `KEIBA_NAR_WEATHER_RESTORE_ROOT` 以下（verify 完了後の一時 restore）

削除禁止:

- `KEIBA_NAR_WEATHER_ARCHIVE_ROOT`（一次 Archive）
- Drive 上 `weather/forecast/` 配下の二次 Backup
- rclone.conf / OAuth credential（本 repo 外で保管）

## 実行順序（自宅・認証済み前提）

作業ディレクトリは repo root。

```bash
export KEIBA_NAR_WEATHER_ARCHIVE_ROOT='…'   # 既存一次 Archive（絶対パス）
export KEIBA_NAR_WEATHER_RESTORE_ROOT='…'   # 空の別 root（絶対パス）
export KEIBA_WEATHER_RCLONE_REMOTE='…'      # remote 名のみ
export KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID='1Qz1QAX58jrekp80kyH5jrRmHaggH2iJb'
# optional: export KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH='weather/forecast'
```

1. Backup（append-only copy + checksum check）

```bash
./tools/forecast_weather_secondary_backup/backup_to_drive.sh
```

- `rclone copy --ignore-existing`（既存 remote の上書き・削除なし）
- 直後に `rclone check --one-way --checksum`（一次 ⊆ remote）
- どちらか失敗したら fail-closed（二次作成だけでは OK にしない）

2. 独立 check（再確認）

```bash
./tools/forecast_weather_secondary_backup/check_primary_vs_remote.sh
```

3. Restore（一次とは別の空 root）

```bash
./tools/forecast_weather_secondary_backup/restore_from_drive.sh
```

- restore root が非空なら拒否
- 一次 Archive には書き込まない
- `rclone check` は remote → restore の one-way（remote ⊆ restore）

4. Integrity verify（既存 Kotlin 経路）

```bash
export KEIBA_WEATHER_RESTORE_VERIFY_TEST=1
# KEIBA_NAR_WEATHER_RESTORE_ROOT は restore 済みのまま
./gradlew :app:testDebugUnitTest --tests 'com.keiba.ai.NarForecastWeatherArchiveLiveTest.verifyRestoredArchive'
```

- 各 Snapshot で `NarForecastWeatherSnapshotStore.verifySnapshot` を実行
- `forecast.json` / `manifest.txt` / forecast SHA-256 / snapshot SHA-256 の整合を確認

5. Cleanup（任意）

```bash
# restore root のみ削除してよい。一次 Archive / Drive Backup は削除しない。
rm -rf -- "${KEIBA_NAR_WEATHER_RESTORE_ROOT}"
```

## スクリプトの fail-closed 挙動

失敗してよい（必須）ケース:

- 必須 env 欠落 / 空
- `KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID` が固定 ID と不一致
- Archive / restore が相対パス、または Git working tree 内
- primary と restore が同一または入れ子
- primary に `forecast.json` が無い
- restore root が非空
- rclone copy / check 失敗（差分・checksum 不一致含む）
- restore 後に snapshot が 0 件

禁止していること:

- remote 既存ファイルの上書き・削除（`--ignore-existing`、delete 系フラグ不使用）
- Drive folder の名前検索
- secret 値のログ出力
- production Kotlin への rclone 依存追加

## 会社で実施する実認証なし検証

```bash
python3 tools/forecast_weather_secondary_backup/test_secondary_backup_synthetic.py
```

fake `RCLONE_BIN` で copy/check の引数契約・fail-closed・secret 非表示を検証する。
実 Google Drive / OAuth は使わない。

## R4 判定メモ

- 本 prep（手順・script・synthetic）完了 ≠ R4 Weather COMPLETE
- R4 Weather COMPLETE には自宅での実 Drive Backup/Restore + `verifyRestoredArchive` PASS が必要
