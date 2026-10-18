# Phase08 全324か月の月次ZIP原本・バイト整合性検査（2026-10-18）

**結論：1998-01～2024-12の原本バイト整合性は限定COMPLETE。開発用の独自NAR馬ID真正性・2021～2024 historical PIT・全主催者coverage・過去走4特徴量の本番採用は未完でありBLOCKED/VERIFY FIRST維持。**

## 実施前の正本と既存証拠

正本 `siyokugo-rgb/lightgbm-android-build/main`、開始時HEAD `4ec53784d4eaa42982d3a0a169b30f37b20f54a2`。 [前回の60か月監査](./phase08-drive-source-byte-recheck-20261018.md) と、マージ済み[PR #34](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/34)から継続し、**当時未確認と明記した1999～2020年264か月**を重点検証した。既に作った `tools/nar_v3_source_byte_replay.py` は変更・再実装しない。

ユーザーがDriveへ2026-08-27に保存したNAR月別原本コピーを接続し、2026-10-18にローカル一時コンテナ（Python 3.13.5、hashlib/zipfile/csv）で読み直した。原本をGitHubや一般公開場所へ添付・複製・配布せず、証拠数値のみ保存した。GitHub `data-manifests/nar-history/months.csv` と同じ**41,523バイト**のDrive `nar-history/manifests/months.csv` を再取得し、SHA-256 `7275040fc666407382c28eee47e2ef9882dbfc2781dd42b778ccd0a88c2c26af` を照合。Git Blob SHAは `3eea220e8e2dfe558fcb6c542de4dd94602939a7`。

## 実バイト実測結果（read-only）

| 対象 | 原本ZIP | ZIP実バイト | horselist実行数 | 検査結果 |
|---|---:|---:|---:|---|
| 前回実証済み1998年12か月 + 2021～2024年48か月 | 60 | 77,952,009 | 856,392 | PASS |
| **今回の1999～2020年264か月** | **264** | **337,831,167** | **3,717,645** | **PASS** |
| **1998～2024全体** | **324** | **415,783,176** | **4,574,037** | **324/324 PASS** |

全ての `YYYYMM_race.zip` について以下を実行：実ファイルサイズとSHA-256をソースmanifestに比較、ZIP 3 CSVメンバー名の正確な一致、3メンバー全件CRC (`ZipFile.testzip`)、UTF-8 BOM処理後の `horselist.csv` ヘッダーの必要9項目/重複なし、各行の列幅とmanifest記載 `horselist_rows` を比較。検査コードは一時実行スクリプトで、同じ判定を再現する正式な読み取り専用CLIは既存 `tools/nar_v3_source_byte_replay.py` を使う（両実装の**同一実行結果**とは主張しない）。

**SHA集約規約**：各月の `YYYYMM:ZIP_SHA256\n` をYYYYMM昇順で連結したASCIIバイトのSHA-256。

| 対象 | SHA-256 |
|---|---|
| 今回の264件 | `321cc61514506f06a713466a218cc4e8f37a5fe5b34630997d2ea3c2cea2a739` |
| 以前実証した60件（分散期間） | `d71a3ade5e6e441aecba78670886f567fa8bb7b7f9e4dd939615b0a3b22d34e5` |
| **全324件** | `581feb17b834f3f9872e21f051e50c2c4095e01ff18924dcd5a71e23dc7939b7` |

監査受入 `tools/phase08_source_324_integrity_receipt_v1.json` に固定し、CIテスト `tools/test_nar_v3_324_source_integrity_receipt.py` がGitHub上のmanifestと件数/サイズ/集約SHAのドリフト、虚偽のPIT/production昇格を拒否する。**このGitHub Actionsは原本324 ZIPをロードしていない**。raw ZIPの324件PASSはChatGPT一時コンテナ上の2026-10-18実観測であり、CIのメタデータ検証と同一視しない。

## 保存済み結果と区別すべき未完了事項

1. **324か月のSQLite候補馬ID台帳の再生成/fingerprint照合は今回まだしていない。** 旧固定 `6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432` と比較することが次の課題。既存コードと1998～2024年の対象ZIPは揃ったが、本番馬UID認証ではない。
2. **G1**：164,934候補IDや4,573,242候補馬行などは開発用。独自IDを維持する確定方針だが、誤結合や改名・去勢・JRA/海外出走を証明なしで併合しない。
3. **G2**：月次ZIPは2026年に取得した後日原本。2021～2024年当時の発走前版/結果利用可能時刻は未認証。HorseOutcome/Payout予測特徴量への流入は禁止。
4. **G3**：NAR/JRA/海外を含む直近1/3/5実走の個体別完全性は未認証。欠落を0走として埋めない。
5. **2025 TEST / 2026 OOT**：Driveの対象ZIPは開かず、モデル選択にも利用していない。shadowは完成後に実アプリを使用しながら行う。

**工程判定**：第8工程VERIFY FIRST、4特徴量のproduction BLOCKED。完了条件はUID/履歴PIT/coverageとFeature個別評価に依存し、単なるZIP整合性の成功では解除されない。
