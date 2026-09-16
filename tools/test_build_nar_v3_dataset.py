#!/usr/bin/env python3

import csv
import gzip
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


HERE = Path(__file__).resolve().parent
MODULE_PATH = HERE / "build_nar_v3_dataset.py"

SPEC = importlib.util.spec_from_file_location(
    "build_nar_v3_dataset",
    MODULE_PATH,
)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def sha256_file(path):
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


class BuildNarV3DatasetTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.history = self.root / "history"
        self.out = self.root / "out"
        self.config = self.root / "config"
        self.manifests = self.root / "manifests"

        self.config.mkdir(parents=True)
        self.manifests.mkdir(parents=True)

        self.features = self.config / "features.json"
        self.labels = self.config / "labels.json"
        self.anomalies = self.manifests / "anomalies.csv"
        self.history_manifest_path = (
            self.history
            / "manifests"
            / "months.csv"
        )

        self.features.write_text(
            json.dumps(
                self.feature_cfg(),
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        self.labels.write_text(
            json.dumps(
                self.label_cfg(),
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        self.anomalies.write_text(
            (
                "anomaly_type,競馬場,競走年月日,"
                "レース番号,detail\n"
            ),
            encoding="utf-8",
        )

    def tearDown(self):
        self.tmp.cleanup()

    def feature_cfg(self):
        return {
            "version": 4,
            "prediction_point_policy":
                "runtime_prediction_as_of",
            "snapshot_selection_rule":
                (
                    "pit_evidence_at_epoch_millis <= "
                    "prediction_as_of_epoch_millis"
                ),
            "production_model_feature_rule":
                (
                    "feature_must_be_allowed_in_"
                    "training_and_serving_contexts"
                ),
            "data_contexts": [
                "HISTORICAL_MONTHLY",
                "LIVE_PIT_SNAPSHOT",
            ],
            "keys": {
                "race": [
                    "競馬場",
                    "競走年月日",
                    "レース番号",
                ],
                "entry": [
                    "競馬場",
                    "競走年月日",
                    "レース番号",
                    "馬番",
                ],
            },
            "historical_monthly_allowed_features": {
                "racelist": [
                    "競馬場",
                    "競走年月日",
                ],
                "horselist": [
                    "毛色",
                    "生年月日",
                    "父馬名",
                    "母馬名",
                    "母父馬名",
                ],
            },
            "historical_monthly_semantic_pending_live_allowed": {
                "racelist": [],
                "horselist": [
                    "性",
                    "齢",
                ],
            },
            "live_pit_snapshot_only_features": {
                "racelist": [
                    "距離",
                ],
                "horselist": [
                    "騎手名",
                    "負担重量",
                ],
            },
            "deferred_dynamic_sources": {
                "racelist": [
                    "天候",
                    "馬場",
                ],
                "horselist": [
                    "馬体重",
                    "人気",
                ],
            },
            "deferred_pending_audit": {
                "racelist": [
                    "発走時刻",
                ],
            },
            "never_prediction_features": {
                "racelist": [
                    "上がり3F",
                ],
                "horselist": [
                    "着順",
                    "タイム",
                    "着差",
                ],
                "payback": [
                    "*",
                ],
            },
            "label_sources": {
                "horselist": [
                    "着順",
                    "タイム",
                    "着差",
                ],
            },
            "source_anomaly_aliases": {
                "SOURCE_NO_RESULT":
                    "SOURCE_RESULT_UNAVAILABLE",
            },
            "v1_training_exclusions": [
                "SOURCE_RESULT_UNAVAILABLE",
                "SOURCE_RACELIST_MISSING",
                "SOURCE_HORSELIST_MISSING",
            ],
        }

    def label_cfg(self):
        return {
            "version": 1,
            "numeric_finish": {
                "source_column": "着順",
                "valid_when": "decimal_integer",
            },
            "entry_status_source": {
                "column": "着差",
            },
            "prestart_no_order_label": [
                "出走取消",
                "競走除外",
            ],
            "started_special_no_order_label": [
                "競走中止",
                "失格",
            ],
            "race_void_statuses": [
                "競走取止め",
                "競走不成立",
            ],
            "race_exclusion_rules": {
                "exclude_if_any_void_status":
                    True,
                "exclude_if_numeric_finish_count_is_zero":
                    True,
            },
            "derived_labels": {
                "numeric_finish_position": {
                    "numeric_finish":
                        "source_numeric_value",
                    "prestart_no_order_label":
                        None,
                    "started_special_no_order_label":
                        None,
                },
                "order_label_valid": {
                    "numeric_finish": 1,
                    "prestart_no_order_label": 0,
                    "started_special_no_order_label": 0,
                },
                "started": {
                    "numeric_finish": 1,
                    "出走取消": 0,
                    "競走除外": 0,
                    "競走中止": 1,
                    "失格": 1,
                },
                "finished": {
                    "numeric_finish": 1,
                    "出走取消": 0,
                    "競走除外": 0,
                    "競走中止": 0,
                    "失格": 0,
                },
                "win": {
                    "numeric_finish_equals_1": 1,
                    "other_numeric_finish": 0,
                    "競走中止": 0,
                    "失格": 0,
                    "出走取消": None,
                    "競走除外": None,
                },
                "top2": {
                    "numeric_finish_lte_2": 1,
                    "other_numeric_finish": 0,
                    "競走中止": 0,
                    "失格": 0,
                    "出走取消": None,
                    "競走除外": None,
                },
                "top3": {
                    "numeric_finish_lte_3": 1,
                    "other_numeric_finish": 0,
                    "競走中止": 0,
                    "失格": 0,
                    "出走取消": None,
                    "競走除外": None,
                },
            },
            "dead_heat_policy": {
                "preserve_official_equal_finish_positions":
                    True,
            },
            "pit_policy": {
                "final_prestart_status_is_not_a_prediction_feature":
                    True,
                "prestart_status_may_only_mask_result_labels":
                    True,
            },
        }

    def create_source(
        self,
        ym="202608",
        duplicate_horse_header=False,
        finish="1",
        status="",
        extra_numeric=False,
    ):
        year = ym[:4]
        month_dir = (
            self.history
            / "monthly"
            / year
        )
        month_dir.mkdir(
            parents=True,
            exist_ok=True,
        )

        zip_path = (
            month_dir
            / f"{ym}_race.zip"
        )

        race_csv = (
            "競馬場,競走年月日,レース番号,距離,上がり3F\n"
            "大井,20260829,1,1200,36.1\n"
        )

        if duplicate_horse_header:
            horse_csv = (
                "競馬場,競走年月日,レース番号,馬番,"
                "毛色,毛色,生年月日,父馬名,母馬名,母父馬名,"
                "着順,着差\n"
                "大井,20260829,1,1,鹿毛,鹿毛,20200101,"
                f"父,母,母父,{finish},{status}\n"
            )
        else:
            horse_csv = (
                "競馬場,競走年月日,レース番号,馬番,"
                "毛色,生年月日,父馬名,母馬名,母父馬名,"
                "着順,着差\n"
                "大井,20260829,1,1,鹿毛,20200101,"
                f"父,母,母父,{finish},{status}\n"
            )

            if extra_numeric:
                horse_csv += (
                    "大井,20260829,1,2,栗毛,20200202,"
                    "父2,母2,母父2,1,\n"
                )

        payback_csv = (
            "競馬場,競走年月日,レース番号,払戻\n"
            "大井,20260829,1,100\n"
        )

        with zipfile.ZipFile(
            zip_path,
            "w",
            compression=zipfile.ZIP_DEFLATED,
        ) as zf:
            zf.writestr(
                f"{ym}_racelist.csv",
                race_csv.encode(
                    "utf-8-sig"
                ),
            )
            zf.writestr(
                f"{ym}_horselist.csv",
                horse_csv.encode(
                    "utf-8-sig"
                ),
            )
            zf.writestr(
                f"{ym}_payback.csv",
                payback_csv.encode(
                    "utf-8-sig"
                ),
            )

        self.history_manifest_path.parent.mkdir(
            parents=True,
            exist_ok=True,
        )

        with self.history_manifest_path.open(
            "w",
            encoding="utf-8",
            newline="",
        ) as f:
            writer = csv.DictWriter(
                f,
                fieldnames=[
                    "ym",
                    "status",
                    "bytes",
                    "sha256",
                    "racelist_rows",
                    "horselist_rows",
                    "payback_rows",
                    "checked_at_utc",
                    "error",
                ],
                lineterminator="\n",
            )
            writer.writeheader()
            writer.writerow({
                "ym": ym,
                "status": "OK",
                "bytes": str(
                    zip_path.stat().st_size
                ),
                "sha256": sha256_file(
                    zip_path
                ),
                "racelist_rows": "1",
                "horselist_rows": (
                    "2"
                    if extra_numeric
                    else "1"
                ),
                "payback_rows": "1",
                "checked_at_utc":
                    "2026-08-29T00:00:00Z",
                "error": "",
            })

        return zip_path

    def build(self, ym="202608"):
        history_manifest = MOD.load_history_manifest(
            self.history_manifest_path
        )

        return MOD.build_month(
            ym=ym,
            history_root=self.history,
            out_root=self.out,
            feature_cfg_path=self.features,
            label_cfg_path=self.labels,
            anomaly_path=self.anomalies,
            history_manifest=history_manifest,
        )

    def test_outputs_only_seven_prediction_features(self):
        self.create_source()

        out_path, sidecar_path, counts = self.build()

        with gzip.open(
            out_path,
            "rt",
            encoding="utf-8",
            newline="",
        ) as f:
            rows = list(
                csv.DictReader(f)
            )

        self.assertEqual(
            1,
            len(rows),
        )

        row = rows[0]

        feature_names = {
            name
            for name in row
            if name.startswith(
                "feature_"
            )
        }

        self.assertEqual(
            {
                "feature_race__競馬場",
                "feature_race__競走年月日",
                "feature_entry__毛色",
                "feature_entry__生年月日",
                "feature_entry__父馬名",
                "feature_entry__母馬名",
                "feature_entry__母父馬名",
            },
            feature_names,
        )

        self.assertNotIn(
            "feature_race__距離",
            row,
        )
        self.assertNotIn(
            "feature_entry__着順",
            row,
        )
        self.assertEqual(
            "1",
            row["label_win"],
        )
        self.assertEqual(
            "1",
            row[
                "label_numeric_finish_position"
            ],
        )

        sidecar = json.loads(
            sidecar_path.read_text(
                encoding="utf-8"
            )
        )

        self.assertEqual(
            sha256_file(out_path),
            sidecar["output_sha256"],
        )
        self.assertEqual(
            7,
            len(
                sidecar[
                    "feature_columns"
                ]
            ),
        )
        self.assertEqual(
            1,
            counts["output_entries"],
        )

    def test_source_hash_mismatch_fails_closed(self):
        zip_path = self.create_source()

        with zip_path.open("ab") as f:
            f.write(b"tamper")

        with self.assertRaises(
            ValueError
        ):
            self.build()

    def test_duplicate_csv_header_fails_closed(self):
        self.create_source(
            duplicate_horse_header=True
        )

        with self.assertRaises(
            ValueError
        ):
            self.build()

    def test_unknown_result_state_fails_closed(self):
        self.create_source(
            finish="",
            status="UNKNOWN",
            extra_numeric=True,
        )

        with self.assertRaises(
            ValueError
        ):
            self.build()

    def test_historical_feature_cannot_overlap_live_only(self):
        cfg = self.feature_cfg()
        cfg[
            "historical_monthly_allowed_features"
        ]["racelist"].append(
            "距離"
        )

        self.features.write_text(
            json.dumps(
                cfg,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        with self.assertRaises(
            ValueError
        ):
            MOD.validate_feature_contract(
                MOD.load_json(
                    self.features
                )
            )


    def test_unclassified_feature_schema_drift_fails_closed(self):
        cfg = self.feature_cfg()
        cfg[
            "historical_monthly_allowed_features"
        ]["racelist"].append(
            "新特徴"
        )

        self.features.write_text(
            json.dumps(
                cfg,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        with self.assertRaises(
            ValueError
        ):
            MOD.validate_feature_contract(
                MOD.load_json(
                    self.features
                )
            )

    def test_label_source_column_drift_fails_closed(self):
        cfg = self.label_cfg()
        cfg["numeric_finish"][
            "source_column"
        ] = "別列"

        self.labels.write_text(
            json.dumps(
                cfg,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        with self.assertRaises(
            ValueError
        ):
            MOD.validate_label_contract(
                MOD.load_json(
                    self.labels
                )
            )

    def test_label_status_group_overlap_fails_closed(self):
        cfg = self.label_cfg()
        cfg[
            "race_void_statuses"
        ].append(
            "失格"
        )

        self.labels.write_text(
            json.dumps(
                cfg,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        with self.assertRaises(
            ValueError
        ):
            MOD.validate_label_contract(
                MOD.load_json(
                    self.labels
                )
            )

    def test_derived_label_contract_drift_fails_closed(self):
        cfg = self.label_cfg()
        cfg["derived_labels"][
            "win"
        ]["出走取消"] = 0

        self.labels.write_text(
            json.dumps(
                cfg,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        with self.assertRaises(
            ValueError
        ):
            MOD.validate_label_contract(
                MOD.load_json(
                    self.labels
                )
            )

    def sex1_feature_cfg(self):
        cfg = self.feature_cfg()
        cfg["candidate_profile"] = (
            MOD.CANDIDATE_PROFILE_SEX1
        )
        cfg[
            "historical_monthly_allowed_features"
        ]["horselist"] = [
            "毛色",
            "生年月日",
            "父馬名",
            "母馬名",
            "母父馬名",
            "性",
        ]
        cfg[
            "historical_monthly_semantic_pending_live_allowed"
        ]["horselist"] = [
            "齢",
        ]
        return cfg

    def write_sex1_features(self):
        self.features.write_text(
            json.dumps(
                self.sex1_feature_cfg(),
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

    def create_sex1_source(
        self,
        ym="202101",
        horses=None,
    ):
        if horses is None:
            horses = [
                {
                    "date": "20210115",
                    "race": "1",
                    "num": "1",
                    "sex": "牡",
                    "finish": "1",
                    "status": "",
                    "color": "鹿毛",
                    "birth": "20180101",
                    "sire": "父",
                    "dam": "母",
                    "damsire": "母父",
                }
            ]

        year = ym[:4]
        month_dir = (
            self.history
            / "monthly"
            / year
        )
        month_dir.mkdir(
            parents=True,
            exist_ok=True,
        )
        zip_path = (
            month_dir
            / f"{ym}_race.zip"
        )

        race_rows = [
            "競馬場,競走年月日,レース番号,距離,上がり3F"
        ]
        horse_rows = [
            "競馬場,競走年月日,レース番号,馬番,"
            "毛色,生年月日,父馬名,母馬名,母父馬名,性,"
            "着順,着差,齢"
        ]
        seen_races = set()

        for horse in horses:
            race_key = (
                horse["date"],
                horse["race"],
            )
            if race_key not in seen_races:
                seen_races.add(race_key)
                race_rows.append(
                    f"大井,{horse['date']},"
                    f"{horse['race']},1200,36.1"
                )
            horse_rows.append(
                "大井,"
                f"{horse['date']},"
                f"{horse['race']},"
                f"{horse['num']},"
                f"{horse['color']},"
                f"{horse['birth']},"
                f"{horse['sire']},"
                f"{horse['dam']},"
                f"{horse['damsire']},"
                f"{horse['sex']},"
                f"{horse['finish']},"
                f"{horse['status']},"
                f"{horse.get('age', '3')}"
            )

        payback_rows = [
            "競馬場,競走年月日,レース番号,払戻"
        ]
        for date_text, race_no in sorted(
            seen_races
        ):
            payback_rows.append(
                f"大井,{date_text},{race_no},100"
            )

        with zipfile.ZipFile(
            zip_path,
            "w",
            compression=zipfile.ZIP_DEFLATED,
        ) as zf:
            zf.writestr(
                f"{ym}_racelist.csv",
                (
                    "\n".join(race_rows)
                    + "\n"
                ).encode("utf-8-sig"),
            )
            zf.writestr(
                f"{ym}_horselist.csv",
                (
                    "\n".join(horse_rows)
                    + "\n"
                ).encode("utf-8-sig"),
            )
            zf.writestr(
                f"{ym}_payback.csv",
                (
                    "\n".join(payback_rows)
                    + "\n"
                ).encode("utf-8-sig"),
            )

        self.history_manifest_path.parent.mkdir(
            parents=True,
            exist_ok=True,
        )
        with self.history_manifest_path.open(
            "w",
            encoding="utf-8",
            newline="",
        ) as f:
            writer = csv.DictWriter(
                f,
                fieldnames=[
                    "ym",
                    "status",
                    "bytes",
                    "sha256",
                    "racelist_rows",
                    "horselist_rows",
                    "payback_rows",
                    "checked_at_utc",
                    "error",
                ],
                lineterminator="\n",
            )
            writer.writeheader()
            writer.writerow({
                "ym": ym,
                "status": "OK",
                "bytes": str(
                    zip_path.stat().st_size
                ),
                "sha256": sha256_file(
                    zip_path
                ),
                "racelist_rows": str(
                    len(seen_races)
                ),
                "horselist_rows": str(
                    len(horses)
                ),
                "payback_rows": str(
                    len(seen_races)
                ),
                "checked_at_utc":
                    "2026-08-29T00:00:00Z",
                "error": "",
            })

        return zip_path

    def build_sex1(self, ym="202101"):
        history_manifest = (
            MOD.load_history_manifest(
                self.history_manifest_path
            )
        )
        return MOD.build_month(
            ym=ym,
            history_root=self.history,
            out_root=self.out,
            feature_cfg_path=self.features,
            label_cfg_path=self.labels,
            anomaly_path=self.anomalies,
            history_manifest=
                history_manifest,
            candidate_profile=
                MOD.CANDIDATE_PROFILE_SEX1,
        )

    def test_production_mode_rejects_sex1_schema(self):
        self.write_sex1_features()

        with self.assertRaises(
            ValueError
        ):
            MOD.validate_feature_contract(
                MOD.load_json(
                    self.features
                )
            )

    def test_sex1_mode_outputs_eight_raw_features(self):
        self.write_sex1_features()
        self.create_sex1_source(
            horses=[
                {
                    "date": "20210115",
                    "race": "1",
                    "num": "1",
                    "sex": "牡",
                    "finish": "1",
                    "status": "",
                    "color": "鹿毛",
                    "birth": "20180101",
                    "sire": "父",
                    "dam": "母",
                    "damsire": "母父",
                },
                {
                    "date": "20210115",
                    "race": "1",
                    "num": "2",
                    "sex": "牝",
                    "finish": "2",
                    "status": "",
                    "color": "栗毛",
                    "birth": "20180202",
                    "sire": "父2",
                    "dam": "母2",
                    "damsire": "母父2",
                },
                {
                    "date": "20210115",
                    "race": "1",
                    "num": "3",
                    "sex": "セン",
                    "finish": "3",
                    "status": "",
                    "color": "青毛",
                    "birth": "20180303",
                    "sire": "父3",
                    "dam": "母3",
                    "damsire": "母父3",
                },
            ]
        )

        out_path, sidecar_path, counts = (
            self.build_sex1()
        )

        with gzip.open(
            out_path,
            "rt",
            encoding="utf-8",
            newline="",
        ) as f:
            rows = list(
                csv.DictReader(f)
            )

        self.assertEqual(3, len(rows))
        feature_names = {
            name
            for name in rows[0]
            if name.startswith(
                "feature_"
            )
        }
        self.assertEqual(
            {
                "feature_race__競馬場",
                "feature_race__競走年月日",
                "feature_entry__毛色",
                "feature_entry__生年月日",
                "feature_entry__父馬名",
                "feature_entry__母馬名",
                "feature_entry__母父馬名",
                "feature_entry__性",
            },
            feature_names,
        )
        self.assertEqual(
            8,
            len(feature_names),
        )
        self.assertNotIn(
            "feature_entry__齢",
            rows[0],
        )
        self.assertNotIn(
            "feature_entry__着順",
            rows[0],
        )
        self.assertNotIn(
            "feature_entry__タイム",
            rows[0],
        )
        self.assertNotIn(
            "feature_entry__着差",
            rows[0],
        )

        sexes = {
            row["feature_entry__性"]
            for row in rows
        }
        self.assertEqual(
            {"牡", "牝", "セン"},
            sexes,
        )

        sidecar = json.loads(
            sidecar_path.read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(
            "nar-v3-sex1-pit-safe",
            sidecar["dataset"],
        )
        self.assertEqual(
            MOD.CANDIDATE_PROFILE_SEX1,
            sidecar["candidate_profile"],
        )
        self.assertEqual(
            8,
            len(
                sidecar[
                    "feature_columns"
                ]
            ),
        )
        self.assertEqual(
            sha256_file(out_path),
            sidecar["output_sha256"],
        )
        self.assertEqual(
            3,
            counts["output_entries"],
        )

    def test_sex1_keeps_missing_and_unexpected_sex_raw(self):
        self.write_sex1_features()
        self.create_sex1_source(
            horses=[
                {
                    "date": "20210115",
                    "race": "1",
                    "num": "1",
                    "sex": "",
                    "finish": "1",
                    "status": "",
                    "color": "鹿毛",
                    "birth": "20180101",
                    "sire": "父",
                    "dam": "母",
                    "damsire": "母父",
                },
                {
                    "date": "20210115",
                    "race": "1",
                    "num": "2",
                    "sex": "不明",
                    "finish": "2",
                    "status": "",
                    "color": "栗毛",
                    "birth": "20180202",
                    "sire": "父2",
                    "dam": "母2",
                    "damsire": "母父2",
                },
            ]
        )

        out_path, _, _ = self.build_sex1()
        with gzip.open(
            out_path,
            "rt",
            encoding="utf-8",
            newline="",
        ) as f:
            rows = list(
                csv.DictReader(f)
            )

        by_num = {
            row["entry_id"].split("|")[-1]:
                row["feature_entry__性"]
            for row in rows
        }
        self.assertEqual("", by_num["1"])
        self.assertEqual(
            "不明",
            by_num["2"],
        )

    def test_sex1_preserves_per_race_sex_without_backfill(self):
        self.write_sex1_features()
        self.create_sex1_source(
            horses=[
                {
                    "date": "20210110",
                    "race": "1",
                    "num": "1",
                    "sex": "牡",
                    "finish": "1",
                    "status": "",
                    "color": "鹿毛",
                    "birth": "20170101",
                    "sire": "父A",
                    "dam": "母A",
                    "damsire": "母父A",
                },
                {
                    "date": "20210120",
                    "race": "2",
                    "num": "1",
                    "sex": "セン",
                    "finish": "1",
                    "status": "",
                    "color": "鹿毛",
                    "birth": "20170101",
                    "sire": "父A",
                    "dam": "母A",
                    "damsire": "母父A",
                },
            ]
        )

        out_path, _, _ = self.build_sex1()
        with gzip.open(
            out_path,
            "rt",
            encoding="utf-8",
            newline="",
        ) as f:
            rows = list(
                csv.DictReader(f)
            )

        by_date = {
            row["feature_race__競走年月日"]:
                row["feature_entry__性"]
            for row in rows
        }
        self.assertEqual(
            "牡",
            by_date["20210110"],
        )
        self.assertEqual(
            "セン",
            by_date["20210120"],
        )

    def test_sex1_period_guard_rejects_2025_before_source_open(self):
        opened = {
            "called": False,
        }
        original = MOD.validate_source_archive

        def guard(*args, **kwargs):
            opened["called"] = True
            return original(*args, **kwargs)

        MOD.validate_source_archive = guard
        try:
            with self.assertRaises(
                ValueError
            ) as ctx:
                MOD.assert_candidate_development_period(
                    ["202501"]
                )
            self.assertIn(
                "DEVELOPMENT",
                str(ctx.exception),
            )
            self.assertFalse(
                opened["called"]
            )

            with self.assertRaises(
                ValueError
            ):
                MOD.assert_candidate_development_period(
                    ["202601"]
                )

            MOD.assert_candidate_development_period(
                ["202101", "202412"]
            )
        finally:
            MOD.validate_source_archive = (
                original
            )

    def test_sex1_build_rejects_locked_month_before_zip_open(self):
        self.write_sex1_features()
        # Intentionally do not create 202501 zip.
        history_manifest = {
            "202501": {
                "status": "OK",
                "bytes": "1",
                "sha256": "0" * 64,
            }
        }

        with self.assertRaises(
            ValueError
        ) as ctx:
            MOD.build_month(
                ym="202501",
                history_root=self.history,
                out_root=self.out,
                feature_cfg_path=
                    self.features,
                label_cfg_path=
                    self.labels,
                anomaly_path=
                    self.anomalies,
                history_manifest=
                    history_manifest,
                candidate_profile=
                    MOD.CANDIDATE_PROFILE_SEX1,
            )

        self.assertIn(
            "DEVELOPMENT",
            str(ctx.exception),
        )
        self.assertFalse(
            (
                self.history
                / "monthly"
                / "2025"
                / "202501_race.zip"
            ).exists()
        )

    def test_sex1_output_collision_with_baseline_root_fails(self):
        forbidden = (
            MOD.forbidden_candidate_out_roots()[0]
        )
        with self.assertRaises(
            ValueError
        ):
            MOD.assert_candidate_out_root_allowed(
                forbidden
            )

        alias = (
            self.root
            / "alias-baseline"
        )
        alias.symlink_to(
            forbidden,
            target_is_directory=True,
        )
        with self.assertRaises(
            ValueError
        ):
            MOD.assert_candidate_out_root_allowed(
                alias
            )

        MOD.assert_candidate_out_root_allowed(
            self.out
        )


if __name__ == "__main__":
    unittest.main()
