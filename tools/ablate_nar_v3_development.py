#!/usr/bin/env python3
"""Fold-local transform and training helpers for NAR V3 DEVELOPMENT ablation.

This module fits category dictionaries on each rolling-origin fold's
training months, transforms that fold's train+validation months, and can
train one fold/arm with the frozen baseline9 LightGBM contract.

It does not concatenate OOF predictions, run paired bootstrap, promote a
champion, or invoke the production transform runner.
"""

import hashlib
import importlib.util
import json
import platform
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
_TRANSFORM_PATH = HERE / "transform_nar_v3_dataset.py"
_SPEC = importlib.util.spec_from_file_location(
    "transform_nar_v3_dataset",
    _TRANSFORM_PATH,
)
TRANSFORM = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(TRANSFORM)

ARM_BASELINE = "baseline"
ARM_SEX1 = "sex1"
ARMS = (ARM_BASELINE, ARM_SEX1)
FOLD_IDS = (1, 2, 3)
LOCKED_OOT_START_YM = "202501"
SEX_FEATURE = "feature_entry__性"
SHARED_CATEGORICAL = list(TRANSFORM.EXPECTED_CATEGORICAL)

CONFIG_PATHS = {
    ARM_BASELINE: REPO_ROOT / "config" / "nar-v3-transform.json",
    ARM_SEX1: (
        REPO_ROOT
        / "config"
        / "candidates"
        / "nar-v3-sex1"
        / "transform.json"
    ),
}

EXPECTED_FEATURE_COUNTS = {
    ARM_BASELINE: {
        "feature_count": 9,
        "categorical_feature_count": 5,
    },
    ARM_SEX1: {
        "feature_count": 10,
        "categorical_feature_count": 6,
    },
}

TARGET = "label_win"
BASELINE9_CONFIG_PATH = REPO_ROOT / "config" / "nar-v3-win-baseline9.json"
SHARED_MODEL_FEATURES = (
    list(TRANSFORM.EXPECTED_NUMERIC_OUTPUTS)
    + list(TRANSFORM.EXPECTED_CATEGORICAL)
)
EXPECTED_CATEGORICAL_INDICES = {
    ARM_BASELINE: [4, 5, 6, 7, 8],
    ARM_SEX1: [4, 5, 6, 7, 8, 9],
}
IDENTITY_COLS = ["race_id", "entry_id", "source_ym", TARGET]
LABEL_FIELDS = list(TRANSFORM.LABEL_FIELDS)

# Exclusive start of months this fold must not open.
FOLD_WINDOWS = {
    1: {
        "fit_start": "202101",
        "fit_end": "202112",
        "validation_start": "202201",
        "validation_end": "202212",
        "forbidden_from": "202301",
    },
    2: {
        "fit_start": "202101",
        "fit_end": "202212",
        "validation_start": "202301",
        "validation_end": "202312",
        "forbidden_from": "202401",
    },
    3: {
        "fit_start": "202101",
        "fit_end": "202312",
        "validation_start": "202401",
        "validation_end": "202412",
        "forbidden_from": "202501",
    },
}

EXPECTED_FOLD_COUNTS = {
    1: {"fit_months": 12, "validation_months": 12, "transform_months": 24},
    2: {"fit_months": 24, "validation_months": 12, "transform_months": 36},
    3: {"fit_months": 36, "validation_months": 12, "transform_months": 48},
}


def _month_list(start, end):
    return list(TRANSFORM.month_range(start, end))


def assert_strict_month_list(months, *, label):
    if not isinstance(months, (list, tuple)) or not months:
        raise ValueError(f"{label} must be a non-empty list")
    seen = set()
    previous = None
    for ym in months:
        if not isinstance(ym, str):
            raise ValueError(f"{label} contains a non-string month")
        TRANSFORM.valid_ym(ym)
        if ym in seen:
            raise ValueError(f"{label} has duplicate month: {ym}")
        seen.add(ym)
        if previous is not None and ym <= previous:
            raise ValueError(f"{label} is not strictly increasing at {ym}")
        previous = ym
    assert_no_locked_oot_months(months, label=label)
    return list(months)


def assert_no_locked_oot_months(months, *, label):
    for ym in months:
        TRANSFORM.valid_ym(ym)
        year = int(ym[:4])
        if year >= 2025 or ym >= LOCKED_OOT_START_YM:
            raise ValueError(
                f"{label} includes locked/OOT month before source open: {ym}"
            )


