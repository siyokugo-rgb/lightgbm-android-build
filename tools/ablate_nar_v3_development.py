#!/usr/bin/env python3
"""Fold-local transform helper for NAR V3 DEVELOPMENT ablation.

This module only fits category dictionaries on each rolling-origin fold's
training months and transforms that fold's train+validation months.

It does not train models, compute OOF/bootstrap, or invoke the production transform runner.
"""

import importlib.util
from pathlib import Path


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


if __name__ == "__main__":
    raise SystemExit(
        "fold-local transform helper only; "
        "run python3 -m unittest tools.test_ablate_nar_v3_development"
    )
