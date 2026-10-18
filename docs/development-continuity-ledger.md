# 開発継続台帳・実行手順（正本）

- **状態:** 正本（進捗・次作業・証跡の入口）
- **基準日:** 2026-10-18 JST
- **対象:** NAR 地方競馬 Android アプリ。JRA は NAR 完成後の別段階。
- **上位ゴール:** `docs/product-goal-roadmap.md`
- **状態語:** `COMPLETE` / `VERIFY FIRST` / `FIX FIRST` / `REDESIGN` / `BLOCKED`
- **重要:** 本書は実装や検証そのものを完了扱いにしない。記録の更新だけで Gate を通過させない。

## 1. なぜこの台帳を置くか

同じ調査・同じ未解決点を会話ごとにやり直さないため、開始地点、確定事項、未解決条件、証拠、次の一手をここに固定する。
現在の実装状態は GitHub の指定 ref を最優先とし、会話・Drive・PDF は仕様意図と過去の証拠に使う。記憶や要約だけで現在のコード状態を断定しない。

## 2. 最新に優先する確定事項

1. **shadow 検証は完成後に実アプリを使いながら行う。** NAR 完成の受入前提・工程ゲートにはしない。旧文書の「R17 Shadow Acceptance」「R18 前の 30 日/1,000 レース/200 BUY」は旧仕様として残すが、ここで上書きする。
2. **オッズは予測・判断には使用しない。表示・保存は可。** 自動投票は仕様外。旧 Product Goal の EV / BUY-SKIP / オッズ依存予算は、矛盾解消まで production 仕様に採用しない。
3. **独自 NAR 馬 ID は公式 ID 調達を待つ先決条件ではない。** 候補 ID と「同一馬である証明」を分離する。候補を本番 feature へ接続するには個別の PIT・coverage・衝突隔離の受入が必要。
4. **PIT / 漏洩防止が性能より優先。** HorseOutcome / Payout を予測 feature に渡さない。予測時点より後の値、結果 CSV の後知恵、未来実測 Weather を使わない。
5. **固定分割:** train 2021--2023、validation 2024、モデル固定後に test 2025、OOT 2026。test/OOT を見て変更したら独立評価として失効を記録する。
6. **作業順は 16 ステップを守る。** 現工程が COMPLETE でない限り後工程を実装開始しない。

## 3. 開発ゴール

### NAR 完成

PIT-safe な発走前 Input Snapshot を使い、着順確率を Android で再現可能に推論・表示し、結果を別の append-only 記録として照合・評価できること。
Python/Android の feature name、順序、category ID、欠損・数値変換、prediction は許容差内で一致し、schema/SHA 不一致では fail-closed する。
自動投票は含まない。オッズは保存・表示してよいが予測、BUY/SKIP、推奨額の判断入力にはしない。

### 最終ゴール

NAR を完成・固定後、JRA 専用の Data/PIT/Dataset/Feature/Model を別契約で追加し、共通 Android へ統合する。NAR 候補 ID と JRA 識別子を直接同一視しない。

## 4. 正式ロードマップと現在判定