def fold_month_lists(fold_id):
    if fold_id not in FOLD_WINDOWS:
        raise ValueError(f"unknown fold id: {fold_id}")
    window = FOLD_WINDOWS[fold_id]
    fit_months = _month_list(window["fit_start"], window["fit_end"])
    validation_months = _month_list(
        window["validation_start"],
        window["validation_end"],
    )
    transform_months = _month_list(
        window["fit_start"],
        window["validation_end"],
    )
    spec = {
        "fold_id": fold_id,
        "fit_months": fit_months,
        "validation_months": validation_months,
        "transform_months": transform_months,
        "forbidden_from": window["forbidden_from"],
    }
    assert_fold_month_lists(spec)
    return spec


def assert_fold_month_lists(spec):
    fold_id = spec["fold_id"]
    fit_months = assert_strict_month_list(
        spec["fit_months"],
        label=f"fold {fold_id} fit_months",
    )
    validation_months = assert_strict_month_list(
        spec["validation_months"],
        label=f"fold {fold_id} validation_months",
    )
    transform_months = assert_strict_month_list(
        spec["transform_months"],
        label=f"fold {fold_id} transform_months",
    )
    expected = EXPECTED_FOLD_COUNTS[fold_id]
    if len(fit_months) != expected["fit_months"]:
        raise ValueError(f"fold {fold_id} fit_months count mismatch")
    if len(validation_months) != expected["validation_months"]:
        raise ValueError(f"fold {fold_id} validation_months count mismatch")
    if len(transform_months) != expected["transform_months"]:
        raise ValueError(f"fold {fold_id} transform_months count mismatch")
    if set(fit_months) & set(validation_months):
        raise ValueError(f"fold {fold_id} train/validation months overlap")
    if transform_months != fit_months + validation_months:
        raise ValueError(
            f"fold {fold_id} transform_months must be fit+validation"
        )
    if not (max(fit_months) < min(validation_months)):
        raise ValueError(
            f"fold {fold_id} requires max(fit_months) < min(validation_months)"
        )
    forbidden_from = spec["forbidden_from"]
    TRANSFORM.valid_ym(forbidden_from)
    for ym in transform_months:
        if ym >= forbidden_from:
            raise ValueError(
                f"fold {fold_id} transform months include future month {ym}"
            )
    return spec


def _role_allowed_months(spec, role):
    if role == "fit":
        return spec["fit_months"]
    if role == "validation":
        return spec["validation_months"]
    if role == "transform":
        return spec["transform_months"]
    raise ValueError(f"unknown month role: {role}")


def _month_rejection_reason(spec, ym, role):
    if ym >= LOCKED_OOT_START_YM or int(ym[:4]) >= 2025:
        return "locked/OOT"
    if ym >= spec["forbidden_from"]:
        return "future-to-fold"
    if role == "fit" and ym in spec["validation_months"]:
        return "validation"
    return "outside-window"


def assert_months_allowed_for_role(fold_id, months, role):
    """Fail-closed month gate. Must run before any source path is opened."""
    spec = fold_month_lists(fold_id)
    checked = assert_strict_month_list(
        months,
        label=f"fold {fold_id} {role} months",
    )
    allowed = _role_allowed_months(spec, role)
    allowed_set = set(allowed)
    for ym in checked:
        if ym not in allowed_set:
            reason = _month_rejection_reason(spec, ym, role)
            raise ValueError(
                f"fold {fold_id} {role} months include {reason} month "
                f"before source open: {ym}"
            )
    if checked != allowed:
        raise ValueError(
            f"fold {fold_id} {role} months must equal the canonical "
            f"{role} window"
        )
    return list(checked)


def resolve_fit_months(fold_id, fit_months=None):
    spec = fold_month_lists(fold_id)
    if fit_months is None:
        return list(spec["fit_months"])
    return assert_months_allowed_for_role(fold_id, fit_months, "fit")


def schema_for_arm(arm):
    if arm == ARM_BASELINE:
        return TRANSFORM.schema_for_profile(None)
    if arm == ARM_SEX1:
        return TRANSFORM.schema_for_profile(
            TRANSFORM.CANDIDATE_PROFILE_SEX1
        )
    raise ValueError(f"unknown ablation arm: {arm}")


def config_path_for_arm(arm):
    if arm not in CONFIG_PATHS:
        raise ValueError(f"unknown ablation arm: {arm}")
    path = CONFIG_PATHS[arm]
    if not path.is_file():
        raise FileNotFoundError(path)
    return path


