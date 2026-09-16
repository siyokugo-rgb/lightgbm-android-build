#!/usr/bin/env python3

import argparse
import csv
import gzip
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE_PATH = HERE / "transform_nar_v3_dataset.py"
CONFIG_PATH = HERE.parent / "config" / "nar-v3-transform.json"
SEX1_CONFIG_PATH = (
    HERE.parent
    / "config"
    / "candidates"
    / "nar-v3-sex1"
    / "transform.json"
)
SEX1_CHECKPOINT_PATH = (
    HERE.parent
    / "data-manifests"
    / "candidates"
    / "nar-v3-sex1"
    / "dataset"
    / "checkpoint.json"
)
PRODUCTION_TRANSFORM_CHECKPOINT = (
    HERE.parent
    / "data-manifests"
    / "nar-v3-transformed"
    / "checkpoint.json"
)
PRODUCTION_BASELINE_CHECKPOINT = (
    HERE.parent
    / "data-manifests"
    / "nar-v3-baseline9"
    / "checkpoint.json"
)
PRODUCTION_BASELINE_CONFIG = (
    HERE.parent
    / "config"
    / "nar-v3-win-baseline9.json"
)

SPEC = importlib.util.spec_from_file_location(
    "transform_nar_v3_dataset",
    MODULE_PATH,
)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def sha256_file(path):
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            obj,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )


