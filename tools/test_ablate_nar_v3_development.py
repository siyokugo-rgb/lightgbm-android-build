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


def month_rows_for(months, *, arm):
    rows = {}
    for ym in months:
        sex = sex_for_month(ym) if arm == MOD.ARM_SEX1 else None
        rows[ym] = [
            sample_row(
                ym,
                sire=sire_for_month(ym),
                sex=sex,
            )
        ]
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

    def test_helper_does_not_include_training_or_production_runner(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("import lightgbm", source)
        self.assertNotIn("lgb.train", source)
        self.assertNotIn("TRANSFORM.run_transform", source)
        self.assertNotIn("early_stopping", source)
        self.assertNotIn("def bootstrap", source)
        self.assertNotIn("def train_", source)


def fold_month_plus_oot():
    months = list(MOD.fold_month_lists(1)["transform_months"])
    months.append("202501")
    return months


if __name__ == "__main__":
    unittest.main()