def load_arm_config(arm):
    path = config_path_for_arm(arm)
    cfg = TRANSFORM.load_json(path)
    TRANSFORM.validate_dictionary_contract(
        cfg.get("categorical_dictionary")
    )
    TRANSFORM.validate_shared_transform_contract(cfg)
    schema = schema_for_arm(arm)
    categorical = cfg.get("categorical_passthrough")
    if categorical != schema["categorical"]:
        raise ValueError(f"{arm} categorical schema mismatch")
    if arm == ARM_BASELINE:
        if SEX_FEATURE in schema["categorical"]:
            raise ValueError("baseline schema must not include sex")
        if cfg.get("candidate_profile") is not None:
            raise ValueError("baseline config must not set candidate_profile")
    else:
        if SEX_FEATURE not in schema["categorical"]:
            raise ValueError("sex1 schema missing sex categorical")
        if cfg.get("candidate_profile") != TRANSFORM.CANDIDATE_PROFILE_SEX1:
            raise ValueError("sex1 config candidate_profile mismatch")
    expected = EXPECTED_FEATURE_COUNTS[arm]
    order = TRANSFORM.build_feature_order(cfg, schema)
    if order["feature_count"] != expected["feature_count"]:
        raise ValueError(f"{arm} feature count mismatch")
    if (
        order["categorical_feature_count"]
        != expected["categorical_feature_count"]
    ):
        raise ValueError(f"{arm} categorical count mismatch")
    return cfg, path, schema, order


def forbidden_ablation_out_roots():
    manifests = REPO_ROOT / "data-manifests"
    paths = [
        manifests / "nar-v3-dataset",
        manifests / "nar-v3-transformed",
        manifests / "nar-v3-baseline9",
        manifests / "candidates" / "nar-v3-sex1",
        Path("/workspaces/nar-v3-dataset"),
        Path("/workspaces/nar-v3-transformed"),
        Path("/workspaces/nar-v3-baseline9"),
    ]
    if manifests.is_dir():
        paths.extend(sorted(manifests.glob("nar-v3-*")))
    unique = []
    seen = set()
    for path in paths:
        resolved = path.resolve()
        key = str(resolved)
        if key in seen:
            continue
        seen.add(key)
        unique.append(path)
    return unique


def assert_ablation_out_root_allowed(out_root):
    if out_root is None:
        raise ValueError("ablation out root is required")
    resolved = out_root.resolve()
    for forbidden in forbidden_ablation_out_roots():
        forbidden_resolved = forbidden.resolve()
        if (
            resolved == forbidden_resolved
            or forbidden_resolved in resolved.parents
            or resolved in forbidden_resolved.parents
        ):
            raise ValueError(
                "ablation output root collides with frozen/production root: "
                f"{resolved}"
            )
    return resolved


def fold_arm_out_root(root, fold_id, arm):
    if fold_id not in FOLD_IDS:
        raise ValueError(f"unknown fold id: {fold_id}")
    if arm not in ARMS:
        raise ValueError(f"unknown ablation arm: {arm}")
    return Path(root) / f"fold{fold_id}" / arm


def prepare_arm_out_root(root, fold_id, arm):
    out_root = fold_arm_out_root(root, fold_id, arm)
    assert_ablation_out_root_allowed(out_root)
    if out_root.exists():
        raise FileExistsError(
            f"ablation fold/arm out root already exists: {out_root}"
        )
    return out_root


def assert_shared_dictionary_parity(baseline_dict, candidate_dict):
    if SEX_FEATURE in baseline_dict.get("features", {}):
        raise ValueError("baseline dictionary must not contain sex")
    candidate_features = candidate_dict.get("features", {})
    if SEX_FEATURE not in candidate_features:
        raise ValueError("candidate dictionary missing sex")
    for name in SHARED_CATEGORICAL:
        baseline_map = (
            baseline_dict["features"][name]["value_to_id"]
        )
        candidate_map = (
            candidate_dict["features"][name]["value_to_id"]
        )
        if baseline_map != candidate_map:
            raise ValueError(
                f"shared categorical value_to_id mismatch: {name}"
            )
        if (
            baseline_dict["features"][name]["known_count"]
            != candidate_dict["features"][name]["known_count"]
        ):
            raise ValueError(
                f"shared categorical known_count mismatch: {name}"
            )
    extra = set(candidate_features) - set(SHARED_CATEGORICAL)
    if extra != {SEX_FEATURE}:
        raise ValueError(
            "candidate categorical extras must be sex only: "
            f"{sorted(extra)}"
        )


def fit_fold_arm_dictionaries(
    fold_id,
    arm,
    dataset_root,
    *,
    fit_months=None,
):
    months = resolve_fit_months(fold_id, fit_months)
    cfg, cfg_path, schema, order = load_arm_config(arm)
    dictionaries = TRANSFORM.fit_category_dictionaries(
        dataset_root,
        months,
        cfg,
        schema,
    )
    if dictionaries.get("fit_months") != months:
        raise ValueError(
            "dictionary fit_months drifted from fold training months"
        )
    if arm == ARM_BASELINE and SEX_FEATURE in dictionaries["features"]:
        raise ValueError("baseline dictionary must not contain sex")
    if arm == ARM_SEX1 and SEX_FEATURE not in dictionaries["features"]:
        raise ValueError("candidate dictionary missing sex")
    return {
        "fold_id": fold_id,
        "arm": arm,
        "fit_months": months,
        "config_path": cfg_path,
        "schema": schema,
        "feature_order": order,
        "dictionaries": dictionaries,
    }


