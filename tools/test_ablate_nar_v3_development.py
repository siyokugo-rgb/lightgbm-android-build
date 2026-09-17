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

import numpy as np
import pandas as pd
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
MODULE_PATH = HERE / "ablate_nar_v3_development.py"
TRANSFORM_PATH = HERE / "transform_nar_v3_dataset.py"
FROZEN_PATHS = [
    REPO_ROOT / "data-manifests" / "nar-v3-dataset" / "checkpoint.json",
    REPO_ROOT / "data-manifests" / "nar-v3-transformed" / "checkpoint.json",
    REPO_ROOT / "data-manifests" / "nar-v3-baseline9" / "checkpoint.json",
    REPO_ROOT
    / "data-manifests"
    / "candidates"
    / "nar-v3-sex1"
    / "dataset"
    / "checkpoint.json",
    REPO_ROOT
    / "data-manifests"
    / "candidates"
    / "nar-v3-sex1"
    / "transformed"
    / "checkpoint.json",
    REPO_ROOT / "config" / "nar-v3-transform.json",
    REPO_ROOT / "config" / "candidates" / "nar-v3-sex1" / "transform.json",
    TRANSFORM_PATH,
    HERE / "train_nar_v3_baseline.py",
]

SPEC = importlib.util.spec_from_file_location(
    "ablate_nar_v3_development",
    MODULE_PATH,
)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

TX = MOD.TRANSFORM
SEXES = ("牡", "牝", "セン")


def sha256_file(path):
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def frozen_fingerprint():
    rows = []
    for path in FROZEN_PATHS:
        rows.append((str(path.relative_to(REPO_ROOT)), sha256_file(path)))
    return tuple(rows)


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            obj,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )


