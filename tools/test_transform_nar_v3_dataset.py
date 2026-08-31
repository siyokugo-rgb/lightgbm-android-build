#!/usr/bin/env python3

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


def gzip_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = MOD.expected_raw_header()
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


if __name__ == "__main__":
    unittest.main()