def write_fold_arm_artifacts(out_root, dictionaries, feature_order):
    artifact_dir = Path(out_root) / "artifacts"
    dictionary_path = artifact_dir / "category-dictionaries.json"
    feature_order_path = artifact_dir / "feature-order.json"
    TRANSFORM.write_json_atomic(dictionary_path, dictionaries)
    TRANSFORM.write_json_atomic(feature_order_path, feature_order)
    return dictionary_path, feature_order_path


def transform_fold_arm(
    fold_id,
    arm,
    dataset_root,
    out_root,
    *,
    dictionaries=None,
    fit_months=None,
):
    spec = fold_month_lists(fold_id)
    transform_months = assert_months_allowed_for_role(
        fold_id,
        spec["transform_months"],
        "transform",
    )
    out_root = Path(out_root)
    assert_ablation_out_root_allowed(out_root)
    if out_root.exists():
        raise FileExistsError(
            f"ablation fold/arm out root already exists: {out_root}"
        )

    if dictionaries is None:
        prepared = fit_fold_arm_dictionaries(
            fold_id,
            arm,
            dataset_root,
            fit_months=fit_months,
        )
        dictionaries = prepared["dictionaries"]
        cfg_path = prepared["config_path"]
        schema = prepared["schema"]
        feature_order = prepared["feature_order"]
        fit_used = prepared["fit_months"]
    else:
        _cfg, cfg_path, schema, feature_order = load_arm_config(arm)
        fit_used = resolve_fit_months(fold_id, fit_months)
        if dictionaries.get("fit_months") != fit_used:
            raise ValueError(
                "provided dictionary fit_months do not match fold training"
            )

    dictionary_path, feature_order_path = write_fold_arm_artifacts(
        out_root,
        dictionaries,
        feature_order,
    )

    outputs = []
    for ym in transform_months:
        assert_months_allowed_for_role(fold_id, transform_months, "transform")
        if ym >= spec["forbidden_from"]:
            raise ValueError(
                f"fold {fold_id} attempted to transform future month {ym}"
            )
        out_path, out_sidecar, counts = TRANSFORM.transform_month(
            ym=ym,
            dataset_root=dataset_root,
            out_root=out_root,
            cfg_path=cfg_path,
            dictionaries=dictionaries,
            dictionary_path=dictionary_path,
            feature_order=feature_order,
            feature_order_path=feature_order_path,
            schema=schema,
        )
        outputs.append((out_path, out_sidecar, counts))

    return {
        "fold_id": fold_id,
        "arm": arm,
        "fit_months": fit_used,
        "validation_months": spec["validation_months"],
        "transform_months": transform_months,
        "out_root": out_root,
        "dictionary_path": dictionary_path,
        "feature_order_path": feature_order_path,
        "dictionaries": dictionaries,
        "feature_order": feature_order,
        "outputs": outputs,
    }


def load_baseline9_training_contract():
    path = BASELINE9_CONFIG_PATH
    if not path.is_file():
        raise FileNotFoundError(path)
    cfg = TRANSFORM.load_json(path)
    params = cfg.get("lightgbm_params")
    training = cfg.get("training")
    expected_params = {
        "objective": "binary",
        "metric": ["binary_logloss", "auc"],
        "learning_rate": 0.05,
        "num_leaves": 31,
        "min_data_in_leaf": 100,
        "feature_fraction": 0.9,
        "bagging_fraction": 0.9,
        "bagging_freq": 1,
        "lambda_l1": 0.0,
        "lambda_l2": 1.0,
        "max_depth": -1,
        "verbosity": -1,
        "deterministic": True,
        "force_col_wise": True,
        "num_threads": 2,
        "seed": 20260825,
        "feature_fraction_seed": 20260825,
        "bagging_seed": 20260825,
        "data_random_seed": 20260825,
    }
    if params != expected_params:
        raise ValueError("baseline9 LightGBM params drifted from contract")
    if training != {
        "num_boost_round": 2000,
        "early_stopping_rounds": 100,
        "log_evaluation_period": 50,
        "early_stopping_metric": "binary_logloss",
    }:
        raise ValueError("baseline9 training contract mismatch")
    if cfg.get("target") != TARGET:
        raise ValueError("baseline9 target mismatch")
    return {
        "config_path": path.name,
        "config_sha256": TRANSFORM.sha256_file(path),
        "lightgbm_params": json.loads(json.dumps(params)),
        "training": json.loads(json.dumps(training)),
        "first_metric_only": True,
        "valid_names": ["validation"],
    }