def gzip_csv(path, rows, header):
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = path.open("wb")
    gz = gzip.GzipFile(
        fileobj=raw,
        mode="wb",
        filename="",
        mtime=0,
    )
    text = io.TextIOWrapper(gz, encoding="utf-8", newline="")
    try:
        writer = csv.DictWriter(
            text,
            fieldnames=header,
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


def read_gzip_csv(path):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def sex_for_month(ym):
    return SEXES[(int(ym[4:]) - 1) % 3]


def sample_row(ym, *, sire="父A", sex=None, entry_suffix="1"):
    year = int(ym[:4])
    race_date = f"{ym}15"
    venue = "大井"
    race_id = f"{venue}|{race_date}|1"
    row = {
        "race_id": race_id,
        "entry_id": f"{race_id}|{entry_suffix}",
        "split": TX.split_for_year(year),
        "source_ym": ym,
        "meta_source_anomalies": "",
        "feature_race__競馬場": venue,
        "feature_race__競走年月日": race_date,
        "feature_entry__毛色": "鹿毛",
        "feature_entry__生年月日": "20200101",
        "feature_entry__父馬名": sire,
        "feature_entry__母馬名": "母A",
        "feature_entry__母父馬名": "母父A",
        "label_result_status": "FINISHED",
        "label_numeric_finish_position": "1",
        "label_order_valid": "1",
        "label_started": "1",
        "label_finished": "1",
        "label_win": "1",
        "label_top2": "1",
        "label_top3": "1",
    }
    if sex is not None:
        row["feature_entry__性"] = sex
    return row


def write_dataset(root, month_rows, *, arm):
    dataset_root = root / f"{arm}-dataset"
    schema = MOD.schema_for_arm(arm)
    header = TX.expected_raw_header(schema)
    for ym, rows in month_rows.items():
        data = (
            dataset_root
            / "monthly"
            / ym[:4]
            / f"{ym}_entries.csv.gz"
        )
        gzip_csv(data, rows, header)
        sidecar = {
            "format_version": 1,
            "dataset": schema["input_dataset"],
            "source_ym": ym,
            "output_file": data.name,
            "output_sha256": sha256_file(data),
            "output_bytes": data.stat().st_size,
            "feature_columns": schema["race_raw"] + schema["entry_raw"],
            "label_columns": TX.LABEL_FIELDS,
            "counts": {
                "included_races": len({row["race_id"] for row in rows}),
                "output_entries": len(rows),
            },
        }
        if arm == MOD.ARM_SEX1:
            sidecar["candidate_profile"] = TX.CANDIDATE_PROFILE_SEX1
        write_json(
            data.with_name(f"{ym}_entries.manifest.json"),
            sidecar,
        )
    return dataset_root


def sire_for_month(ym, *, fold1_val="FOLD1_VAL_SIRE", fold2_val="FOLD2_VAL_SIRE"):
    year = int(ym[:4])
    if year == 2022:
        return fold1_val
    if year == 2023:
        return fold2_val
    return "TRAIN_SIRE"


def month_rows_for(months, *, arm, n=1, mask_every=None):
    rows = {}
    for ym in months:
        month_rows = []
        for index in range(n):
            sex = sex_for_month(ym) if arm == MOD.ARM_SEX1 else None
            row = sample_row(
                ym,
                sire=sire_for_month(ym),
                sex=sex,
                entry_suffix=str(index + 1),
            )
            row["label_win"] = "1" if index % 2 == 0 else "0"
            if mask_every is not None and (index + 1) % mask_every == 0:
                row["label_win"] = ""
            month_rows.append(row)
        rows[ym] = month_rows
    return rows


class SourceOpenSpy:
    def __init__(self):
        self.opened = []
        self._original = TX.source_path

    def __enter__(self):
        def wrapped(dataset_root, ym):
            path = self._original(dataset_root, ym)
            self.opened.append(ym)
            return path

        TX.source_path = wrapped
        return self

    def __exit__(self, exc_type, exc, tb):
        TX.source_path = self._original
        return False


class AblateNarV3DevelopmentTest(unittest.TestCase):
    def test_fold_definitions(self):
        fold1 = MOD.fold_month_lists(1)
        self.assertEqual(
            fold1["fit_months"],
            list(TX.month_range("202101", "202112")),
        )
        self.assertEqual(
            fold1["validation_months"],
            list(TX.month_range("202201", "202212")),
        )
        self.assertEqual(len(fold1["fit_months"]), 12)
        self.assertEqual(len(fold1["transform_months"]), 24)

        fold2 = MOD.fold_month_lists(2)
        self.assertEqual(
            fold2["fit_months"],
            list(TX.month_range("202101", "202212")),
        )
        self.assertEqual(
            fold2["validation_months"],
            list(TX.month_range("202301", "202312")),
        )
        self.assertEqual(len(fold2["fit_months"]), 24)
        self.assertEqual(len(fold2["transform_months"]), 36)

        fold3 = MOD.fold_month_lists(3)
        self.assertEqual(
            fold3["fit_months"],
            list(TX.month_range("202101", "202312")),
        )
        self.assertEqual(
            fold3["validation_months"],
            list(TX.month_range("202401", "202412")),
        )
        self.assertEqual(len(fold3["fit_months"]), 36)
        self.assertEqual(len(fold3["transform_months"]), 48)

        for fold_id in MOD.FOLD_IDS:
            spec = MOD.fold_month_lists(fold_id)
            self.assertLess(
                max(spec["fit_months"]),
                min(spec["validation_months"]),
            )
            self.assertEqual(
                spec["transform_months"],
                spec["fit_months"] + spec["validation_months"],
            )
            self.assertFalse(
                any(ym.startswith(("2025", "2026")) for ym in spec["transform_months"])
            )

    def test_invalid_and_overlapping_months_fail(self):
        with self.assertRaises(ValueError):
            MOD.assert_strict_month_list(["202113"], label="bad")
        with self.assertRaises(ValueError):
            MOD.assert_strict_month_list(
                ["202102", "202101"],
                label="unordered",
            )
        with self.assertRaises(ValueError):
            MOD.assert_strict_month_list(
                ["202101", "202101"],
                label="duplicate",
            )
        spec = MOD.fold_month_lists(1)
        bad = dict(spec)
        bad["fit_months"] = spec["fit_months"] + spec["validation_months"][:1]
        with self.assertRaises(ValueError):
            MOD.assert_fold_month_lists(bad)

    def test_fold1_future_vocabulary_is_unknown(self):
        spec = MOD.fold_month_lists(1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root = write_dataset(
                root,
                month_rows_for(spec["transform_months"], arm=MOD.ARM_BASELINE),
                arm=MOD.ARM_BASELINE,
            )
            with SourceOpenSpy() as spy:
                fitted = MOD.fit_fold_arm_dictionaries(
                    1,
                    MOD.ARM_BASELINE,
                    dataset_root,
                )
            self.assertEqual(spy.opened, spec["fit_months"])
            mapping = fitted["dictionaries"]["features"]["feature_entry__父馬名"][
                "value_to_id"
            ]
            self.assertIn("TRAIN_SIRE", mapping)
            self.assertNotIn("FOLD1_VAL_SIRE", mapping)
            encoded, state = TX.encode_category(
                "FOLD1_VAL_SIRE",
                "feature_entry__父馬名",
                fitted["dictionaries"],
            )
            self.assertEqual(1, encoded)
            self.assertEqual("unknown", state)

            result = MOD.transform_fold_arm(
                1,
                MOD.ARM_BASELINE,
                dataset_root,
                root / "fold1" / "baseline",
                dictionaries=fitted["dictionaries"],
                fit_months=spec["fit_months"],
            )
            val_rows = read_gzip_csv(
                result["out_root"]
                / "monthly"
                / "2022"
                / "202201_model.csv.gz"
            )
            self.assertEqual(val_rows[0]["feature_entry__父馬名"], "1")
            self.assertEqual(val_rows[0]["split"], "train")
            self.assertEqual(val_rows[0]["source_ym"], "202201")
            train_rows = read_gzip_csv(
                result["out_root"]
                / "monthly"
                / "2021"
                / "202101_model.csv.gz"
            )
            self.assertEqual(train_rows[0]["split"], "train")
            self.assertNotEqual(train_rows[0]["feature_entry__父馬名"], "1")

    def test_fold2_future_vocabulary_is_unknown(self):
        spec = MOD.fold_month_lists(2)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root = write_dataset(
                root,
                month_rows_for(spec["transform_months"], arm=MOD.ARM_BASELINE),
                arm=MOD.ARM_BASELINE,
            )
            fitted = MOD.fit_fold_arm_dictionaries(
                2,
                MOD.ARM_BASELINE,
                dataset_root,
            )
            mapping = fitted["dictionaries"]["features"]["feature_entry__父馬名"][
                "value_to_id"
            ]
            self.assertIn("TRAIN_SIRE", mapping)
            self.assertIn("FOLD1_VAL_SIRE", mapping)
            self.assertNotIn("FOLD2_VAL_SIRE", mapping)
            encoded, state = TX.encode_category(
                "FOLD2_VAL_SIRE",
                "feature_entry__父馬名",
                fitted["dictionaries"],
            )
            self.assertEqual(1, encoded)
            self.assertEqual("unknown", state)

            result = MOD.transform_fold_arm(
                2,
                MOD.ARM_BASELINE,
                dataset_root,
                root / "fold2" / "baseline",
                dictionaries=fitted["dictionaries"],
            )
            val_rows = read_gzip_csv(
                result["out_root"]
                / "monthly"
                / "2023"
                / "202301_model.csv.gz"
            )
            self.assertEqual(val_rows[0]["feature_entry__父馬名"], "1")
            self.assertEqual(val_rows[0]["split"], "train")

    def test_sex_is_ordinary_category_on_candidate_only(self):
        spec = MOD.fold_month_lists(1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            baseline_root = write_dataset(
                root / "baseline",
                month_rows_for(spec["transform_months"], arm=MOD.ARM_BASELINE),
                arm=MOD.ARM_BASELINE,
            )
            sex1_root = write_dataset(
                root / "sex1",
                month_rows_for(spec["transform_months"], arm=MOD.ARM_SEX1),
                arm=MOD.ARM_SEX1,
            )
            baseline = MOD.fit_fold_arm_dictionaries(
                1,
                MOD.ARM_BASELINE,
                baseline_root,
            )
            candidate = MOD.fit_fold_arm_dictionaries(
                1,
                MOD.ARM_SEX1,
                sex1_root,
            )
            self.assertNotIn(
                MOD.SEX_FEATURE,
                baseline["dictionaries"]["features"],
            )
            sex_map = candidate["dictionaries"]["features"][MOD.SEX_FEATURE][
                "value_to_id"
            ]
            for value in SEXES:
                self.assertIn(value, sex_map)
                self.assertGreaterEqual(sex_map[value], 2)
            self.assertEqual(len(set(sex_map.values())), 3)
            self.assertEqual(
                baseline["feature_order"]["feature_count"],
                9,
            )
            self.assertEqual(
                candidate["feature_order"]["feature_count"],
                10,
            )
            self.assertNotIn(
                MOD.SEX_FEATURE,
                baseline["feature_order"]["categorical_feature_names"],
            )
            self.assertIn(
                MOD.SEX_FEATURE,
                candidate["feature_order"]["categorical_feature_names"],
            )

    def test_common_categorical_parity_per_fold(self):
        for fold_id in MOD.FOLD_IDS:
            spec = MOD.fold_month_lists(fold_id)
            with tempfile.TemporaryDirectory() as td:
                root = Path(td)
                months = spec["transform_months"]
                baseline_root = write_dataset(
                    root / "baseline",
                    month_rows_for(months, arm=MOD.ARM_BASELINE),
                    arm=MOD.ARM_BASELINE,
                )
                sex1_root = write_dataset(
                    root / "sex1",
                    month_rows_for(months, arm=MOD.ARM_SEX1),
                    arm=MOD.ARM_SEX1,
                )
                baseline = MOD.fit_fold_arm_dictionaries(
                    fold_id,
                    MOD.ARM_BASELINE,
                    baseline_root,
                )
                candidate = MOD.fit_fold_arm_dictionaries(
                    fold_id,
                    MOD.ARM_SEX1,
                    sex1_root,
                )
                MOD.assert_shared_dictionary_parity(
                    baseline["dictionaries"],
                    candidate["dictionaries"],
                )

    def test_future_fit_months_fail_before_source_open(self):
        fold1 = MOD.fold_month_lists(1)
        fold2 = MOD.fold_month_lists(2)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root = write_dataset(
                root,
                month_rows_for(fold2["transform_months"], arm=MOD.ARM_BASELINE),
                arm=MOD.ARM_BASELINE,
            )
            with SourceOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.fit_fold_arm_dictionaries(
                        1,
                        MOD.ARM_BASELINE,
                        dataset_root,
                        fit_months=fold1["fit_months"] + ["202201"],
                    )
                self.assertIn("validation", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with SourceOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.fit_fold_arm_dictionaries(
                        2,
                        MOD.ARM_BASELINE,
                        dataset_root,
                        fit_months=fold2["fit_months"] + ["202301"],
                    )
                self.assertIn("validation", str(ctx.exception))
                self.assertEqual(spy.opened, [])

    def test_locked_oot_fails_before_source_open(self):
        fold3 = MOD.fold_month_lists(3)
        with tempfile.TemporaryDirectory() as td:
            dataset_root = Path(td) / "unused-dataset"
            with SourceOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.assert_months_allowed_for_role(
                        3,
                        ["202501"],
                        "fit",
                    )
                self.assertIn("before source open", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with SourceOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.fit_fold_arm_dictionaries(
                        3,
                        MOD.ARM_BASELINE,
                        dataset_root,
                        fit_months=fold3["fit_months"] + ["202501"],
                    )
                self.assertIn("locked/OOT", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with SourceOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.assert_months_allowed_for_role(
                        1,
                        fold_month_plus_oot(),
                        "transform",
                    )
                self.assertIn("locked/OOT", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with SourceOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.assert_no_locked_oot_months(
                        ["202607"],
                        label="oot",
                    )
                self.assertIn("locked/OOT", str(ctx.exception))
                self.assertEqual(spy.opened, [])

    def test_output_collision_and_symlink_fail(self):
        transformed = (
            REPO_ROOT / "data-manifests" / "nar-v3-transformed"
        )
        baseline = REPO_ROOT / "data-manifests" / "nar-v3-baseline9"
        sex1 = (
            REPO_ROOT
            / "data-manifests"
            / "candidates"
            / "nar-v3-sex1"
        )
        with self.assertRaises(ValueError):
            MOD.assert_ablation_out_root_allowed(transformed)
        with self.assertRaises(ValueError):
            MOD.assert_ablation_out_root_allowed(baseline)
        with self.assertRaises(ValueError):
            MOD.assert_ablation_out_root_allowed(sex1)
        with self.assertRaises(ValueError):
            MOD.assert_ablation_out_root_allowed(
                sex1 / "transformed"
            )

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            alias = root / "alias-transformed"
            alias.symlink_to(transformed, target_is_directory=True)
            with self.assertRaises(ValueError) as ctx:
                MOD.assert_ablation_out_root_allowed(alias)
            self.assertIn("collides", str(ctx.exception))

            allowed = root / "fold1" / "baseline"
            MOD.assert_ablation_out_root_allowed(allowed)
            self.assertNotEqual(
                MOD.fold_arm_out_root(root, 1, MOD.ARM_BASELINE),
                MOD.fold_arm_out_root(root, 2, MOD.ARM_BASELINE),
            )
            self.assertNotEqual(
                MOD.fold_arm_out_root(root, 1, MOD.ARM_BASELINE),
                MOD.fold_arm_out_root(root, 1, MOD.ARM_SEX1),
            )

    def test_existing_arm_root_is_rejected(self):
        spec = MOD.fold_month_lists(1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dataset_root = write_dataset(
                root,
                month_rows_for(spec["transform_months"], arm=MOD.ARM_BASELINE),
                arm=MOD.ARM_BASELINE,
            )
            out = root / "fold1" / "baseline"
            MOD.transform_fold_arm(
                1,
                MOD.ARM_BASELINE,
                dataset_root,
                out,
            )
            with self.assertRaises(FileExistsError):
                MOD.transform_fold_arm(
                    1,
                    MOD.ARM_BASELINE,
                    dataset_root,
                    out,
                )

    def test_frozen_artifacts_are_unchanged(self):
        before = frozen_fingerprint()
        spec = MOD.fold_month_lists(1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            baseline_root = write_dataset(
                root / "baseline",
                month_rows_for(spec["transform_months"], arm=MOD.ARM_BASELINE),
                arm=MOD.ARM_BASELINE,
            )
            sex1_root = write_dataset(
                root / "sex1",
                month_rows_for(spec["transform_months"], arm=MOD.ARM_SEX1),
                arm=MOD.ARM_SEX1,
            )
            baseline = MOD.transform_fold_arm(
                1,
                MOD.ARM_BASELINE,
                baseline_root,
                root / "out" / "fold1" / "baseline",
            )
            candidate = MOD.transform_fold_arm(
                1,
                MOD.ARM_SEX1,
                sex1_root,
                root / "out" / "fold1" / "sex1",
            )
            MOD.assert_shared_dictionary_parity(
                baseline["dictionaries"],
                candidate["dictionaries"],
            )
        after = frozen_fingerprint()
        self.assertEqual(before, after)

    def test_helper_does_not_include_production_runner_or_champion_rewrite(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("TRANSFORM.run_transform", source)
        self.assertNotIn("def promote_champion", source)
        self.assertNotIn("champion.json", source)
        self.assertIn("def train_fold_arm", source)
        self.assertIn("def build_development_oof", source)
        self.assertIn("def run_race_paired_bootstrap", source)
        self.assertNotIn("best_iteration = 55", source)
        self.assertNotIn("best = 55", source)


def fold_month_plus_oot():
    months = list(MOD.fold_month_lists(1)["transform_months"])
    months.append("202501")
    return months


class ModelCsvOpenSpy:
    def __init__(self):
        self.opened = []
        self._original = MOD.model_csv_path

    def __enter__(self):
        def wrapped(transform_root, ym):
            path = self._original(transform_root, ym)
            self.opened.append(ym)
            return path

        MOD.model_csv_path = wrapped
        return self

    def __exit__(self, exc_type, exc, tb):
        MOD.model_csv_path = self._original
        return False


def transform_arm(root, fold_id, arm, *, n=1, mask_every=None):
    spec = MOD.fold_month_lists(fold_id)
    dataset_root = write_dataset(
        root / f"{arm}-raw",
        month_rows_for(
            spec["transform_months"],
            arm=arm,
            n=n,
            mask_every=mask_every,
        ),
        arm=arm,
    )
    return MOD.transform_fold_arm(
        fold_id,
        arm,
        dataset_root,
        root / "transformed" / f"fold{fold_id}" / arm,
    )


def rewrite_model_month(transform_root, ym, mutate):
    csv_path = (
        Path(transform_root)
        / "monthly"
        / ym[:4]
        / f"{ym}_model.csv.gz"
    )
    manifest_path = csv_path.with_name(f"{ym}_model.manifest.json")
    rows = read_gzip_csv(csv_path)
    header = list(rows[0].keys()) if rows else []
    mutate(rows)
    gzip_csv(csv_path, rows, header)
    sidecar = json.loads(manifest_path.read_text(encoding="utf-8"))
    sidecar["output_sha256"] = sha256_file(csv_path)
    sidecar["output_bytes"] = csv_path.stat().st_size
    sidecar.setdefault("counts", {})["rows"] = len(rows)
    write_json(manifest_path, sidecar)


class AblateNarV3FoldTrainingTest(unittest.TestCase):
    def test_lightgbm_params_match_baseline9(self):
        cfg = json.loads(
            (
                REPO_ROOT / "config" / "nar-v3-win-baseline9.json"
            ).read_text(encoding="utf-8")
        )
        contract = MOD.load_baseline9_training_contract()
        self.assertEqual(contract["lightgbm_params"], cfg["lightgbm_params"])
        self.assertEqual(contract["training"], cfg["training"])
        self.assertTrue(contract["first_metric_only"])
        self.assertEqual(contract["valid_names"], ["validation"])
        self.assertEqual(
            contract["lightgbm_params"]["metric"][0],
            "binary_logloss",
        )

    def test_feature_contract_baseline_and_candidate(self):
        self.assertEqual(MOD.EXPECTED_FEATURE_COUNTS[MOD.ARM_BASELINE], {
            "feature_count": 9,
            "categorical_feature_count": 5,
        })
        self.assertEqual(MOD.EXPECTED_FEATURE_COUNTS[MOD.ARM_SEX1], {
            "feature_count": 10,
            "categorical_feature_count": 6,
        })
        self.assertEqual(
            MOD.EXPECTED_CATEGORICAL_INDICES[MOD.ARM_BASELINE],
            [4, 5, 6, 7, 8],
        )
        self.assertEqual(
            MOD.EXPECTED_CATEGORICAL_INDICES[MOD.ARM_SEX1],
            [4, 5, 6, 7, 8, 9],
        )

    def test_fold_loads_use_source_ym_not_calendar_split(self):
        cases = (
            (1, {"2021"}, {"2022"}, "train"),
            (2, {"2021", "2022"}, {"2023"}, "train"),
            (3, {"2021", "2022", "2023"}, {"2024"}, "validation"),
        )
        for fold_id, train_years, val_years, val_calendar_split in cases:
            spec = MOD.fold_month_lists(fold_id)
            with tempfile.TemporaryDirectory() as td:
                transformed = transform_arm(
                    Path(td),
                    fold_id,
                    MOD.ARM_BASELINE,
                )
                with ModelCsvOpenSpy() as spy:
                    loaded = MOD.load_fold_training_frames(
                        fold_id,
                        MOD.ARM_BASELINE,
                        transformed["out_root"],
                    )
                self.assertEqual(spy.opened, spec["transform_months"])
                self.assertEqual(
                    loaded["train_months"],
                    spec["fit_months"],
                )
                self.assertEqual(
                    loaded["validation_months"],
                    spec["validation_months"],
                )
                self.assertEqual(
                    set(loaded["train"].source_ym.str[:4]),
                    train_years,
                )
                self.assertEqual(
                    set(loaded["validation"].source_ym.str[:4]),
                    val_years,
                )
                self.assertTrue((loaded["train"]["split"] == "train").all())
                self.assertTrue(
                    (loaded["validation"]["split"] == val_calendar_split).all()
                )
                if fold_id in (1, 2):
                    self.assertEqual(val_calendar_split, "train")

    def test_future_and_locked_months_fail_before_model_open(self):
        spec1 = MOD.fold_month_lists(1)
        spec2 = MOD.fold_month_lists(2)
        with tempfile.TemporaryDirectory() as td:
            transformed = transform_arm(
                Path(td),
                1,
                MOD.ARM_BASELINE,
            )
            with ModelCsvOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.load_fold_training_frames(
                        1,
                        MOD.ARM_BASELINE,
                        transformed["out_root"],
                        validation_months=spec1["validation_months"]
                        + ["202301"],
                    )
                self.assertIn("before source open", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with ModelCsvOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.load_fold_role_frame(
                        2,
                        "validation",
                        transformed["out_root"],
                        MOD.ARM_BASELINE,
                        months=spec2["validation_months"] + ["202401"],
                    )
                self.assertIn("before source open", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with ModelCsvOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.load_fold_training_frames(
                        3,
                        MOD.ARM_BASELINE,
                        transformed["out_root"],
                        train_months=MOD.fold_month_lists(3)["fit_months"]
                        + ["202501"],
                    )
                self.assertIn("locked/OOT", str(ctx.exception))
                self.assertEqual(spy.opened, [])

            with ModelCsvOpenSpy() as spy:
                with self.assertRaises(ValueError) as ctx:
                    MOD.assert_months_allowed_for_role(
                        1,
                        ["202607"],
                        "fit",
                    )
                self.assertIn("locked/OOT", str(ctx.exception))
                self.assertEqual(spy.opened, [])

    def test_alignment_and_masked_labels(self):
        spec = MOD.fold_month_lists(1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            baseline = transform_arm(
                root / "base",
                1,
                MOD.ARM_BASELINE,
                n=4,
                mask_every=4,
            )
            candidate = transform_arm(
                root / "sex",
                1,
                MOD.ARM_SEX1,
                n=4,
                mask_every=4,
            )
            left = MOD.load_fold_training_frames(
                1,
                MOD.ARM_BASELINE,
                baseline["out_root"],
            )
            right = MOD.load_fold_training_frames(
                1,
                MOD.ARM_SEX1,
                candidate["out_root"],
            )
            MOD.assert_fold_arm_alignment(left, right)
            self.assertEqual(left["feature_names"], MOD.SHARED_MODEL_FEATURES)
            self.assertEqual(
                right["feature_names"],
                MOD.SHARED_MODEL_FEATURES + [MOD.SEX_FEATURE],
            )
            expected_source = 4 * len(spec["fit_months"])
            expected_masked = expected_source // 4
            self.assertEqual(left["train_stats"]["source"], expected_source)
            self.assertEqual(left["train_stats"]["masked"], expected_masked)
            self.assertEqual(
                left["train_stats"]["supervised"],
                expected_source - expected_masked,
            )
            self.assertEqual(left["train_stats"], right["train_stats"])
            self.assertEqual(
                left["validation_stats"],
                right["validation_stats"],
            )

    def test_alignment_fails_on_label_mismatch(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            baseline = transform_arm(root / "base", 1, MOD.ARM_BASELINE, n=2)
            candidate = transform_arm(root / "sex", 1, MOD.ARM_SEX1, n=2)
            rewrite_model_month(
                candidate["out_root"],
                "202101",
                lambda rows: rows.__setitem__(
                    0,
                    {**rows[0], "label_win": "0"},
                )
                or rows,
            )
            left = MOD.load_fold_training_frames(
                1,
                MOD.ARM_BASELINE,
                baseline["out_root"],
            )
            right = MOD.load_fold_training_frames(
                1,
                MOD.ARM_SEX1,
                candidate["out_root"],
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.assert_fold_arm_alignment(left, right)
            self.assertIn("label", str(ctx.exception))

    def test_invalid_target_and_duplicate_entry_fail(self):
        with tempfile.TemporaryDirectory() as td:
            transformed = transform_arm(Path(td) / "inv", 1, MOD.ARM_BASELINE)
            rewrite_model_month(
                transformed["out_root"],
                "202101",
                lambda rows: rows.__setitem__(
                    0,
                    {**rows[0], "label_win": "2"},
                )
                or rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.load_fold_training_frames(
                    1,
                    MOD.ARM_BASELINE,
                    transformed["out_root"],
                )
            self.assertIn("invalid target", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            transformed = transform_arm(Path(td) / "dup", 1, MOD.ARM_BASELINE)
            rewrite_model_month(
                transformed["out_root"],
                "202102",
                lambda rows: rows.append(dict(rows[0])) or rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.load_fold_training_frames(
                    1,
                    MOD.ARM_BASELINE,
                    transformed["out_root"],
                )
            self.assertIn("duplicate entry_id", str(ctx.exception))

    def test_prediction_range_guard(self):
        frame = pd.DataFrame({"entry_id": ["a", "b"]})
        MOD.assert_valid_predictions(frame, [0.0, 1.0])
        with self.assertRaises(ValueError):
            MOD.assert_valid_predictions(frame, [float("nan"), 0.2])
        with self.assertRaises(ValueError):
            MOD.assert_valid_predictions(frame, [0.1, float("inf")])
        with self.assertRaises(ValueError):
            MOD.assert_valid_predictions(frame, [-0.01, 0.2])
        with self.assertRaises(ValueError):
            MOD.assert_valid_predictions(frame, [0.2, 1.01])
        with self.assertRaises(ValueError):
            MOD.assert_valid_predictions(
                pd.DataFrame({"entry_id": ["a", "a"]}),
                [0.2, 0.3],
            )

    def test_training_output_collision_and_symlink(self):
        frozen = REPO_ROOT / "data-manifests" / "nar-v3-baseline9"
        with self.assertRaises(ValueError):
            MOD.assert_ablation_out_root_allowed(frozen)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            alias = root / "alias-baseline9"
            alias.symlink_to(frozen, target_is_directory=True)
            with self.assertRaises(ValueError) as ctx:
                MOD.train_fold_arm(
                    1,
                    MOD.ARM_BASELINE,
                    root / "missing-transform",
                    alias,
                )
            self.assertIn("collides", str(ctx.exception))
            existing = root / "fold1" / "baseline"
            existing.mkdir(parents=True)
            with self.assertRaises(FileExistsError):
                MOD.train_fold_arm(
                    1,
                    MOD.ARM_BASELINE,
                    root / "missing-transform",
                    existing,
                )

    def test_synthetic_lightgbm_smoke_and_determinism(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            baseline_tf = transform_arm(
                root / "base",
                1,
                MOD.ARM_BASELINE,
                n=20,
                mask_every=10,
            )
            candidate_tf = transform_arm(
                root / "sex",
                1,
                MOD.ARM_SEX1,
                n=20,
                mask_every=10,
            )
            left = MOD.load_fold_training_frames(
                1,
                MOD.ARM_BASELINE,
                baseline_tf["out_root"],
            )
            first = MOD.train_fold_arm(
                1,
                MOD.ARM_BASELINE,
                baseline_tf["out_root"],
                root / "train" / "run1" / "baseline",
            )
            second = MOD.train_fold_arm(
                1,
                MOD.ARM_BASELINE,
                baseline_tf["out_root"],
                root / "train" / "run2" / "baseline",
            )
            candidate = MOD.train_fold_arm(
                1,
                MOD.ARM_SEX1,
                candidate_tf["out_root"],
                root / "train" / "run1" / "sex1",
                align_with=left,
            )
            self.assertGreater(first["best_iteration"], 0)
            self.assertEqual(first["best_iteration"], second["best_iteration"])
            self.assertEqual(
                list(first["prediction"]),
                list(second["prediction"]),
            )
            self.assertEqual(
                first["metrics"]["sha256"]["validation_predictions"],
                second["metrics"]["sha256"]["validation_predictions"],
            )
            self.assertEqual(
                first["metrics"]["sha256"]["lightgbm_params"],
                candidate["metrics"]["sha256"]["lightgbm_params"],
            )
            self.assertEqual(first["metrics"]["feature_count"], 9)
            self.assertEqual(candidate["metrics"]["feature_count"], 10)
            self.assertEqual(
                first["metrics"]["categorical_feature_indices"],
                [4, 5, 6, 7, 8],
            )
            self.assertEqual(
                candidate["metrics"]["categorical_feature_indices"],
                [4, 5, 6, 7, 8, 9],
            )
            self.assertEqual(
                first["metrics"]["early_stopping"],
                {
                    "metric": "binary_logloss",
                    "rounds": 100,
                    "first_metric_only": True,
                    "valid_names": ["validation"],
                },
            )
            pred_rows = read_gzip_csv(first["predictions_path"])
            self.assertEqual(
                list(pred_rows[0].keys()),
                [
                    "race_id",
                    "entry_id",
                    "source_ym",
                    "label_win",
                    "prediction",
                ],
            )
            self.assertEqual(len(pred_rows), left["validation_stats"]["supervised"])
            metrics_text = (
                first["out_root"] / "fold-metrics.json"
            ).read_text(encoding="utf-8")
            self.assertNotIn("/workspace", metrics_text)
            self.assertNotIn("C:\\", metrics_text)
            self.assertFalse(
                MOD._json_contains_absolute_path(first["metrics"])
            )
            self.assertNotIn("best_iteration = 55", MODULE_PATH.read_text())
            try:
                self.assertEqual(
                    first["metrics"]["sha256"]["model"],
                    second["metrics"]["sha256"]["model"],
                )
                model_sha_matched = True
            except AssertionError:
                model_sha_matched = False
            self.assertTrue(
                model_sha_matched
                or first["metrics"]["sha256"]["validation_predictions"]
                == second["metrics"]["sha256"]["validation_predictions"]
            )


def write_prediction_artifact(root, fold_id, arm, rows):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=False)
    pred_path = root / "validation-predictions.csv.gz"
    gzip_csv(
        pred_path,
        rows,
        [
            "race_id",
            "entry_id",
            "source_ym",
            "label_win",
            "prediction",
        ],
    )
    metrics = {
        "fold_id": fold_id,
        "arm": arm,
        "validation_months": MOD.fold_month_lists(fold_id)[
            "validation_months"
        ],
        "supervised_rows": {
            "train": 1,
            "validation": len(rows),
        },
        "sha256": {
            "validation_predictions": sha256_file(pred_path),
        },
    }
    write_json(root / "fold-metrics.json", metrics)
    return root


def synthetic_fold_rows(
    fold_id,
    *,
    prediction_offset=0.0,
    scramble=False,
    improve_winners=False,
):
    ym = {
        1: "202206",
        2: "202306",
        3: "202406",
    }[fold_id]
    rows = [
        {
            "race_id": f"R{fold_id}|1",
            "entry_id": f"R{fold_id}|1|A",
            "source_ym": ym,
            "label_win": "1",
            "prediction": f"{0.70 + prediction_offset:.6f}",
        },
        {
            "race_id": f"R{fold_id}|1",
            "entry_id": f"R{fold_id}|1|B",
            "source_ym": ym,
            "label_win": "0",
            "prediction": f"{0.30 + prediction_offset:.6f}",
        },
        {
            "race_id": f"R{fold_id}|2",
            "entry_id": f"R{fold_id}|2|A",
            "source_ym": ym,
            "label_win": "0",
            "prediction": f"{0.40 + prediction_offset:.6f}",
        },
        {
            "race_id": f"R{fold_id}|2",
            "entry_id": f"R{fold_id}|2|B",
            "source_ym": ym,
            "label_win": "1",
            "prediction": f"{0.60 + prediction_offset:.6f}",
        },
    ]
    if improve_winners:
        for row in rows:
            value = float(row["prediction"])
            if row["label_win"] == "1":
                value = min(0.99, value + 0.15)
            else:
                value = max(0.01, value - 0.15)
            row["prediction"] = f"{value:.6f}"
    if scramble:
        rows = list(reversed(rows))
    return rows


def build_three_fold_roots(
    base,
    *,
    scramble_candidate=False,
    improve_candidate=False,
):
    roots = {}
    for fold_id in MOD.FOLD_IDS:
        baseline_rows = synthetic_fold_rows(fold_id)
        candidate_rows = synthetic_fold_rows(
            fold_id,
            scramble=scramble_candidate,
            improve_winners=improve_candidate,
        )
        roots[fold_id] = {
            MOD.ARM_BASELINE: write_prediction_artifact(
                base / f"fold{fold_id}" / "baseline",
                fold_id,
                MOD.ARM_BASELINE,
                baseline_rows,
            ),
            MOD.ARM_SEX1: write_prediction_artifact(
                base / f"fold{fold_id}" / "sex1",
                fold_id,
                MOD.ARM_SEX1,
                candidate_rows,
            ),
        }
    return roots


class AblateNarV3OofTest(unittest.TestCase):
    def test_build_development_oof_happy_path(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "inputs")
            first = MOD.build_development_oof(
                fold_roots,
                root / "oof1",
            )
            second = MOD.build_development_oof(
                fold_roots,
                root / "oof2",
            )
            oof = first["oof"]
            self.assertEqual(list(oof.columns), MOD.OOF_COLUMNS)
            self.assertEqual(set(oof["fold"]), {1, 2, 3})
            self.assertEqual(
                set(oof["source_ym"].str[:4]),
                {"2022", "2023", "2024"},
            )
            self.assertEqual(len(oof), 12)
            self.assertEqual(
                first["metrics"]["fold_supervised_rows"],
                {"1": 4, "2": 4, "3": 4},
            )
            self.assertEqual(
                first["metrics"]["sha256"]["oof_predictions"],
                second["metrics"]["sha256"]["oof_predictions"],
            )
            self.assertEqual(
                first["oof"].to_dict(orient="list"),
                second["oof"].to_dict(orient="list"),
            )
            self.assertEqual(
                json.loads(first["metrics_path"].read_text(encoding="utf-8")),
                json.loads(second["metrics_path"].read_text(encoding="utf-8")),
            )
            self.assertEqual(
                first["metrics"]["calibration"]["status"],
                "not_yet_frozen",
            )

    def test_canonical_alignment_and_delta_direction(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(
                root / "inputs",
                scramble_candidate=True,
                improve_candidate=True,
            )
            result = MOD.build_development_oof(fold_roots, root / "oof")
            y = result["oof"][MOD.TARGET].to_numpy(dtype=np.int8)
            base_p = result["oof"]["baseline_prediction"].to_numpy()
            cand_p = result["oof"]["candidate_prediction"].to_numpy()
            expected_base = float(log_loss(y, base_p, labels=[0, 1]))
            expected_cand = float(log_loss(y, cand_p, labels=[0, 1]))
            self.assertAlmostEqual(
                result["metrics"]["baseline"]["global"]["log_loss"],
                expected_base,
            )
            self.assertAlmostEqual(
                result["metrics"]["candidate"]["global"]["log_loss"],
                expected_cand,
            )
            self.assertAlmostEqual(
                result["metrics"][
                    "observed_log_loss_delta_candidate_minus_baseline"
                ],
                expected_cand - expected_base,
            )
            self.assertLess(
                result["metrics"][
                    "observed_log_loss_delta_candidate_minus_baseline"
                ],
                0.0,
            )

    def test_metric_parity_with_baseline_trainer(self):
        frame = pd.DataFrame(
            {
                "race_id": ["R1", "R1", "R2", "R2"],
                "entry_id": ["R1|A", "R1|B", "R2|A", "R2|B"],
                "label_win": [1, 0, 0, 1],
            }
        )
        pred = np.asarray([0.8, 0.2, 0.3, 0.7], dtype=np.float64)
        y = frame["label_win"].to_numpy(dtype=np.int8)
        ours = MOD.oof_metrics(y, pred)
        theirs = MOD.BASELINE_TRAINER.metrics(y, pred)
        self.assertEqual(ours, theirs)
        self.assertAlmostEqual(
            ours["brier_score"],
            float(brier_score_loss(y, pred)),
        )
        self.assertAlmostEqual(
            ours["roc_auc"],
            float(roc_auc_score(y, pred)),
        )
        self.assertEqual(
            MOD.oof_race_metrics(frame, pred),
            MOD.BASELINE_TRAINER.race_metrics(frame, pred),
        )

    def test_auc_single_class_fails_closed(self):
        with self.assertRaises(ValueError) as ctx:
            MOD.oof_metrics([1, 1, 1], [0.2, 0.3, 0.4])
        self.assertIn("both label classes", str(ctx.exception))

    def test_alignment_failures(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            # missing entry
            bad = synthetic_fold_rows(1)[:-1]
            fold_roots[1][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "missing" / "sex1",
                1,
                MOD.ARM_SEX1,
                bad,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-missing")
            self.assertIn("entry mismatch", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            extra = synthetic_fold_rows(1) + [
                {
                    "race_id": "RX",
                    "entry_id": "RX|Z",
                    "source_ym": "202206",
                    "label_win": "0",
                    "prediction": "0.1",
                }
            ]
            fold_roots[1][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "extra" / "sex1",
                1,
                MOD.ARM_SEX1,
                extra,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-extra")
            self.assertIn("entry mismatch", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows[0] = {**rows[0], "label_win": "0"}
            fold_roots[1][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "label" / "sex1",
                1,
                MOD.ARM_SEX1,
                rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-label")
            self.assertIn("label", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows[0] = {**rows[0], "race_id": "CHANGED"}
            fold_roots[1][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "race" / "sex1",
                1,
                MOD.ARM_SEX1,
                rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-race")
            self.assertIn("race_id", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows.append(dict(rows[0]))
            fold_roots[1][MOD.ARM_BASELINE] = write_prediction_artifact(
                root / "dup" / "baseline",
                1,
                MOD.ARM_BASELINE,
                rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-dup")
            self.assertIn("duplicate", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(2)
            # reuse fold1 entry ids inside fold2 artifact wrongly
            rows[0] = {
                **rows[0],
                "entry_id": "R1|1|A",
                "race_id": "R1|1",
                "source_ym": "202306",
            }
            fold_roots[2][MOD.ARM_BASELINE] = write_prediction_artifact(
                root / "cross" / "baseline",
                2,
                MOD.ARM_BASELINE,
                rows,
            )
            fold_roots[2][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "cross" / "sex1",
                2,
                MOD.ARM_SEX1,
                rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-cross")
            self.assertIn("global duplicate", str(ctx.exception))

    def test_prediction_and_sha_and_locked_guards(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows[0] = {**rows[0], "prediction": "nan"}
            fold_roots[1][MOD.ARM_BASELINE] = write_prediction_artifact(
                root / "nan" / "baseline",
                1,
                MOD.ARM_BASELINE,
                rows,
            )
            with self.assertRaises(ValueError):
                MOD.build_development_oof(fold_roots, root / "oof-nan")

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows[0] = {**rows[0], "prediction": "1.5"}
            fold_roots[1][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "hi" / "sex1",
                1,
                MOD.ARM_SEX1,
                rows,
            )
            with self.assertRaises(ValueError):
                MOD.build_development_oof(fold_roots, root / "oof-hi")

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            metrics_path = (
                fold_roots[1][MOD.ARM_BASELINE] / "fold-metrics.json"
            )
            obj = json.loads(metrics_path.read_text(encoding="utf-8"))
            obj["sha256"]["validation_predictions"] = "0" * 64
            write_json(metrics_path, obj)
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-sha")
            self.assertIn("SHA mismatch", str(ctx.exception))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows[0] = {**rows[0], "source_ym": "202501"}
            fold_roots[1][MOD.ARM_BASELINE] = write_prediction_artifact(
                root / "locked" / "baseline",
                1,
                MOD.ARM_BASELINE,
                rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-locked")
            self.assertIn("locked/OOT", str(ctx.exception))

        with self.assertRaises(ValueError) as ctx:
            MOD.fold_id_for_validation_ym("202607")
        self.assertIn("locked/OOT", str(ctx.exception))

    def test_wrong_fold_month_assignment_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "ok")
            rows = synthetic_fold_rows(1)
            rows = [
                {**row, "source_ym": "202306", "entry_id": row["entry_id"] + "X"}
                for row in rows
            ]
            fold_roots[1][MOD.ARM_BASELINE] = write_prediction_artifact(
                root / "wrong" / "baseline",
                1,
                MOD.ARM_BASELINE,
                rows,
            )
            fold_roots[1][MOD.ARM_SEX1] = write_prediction_artifact(
                root / "wrong" / "sex1",
                1,
                MOD.ARM_SEX1,
                rows,
            )
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, root / "oof-wrong")
            self.assertIn("wrong validation month", str(ctx.exception))

    def test_output_collision_and_no_absolute_paths(self):
        frozen = REPO_ROOT / "data-manifests" / "nar-v3-baseline9"
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fold_roots = build_three_fold_roots(root / "inputs")
            with self.assertRaises(ValueError):
                MOD.build_development_oof(fold_roots, frozen)
            alias = root / "alias"
            alias.symlink_to(frozen, target_is_directory=True)
            with self.assertRaises(ValueError) as ctx:
                MOD.build_development_oof(fold_roots, alias)
            self.assertIn("collides", str(ctx.exception))
            existing = root / "existing"
            existing.mkdir()
            with self.assertRaises(FileExistsError):
                MOD.build_development_oof(fold_roots, existing)
            result = MOD.build_development_oof(fold_roots, root / "oof")
            text = result["metrics_path"].read_text(encoding="utf-8")
            self.assertNotIn("/workspace", text)
            self.assertNotIn("C:\\", text)
            self.assertFalse(
                MOD._json_contains_absolute_path(result["metrics"])
            )


def make_bootstrap_oof(rows):
    frame = pd.DataFrame(rows)
    return frame[MOD.OOF_COLUMNS]


def unequal_field_oof(*, candidate_mode="better"):
    """Unequal race sizes: one tiny race, one large race."""
    rows = []
    # Race A: 2 entries
    if candidate_mode == "better":
        a_base = (0.20, 0.80)
        a_cand = (0.90, 0.10)
    elif candidate_mode == "worse":
        a_base = (0.90, 0.10)
        a_cand = (0.20, 0.80)
    else:
        a_base = (0.35, 0.65)
        a_cand = a_base
    rows.append(
        {
            "race_id": "A",
            "entry_id": "A-1",
            "fold": 1,
            "source_ym": "202206",
            MOD.TARGET: 1,
            "baseline_prediction": a_base[0],
            "candidate_prediction": a_cand[0],
        }
    )
    rows.append(
        {
            "race_id": "A",
            "entry_id": "A-2",
            "fold": 1,
            "source_ym": "202206",
            MOD.TARGET: 0,
            "baseline_prediction": a_base[1],
            "candidate_prediction": a_cand[1],
        }
    )
    # Race B: 8 entries (dominates global log loss)
    for i in range(8):
        label = 1 if i % 2 == 0 else 0
        if candidate_mode == "better":
            base = 0.55 if label == 1 else 0.45
            cand = 0.85 if label == 1 else 0.15
        elif candidate_mode == "worse":
            base = 0.85 if label == 1 else 0.15
            cand = 0.55 if label == 1 else 0.45
        else:
            base = 0.6 if label == 1 else 0.4
            cand = base
        rows.append(
            {
                "race_id": "B",
                "entry_id": f"B-{i}",
                "fold": 2,
                "source_ym": "202306",
                MOD.TARGET: label,
                "baseline_prediction": base,
                "candidate_prediction": cand,
            }
        )
    return make_bootstrap_oof(rows)


class AblateNarV3BootstrapTest(unittest.TestCase):
    def test_contract_constants_and_rng(self):
        self.assertEqual(MOD.BOOTSTRAP_RESAMPLING_UNIT, "race_id")
        self.assertEqual(MOD.BOOTSTRAP_RESAMPLES, 10000)
        self.assertEqual(MOD.BOOTSTRAP_SEED, 20260825)
        self.assertEqual(MOD.BOOTSTRAP_CONFIDENCE_LEVEL, 0.95)
        self.assertEqual(MOD.BOOTSTRAP_INTERVAL_METHOD, "percentile")
        self.assertEqual(MOD.BOOTSTRAP_CI_LOWER_Q, 2.5)
        self.assertEqual(MOD.BOOTSTRAP_CI_UPPER_Q, 97.5)
        self.assertEqual(
            MOD.BOOTSTRAP_STATISTIC,
            "candidate_log_loss_minus_baseline_log_loss",
        )
        rng = MOD.bootstrap_rng()
        self.assertIsInstance(rng, np.random.Generator)
        self.assertIsInstance(rng.bit_generator, np.random.PCG64)
        a = MOD.bootstrap_rng(MOD.BOOTSTRAP_SEED).integers(0, 1000, size=5)
        b = MOD.bootstrap_rng(MOD.BOOTSTRAP_SEED).integers(0, 1000, size=5)
        self.assertTrue(np.array_equal(a, b))

    def test_race_cluster_sampling_not_entry_unit(self):
        # Race A: 2 entries, Race B: 5 entries — unequal field size.
        rows = []
        for i in range(2):
            rows.append(
                {
                    "race_id": "A",
                    "entry_id": f"A-{i}",
                    "fold": 1,
                    "source_ym": "202206",
                    MOD.TARGET: i % 2,
                    "baseline_prediction": 0.3,
                    "candidate_prediction": 0.4,
                }
            )
        for i in range(5):
            rows.append(
                {
                    "race_id": "B",
                    "entry_id": f"B-{i}",
                    "fold": 1,
                    "source_ym": "202206",
                    MOD.TARGET: i % 2,
                    "baseline_prediction": 0.3,
                    "candidate_prediction": 0.4,
                }
            )
        oof = make_bootstrap_oof(rows)
        races, groups = MOD.race_index_groups(oof["race_id"])
        self.assertEqual(races, ["A", "B"])
        self.assertEqual(len(groups[0]), 2)
        self.assertEqual(len(groups[1]), 5)

        # Sample sequence B, B, A => positions [1, 1, 0]
        expanded = MOD.expand_race_sample(groups, [1, 1, 0])
        self.assertEqual(len(expanded), 5 + 5 + 2)
        # B entries appear twice contiguously, then A.
        self.assertTrue(np.array_equal(expanded[:5], groups[1]))
        self.assertTrue(np.array_equal(expanded[5:10], groups[1]))
        self.assertTrue(np.array_equal(expanded[10:], groups[0]))

        # Entry-unit sampling would draw independent entries and would not
        # guarantee whole-race replication. Cluster expand always
        # replicates full race membership.
        for idx in groups[0]:
            self.assertEqual(int(np.sum(expanded == idx)), 1)
        for idx in groups[1]:
            self.assertEqual(int(np.sum(expanded == idx)), 2)

    def test_paired_same_sequence_and_statistic_direction(self):
        oof = unequal_field_oof(candidate_mode="better")
        races, groups = MOD.race_index_groups(oof["race_id"])
        y = oof[MOD.TARGET].to_numpy(dtype=np.int8)
        base = oof["baseline_prediction"].to_numpy()
        cand = oof["candidate_prediction"].to_numpy()
        # Fixed sample: always draw race B then A then B => same rows for both
        positions = np.array([1, 0, 1], dtype=np.int64)
        rows = MOD.expand_race_sample(groups, positions)
        delta = (
            float(log_loss(y[rows], cand[rows], labels=[0, 1]))
            - float(log_loss(y[rows], base[rows], labels=[0, 1]))
        )
        # Paired: swapping arms negates delta with identical row index.
        swapped = (
            float(log_loss(y[rows], base[rows], labels=[0, 1]))
            - float(log_loss(y[rows], cand[rows], labels=[0, 1]))
        )
        self.assertAlmostEqual(delta, -swapped)
        self.assertEqual(
            MOD.BOOTSTRAP_STATISTIC,
            "candidate_log_loss_minus_baseline_log_loss",
        )

    def test_global_log_loss_not_race_average(self):
        # Craft unequal races so race-mean delta != global delta.
        rows = [
            {
                "race_id": "tiny",
                "entry_id": "t1",
                "fold": 1,
                "source_ym": "202206",
                MOD.TARGET: 1,
                "baseline_prediction": 0.99,
                "candidate_prediction": 0.01,
            },
            {
                "race_id": "tiny",
                "entry_id": "t0",
                "fold": 1,
                "source_ym": "202206",
                MOD.TARGET: 0,
                "baseline_prediction": 0.01,
                "candidate_prediction": 0.99,
            },
        ]
        # Large race: candidate slightly worse on many rows.
        for i in range(20):
            label = 1 if i < 10 else 0
            rows.append(
                {
                    "race_id": "huge",
                    "entry_id": f"h{i}",
                    "fold": 1,
                    "source_ym": "202206",
                    MOD.TARGET: label,
                    "baseline_prediction": 0.80 if label == 1 else 0.20,
                    "candidate_prediction": 0.70 if label == 1 else 0.30,
                }
            )
        oof = make_bootstrap_oof(rows)
        y = oof[MOD.TARGET].to_numpy(dtype=np.int8)
        base = oof["baseline_prediction"].to_numpy()
        cand = oof["candidate_prediction"].to_numpy()
        global_delta = float(log_loss(y, cand, labels=[0, 1])) - float(
            log_loss(y, base, labels=[0, 1])
        )
        race_deltas = []
        for race in oof["race_id"].unique():
            mask = oof["race_id"] == race
            race_deltas.append(
                float(log_loss(y[mask], cand[mask], labels=[0, 1]))
                - float(log_loss(y[mask], base[mask], labels=[0, 1]))
            )
        race_mean_delta = float(np.mean(race_deltas))
        self.assertNotAlmostEqual(global_delta, race_mean_delta, places=6)

        with tempfile.TemporaryDirectory() as td:
            result = MOD.run_race_paired_bootstrap(oof, Path(td) / "boot")
            self.assertAlmostEqual(
                result["metrics"]["observed_delta"],
                global_delta,
                places=12,
            )
            self.assertNotAlmostEqual(
                result["metrics"]["observed_delta"],
                race_mean_delta,
                places=6,
            )

    def test_better_worse_identical_and_single_race(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            better = unequal_field_oof(candidate_mode="better")
            worse = unequal_field_oof(candidate_mode="worse")
            identical = unequal_field_oof(candidate_mode="identical")

            b = MOD.run_race_paired_bootstrap(better, root / "better")
            self.assertLess(b["metrics"]["observed_delta"], 0)

            w = MOD.run_race_paired_bootstrap(worse, root / "worse")
            self.assertGreater(w["metrics"]["observed_delta"], 0)

            same = MOD.run_race_paired_bootstrap(identical, root / "same")
            self.assertEqual(same["metrics"]["observed_delta"], 0.0)
            self.assertTrue(np.all(same["deltas"] == 0.0))
            self.assertEqual(same["metrics"]["bootstrap"]["ci_lower"], 0.0)
            self.assertEqual(same["metrics"]["bootstrap"]["ci_upper"], 0.0)
            self.assertEqual(same["metrics"]["bootstrap"]["mean"], 0.0)
            self.assertEqual(same["metrics"]["bootstrap"]["median"], 0.0)

            # Single race: still well-defined (always resamples that race).
            single_rows = [
                {
                    "race_id": "only",
                    "entry_id": f"e{i}",
                    "fold": 1,
                    "source_ym": "202206",
                    MOD.TARGET: 1 if i % 2 == 0 else 0,
                    "baseline_prediction": 0.4,
                    "candidate_prediction": 0.7 if i % 2 == 0 else 0.2,
                }
                for i in range(4)
            ]
            single = make_bootstrap_oof(single_rows)
            s1 = MOD.run_race_paired_bootstrap(single, root / "single1")
            s2 = MOD.run_race_paired_bootstrap(single, root / "single2")
            self.assertEqual(s1["metrics"]["race_count"], 1)
            self.assertEqual(
                s1["metrics"]["observed_delta"],
                s1["metrics"]["bootstrap"]["mean"],
            )
            self.assertTrue(np.allclose(s1["deltas"], s1["deltas"][0]))
            self.assertEqual(
                s1["metrics"]["bootstrap"]["distribution_sha256"],
                s2["metrics"]["bootstrap"]["distribution_sha256"],
            )

    def test_determinism_two_runs(self):
        oof = unequal_field_oof(candidate_mode="better")
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            first = MOD.run_race_paired_bootstrap(oof, root / "a")
            second = MOD.run_race_paired_bootstrap(oof, root / "b")
            m1 = first["metrics"]
            m2 = second["metrics"]
            self.assertEqual(
                m1["bootstrap"]["ci_lower"],
                m2["bootstrap"]["ci_lower"],
            )
            self.assertEqual(
                m1["bootstrap"]["ci_upper"],
                m2["bootstrap"]["ci_upper"],
            )
            self.assertEqual(m1["bootstrap"]["mean"], m2["bootstrap"]["mean"])
            self.assertEqual(
                m1["bootstrap"]["median"],
                m2["bootstrap"]["median"],
            )
            self.assertEqual(
                m1["bootstrap"]["distribution_sha256"],
                m2["bootstrap"]["distribution_sha256"],
            )
            self.assertEqual(
                first["bootstrap_metrics_sha256"],
                second["bootstrap_metrics_sha256"],
            )
            self.assertEqual(
                first["metrics_path"].read_text(encoding="utf-8"),
                second["metrics_path"].read_text(encoding="utf-8"),
            )
            self.assertEqual(len(first["deltas"]), 10000)
            self.assertEqual(m1["resamples"], 10000)
            self.assertEqual(m1["seed"], 20260825)
            self.assertEqual(m1["interval_method"], "percentile")
            self.assertEqual(m1["ci_lower_percentile"], 2.5)
            self.assertEqual(m1["ci_upper_percentile"], 97.5)
            self.assertEqual(m1["reproducibility"], "PASS")
            self.assertEqual(
                m1["calibration"]["status"],
                "not_yet_frozen",
            )
            lower, upper = MOD.percentile_ci(first["deltas"])
            self.assertEqual(lower, m1["bootstrap"]["ci_lower"])
            self.assertEqual(upper, m1["bootstrap"]["ci_upper"])

    def test_eligibility_boundary(self):
        self.assertTrue(
            MOD.development_candidate_eligible(0.5, 0.6, -0.01, 0)
        )
        self.assertFalse(
            MOD.development_candidate_eligible(0.5, 0.6, 0.0, 0)
        )
        self.assertFalse(
            MOD.development_candidate_eligible(0.5, 0.6, 0.01, 0)
        )
        self.assertFalse(
            MOD.development_candidate_eligible(0.6, 0.6, -0.01, 0)
        )
        self.assertFalse(
            MOD.development_candidate_eligible(0.7, 0.6, -0.01, 0)
        )
        self.assertFalse(
            MOD.development_candidate_eligible(0.5, 0.6, -0.01, 1)
        )

    def test_input_guards(self):
        base = unequal_field_oof(candidate_mode="better")
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(base.iloc[0:0])
        self.assertIn("empty", str(ctx.exception))

        missing_race = base.copy()
        missing_race.loc[0, "race_id"] = pd.NA
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(missing_race)
        self.assertIn("missing race_id", str(ctx.exception))

        dup = base.copy()
        dup.loc[1, "entry_id"] = dup.loc[0, "entry_id"]
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(dup)
        self.assertIn("duplicate entry_id", str(ctx.exception))

        bad_label = base.copy()
        bad_label.loc[0, MOD.TARGET] = 2
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(bad_label)
        self.assertIn("invalid target", str(ctx.exception))

        nan_pred = base.copy()
        nan_pred.loc[0, "baseline_prediction"] = np.nan
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(nan_pred)
        self.assertIn("NaN/inf", str(ctx.exception))

        inf_pred = base.copy()
        inf_pred.loc[0, "candidate_prediction"] = np.inf
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(inf_pred)
        self.assertIn("NaN/inf", str(ctx.exception))

        oor = base.copy()
        oor.loc[0, "candidate_prediction"] = 1.5
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(oor)
        self.assertIn("out of range", str(ctx.exception))

        locked = base.copy()
        locked.loc[0, "source_ym"] = "202501"
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(locked)
        self.assertIn("locked/OOT", str(ctx.exception))

        oot = base.copy()
        oot.loc[0, "source_ym"] = "202606"
        with self.assertRaises(ValueError) as ctx:
            MOD.assert_bootstrap_oof_input(oot)
        self.assertIn("locked/OOT", str(ctx.exception))

    def test_output_collision_symlink_and_no_leaks(self):
        oof = unequal_field_oof(candidate_mode="better")
        frozen = REPO_ROOT / "data-manifests" / "nar-v3-baseline9"
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            with self.assertRaises(ValueError):
                MOD.run_race_paired_bootstrap(oof, frozen)
            alias = root / "alias"
            alias.symlink_to(frozen, target_is_directory=True)
            with self.assertRaises(ValueError) as ctx:
                MOD.run_race_paired_bootstrap(oof, alias)
            self.assertIn("collides", str(ctx.exception))
            existing = root / "existing"
            existing.mkdir()
            with self.assertRaises(FileExistsError):
                MOD.run_race_paired_bootstrap(oof, existing)
            result = MOD.run_race_paired_bootstrap(oof, root / "boot")
            text = result["metrics_path"].read_text(encoding="utf-8")
            self.assertNotIn("/workspace", text)
            self.assertNotIn("C:\\", text)
            self.assertNotIn("secret", text.lower())
            self.assertNotIn("api_key", text.lower())
            self.assertFalse(
                MOD._json_contains_absolute_path(result["metrics"])
            )
            # Huge delta array must not appear in the artifact.
            self.assertNotIn("deltas", result["metrics"])
            parsed = json.loads(text)
            self.assertNotIn("deltas", parsed)


if __name__ == "__main__":
    unittest.main()
