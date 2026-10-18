# Phase08 NAR内部馬ID候補台帳：324か月再生成fingerprint再現（2026-10-18）

**判定：全324月・4,574,037行の開発用馬ID候補台帳について、既存の集計値・ロジカルfingerprintの再現検算を限定COMPLETE。Phase08全体VERIFY FIRST、4過去走特徴量production BLOCKEDは不変。**

## 作業起点と検証の独立性

- 作業開始の正本 `siyokugo-rgb/lightgbm-android-build/main@64adcf6ab851aa09dfe6c4067186f0a071bc768b`。台帳第12章・PR #35を確認。旧NAR ID実装は同repoのPR #33で復旧済み。
- 2026-10-18にDriveから取得した1998～2024年のNAR実月次ZIP **324ファイル**を、隔離したローカル一時SQLiteで全件入力。前回第12章で全ZIPのSHA/CRC/CSV行数を既存manifestへ照合済み。**2025 TEST・2026 OOTのZIPは開いていない。**
- 処理の独立性：GitHub版 `tools/nar_v3_build_internal_horse_registry.py` と `tools/nar_v3_internal_horse_id.py` をソースで照合し、**同じ正規化・IDハッシュ・競技区分・3種類の矛盾隔離・最古観測キー・行順fingerprint演算を一時Python/SQLiteへ独立に再現**した。元の本番コード自体を動かしたものやコード差分を変更したと主張しない。
- Source SHA-256 はDrive版マニフェスト `7275040fc666407382c28eee47e2ef9882dbfc2781dd42b778ccd0a88c2c26af` に対応する。GitHub版は検査日時と対象外2026年2行でrawバイトが異なるが、1998～2024対象324月の**ZIP SHA/サイズ/行数は全て一致**している。既存[監査第12章](./phase08-324-month-original-source-integrity-20261018.md)を参照。
- Local：Python 3.13.5、標準ライブラリ`csv`, `sqlite3`, `hashlib`, `zipfile`。DBは一時コンテナ内のみ、GitHubへ5GB近いSQLiteファイルやNARデータを転載しない。

## 比較対象と検証結果

| 指標 | 2026-10-15の既存固定値 | 今回全324月の独立再生成 | 判定 |
|---|---:|---:|---|
| 入力元 ZIP | 324月 | 324月 | PASS |
| 読み取り馬行数 | 4,574,037 | 4,574,037 | PASS |
| `CANDIDATE_ONLY` 行数 | 4,573,242 | 4,573,242 | PASS |
| `AMBIGUOUS` 行数 | 354 | 354 | PASS |
| `UNMATCHABLE` 行数 | 441 | 441 | PASS |
| 一意の候補馬ID数 | 164,934 | 164,934 | PASS |

**旧ロジカル台帳fingerprint：** `6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432`

**今回実測fingerprint：** `6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432`（**完全一致**）。

矛盾隔離の内訳：`same_birth_pedigree_different_names=354`、`candidate attributes missing or invalid=441`。同名+生年月日で血統矛盾による全台帳隔離0、同日重複出走による追加隔離0。候補IDが発行された4,573,242馬行では最古観測日／最古観測エントリーIDが欠損している行は**0**。**最古観測は生涯初出走や新馬戦の確証ではない**。

受入証拠 `tools/phase08_internal_horse_registry_reproduction_v1.json` に件数・fingerprint・候補ID算出器のGit blob SHA・PIT未承認を固定。CI `tools/test_nar_v3_324_registry_reproduction_receipt.py` はソース変化・集計値改変・PIT昇格等を検出する。**GitHub Actionsで4,574,037行の元原本を再投入するわけではない**ので、CI合成QAを実体の再演算と混同しない。

## G1/G2/G3および正式工程の受入は別

1. **G1 個体同一性**：同じ5属性が全期間で本当に同一馬と証明されるか、改名・去勢・移籍・同名異馬、NAR-JRA連結の誤対応は今回認証されていない。内部ID設計の再議論・公式IDの先決条件化はしないが、曖昧な個体を自動結合しない。
2. **G2 当時のPIT**：2026年に取得した最終版ZIPは2021～2024年の各レースの`prediction_as_of`以前の公開値・訂正時刻の証明ではない。HorseOutcome/Payoutの発走前Feature投入は禁止。
3. **G3 履歴網羅性**：JRA/海外/平地/ばんえいを通じた直近1/3/5実出走の完全性、非出走・特殊結果の意味づけは未証明。欠損を0走/未出走へ変換しない。
4. **モデルとアプリ**：NAR V3 baseline9、Dataset、Transform、Android、PredictionSnapshot、2025 test/2026 OOTは変更・再学習していない。**shadowはアプリ完成後に実際に使用しながら検証する。**

**次の開発**：G1候補台帳内の個体別誤結合・左打切り条件を実データで分類して安全に認証できるサブセットを確立→G2の2021～24発走前版・当時利用可能時刻の独立証跡を確保→G3の全主催者coverage→初めて4特徴量を個別にproduction審査。未証明のままCOMPLETEにしない。