def fold_lightgbm_train_contract():
    return load_baseline9_training_contract()


def model_csv_path(transform_root, ym):
    TRANSFORM.valid_ym(ym)
    return Path(transform_root) / "monthly" / ym[:4] / f"{ym}_model.csv.gz"


def model_manifest_path(transform_root, ym):
    TRANSFORM.valid_ym(ym)
    return (
        Path(transform_root)
        / "monthly"
        / ym[:4]
        / f"{ym}_model.manifest.json"
    )


def resolve_role_months(fold_id, role, months=None):
    spec = fold_month_lists(fold_id)
    canonical = _role_allowed_months(spec, role)
    requested = list(canonical) if months is None else list(months)
    return assert_months_allowed_for_role(fold_id, requested, role)


def assert_feature_order_for_arm(arm, feature_order):
    expected = EXPECTED_FEATURE_COUNTS[arm]
    if feature_order.get("feature_count") != expected["feature_count"]:
        raise ValueError(f"{arm} feature_count mismatch")
    if (
        feature_order.get("categorical_feature_count")
        != expected["categorical_feature_count"]
    ):
        raise ValueError(f"{arm} categorical_feature_count mismatch")
    names = [item.get("name") for item in feature_order.get("features", [])]
    if arm == ARM_BASELINE:
        if names != SHARED_MODEL_FEATURES:
            raise ValueError("baseline feature names mismatch")
        if SEX_FEATURE in names:
            raise ValueError("baseline feature-order must not include sex")
    else:
        if names != SHARED_MODEL_FEATURES + [SEX_FEATURE]:
            raise ValueError("sex1 feature names mismatch")
        if SEX_FEATURE not in names:
            raise ValueError("sex1 feature-order missing sex")
    indices = feature_order.get("categorical_feature_indices")
    if indices != EXPECTED_CATEGORICAL_INDICES[arm]:
        raise ValueError(f"{arm} categorical indices mismatch")
    cat_names = feature_order.get("categorical_feature_names")
    expected_cats = names[4:]
    if cat_names != expected_cats:
        raise ValueError(f"{arm} categorical names mismatch")
    return list(names), list(expected_cats)


def load_fold_feature_contract(transform_root, arm):
    root = Path(transform_root)
    feature_order_path = root / "artifacts" / "feature-order.json"
    dictionary_path = root / "artifacts" / "category-dictionaries.json"
    if not feature_order_path.is_file() or not dictionary_path.is_file():
        raise FileNotFoundError("fold transform artifacts missing")
    feature_order = TRANSFORM.load_json(feature_order_path)
    dictionaries = TRANSFORM.load_json(dictionary_path)
    names, cats = assert_feature_order_for_arm(arm, feature_order)
    if arm == ARM_BASELINE and SEX_FEATURE in dictionaries.get("features", {}):
        raise ValueError("baseline dictionary must not contain sex")
    if arm == ARM_SEX1 and SEX_FEATURE not in dictionaries.get("features", {}):
        raise ValueError("candidate dictionary missing sex")
    return {
        "feature_order": feature_order,
        "feature_order_path": feature_order_path,
        "feature_names": names,
        "categorical_names": cats,
        "dictionaries": dictionaries,
        "dictionary_path": dictionary_path,
        "feature_order_sha256": TRANSFORM.sha256_file(feature_order_path),
        "dictionary_sha256": TRANSFORM.sha256_file(dictionary_path),
    }


def _verify_model_month(transform_root, ym, arm, feature_names):
    csv_path = model_csv_path(transform_root, ym)
    manifest_path = model_manifest_path(transform_root, ym)
    if not csv_path.is_file() or not manifest_path.is_file():
        raise FileNotFoundError(ym)
    text = manifest_path.read_text(encoding="utf-8")
    if "C:\\" in text or "C:/" in text:
        raise ValueError(f"{ym}: absolute path leaked")
    sidecar = json.loads(text)
    schema = schema_for_arm(arm)
    if sidecar.get("dataset") != schema["output_dataset"]:
        raise ValueError(f"{ym}: model dataset mismatch")
    if sidecar.get("source_ym") != ym:
        raise ValueError(f"{ym}: model source_ym mismatch")
    if sidecar.get("output_file") != csv_path.name:
        raise ValueError(f"{ym}: model output file mismatch")
    if sidecar.get("feature_columns") != feature_names:
        raise ValueError(f"{ym}: model feature schema mismatch")
    if sidecar.get("label_columns") != LABEL_FIELDS:
        raise ValueError(f"{ym}: model label schema mismatch")
    if sidecar.get("output_sha256") != TRANSFORM.sha256_file(csv_path):
        raise ValueError(f"{ym}: model SHA-256 mismatch")
    if sidecar.get("output_bytes") != csv_path.stat().st_size:
        raise ValueError(f"{ym}: model byte count mismatch")
    rows = sidecar.get("counts", {}).get("rows")
    if not isinstance(rows, int) or rows <= 0:
        raise ValueError(f"{ym}: bad model row count")
    return csv_path, rows