| # | 工程 | 状態 | 根拠と次の完了条件 |
|---:|---|---|---|
| 1 | PIT 基盤 | COMPLETE（既存範囲） | `docs/nar-pit-contract.md` と既存 guard。ただし新 input は都度 PIT 監査。 |
| 2 | V3 Dataset | COMPLETE（baseline 範囲） | baseline9 checkpoint。新 feature は別 candidate として再検証。 |
| 3 | V3 Transform | COMPLETE（baseline 範囲） | baseline9 input hashes。 |
| 4 | baseline trainer | COMPLETE | baseline9 checkpoint の trainer hash。 |
| 5 | baseline preflight | COMPLETE | baseline9 freeze evidence。 |
| 6 | baseline 学習 | COMPLETE | baseline9, best iteration 55。 |
| 7 | test / OOT | NOT STARTED | 2025/2026 を feature 選択に使わない。 |
| 8 | NAR 内部特徴量拡張 | VERIFY FIRST | sex1 は DEVELOPMENT_ELIGIBLE だが production 未接続。過去走4特徴量は下記 BLOCKED。 |
| 9 | 外部気象データ基盤 | VERIFY FIRST | Weather の保存/reader 実装はあるが feature production 採用・end-to-end 受入は未完。 |
| 10 | 競馬場・物理特徴量 | NOT STARTED | course × weather を PIT-safe な candidate として評価する前に #9 を完了。 |
| 11 | 最終モデル選択 | NOT STARTED | #8--#10 と calibration/順位分布の受入後。 |
| 12 | Android feature parity | VERIFY FIRST | 既存 parity 実績と、新 production package に対する再実証を分離。 |
| 13 | Android 本番推論 | NOT STARTED | #11/#12 後。 |
| 14 | 当日自動取得 | VERIFY FIRST | capture 実装と運用受入を分離。 |
| 15 | 結果評価・更新基盤 | NOT STARTED | immutable prediction と outcome 分離を end-to-end 受入。 |
| 16 | 完成版 | NOT STARTED | 1--15 の受入完了後。shadow は完成後の実運用検証。 |

## 5. 完了済み・確認済みの証拠