def gzip_csv(path, rows, header=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = header if header is not None else MOD.expected_raw_header()
    raw = path.open("wb")
    gz = gzip.GzipFile(
        fileobj=raw,
        mode="wb",
        filename="",
        mtime=0,
    )
    text = io.TextIOWrapper(
        gz,
        encoding="utf-8",
        newline="",
    )
    try:
        writer = csv.DictWriter(
            text,
            fieldnames=fields,
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(rows)
    finally:
        text.close()
        if not gz.closed:
            gz.close()
        if not raw.closed:
            raw.close()


def sample_row(
    ym,
    *,
    entry_suffix="1",
    venue="大井",
    color="鹿毛",
    sire="父A",
    dam="母A",
    damsire="母父A",
    race_date=None,
    birth_date="20200101",
):
    year = int(ym[:4])
    if race_date is None:
        race_date = f"{ym}15"
    race_id = f"{venue}|{race_date}|1"
    return {
        "race_id": race_id,
        "entry_id": f"{race_id}|{entry_suffix}",
        "split": MOD.split_for_year(year),
        "source_ym": ym,
        "meta_source_anomalies": "",
        "feature_race__競馬場": venue,
        "feature_race__競走年月日": race_date,
        "feature_entry__毛色": color,
        "feature_entry__生年月日": birth_date,
        "feature_entry__父馬名": sire,
        "feature_entry__母馬名": dam,
        "feature_entry__母父馬名": damsire,
        "label_result_status": "FINISHED",
        "label_numeric_finish_position": "1",
        "label_order_valid": "1",
        "label_started": "1",
        "label_finished": "1",
        "label_win": "1",
        "label_top2": "1",
        "label_top3": "1",
    }


def create_dataset(root, month_rows):
    dataset_root = root / "dataset"
    for ym, rows in month_rows.items():
        data = (
            dataset_root
            / "monthly"
            / ym[:4]
            / f"{ym}_entries.csv.gz"
        )
        gzip_csv(data, rows)
        sidecar = {
            "format_version": 1,
            "dataset": "nar-v3-pit-safe",
            "source_ym": ym,
            "output_file": data.name,
            "output_sha256": sha256_file(data),
            "output_bytes": data.stat().st_size,
            "feature_columns": (
                MOD.EXPECTED_RACE_RAW_FEATURES
                + MOD.EXPECTED_ENTRY_RAW_FEATURES
            ),
            "label_columns": MOD.LABEL_FIELDS,
            "counts": {
                "included_races": len(
                    {row["race_id"] for row in rows}
                ),
                "output_entries": len(rows),
            },
        }
        write_json(
            data.with_name(
                f"{ym}_entries.manifest.json"
            ),
            sidecar,
        )

    months = sorted(month_rows)
    root_hash = MOD.compute_dataset_root_hash(
        dataset_root,
        months,
    )
    checkpoint = {
        "format_version": 1,
        "dataset": "nar-v3-pit-safe",
        "period": {
            "start_ym": months[0],
            "end_ym": months[-1],
            "months": len(months),
        },
        "dataset_root_hash": {
            "algorithm": "sha256",
            "value": root_hash,
        },
    }
    checkpoint_path = root / "checkpoint.json"
    write_json(checkpoint_path, checkpoint)
    return dataset_root, checkpoint_path, root_hash


def config_for_root_hash(root_hash):
    cfg = json.loads(
        CONFIG_PATH.read_text(encoding="utf-8")
    )
    cfg["input_checkpoint"] = {
        "start_ym": "202101",
        "end_ym": "202607",
        "months": 67,
        "dataset_root_sha256": root_hash,
    }
    return cfg


def sample_sex1_row(ym, *, sex="牡", **kwargs):
    row = sample_row(ym, **kwargs)
    row["feature_entry__性"] = sex
    return row


def sex1_schema():
    return MOD.sex1_schema()


def sex1_config_for_root_hash(root_hash):
    cfg = json.loads(
        SEX1_CONFIG_PATH.read_text(encoding="utf-8")
    )
    cfg["input_checkpoint"] = {
        "start_ym": "202101",
        "end_ym": "202412",
        "months": 48,
        "dataset_root_sha256": root_hash,
    }
    return cfg


def create_sex1_dataset(root, month_rows):
    dataset_root = root / "dataset"
    schema = sex1_schema()
    header = MOD.expected_raw_header(schema)
    for ym, rows in month_rows.items():
        data = (
            dataset_root
            / "monthly"
            / ym[:4]
            / f"{ym}_entries.csv.gz"
        )
        gzip_csv(data, rows, header=header)
        sidecar = {
            "format_version": 1,
            "dataset": "nar-v3-sex1-pit-safe",
            "candidate_profile": MOD.CANDIDATE_PROFILE_SEX1,
            "source_ym": ym,
            "output_file": data.name,
            "output_sha256": sha256_file(data),
            "output_bytes": data.stat().st_size,
            "feature_columns": (
                schema["race_raw"]
                + schema["entry_raw"]
            ),
            "label_columns": MOD.LABEL_FIELDS,
            "counts": {
                "included_races": len(
                    {row["race_id"] for row in rows}
                ),
                "output_entries": len(rows),
            },
        }
        write_json(
            data.with_name(
                f"{ym}_entries.manifest.json"
            ),
            sidecar,
        )

    months = sorted(month_rows)
    root_hash = MOD.compute_dataset_root_hash(
        dataset_root,
        months,
    )
    checkpoint = {
        "format_version": 1,
        "dataset": "nar-v3-sex1-pit-safe",
        "candidate_profile": MOD.CANDIDATE_PROFILE_SEX1,
        "period": {
            "start_ym": months[0],
            "end_ym": months[-1],
            "months": len(months),
        },
        "dataset_root_hash": {
            "algorithm": "sha256",
            "value": root_hash,
        },
        "audit": {
            "full_dataset_audit": "PASS",
            "locked_oot_open": False,
            "source_ym_max": months[-1],
        },
    }
    checkpoint_path = root / "checkpoint.json"
    write_json(checkpoint_path, checkpoint)
    return dataset_root, checkpoint_path, root_hash, checkpoint


def sex1_identity_checkpoint(root_hash, **audit_overrides):
    audit = {
        "full_dataset_audit": "PASS",
        "locked_oot_open": False,
        "source_ym_max": "202412",
    }
    audit.update(audit_overrides)
    return {
        "format_version": 1,
        "dataset": "nar-v3-sex1-pit-safe",
        "candidate_profile": MOD.CANDIDATE_PROFILE_SEX1,
        "period": {
            "start_ym": "202101",
            "end_ym": "202412",
            "months": 48,
        },
        "dataset_root_hash": {
            "algorithm": "sha256",
            "value": root_hash,
        },
        "audit": audit,
    }


def candidate_args(**kwargs):
    values = {
        "start": "202101",
        "end": "202101",
        "dataset_root": Path("/tmp/missing-nar-v3-sex1-dataset"),
        "out": Path("/tmp/nar-v3-sex1-transformed-unit"),
        "config": None,
        "checkpoint": None,
        "candidate_profile": MOD.CANDIDATE_PROFILE_SEX1,
    }
    values.update(kwargs)
    return argparse.Namespace(**values)


class TransformNarV3DatasetTest(unittest.TestCase):
    def test_config_rejects_schema_drift(self):
        cfg = json.loads(
            CONFIG_PATH.read_text(encoding="utf-8")
        )
        cfg["input_features"]["entry"].append(
            "feature_entry__着順"
        )
        with self.assertRaises(ValueError):
            MOD.validate_config(cfg)

    def test_numeric_date_features(self):
        row = sample_row(
            "202401",
            race_date="20240229",
            birth_date="20200101",
        )
        values = MOD.numeric_values(row)
        self.assertEqual(2, values["race_month"])
        self.assertEqual(
            60,
            values["race_day_of_year"],
        )
        self.assertEqual(
            3,
            values["race_weekday_mon0"],
        )
        self.assertEqual(
            (
                MOD.date(2024, 2, 29)
                - MOD.date(2020, 1, 1)
            ).days,
            values["age_days"],
        )

    def test_birth_after_race_rejected(self):
        row = sample_row(
            "202401",
            race_date="20240115",
            birth_date="20250101",
        )
        with self.assertRaises(ValueError):
            MOD.numeric_values(row)

    def test_unknown_category_uses_unknown_id(self):
        dictionaries = {
            "missing_id": 0,
            "unknown_id": 1,
            "features": {
                name: {
                    "value_to_id": {
                        "KNOWN": 2,
                    }
                }
                for name in MOD.EXPECTED_CATEGORICAL
            },
        }
        encoded, state = MOD.encode_category(
            "UNSEEN",
            MOD.EXPECTED_CATEGORICAL[0],
            dictionaries,
        )
        self.assertEqual(1, encoded)
        self.assertEqual("unknown", state)

    def test_missing_category_uses_missing_id(self):
        dictionaries = {
            "missing_id": 0,
            "unknown_id": 1,
            "features": {
                name: {
                    "value_to_id": {}
                }
                for name in MOD.EXPECTED_CATEGORICAL
            },
        }
        encoded, state = MOD.encode_category(
            "",
            MOD.EXPECTED_CATEGORICAL[0],
            dictionaries,
        )
        self.assertEqual(0, encoded)
        self.assertEqual("missing", state)

    def test_dictionary_fit_uses_train_only(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _ = create_dataset(
                root,
                {
                    "202101": [
                        sample_row(
                            "202101",
                            sire="TRAIN_SIRE",
                        )
                    ],
                    "202401": [
                        sample_row(
                            "202401",
                            sire="VALIDATION_ONLY",
                        )
                    ],
                },
            )
            cfg = json.loads(
                CONFIG_PATH.read_text(encoding="utf-8")
            )
            dictionaries = MOD.fit_category_dictionaries(
                dataset_root,
                ["202101", "202401"],
                cfg,
            )
            mapping = dictionaries["features"][
                "feature_entry__父馬名"
            ]["value_to_id"]
            self.assertIn("TRAIN_SIRE", mapping)
            self.assertNotIn("VALIDATION_ONLY", mapping)
            self.assertEqual(["202101"], dictionaries["fit_months"])

    def test_sidecar_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _ = create_dataset(
                root,
                {"202101": [sample_row("202101")]},
            )
            data = MOD.source_path(dataset_root, "202101")
            with data.open("ab") as f:
                f.write(b"tamper")
            with self.assertRaises(ValueError):
                MOD.verify_sidecar(
                    dataset_root,
                    "202101",
                )

    def test_root_hash_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, checkpoint_path, root_hash = create_dataset(
                root,
                {"202101": [sample_row("202101")]},
            )
            cfg = config_for_root_hash(root_hash)
            validated = MOD.validate_config(cfg)
            sidecar = MOD.sidecar_path(
                dataset_root,
                "202101",
            )
            obj = json.loads(
                sidecar.read_text(encoding="utf-8")
            )
            obj["counts"]["output_entries"] = 999
            write_json(sidecar, obj)
            with self.assertRaises(ValueError):
                MOD.validate_checkpoint(
                    dataset_root,
                    checkpoint_path,
                    validated["expected_root_hash"],
                )

    def test_transform_is_deterministic(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _ = create_dataset(
                root,
                {
                    "202101": [
                        sample_row(
                            "202101",
                            entry_suffix="1",
                        ),
                        sample_row(
                            "202101",
                            entry_suffix="2",
                            sire="父B",
                        ),
                    ]
                },
            )
            cfg = json.loads(
                CONFIG_PATH.read_text(encoding="utf-8")
            )
            dictionaries = MOD.fit_category_dictionaries(
                dataset_root,
                ["202101"],
                cfg,
            )
            cfg_path = root / "config.json"
            write_json(cfg_path, cfg)

            for out_name in ["out1", "out2"]:
                out_root = root / out_name
                artifact = out_root / "artifacts"
                dict_path = artifact / "category-dictionaries.json"
                feature_path = artifact / "feature-order.json"
                MOD.write_json_atomic(
                    dict_path,
                    dictionaries,
                )
                feature_order = MOD.build_feature_order(cfg)
                MOD.write_json_atomic(
                    feature_path,
                    feature_order,
                )
                MOD.transform_month(
                    ym="202101",
                    dataset_root=dataset_root,
                    out_root=out_root,
                    cfg_path=cfg_path,
                    dictionaries=dictionaries,
                    dictionary_path=dict_path,
                    feature_order=feature_order,
                    feature_order_path=feature_path,
                )

            p1 = root / "out1/monthly/2021/202101_model.csv.gz"
            p2 = root / "out2/monthly/2021/202101_model.csv.gz"
            self.assertEqual(
                sha256_file(p1),
                sha256_file(p2),
            )

    def test_transform_output_has_nine_model_features(self):
        order = MOD.build_feature_order(
            json.loads(
                CONFIG_PATH.read_text(encoding="utf-8")
            )
        )
        self.assertEqual(9, order["feature_count"])
        self.assertEqual(
            4,
            order["numeric_feature_count"],
        )
        self.assertEqual(
            5,
            order["categorical_feature_count"],
        )


class TransformNarV3Sex1CandidateTest(unittest.TestCase):
    def test_committed_sex1_config_matches_contract(self):
        cfg = json.loads(
            SEX1_CONFIG_PATH.read_text(encoding="utf-8")
        )
        schema = MOD.validate_config(
            cfg,
            MOD.CANDIDATE_PROFILE_SEX1,
        )
        self.assertEqual(8, len(schema["race_raw"] + schema["entry_raw"]))
        self.assertEqual(4, len(schema["numeric"]))
        self.assertEqual(6, len(schema["categorical"]))
        self.assertEqual(
            "feature_entry__性",
            schema["categorical"][-1],
        )
        self.assertNotIn(
            "feature_entry__齢",
            schema["entry_raw"],
        )
        self.assertEqual(
            MOD.SEX1_DATASET_ROOT_SHA256,
            schema["expected_root_hash"],
        )
        checkpoint = json.loads(
            SEX1_CHECKPOINT_PATH.read_text(encoding="utf-8")
        )
        self.assertEqual(
            "nar-v3-sex1-pit-safe",
            checkpoint["dataset"],
        )
        self.assertEqual(
            MOD.CANDIDATE_PROFILE_SEX1,
            checkpoint["candidate_profile"],
        )
        self.assertEqual(
            {
                "start_ym": "202101",
                "end_ym": "202412",
                "months": 48,
            },
            checkpoint["period"],
        )
        self.assertEqual(
            MOD.SEX1_DATASET_ROOT_SHA256,
            checkpoint["dataset_root_hash"]["value"],
        )
        self.assertEqual(
            "PASS",
            checkpoint["audit"]["full_dataset_audit"],
        )
        self.assertIs(
            False,
            checkpoint["audit"]["locked_oot_open"],
        )
        self.assertEqual(
            "202412",
            checkpoint["audit"]["source_ym_max"],
        )

    def test_production_config_still_has_nine_features(self):
        cfg = json.loads(
            CONFIG_PATH.read_text(encoding="utf-8")
        )
        schema = MOD.validate_config(cfg)
        order = MOD.build_feature_order(cfg, schema)
        self.assertEqual(9, order["feature_count"])
        self.assertEqual(4, order["numeric_feature_count"])
        self.assertEqual(5, order["categorical_feature_count"])
        self.assertEqual(
            [4, 5, 6, 7, 8],
            order["categorical_feature_indices"],
        )
        self.assertNotIn(
            "feature_entry__性",
            order["categorical_feature_names"],
        )
        self.assertEqual(
            9,
            json.loads(
                PRODUCTION_BASELINE_CONFIG.read_text(
                    encoding="utf-8"
                )
            )["input"]["feature_count"],
        )

    def test_production_mode_rejects_sex1_config(self):
        cfg = json.loads(
            SEX1_CONFIG_PATH.read_text(encoding="utf-8")
        )
        with self.assertRaises(ValueError):
            MOD.validate_config(cfg)

    def test_sex1_mode_rejects_production_config(self):
        cfg = json.loads(
            CONFIG_PATH.read_text(encoding="utf-8")
        )
        with self.assertRaises(ValueError):
            MOD.validate_config(
                cfg,
                MOD.CANDIDATE_PROFILE_SEX1,
            )

    def test_unknown_candidate_profile_fails_closed(self):
        with self.assertRaises(ValueError) as ctx:
            MOD.schema_for_profile("nar-v3-unknown")
        self.assertIn("unknown candidate profile", str(ctx.exception))
        cfg = json.loads(
            SEX1_CONFIG_PATH.read_text(encoding="utf-8")
        )
        with self.assertRaises(ValueError):
            MOD.validate_config(cfg, "nar-v3-unknown")

    def test_sex1_feature_order_is_ten(self):
        cfg = json.loads(
            SEX1_CONFIG_PATH.read_text(encoding="utf-8")
        )
        schema = MOD.validate_config(
            cfg,
            MOD.CANDIDATE_PROFILE_SEX1,
        )
        order = MOD.build_feature_order(cfg, schema)
        self.assertEqual(10, order["feature_count"])
        self.assertEqual(4, order["numeric_feature_count"])
        self.assertEqual(6, order["categorical_feature_count"])
        self.assertEqual(
            [4, 5, 6, 7, 8, 9],
            order["categorical_feature_indices"],
        )
        self.assertEqual(
            [
                "feature_race__競馬場",
                "feature_entry__毛色",
                "feature_entry__父馬名",
                "feature_entry__母馬名",
                "feature_entry__母父馬名",
                "feature_entry__性",
            ],
            order["categorical_feature_names"],
        )
        self.assertNotIn(
            "feature_entry__齢",
            [item["name"] for item in order["features"]],
        )
        self.assertEqual(8, len(schema["race_raw"] + schema["entry_raw"]))

    def test_sex1_dictionary_fit_uses_train_only(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _, _ = create_sex1_dataset(
                root,
                {
                    "202101": [
                        sample_sex1_row(
                            "202101",
                            sire="TRAIN_SIRE",
                            sex="牡",
                        )
                    ],
                    "202312": [
                        sample_sex1_row(
                            "202312",
                            sire="TRAIN_SIRE_2",
                            sex="牝",
                        )
                    ],
                    "202401": [
                        sample_sex1_row(
                            "202401",
                            sire="VALIDATION_ONLY",
                            sex="セン",
                        )
                    ],
                },
            )
            cfg = json.loads(
                SEX1_CONFIG_PATH.read_text(encoding="utf-8")
            )
            schema = sex1_schema()
            dictionaries = MOD.fit_category_dictionaries(
                dataset_root,
                ["202101", "202312", "202401"],
                cfg,
                schema,
            )
            sire_map = dictionaries["features"][
                "feature_entry__父馬名"
            ]["value_to_id"]
            sex_map = dictionaries["features"][
                "feature_entry__性"
            ]["value_to_id"]
            self.assertIn("TRAIN_SIRE", sire_map)
            self.assertIn("TRAIN_SIRE_2", sire_map)
            self.assertNotIn("VALIDATION_ONLY", sire_map)
            self.assertIn("牡", sex_map)
            self.assertIn("牝", sex_map)
            self.assertNotIn("セン", sex_map)
            self.assertEqual(
                ["202101", "202312"],
                dictionaries["fit_months"],
            )
            encoded, state = MOD.encode_category(
                "セン",
                "feature_entry__性",
                dictionaries,
            )
            self.assertEqual(1, encoded)
            self.assertEqual("unknown", state)

    def test_sex1_known_missing_unknown_encoding(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _, _ = create_sex1_dataset(
                root,
                {
                    "202101": [
                        sample_sex1_row("202101", sex="牡"),
                        sample_sex1_row(
                            "202101",
                            entry_suffix="2",
                            sex="牝",
                        ),
                        sample_sex1_row(
                            "202101",
                            entry_suffix="3",
                            sex="セン",
                        ),
                    ]
                },
            )
            cfg = json.loads(
                SEX1_CONFIG_PATH.read_text(encoding="utf-8")
            )
            schema = sex1_schema()
            dictionaries = MOD.fit_category_dictionaries(
                dataset_root,
                ["202101"],
                cfg,
                schema,
            )
            sex_map = dictionaries["features"][
                "feature_entry__性"
            ]["value_to_id"]
            self.assertEqual(
                ["セン", "牝", "牡"],
                sorted(sex_map),
            )
            self.assertEqual(2, sex_map["セン"])
            self.assertEqual(3, sex_map["牝"])
            self.assertEqual(4, sex_map["牡"])
            self.assertNotEqual(sex_map["牡"], sex_map["セン"])
            missing, missing_state = MOD.encode_category(
                "",
                "feature_entry__性",
                dictionaries,
            )
            self.assertEqual(0, missing)
            self.assertEqual("missing", missing_state)
            unknown, unknown_state = MOD.encode_category(
                "不明",
                "feature_entry__性",
                dictionaries,
            )
            self.assertEqual(1, unknown)
            self.assertEqual("unknown", unknown_state)

    def test_sex1_transform_outputs_ten_features_and_keeps_labels(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _, _ = create_sex1_dataset(
                root,
                {
                    "202101": [
                        sample_sex1_row("202101", sex="牡"),
                        sample_sex1_row(
                            "202101",
                            entry_suffix="2",
                            sex="セン",
                        ),
                        sample_sex1_row(
                            "202101",
                            entry_suffix="3",
                            sex="牝",
                        ),
                    ]
                },
            )
            cfg = json.loads(
                SEX1_CONFIG_PATH.read_text(encoding="utf-8")
            )
            schema = sex1_schema()
            dictionaries = MOD.fit_category_dictionaries(
                dataset_root,
                ["202101"],
                cfg,
                schema,
            )
            cfg_path = root / "config.json"
            write_json(cfg_path, cfg)
            out_root = root / "out"
            artifact = out_root / "artifacts"
            dict_path = artifact / "category-dictionaries.json"
            feature_path = artifact / "feature-order.json"
            MOD.write_json_atomic(dict_path, dictionaries)
            feature_order = MOD.build_feature_order(cfg, schema)
            MOD.write_json_atomic(feature_path, feature_order)
            out_path, sidecar_path, counts = MOD.transform_month(
                ym="202101",
                dataset_root=dataset_root,
                out_root=out_root,
                cfg_path=cfg_path,
                dictionaries=dictionaries,
                dictionary_path=dict_path,
                feature_order=feature_order,
                feature_order_path=feature_path,
                schema=schema,
            )
            sidecar = json.loads(
                sidecar_path.read_text(encoding="utf-8")
            )
            self.assertEqual(
                "nar-v3-sex1-model-input",
                sidecar["dataset"],
            )
            self.assertEqual(
                MOD.CANDIDATE_PROFILE_SEX1,
                sidecar["candidate_profile"],
            )
            self.assertEqual(10, len(sidecar["feature_columns"]))
            self.assertEqual(4, feature_order["numeric_feature_count"])
            self.assertEqual(6, feature_order["categorical_feature_count"])
            self.assertIn(
                "feature_entry__性",
                sidecar["feature_columns"],
            )
            self.assertNotIn(
                "feature_entry__齢",
                sidecar["feature_columns"],
            )
            self.assertEqual(
                [4, 5, 6, 7, 8, 9],
                feature_order["categorical_feature_indices"],
            )
            self.assertEqual(3, counts["rows"])
            with gzip.open(
                out_path,
                "rt",
                encoding="utf-8",
                newline="",
            ) as f:
                rows = list(csv.DictReader(f))
            self.assertEqual(3, len(rows))
            self.assertEqual(
                rows[0]["label_win"],
                "1",
            )
            self.assertIn("race_id", rows[0])
            sex_map = dictionaries["features"][
                "feature_entry__性"
            ]["value_to_id"]
            sex_ids = {
                row["feature_entry__性"]
                for row in rows
            }
            self.assertEqual(
                {
                    str(sex_map["牡"]),
                    str(sex_map["セン"]),
                    str(sex_map["牝"]),
                },
                sex_ids,
            )
            self.assertEqual(
                sidecar["source_sha256"],
                sha256_file(
                    MOD.source_path(dataset_root, "202101")
                ),
            )
            self.assertEqual(
                sidecar["output_sha256"],
                sha256_file(out_path),
            )

    def test_sex1_schema_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _ = create_dataset(
                root,
                {"202101": [sample_row("202101")]},
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.verify_sidecar(
                    dataset_root,
                    "202101",
                    sex1_schema(),
                )
            self.assertIn("dataset mismatch", str(ctx.exception))

    def test_sex1_candidate_profile_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _, _ = create_sex1_dataset(
                root,
                {"202101": [sample_sex1_row("202101")]},
            )
            sidecar = MOD.sidecar_path(dataset_root, "202101")
            obj = json.loads(sidecar.read_text(encoding="utf-8"))
            obj["candidate_profile"] = "nar-v3-other"
            write_json(sidecar, obj)
            with self.assertRaises(ValueError) as ctx:
                MOD.verify_sidecar(
                    dataset_root,
                    "202101",
                    sex1_schema(),
                )
            self.assertIn(
                "candidate_profile mismatch",
                str(ctx.exception),
            )

    def test_sex1_age_and_result_features_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _, _ = create_sex1_dataset(
                root,
                {"202101": [sample_sex1_row("202101")]},
            )
            sidecar = MOD.sidecar_path(dataset_root, "202101")
            obj = json.loads(sidecar.read_text(encoding="utf-8"))
            obj["feature_columns"] = obj["feature_columns"] + [
                "feature_entry__齢"
            ]
            write_json(sidecar, obj)
            with self.assertRaises(ValueError):
                MOD.verify_sidecar(
                    dataset_root,
                    "202101",
                    sex1_schema(),
                )

    def test_sex1_checkpoint_identity_failures(self):
        schema = sex1_schema()
        schema["expected_root_hash"] = "a" * 64
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root = root / "dataset"
            dataset_root.mkdir()

            cases = [
                (
                    {"dataset": "nar-v3-pit-safe"},
                    "dataset mismatch",
                ),
                (
                    {"candidate_profile": "other"},
                    "candidate_profile mismatch",
                ),
                (
                    {
                        "audit": {
                            "full_dataset_audit": "FAIL",
                            "locked_oot_open": False,
                            "source_ym_max": "202412",
                        }
                    },
                    "audit is not PASS",
                ),
                (
                    {
                        "audit": {
                            "full_dataset_audit": "PASS",
                            "locked_oot_open": True,
                            "source_ym_max": "202412",
                        }
                    },
                    "locked/OOT",
                ),
                (
                    {
                        "audit": {
                            "full_dataset_audit": "PASS",
                            "locked_oot_open": False,
                            "source_ym_max": "202501",
                        }
                    },
                    "source_ym_max",
                ),
            ]
            for overrides, needle in cases:
                checkpoint = sex1_identity_checkpoint("a" * 64)
                checkpoint.update(overrides)
                path = root / "checkpoint.json"
                write_json(path, checkpoint)
                with self.assertRaises(ValueError) as ctx:
                    MOD.validate_checkpoint(
                        dataset_root,
                        path,
                        "a" * 64,
                        schema,
                    )
                self.assertIn(needle, str(ctx.exception))

    def test_sex1_root_hash_mismatch_fails_before_sidecar_walk(self):
        schema = sex1_schema()
        schema["expected_root_hash"] = "b" * 64
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "checkpoint.json"
            write_json(
                path,
                sex1_identity_checkpoint("a" * 64),
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.validate_checkpoint(
                    root / "dataset",
                    path,
                    "b" * 64,
                    schema,
                )
            self.assertIn(
                "checkpoint/config root hash mismatch",
                str(ctx.exception),
            )

    def test_sex1_period_guard_rejects_locked_and_oot_before_source_open(self):
        opened = {"called": False}
        original = MOD.validate_checkpoint

        def guard(*args, **kwargs):
            opened["called"] = True
            return original(*args, **kwargs)

        MOD.validate_checkpoint = guard
        try:
            with self.assertRaises(ValueError) as ctx:
                MOD.run_transform(
                    candidate_args(
                        start="202501",
                        end="202501",
                    )
                )
            self.assertIn("DEVELOPMENT", str(ctx.exception))
            self.assertFalse(opened["called"])

            with self.assertRaises(ValueError) as ctx:
                MOD.run_transform(
                    candidate_args(
                        start="202601",
                        end="202601",
                    )
                )
            self.assertIn("DEVELOPMENT", str(ctx.exception))
            self.assertFalse(opened["called"])
        finally:
            MOD.validate_checkpoint = original

    def test_sex1_output_collision_and_symlink_fail(self):
        forbidden = MOD.forbidden_candidate_out_roots()
        transformed = None
        for path in forbidden:
            if path.name == "nar-v3-transformed":
                transformed = path
                break
        self.assertIsNotNone(transformed)
        with self.assertRaises(ValueError):
            MOD.assert_candidate_out_root_allowed(transformed)

        baseline = None
        for path in forbidden:
            if path.name == "nar-v3-baseline9":
                baseline = path
                break
        self.assertIsNotNone(baseline)
        with self.assertRaises(ValueError):
            MOD.assert_candidate_out_root_allowed(baseline)

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            alias = root / "alias-transformed"
            alias.symlink_to(
                transformed,
                target_is_directory=True,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.assert_candidate_out_root_allowed(alias)
            self.assertIn("collides", str(ctx.exception))
            allowed = root / "nar-v3-sex1-transformed"
            allowed.mkdir()
            MOD.assert_candidate_out_root_allowed(allowed)

    def test_sex1_cli_requires_explicit_out_and_dataset_root(self):
        with self.assertRaises(ValueError) as ctx:
            MOD.run_transform(
                candidate_args(out=None)
            )
        self.assertIn("explicit --out", str(ctx.exception))
        with self.assertRaises(ValueError) as ctx:
            MOD.run_transform(
                candidate_args(dataset_root=None)
            )
        self.assertIn(
            "explicit --dataset-root",
            str(ctx.exception),
        )

    def test_sex1_does_not_change_production_artifacts(self):
        before = {
            path: sha256_file(path)
            for path in [
                CONFIG_PATH,
                PRODUCTION_BASELINE_CONFIG,
                PRODUCTION_TRANSFORM_CHECKPOINT,
                PRODUCTION_BASELINE_CHECKPOINT,
            ]
        }
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root, _, _, _ = create_sex1_dataset(
                root,
                {"202101": [sample_sex1_row("202101")]},
            )
            cfg = json.loads(
                SEX1_CONFIG_PATH.read_text(encoding="utf-8")
            )
            schema = sex1_schema()
            dictionaries = MOD.fit_category_dictionaries(
                dataset_root,
                ["202101"],
                cfg,
                schema,
            )
            cfg_path = root / "config.json"
            write_json(cfg_path, cfg)
            out_root = root / "out"
            artifact = out_root / "artifacts"
            dict_path = artifact / "category-dictionaries.json"
            feature_path = artifact / "feature-order.json"
            MOD.write_json_atomic(dict_path, dictionaries)
            feature_order = MOD.build_feature_order(cfg, schema)
            MOD.write_json_atomic(feature_path, feature_order)
            MOD.transform_month(
                ym="202101",
                dataset_root=dataset_root,
                out_root=out_root,
                cfg_path=cfg_path,
                dictionaries=dictionaries,
                dictionary_path=dict_path,
                feature_order=feature_order,
                feature_order_path=feature_path,
                schema=schema,
            )
        after = {
            path: sha256_file(path)
            for path in before
        }
        self.assertEqual(before, after)

    def test_production_defaults_do_not_opt_into_candidate(self):
        args = MOD.parse_args(
            ["--start", "202101", "--end", "202101"]
        )
        self.assertIsNone(args.candidate_profile)
        args = MOD.apply_runtime_defaults(args)
        self.assertEqual(
            Path("config/nar-v3-transform.json"),
            args.config,
        )
        self.assertEqual(
            Path("/workspaces/nar-v3-transformed"),
            args.out,
        )
        self.assertEqual(
            Path("/workspaces/nar-v3-dataset"),
            args.dataset_root,
        )


if __name__ == "__main__":
    unittest.main()