def _label_stats(frame):
    source = len(frame)
    good = frame[TARGET].notna()
    masked = int((~good).sum())
    supervised = frame.loc[good].copy()
    remaining = supervised[TARGET]
    if not remaining.isin([0.0, 1.0]).all():
        raise ValueError("invalid target")
    supervised[TARGET] = supervised[TARGET].astype("int8")
    positive = int(supervised[TARGET].sum())
    stats = {
        "source": source,
        "masked": masked,
        "supervised": len(supervised),
        "positive": positive,
        "negative": len(supervised) - positive,
    }
    return supervised.reset_index(drop=True), stats


def load_fold_role_frame(
    fold_id,
    role,
    transform_root,
    arm,
    *,
    months=None,
):
    allowed = resolve_role_months(fold_id, role, months)
    contract = load_fold_feature_contract(transform_root, arm)
    feature_names = contract["feature_names"]
    cats = contract["categorical_names"]
    use = [
        "race_id",
        "entry_id",
        "source_ym",
        "split",
        *feature_names,
        TARGET,
    ]
    dtype = {name: "int32" for name in feature_names}
    dtype.update(
        {
            "race_id": "string",
            "entry_id": "string",
            "source_ym": "string",
            "split": "string",
            TARGET: "float32",
        }
    )
    frames = []
    expected = 0
    for ym in allowed:
        csv_path, rows = _verify_model_month(
            transform_root,
            ym,
            arm,
            feature_names,
        )
        frame = pd.read_csv(
            csv_path,
            compression="gzip",
            usecols=use,
            dtype=dtype,
            keep_default_na=True,
        )
        if len(frame) != rows:
            raise ValueError(f"{ym}: model row count mismatch")
        if not (frame.source_ym.astype(str) == ym).all():
            raise ValueError(f"{ym}: source_ym mismatch")
        if frame[["race_id", "entry_id", *feature_names]].isna().any().any():
            raise ValueError(f"{ym}: missing IDs/features")
        if (frame[cats] < 0).any().any():
            raise ValueError(f"{ym}: negative category id")
        if not frame[TARGET].dropna().isin([0.0, 1.0]).all():
            raise ValueError(f"{ym}: invalid target")
        frames.append(frame)
        expected += rows
    combined = pd.concat(frames, ignore_index=True)
    if len(combined) != expected:
        raise ValueError(f"{role}: concat row mismatch")
    if combined.entry_id.duplicated().any():
        raise ValueError(f"{role}: duplicate entry_id")
    supervised, stats = _label_stats(combined)
    return {
        "fold_id": fold_id,
        "arm": arm,
        "role": role,
        "months": list(allowed),
        "frame": supervised,
        "stats": stats,
        "feature_names": feature_names,
        "categorical_names": cats,
        "feature_order_sha256": contract["feature_order_sha256"],
        "dictionary_sha256": contract["dictionary_sha256"],
        "opened_months": list(allowed),
    }


def load_fold_training_frames(
    fold_id,
    arm,
    transform_root,
    *,
    train_months=None,
    validation_months=None,
):
    train_allowed = resolve_role_months(fold_id, "fit", train_months)
    validation_allowed = resolve_role_months(
        fold_id,
        "validation",
        validation_months,
    )
    train = load_fold_role_frame(
        fold_id,
        "fit",
        transform_root,
        arm,
        months=train_allowed,
    )
    validation = load_fold_role_frame(
        fold_id,
        "validation",
        transform_root,
        arm,
        months=validation_allowed,
    )
    train_ids = set(train["frame"].entry_id.astype(str))
    val_ids = set(validation["frame"].entry_id.astype(str))
    if train_ids & val_ids:
        raise ValueError("train/validation entry_id overlap")
    return {
        "fold_id": fold_id,
        "arm": arm,
        "train": train["frame"],
        "validation": validation["frame"],
        "train_stats": train["stats"],
        "validation_stats": validation["stats"],
        "train_months": train["months"],
        "validation_months": validation["months"],
        "feature_names": train["feature_names"],
        "categorical_names": train["categorical_names"],
        "feature_order_sha256": train["feature_order_sha256"],
        "dictionary_sha256": train["dictionary_sha256"],
        "opened_months": train["opened_months"] + validation["opened_months"],
    }