- baseline9: `data-manifests/nar-v3-baseline9/checkpoint.json`（main の blob `b31fa38`）。2021--2023 train、2024 validation、test/OOT opened は 0、独立 2 runs の 5/5 artifact 一致。
- sex1 candidate: [PR #13](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/13) は merged。DEVELOPMENT OOF 46,003 races、candidate log loss 0.320499 vs baseline 0.321050、bootstrap 95% CI [-0.0006753, -0.0004293]。**production/Android/2025/OOT を意味しない**。
- Weather: `docs/nar-observed-weather-contract.md` は historical final observed を `NOT_PIT_ELIGIBLE` と明記し、live capture と feature authorization を分離している。
- 既知の実機証拠: 開発ページ10（2026-10-15相当の会話記録）では Xperia 1 VIII 隔離 QA で install/start、read-only Ledger audit、DIAG/BUT FORBIDDEN、画面遷移中操作を PASS と報告。これは production release、Activity 再生成、例外表示、原アプリ Ledger 不変性の全受入ではない。
- Drive: `keiba_android_consolidated_spec_review_v1_1_2026-10-15.docx`（Drive ID `1GsIL7tjjquGGg7dtvk4KOTThJkinR5QM`）は仕様・実装突合の補助資料。GitHub current ref を置換しない。

## 6. Phase08 / 過去走4特徴量

**状態: BLOCKED（外部データ全件監査待ち。文書更新で解除しない）**

引継ぎされた候補 ID 集計（2026-10-15 時点の作業記録）:
- 1998--2024、324 か月、4,574,037 行
- 候補割当 4,573,242、候補 164,934、曖昧 354、未識別 441

**2026-10-18再検証で訂正:** 上記 `d7fc08b9f01e6b156a17065701aeb6c51ffb0f71` は `siyokugo-rgb/keiba-hozON` の `feature/phase08-horse-continuity-pit-guard-v1` で実際に解決できた。以前の「参照不能」という記録は別リポジトリ `lightgbm-android-build` 側で見つからなかったことを取り違えたもの。旧repoのツリーは619エントリ、新しい `lightgbm-android-build/main` は324エントリであり、**両repoの分岐を無視して同じHEADと扱わない**。

`data-manifests/nar-history/months.csv` のGit Blob SHA **`3eea220e8e2dfe558fcb6c542de4dd94602939a7` は両repoで完全一致**。これは**manifestファイルの内容一致**であって月次ZIP324本の全バイトを今回再検証した結果ではない。

旧repoの `nar_v3_internal_horse_id.py`（候補ID計算）、`nar_v3_build_internal_horse_registry.py`（SQLite台帳生成）、対応する合成テスト2本、31表記の競技区分証拠表を**ソースBlob一致の開発用移植候補**として回収。候補IDの独立個体証明・G2 historical PIT・G3全主催者coverageは引き続き未完。正式モデル・Androidや2025 TEST/OOTへの配線禁止。

解除に必要な順序:
1. 324 monthly ZIP の実体、SHA/CRC、parser/version、行数を再現可能な ledger に固定する。
2. 候補キー・最古観測・競技区分を記録し、同名/血統矛盾/改名疑義/複数同日出走を自動結合せず隔離する。
3. train/validation feature を、その race の `prediction_as_of` 以前に証明できる履歴のみで build する。coverage 不足を 0 / 未出走へ偽装しない。
4. Python feature parity、candidate ablation、regression/PIT tests を PASS してからのみ production 接続を提案する。

## 7. 仕様矛盾台帳

| 領域 | 旧記述 | 最新優先 | 取扱い |
|---|---|---|---|
| shadow | Product Goal §16--17: R17 を完成前受入 | 完成後にアプリで実施 | 旧文を削除せず、本台帳の最新決定を優先。 |
| odds / BUY | Product Goal §8--10: odds→EV→BUY/SKIP→budget | odds は表示/保存のみ、予測・判断に不使用 | EV/BUY/budget を production 化しない。 |
| Weather | 旧 V1 の Weather 不使用 | Weather Data Layer は完成前に PIT-safe 基盤として必要。ただし model 採用は別 Gate | historical final observed を T-60 入力へ流用しない。 |
| 予測時点 | T-60 が既存標準 | 実装は任意 `prediction_as_of` を維持 | T-30/T-15 を勝手に完成条件へ追加しない。 |

## 8. 毎回の必須手順（開始から記録まで）

1. **開始前:** `main` と対象 branch の HEAD、`git status`、関連 Issue/PR、変更対象の現行コード・テスト・本台帳を確認する。作業開始記録には ref/SHA、対象 Gate、状態、禁止境界、成功条件を残す。
2. **設計/PIT:** feature/input ごとに source、available-at、prediction-as-of、禁止結果列、欠損・fallback、train/serving parity を表にする。
3. **実装:** 最小差分に限定する。既存の証跡、snapshot、raw archive、unrelated dirty worktree は削除・上書きしない。
4. **検証:** unit/regression、PIT negative tests、schema/SHA/fail-closed、必要な Android/CI を実行する。失敗・skip・未実行を PASS と数えない。
5. **記録:** 変更と同じ PR にこの台帳または対象 Gate の checkpoint を更新し、commit SHA、実行 command、環境、PASS/FAIL/SKIP 数、artifact hash、未解決条件、次タスクを保存する。
6. **終了判定:** COMPLETE / VERIFY FIRST / FIX FIRST / REDESIGN / BLOCKED のいずれかを明記する。CI 成功と final HEAD を確認後に限り「記録済み」とする。

## 9. 今回の記録

- 目的: 会話・Drive・GitHub の入口を一つにし、過去走4特徴量を根拠なく再着手/完了扱いしない。
- 変更範囲: 文書のみ。
- 今回 Phase08 を解除しない。NAR 完成や shadow 完了も主張しない。
- 次タスク: reachable な Phase08 branch/artifact と 324-month input manifest を確定し、§6 の解除条件 1 から実証する。

## 10. Phase08 開発用ID基盤の復元作業（2026-10-18）

- **作業開始時 main:** `dc77103855d154a2986911e984f652f421063e40` / [前回マージ済みPR #32](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/32)。
- **元コード参照元:** `siyokugo-rgb/keiba-hozON@d7fc08b9f01e6b156a17065701aeb6c51ffb0f71`。別repo間の既存実装の回収であり**再実装しない**。旧repoのPhase08 905テストPASSと、新repoの回帰結果を同一視しない。
- **引継ぎPR:** この台帳を同じPRで更新し、開発専用5ファイル（計算器・台帳生成器・テスト2本・競技区分表）を取り込む。単体テストはソースが全てsynthetic、1998–2024限定、2025/2026拒否、SQLite出力は新規ディレクトリに限定。
- **移植時の禁止条件:** `app/`、正式 `config/`、baseline9、現在のraw archive、Dataset、モデル、PredictionSnapshot、test/OOTは変更しない。独自ID候補を検証済みグローバルIDへ昇格させない。
- **QA受入:** PR GitHub Actionsで `test_nar_v3_internal_horse_id` と `test_nar_v3_build_internal_horse_registry` の合成正常・負例を独立実行。結果が出るまで VERIFY FIRST。
- **残課題:** Drive月次ZIP324本との全件再現チェック、2021–24当時利用時刻の証拠（G2）、cross-authority正当性（G1/G3）、Feature個別昇格、モデル/Android parity。第8工程は VERIFY FIRST、4特徴量本番は BLOCKED。
- **次の作業:** PR単体/異常系CIを確認→旧候補台帳の固定registry fingerprint（既存 `6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432`）と manifest SHAを参照基準にDrive実データ再生成可否判定→重大差がなければ次Gateへ。全件ZIPを今回再読していないことを維持。


### 10.1 復元実行後のチェックポイント（実施済み）

- **復元コード commit:** `e6d3fd64c8e91fb4f7c205da163b53c8fd691673`。元の5ファイルの**Blob SHA完全一致**（新しい内容への再実装ではない）。
- **PR:** [#33 MERGED](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/33)。`main`へのマージコミット `747f2a81bc028bdc27186bd553457dd8c16feba8`。コード以外は開発継続台帳・独立QA workflowのみ。
- **CI 1:** [Actions #39116825855](https://github.com/siyokugo-rgb/lightgbm-android-build/actions/runs/39116825855) 成功、`test_nar_v3_internal_horse_id` + `test_nar_v3_build_internal_horse_registry` **37/37 PASS**。
- **CI 2:** [Actions #39116844733](https://github.com/siyokugo-rgb/lightgbm-android-build/actions/runs/39116844733) PR用の同じ37件 **37/37 PASS**。
- **CI 3（マージ直前のPR HEAD）:** [Actions #39116937514](https://github.com/siyokugo-rgb/lightgbm-android-build/actions/runs/39116937514) **SUCCESS**。文書追記後のHEAD `b96b27d51f550706254fd2a84bfe9390544b4f6a` と一緒に受入。新 `main`へのマージ操作そのものに単体テスト結果を捏造しない。
- **実行環境:** GitHub Actions `ubuntu-24.04` / Python `3.12`、`PYTHONPATH=tools python3 -m unittest -v test_nar_v3_internal_horse_id test_nar_v3_build_internal_horse_registry`、さらに`py_compile`・`git diff --check`・tracked clean checkをPASS。
- **制限:** 今回の37件は合成・異常系であり、324月ZIP実体をCIで読み直したものでも、旧repoの905件を新repoで再実行したものでもない。馬IDは**CANDIDATE_ONLY**、4特徴量productionは**BLOCKED**、Phase08 **VERIFY FIRST**。
- **受入後の次作業:** 既存Drive `nar-history` ZIPの現実体がmanifestの `sha256` / `horselist_rows` と一致するか安全に検査し、旧固定 `source_manifest_sha256=7275040fc666407382c28eee47e2ef9882dbfc2781dd42b778ccd0a88c2c26af`、`logical_registry_fingerprint_sha256=6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432` に照合する。検証済み個体識別やhistorical PITを生成したとみなさない。

### 10.2 終了判定（この復元作業のみ）

**COMPLETE:** 独自ID候補の発行器・SQLite台帳生成器・31場分類マップと合成QAの旧repoから正本への**コード移植・PR統合**。原本5ファイルはGit Blob SHAが旧repoと一致し、CI合成37/37 PASS。

**VERIFY FIRST:** Driveの1998–2024年324月ZIP実データから新正本の同一台帳を再生成し、旧registry fingerprintと照合。ソースmanifestは2repoでBlob一致だが、**今回は324ZIP実バイトの再照合をしていない**。

**BLOCKED維持:** 同一馬の真正性・必要な全主催者履歴・2021–24の発走前結果のPIT証明・4特徴量本番採用。Stage #8は VERIFY FIRST。

**次回開始地点:** 新正本 `main` の現HEADと本台帳・PR #33・既存モデル/Android境界の照合→Drive 324 ZIPの再現性照合→不足した同一馬/外部過去走とPIT証拠を個別に埋める。今回作った内部IDコードを再実装しない。Shadowはアプリ完成後の利用時検証であり完成条件にしない。

## 11. Phase08 Drive原本バイト復旧チェックポイント（2026-10-18）

- **開始前HEAD** `ccc337bc78fdc7b6f6b2ae17c2b8b9b9c4b5fe68`、PR #33統合済み。対象Gateは第8工程の入力データ再現性（G0/G1前段）であり、本番の4特徴量認可ではない。
- **実施**：[詳細監査](./phase08-drive-source-byte-recheck-20261018.md)。Drive元原本の27年324月の存在/サイズ/重複をGitHubマニフェストと全件照合し **324/324 metadata PASS**。実バイトSHA-256 / ZIP3メンバーCRC / 馬行数は **1998年12か月 + 2021–24年48か月の合計60/60件・856,392馬行PASS**。1999–2020年264月は実バイト未再検査。
- **ツール**：`tools/nar_v3_source_byte_replay.py` を追加。既存のZIP入力/parser/期間ガードを再利用し、後続のバイト検証を簡単に再現できるようにする。合成QAをPRに接続する。
- **PIT**：過去年月ZIPは2026年に取得した後日原本。2021–24当時に発走前に入手可能だった証拠ではない。horse ID候補の真正性/全主催者coverageも未完。
- **判定**：60本の当日再取得原本integrityは限定COMPLETE、全324バイト再検証はVERIFY FIRST、Phase08 VERIFY FIRST、過去走4特徴量production BLOCKED。test/OOT未使用、shadowは完成後。
- **次回開始地点**：本台帳と[詳細監査](./phase08-drive-source-byte-recheck-20261018.md)の固定値を確認し、**1999～2020年264月のみ**を新CLIで順次検証→324月の旧SQLite台帳fingerprintと比較。検査済み60月を理由なく再実装・再調査しない。

### 11.1 原本再検査CLIのCI受入と再現性（2026-10-18）

- **実データ検証（ChatGPT接続のDrive原本を一時コンテナで直接実行）**：1998年12本+2021--2024年48本、計 **60 ZIP / 856,392馬行**の実ZIP SHA-256、内部3CSVのCRC・メンバー・行数がGitHub manifestと一致。1998年元ファイルの一部SHAを手作業転記した不一致2件（199806/199809）はGitHub記載値との再照合で転記ミスと判明し、原ZIPは一致。開発期間の48件は独立に **617,151行すべてPASS**。未調査の264件のZIPバイトをPASS扱いしない。
- **source-byte replay候補PR**：[PR #34](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/34)。本番モデル・Android・test/OOTに変更なし。
- **合成・異常系検査の初回CI**：[Actions #39118430286](https://github.com/siyokugo-rgb/lightgbm-android-build/actions/runs/39118430286) **FAILED (43 PASS, 1 ERROR)**。テストの破損ZIPをバイト数不一致にしてしまい、CRCではなくZIP構造例外になっていた。
- **修正 commit**：`a9a35f9c605c52657f72cce2fb09df355465ffe5`。合成fixtureの破損バイト数を維持し、CRC単体の不一致を検証できるように修正。
- **修正後CI**：[Actions #39118532297](https://github.com/siyokugo-rgb/lightgbm-android-build/actions/runs/39118532297) **44/44 PASS**（新規7テスト、従来37テストを含む）。CIは **実Drive ZIPを入力していない** ため、実データ60件の監査と同一実行とは表現しない。
- **終了判定**：原本60件のbyte replay/QAは対象限定COMPLETE。コード+QAのPR統合はマージ結果を確認して判定。**G1/G2/G3、本番4特徴量はBLOCKED / VERIFY FIRSTから変更なし**。

### 11.2 マージ終了記録

- [PR #34](https://github.com/siyokugo-rgb/lightgbm-android-build/pull/34) **MERGED** → 正本 `main` マージHEAD `1095f231aba247b03824b40024ad8011315eb614`。マージ後に `tools/nar_v3_source_byte_replay.py` と台帳を実際に読み戻し確認。
- マージ前の最終PR HEAD `c84252893f246f78043a71963b9aaca1863d984f` に対して [Actions #39118599493](https://github.com/siyokugo-rgb/lightgbm-android-build/actions/runs/39118599493) **SUCCESS、44/44 PASS**（新規7＋既存37）。これは合成ソース検査のCI。今回実Driveから取得した60件のZIP実測は別実行。
- **COMPLETE（限定）:** Driveファイルの324月metadata照合、1998年12月＋2021～2024年48月の60 ZIPバイト検証・確認と、それを今後再実施可能にする開発用CLI/QAの正本統合。
- **VERIFY FIRST:** 1999～2020年264月の実バイト再検査、324月を使うSQLite台帳の既存 `6c63a2e5...` fingerprintとの照合。
- **BLOCKED維持:** 2021～2024年T-60歴史的PIT、誤結合/外部・海外を含む直近走履歴、4特徴量本番採用。後日ZIPのCSV行数一致は historical prediction availability を証明しない。
- **次回の実行**：本台帳11章と監査 `docs/phase08-drive-source-byte-recheck-20261018.md` を読み、未検証264月だけのsource-byteチェックを進める。以後、G1内部IDの真正性・G2のPIT・G3 coverageを調達する。既存CI PASSを本番機能COMPLETEと混同せず、shadowはアプリ完成後。

## 12. Phase08 NAR ZIP全324か月のバイト整合性 COMPLETE（2026-10-18）

- **作業開始HEAD** `4ec53784d4eaa42982d3a0a169b30f37b20f54a2`、PR #34マージ・44/44 CI完了を確認してから着手。既存内部馬ID方式や月次parserを作り直さない。
- **実体全検査**：[原本324か月詳細レポート](./phase08-324-month-original-source-integrity-20261018.md)。以前未実施の1999～2020年264か月について、Drive原本実バイトのSHA-256/ZIP 3メンバーCRC/CSV header・行数を検査し、**264/264 PASS、337,831,167 byte、3,717,645馬行**。
- **合計**：1998～2024年 **324/324 ZIP、415,783,176バイト、4,574,037馬行全件PASS**。SHA digest `581feb17b834f3f9872e21f051e50c2c4095e01ff18924dcd5a71e23dc7939b7`、manifest SHA `7275040fc666407382c28eee47e2ef9882dbfc2781dd42b778ccd0a88c2c26af`。
- **証拠の固定**：`tools/phase08_source_324_integrity_receipt_v1.json` を受入記録とし、GitHub Actionsには**manifest/記録の契約QA（raw ZIPはCIにない）**を追加する。CIに実原本を取り込んだと誤記しない。
- **COMPLETEは原本のbyte integrityのみ**。次の必須作業は `nar_v3_build_internal_horse_registry.py` の**全324月実体での再生成→以前のSQLite台帳fingerprint `6c63a2e5...` と件数を照合**すること。こちらは引き続き VERIFY FIRST。
- **PITは別問題**。G1馬の真正同一性・G2予測前データ版の時刻証明・G3全主催者coverageは未成立。Phase08 VERIFY FIRST、過去走4特徴量本番BLOCKED。2025 TEST/OOT、モデル/Android/Snapshotに変更なし。

### 12.1 二つの元manifestのvintage差を分離（2026-10-18）

[324か月実バイト監査](./phase08-324-month-original-source-integrity-20261018.md)をGitHubでCI化した初回試験により、GitHubとDriveの`months.csv`は**別バイト版**と判明。Drive原本raw SHA256 `7275040f...`、GitHub版Git Blob SHA `3eea220e...`。343行の`checked_at_utc`が異なり、`sha256`差2行は**対象外の202606と202607のみ**。1998～2024年の324行のSHA/サイズ/CSV行数は全件一致。GitHub CIの契約検査はGitHub版Git Blobと対象期間欄をピン留めし、Drive原本のSHAと同一視しない。2025 TEST/2026 OOTのZIP実体は開いていない。初回CIがFAILした事実と修正を監査履歴として保持。4特徴量本番BLOCKEDは不変。
