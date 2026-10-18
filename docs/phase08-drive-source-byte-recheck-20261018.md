# Phase08: Drive NAR ZIP source bytes recovery checkpoint (2026-10-18)

**工程判定：2021～2024年の全48月の当日外で取得したZIP原本の integrity replay はCOMPLETE（対象48月のファイルバイトに限定）。1998年の12月も同じ検査でCOMPLETE。残り1999～2020年264月の今回は metadata のみPASS。G1/2/3と4特徴量本番はBLOCKED/VERIFY FIRSTを維持。**

## 作業開始状態・変更しない境界

- 正本 `siyokugo-rgb/lightgbm-android-build/main`、作業開始HEAD `ccc337bc78fdc7b6f6b2ae17c2b8b9b9c4b5fe68`。
- ソース GitHub `data-manifests/nar-history/months.csv` のGit Blob SHA `3eea220e8e2dfe558fcb6c542de4dd94602939a7`。旧repo `keiba-hozON` の同一パスとBlob一致。
- Driveの既存 `nar-history` 展開済み原本コピー（2026-08-27保存）の年別フォルダを読み取った。2025 TESTと2026 OOTは読み込んでいない（Driveルートのフォルダ一覧は参照したが各年原本は未開封）。
- **Drive上の作成日が2026年であり、過去の2021～2024年発走前に実際取得したPIT証拠ではない。**

## 実証済み範囲

| 検査 | 実対象 | 結果 |
|---|---|---|
| Drive年フォルダ／各ZIPファイル名、月欠落、重複、サイズ | 1998-01～2024-12、27年×12月＝**324件** | PASS：**324/324**件、サイズ総和**415,783,176 byte**、欠落/重複/サイズ差0 |
| ZIP実体SHA-256、各3CSVのCRC、3メンバー名、出走馬CSVヘッダー・行数 | **1998年の12件** | **12/12 PASS**、20,262,443 byte、239,241馬行 |
| 同上 | **2021-01～2024-12の48件** | **48/48 PASS**、57,689,566 byte、617,151馬行 |
| 実バイト検査総計 | **60件** | **60/60 PASS**、77,952,009 byte、856,392馬行 |
| 未実施範囲 | **1999-01～2020-12の264件** | 現回はZIPのメタデータ照合のみ。**実バイトSHA/CRCの再検査未実施** |

学習・validation用原本の各年の馬行: 2021=150,008 / 2022=152,959 / 2023=156,031 / 2024=158,153（合計617,151）。

### 再検証した生ZIPの集約固定値

各月について `YYYYMM:sha256_of_ZIP\n` を**月昇順**に連結したUTF-8の SHA-256:

- 1998年12件: `b28fa9487b267a15195b37748e86675c104c71ac240093aa57e0e071f85b86ca`
- 2021～2024年48件: `fc1d2ba6447d96535950fd3cab71029e752439beb658dcb161dd5be081c58b29`
- 両範囲を連結した60件: `d71a3ade5e6e441aecba78670886f567fa8bb7b7f9e4dd939615b0a3b22d34e5`

これらは原ZIP実バイトをSHA-256計算し、マニフェスト記載の各月固定ZIP SHA、サイズ、行数と照合後に生成した集約値。SHA集約は**ファイルのmetadataではなく、実ZIPのSHA256**から計算。SHA文字列を手入力で照合した際の199806/199809の転記ミスは、GitHubの正式な固定値と再突合して訂正済み。原本に不一致はなかった。

## 次回以降に再実行できる検査CLI

`tools/nar_v3_source_byte_replay.py` は既存 `audit_nar_horse_identity_2021_2023.py` の`checked_archive`/`read_month`と、移植済み`nar_v3_build_internal_horse_registry.py`の期間ガードを再利用。テスト/OOT期間は読まず、`--history-root` の原本構造 `manifests/months.csv` と `monthly/YYYY/YYYYMM_race.zip` を**読み取り専用**で検査する。

```bash
PYTHONPATH=tools python3 tools/nar_v3_source_byte_replay.py \
  --history-root /path/to/nar-history --first 202101 --last 202412 \
  --expected-manifest-sha256 7275040fc666407382c28eee47e2ef9882dbfc2781dd42b778ccd0a88c2c26af
```

成功時は`verified_months=48`, `horselist_rows=617151`、`monthly_zip_shas_digest=fc1d2...` が本書と一致する必要がある。新CLIの合成CIは実原本を読み込んだQAではないため、**この検査を現回のコンテナ実測と同じCI実行だったとは主張しない**。現回原本実測環境はPython 3.13.5 `hashlib/zipfile/csv`、GitHub Actionsの合成CIは別環境。

## 明示的に未達成のGate

- 候補馬ID台帳 **164,934候補、4,573,242候補割当、795隔離**は過去実行の数値を保持する。本番の個体認証ではない。
- 1998～2024全期間の新コードを用いたSQLite `registry_fingerprint` 再生成と、旧 `6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432` の比較は未実行。**今回の60月分から全324月の再現性を断定しない。**
- 2021–24当時にT-60で観測できた出馬表/結果公開時刻と過去版（G2）、誤結合のない馬のidentity（G1）、JRA/海外/ばんえい等の直近走coverage（G3）は**未認証**。
- 原本月次ZIPには結果列が同居するため、PIT証拠なしに予測特徴量へ使用しない。Feature/Model、Android、Snapshot、2025 TEST、2026 OOTは変更しない。

**次工程**：1999～2020年264件の原本バイト検証を段階的に終了し、全324月の固定manifest/SQLite fingerprintを再照合。その後にG1/G2/G3を特徴量別に受け入れ、4特徴量を不当にCOMPLETEへ昇格させない。