def _canonical_identity(frame):
    out = pd.DataFrame(
        {
            "race_id": frame.race_id.astype(str),
            "entry_id": frame.entry_id.astype(str),
            "source_ym": frame.source_ym.astype(str),
            TARGET: frame[TARGET].astype("int8"),
        }
    )
    return out.sort_values(
        ["source_ym", "entry_id"],
        kind="mergesort",
    ).reset_index(drop=True)


def assert_fold_arm_alignment(baseline_loaded, candidate_loaded):
    if baseline_loaded["fold_id"] != candidate_loaded["fold_id"]:
        raise ValueError("fold_id mismatch")
    if baseline_loaded["arm"] != ARM_BASELINE:
        raise ValueError("left arm must be baseline")
    if candidate_loaded["arm"] != ARM_SEX1:
        raise ValueError("right arm must be sex1")
    if (
        baseline_loaded["feature_names"] != SHARED_MODEL_FEATURES
        or SEX_FEATURE in baseline_loaded["feature_names"]
    ):
        raise ValueError("baseline features must be the shared 9")
    if candidate_loaded["feature_names"] != SHARED_MODEL_FEATURES + [
        SEX_FEATURE
    ]:
        raise ValueError("candidate features must be shared 9 plus sex")
    for split_name in ("train", "validation"):
        left = baseline_loaded[split_name]
        right = candidate_loaded[split_name]
        left_id = _canonical_identity(left)
        right_id = _canonical_identity(right)
        left_entries = set(left_id["entry_id"])
        right_entries = set(right_id["entry_id"])
        missing = sorted(left_entries - right_entries)
        extra = sorted(right_entries - left_entries)
        if missing or extra:
            raise ValueError(
                f"{split_name} entry mismatch missing={missing[:5]!r} "
                f"extra={extra[:5]!r}"
            )
        if not left_id.equals(right_id):
            raise ValueError(
                f"{split_name} race_id/entry_id/source_ym/label mismatch"
            )
        order = list(left_id["entry_id"])
        left_aligned = left.set_index(left.entry_id.astype(str)).loc[order]
        right_aligned = right.set_index(right.entry_id.astype(str)).loc[order]
        for name in SHARED_MODEL_FEATURES:
            left_values = left_aligned[name].to_numpy()
            right_values = right_aligned[name].to_numpy()
            if not np.array_equal(left_values, right_values):
                raise ValueError(
                    f"{split_name} shared feature mismatch: {name}"
                )
        if SEX_FEATURE in left.columns:
            raise ValueError("baseline frame must not contain sex")
        if SEX_FEATURE not in right.columns:
            raise ValueError("candidate frame missing sex")
    if baseline_loaded["train_stats"] != candidate_loaded["train_stats"]:
        raise ValueError("train label accounting mismatch")
    if (
        baseline_loaded["validation_stats"]
        != candidate_loaded["validation_stats"]
    ):
        raise ValueError("validation label accounting mismatch")


def assert_valid_predictions(frame, pred):
    values = np.asarray(pred, dtype=np.float64)
    if len(values) != len(frame):
        raise ValueError("prediction row count mismatch")
    if frame.entry_id.duplicated().any():
        raise ValueError("duplicate entry_id in prediction frame")
    if not np.isfinite(values).all():
        raise ValueError("prediction contains NaN/inf")
    if np.any(values < 0) or np.any(values > 1):
        raise ValueError("prediction out of range")
    return values


def _json_contains_absolute_path(obj):
    if isinstance(obj, dict):
        return any(_json_contains_absolute_path(v) for v in obj.values())
    if isinstance(obj, list):
        return any(_json_contains_absolute_path(v) for v in obj)
    if isinstance(obj, str):
        if obj.startswith("C:\\") or obj.startswith("C:/"):
            return True
        if obj.startswith("/") and not obj.startswith("feature_"):
            return True
    return False


def train_fold_arm(
    fold_id,
    arm,
    transform_root,
    out_root,
    *,
    align_with=None,
    train_months=None,
    validation_months=None,
):
    out_root = Path(out_root)
    assert_ablation_out_root_allowed(out_root)
    if out_root.exists():
        raise FileExistsError(
            f"ablation training out root already exists: {out_root}"
        )
    loaded = load_fold_training_frames(
        fold_id,
        arm,
        transform_root,
        train_months=train_months,
        validation_months=validation_months,
    )
    if align_with is not None:
        if arm == ARM_SEX1:
            assert_fold_arm_alignment(align_with, loaded)
        elif arm == ARM_BASELINE:
            assert_fold_arm_alignment(loaded, align_with)
        else:
            raise ValueError(f"unknown ablation arm: {arm}")
    contract = load_baseline9_training_contract()
    params = json.loads(json.dumps(contract["lightgbm_params"]))
    training = contract["training"]
    train = loaded["train"]
    validation = loaded["validation"]
    features = loaded["feature_names"]
    cats = loaded["categorical_names"]
    dtrain = lgb.Dataset(
        train[features],
        label=train[TARGET],
        categorical_feature=cats,
        free_raw_data=False,
    )
    dvalid = lgb.Dataset(
        validation[features],
        label=validation[TARGET],
        reference=dtrain,
        categorical_feature=cats,
        free_raw_data=False,
    )
    model = lgb.train(
        params,
        dtrain,
        num_boost_round=training["num_boost_round"],
        valid_sets=[dvalid],
        valid_names=["validation"],
        callbacks=[
            lgb.early_stopping(
                training["early_stopping_rounds"],
                first_metric_only=True,
                verbose=False,
            ),
        ],
    )
    best_iteration = int(model.best_iteration)
    if best_iteration <= 0:
        raise ValueError("invalid best_iteration")
    prediction = assert_valid_predictions(
        validation,
        model.predict(validation[features], num_iteration=best_iteration),
    )
    out_root.mkdir(parents=True, exist_ok=False)
    model_path = out_root / "model.txt"
    model.save_model(str(model_path), num_iteration=best_iteration)
    pred_path = out_root / "validation-predictions.csv.gz"
    pd.DataFrame(
        {
            "race_id": validation.race_id.astype(str),
            "entry_id": validation.entry_id.astype(str),
            "source_ym": validation.source_ym.astype(str),
            TARGET: validation[TARGET].astype("int8"),
            "prediction": prediction,
        }
    ).to_csv(
        pred_path,
        index=False,
        compression={"method": "gzip", "mtime": 0},
        lineterminator="\n",
    )
    metrics = {
        "fold_id": fold_id,
        "arm": arm,
        "train_months": list(loaded["train_months"]),
        "validation_months": list(loaded["validation_months"]),
        "feature_count": len(features),
        "categorical_count": len(cats),
        "categorical_feature_indices": list(
            EXPECTED_CATEGORICAL_INDICES[arm]
        ),
        "best_iteration": best_iteration,
        "source_rows": {
            "train": loaded["train_stats"]["source"],
            "validation": loaded["validation_stats"]["source"],
        },
        "masked_rows": {
            "train": loaded["train_stats"]["masked"],
            "validation": loaded["validation_stats"]["masked"],
        },
        "supervised_rows": {
            "train": loaded["train_stats"]["supervised"],
            "validation": loaded["validation_stats"]["supervised"],
        },
        "positive": {
            "train": loaded["train_stats"]["positive"],
            "validation": loaded["validation_stats"]["positive"],
        },
        "negative": {
            "train": loaded["train_stats"]["negative"],
            "validation": loaded["validation_stats"]["negative"],
        },
        "runtime": {
            "python": platform.python_version(),
            "lightgbm": lgb.__version__,
            "numpy": np.__version__,
            "pandas": pd.__version__,
        },
        "model_file": model_path.name,
        "predictions_file": pred_path.name,
        "sha256": {
            "baseline9_config": contract["config_sha256"],
            "lightgbm_params": hashlib.sha256(
                json.dumps(
                    params,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
            ).hexdigest(),
            "feature_order": loaded["feature_order_sha256"],
            "category_dictionaries": loaded["dictionary_sha256"],
            "model": TRANSFORM.sha256_file(model_path),
            "validation_predictions": TRANSFORM.sha256_file(pred_path),
        },
        "early_stopping": {
            "metric": training["early_stopping_metric"],
            "rounds": training["early_stopping_rounds"],
            "first_metric_only": True,
            "valid_names": ["validation"],
        },
        "prediction_min": float(prediction.min()),
        "prediction_max": float(prediction.max()),
    }
    if _json_contains_absolute_path(metrics):
        raise ValueError("absolute path leaked into fold metrics")
    TRANSFORM.write_json_atomic(out_root / "fold-metrics.json", metrics)
    return {
        "fold_id": fold_id,
        "arm": arm,
        "out_root": out_root,
        "best_iteration": best_iteration,
        "loaded": loaded,
        "prediction": prediction,
        "metrics": metrics,
        "model_path": model_path,
        "predictions_path": pred_path,
    }


if __name__ == "__main__":
    raise SystemExit(
        "fold-local transform/training helper; "
        "run python3 -m unittest tools.test_ablate_nar_v3_development"
    )
