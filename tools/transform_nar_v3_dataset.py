#!/usr/bin/env python3

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
from collections import Counter
from datetime import date
from pathlib import Path

META_FIELDS = [
    "race_id",
    "entry_id",
    "split",
    "source_ym",
    "meta_source_anomalies",
]

LABEL_FIELDS = [
    "label_result_status",
    "label_numeric_finish_position",
    "label_order_valid",
    "label_started",
    "label_finished",
    "label_win",
    "label_top2",
    "label_top3",
]

EXPECTED_RACE_RAW_FEATURES = [
    "feature_race__競馬場",
    "feature_race__競走年月日",
]

EXPECTED_ENTRY_RAW_FEATURES = [
    "feature_entry__毛色",
    "feature_entry__生年月日",
    "feature_entry__父馬名",
    "feature_entry__母馬名",
    "feature_entry__母父馬名",
]

EXPECTED_SEX1_ENTRY_RAW_FEATURES = [
    "feature_entry__毛色",
    "feature_entry__生年月日",
    "feature_entry__父馬名",
    "feature_entry__母馬名",
    "feature_entry__母父馬名",
    "feature_entry__性",
]

EXPECTED_CATEGORICAL = [
    "feature_race__競馬場",
    "feature_entry__毛色",
    "feature_entry__父馬名",
    "feature_entry__母馬名",
    "feature_entry__母父馬名",
]

EXPECTED_SEX1_CATEGORICAL = [
    "feature_race__競馬場",
    "feature_entry__毛色",
    "feature_entry__父馬名",
    "feature_entry__母馬名",
    "feature_entry__母父馬名",
    "feature_entry__性",
]

CANDIDATE_PROFILE_SEX1 = "nar-v3-sex1"
CANDIDATE_DEV_START_YM = "202101"
CANDIDATE_DEV_END_YM = "202412"
CANDIDATE_DEV_MONTHS = 48
SEX1_INPUT_DATASET = "nar-v3-sex1-pit-safe"
SEX1_OUTPUT_DATASET = "nar-v3-sex1-model-input"
SEX1_DATASET_ROOT_SHA256 = (
    "e224c3cd74c42576c23941e61e4a8cac499d5622421bff6ed1ce4e71a9d4a6b9"
)

FORBIDDEN_RAW_INPUT_FEATURES = (
    "feature_entry__齢",
    "feature_entry__着順",
    "feature_entry__タイム",
    "feature_entry__着差",
    "feature_race__着順",
    "齢",
    "着順",
    "タイム",
    "着差",
)

EXPECTED_NUMERIC_OUTPUTS = [
    "race_month",
    "race_day_of_year",
    "race_weekday_mon0",
    "age_days",
]

MAX_FIELD_CHARS = 1024 * 1024


def sha256_file(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def valid_ym(value):
    if len(value) != 6 or not value.isdigit():
        raise ValueError(f"invalid YYYYMM: {value!r}")
    year = int(value[:4])
    month = int(value[4:])
    if not 1 <= month <= 12:
        raise ValueError(f"invalid YYYYMM: {value!r}")
    return year, month


def month_range(start, end):
    year, month = valid_ym(start)
    end_year, end_month = valid_ym(end)
    if (year, month) > (end_year, end_month):
        raise ValueError("start is after end")
    while (year, month) <= (end_year, end_month):
        yield f"{year:04d}{month:02d}"
        month += 1
        if month == 13:
            year += 1
            month = 1


def split_for_year(year):
    if 2021 <= year <= 2023:
        return "train"
    if year == 2024:
        return "validation"
    if year == 2025:
        return "test"
    if year == 2026:
        return "out_of_time"
    return "other"


def strict_text(value, label):
    if not isinstance(value, str):
        raise ValueError(f"{label} is not text")
    if "\x00" in value:
        raise ValueError(f"NUL in {label}")
    if len(value) > MAX_FIELD_CHARS:
        raise ValueError(f"{label} too long")
    return value


def parse_date8(value, label):
    strict_text(value, label)
    if len(value) != 8 or not value.isdigit():
        raise ValueError(f"invalid {label}: {value!r}")
    try:
        return date(
            int(value[:4]),
            int(value[4:6]),
            int(value[6:8]),
        )
    except ValueError as exc:
        raise ValueError(f"invalid {label}: {value!r}") from exc


def ensure_string_list(value, label):
    if (
        not isinstance(value, list)
        or any(not isinstance(x, str) for x in value)
        or len(value) != len(set(value))
    ):
        raise ValueError(f"invalid string list: {label}")
    return list(value)


def load_json(path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def production_schema():
    return {
        "candidate_profile": None,
        "input_dataset": "nar-v3-pit-safe",
        "output_dataset": "nar-v3-model-input",
        "race_raw": list(EXPECTED_RACE_RAW_FEATURES),
        "entry_raw": list(EXPECTED_ENTRY_RAW_FEATURES),
        "categorical": list(EXPECTED_CATEGORICAL),
        "numeric": list(EXPECTED_NUMERIC_OUTPUTS),
        "checkpoint_start_ym": "202101",
        "checkpoint_end_ym": "202607",
        "checkpoint_months": 67,
    }


def sex1_schema():
    return {
        "candidate_profile": CANDIDATE_PROFILE_SEX1,
        "input_dataset": SEX1_INPUT_DATASET,
        "output_dataset": SEX1_OUTPUT_DATASET,
        "race_raw": list(EXPECTED_RACE_RAW_FEATURES),
        "entry_raw": list(EXPECTED_SEX1_ENTRY_RAW_FEATURES),
        "categorical": list(EXPECTED_SEX1_CATEGORICAL),
        "numeric": list(EXPECTED_NUMERIC_OUTPUTS),
        "checkpoint_start_ym": CANDIDATE_DEV_START_YM,
        "checkpoint_end_ym": CANDIDATE_DEV_END_YM,
        "checkpoint_months": CANDIDATE_DEV_MONTHS,
    }


def schema_for_profile(candidate_profile=None):
    if candidate_profile is None:
        return production_schema()
    if candidate_profile == CANDIDATE_PROFILE_SEX1:
        return sex1_schema()
    raise ValueError(
        "unknown candidate profile: "
        f"{candidate_profile}"
    )


def resolve_schema(schema=None):
    if schema is None:
        return production_schema()
    return schema


def assert_candidate_development_period(months):
    if not months:
        raise ValueError("candidate period is empty")
    for ym in months:
        valid_ym(ym)
        if (
            ym < CANDIDATE_DEV_START_YM
            or ym > CANDIDATE_DEV_END_YM
        ):
            raise ValueError(
                "candidate period outside "
                "DEVELOPMENT window "
                f"{CANDIDATE_DEV_START_YM}-"
                f"{CANDIDATE_DEV_END_YM}: {ym}"
            )


def forbidden_candidate_out_roots():
    repo_root = Path(__file__).resolve().parent.parent
    return [
        repo_root / "data-manifests" / "nar-v3-dataset",
        repo_root / "data-manifests" / "nar-v3-transformed",
        repo_root / "data-manifests" / "nar-v3-baseline9",
        Path("/workspaces/nar-v3-dataset"),
        Path("/workspaces/nar-v3-transformed"),
        Path("/workspaces/nar-v3-baseline9"),
    ]


def assert_candidate_out_root_allowed(out_root):
    if out_root is None:
        raise ValueError(
            "candidate mode requires explicit --out"
        )

    resolved = out_root.resolve()
    for forbidden in forbidden_candidate_out_roots():
        forbidden_resolved = forbidden.resolve()
        if (
            resolved == forbidden_resolved
            or forbidden_resolved in resolved.parents
            or resolved in forbidden_resolved.parents
        ):
            raise ValueError(
                "candidate output root collides "
                "with production/baseline root: "
                f"{resolved}"
            )


def validate_dictionary_contract(dictionary):
    if dictionary != {
        "fit_split": "train",
        "normalization": "none",
        "missing_id": 0,
        "unknown_id": 1,
        "known_id_start": 2,
        "known_value_order": "unicode_lexicographic",
    }:
        raise ValueError("unexpected categorical dictionary contract")


def validate_root_hash_hex(root_hash):
    if (
        not isinstance(root_hash, str)
        or len(root_hash) != 64
        or any(c not in "0123456789abcdef" for c in root_hash)
    ):
        raise ValueError("invalid expected dataset root SHA-256")
    return root_hash


def validate_shared_transform_contract(cfg):
    if cfg.get("race_date") != {
        "source": "feature_race__競走年月日",
        "format": "YYYYMMDD",
        "outputs": [
            "race_month",
            "race_day_of_year",
            "race_weekday_mon0",
        ],
    }:
        raise ValueError("unexpected race date transform")

    if cfg.get("birth_date") != {
        "source": "feature_entry__生年月日",
        "format": "YYYYMMDD",
        "race_date_source": "feature_race__競走年月日",
        "outputs": ["age_days"],
    }:
        raise ValueError("unexpected birth date transform")

    if cfg.get("drop_raw_after_transform") != [
        "feature_race__競走年月日",
        "feature_entry__生年月日",
    ]:
        raise ValueError("unexpected raw drop contract")

    if cfg.get("android_parity") != {
        "category_unknown_required": True,
        "category_dictionary_must_be_exported": True,
        "raw_string_unicode_normalization": "none",
        "date_calculation": "proleptic_gregorian",
    }:
        raise ValueError("unexpected Android parity contract")


def validate_config(cfg, candidate_profile=None):
    schema = schema_for_profile(candidate_profile)

    if cfg.get("version") != 1:
        raise ValueError("nar-v3 transform version must be 1")

    if candidate_profile is None:
        if cfg.get("candidate_profile") is not None:
            raise ValueError(
                "production transform config must not set candidate_profile"
            )
        if cfg.get("input_dataset") != "nar-v3-pit-safe":
            raise ValueError("unexpected input dataset")
    else:
        if cfg.get("candidate_profile") != candidate_profile:
            raise ValueError("candidate_profile mismatch")
        if cfg.get("input_dataset") != schema["input_dataset"]:
            raise ValueError("unexpected input dataset")

    checkpoint = cfg.get("input_checkpoint")
    if not isinstance(checkpoint, dict):
        raise ValueError("input checkpoint missing")
    if checkpoint.get("start_ym") != schema["checkpoint_start_ym"]:
        raise ValueError("unexpected checkpoint start")
    if checkpoint.get("end_ym") != schema["checkpoint_end_ym"]:
        raise ValueError("unexpected checkpoint end")
    if checkpoint.get("months") != schema["checkpoint_months"]:
        raise ValueError("unexpected checkpoint month count")
    root_hash = validate_root_hash_hex(
        checkpoint.get("dataset_root_sha256")
    )

    validate_dictionary_contract(cfg.get("categorical_dictionary"))

    inputs = cfg.get("input_features")
    if not isinstance(inputs, dict):
        raise ValueError("input_features missing")
    race = ensure_string_list(inputs.get("race"), "input_features.race")
    entry = ensure_string_list(inputs.get("entry"), "input_features.entry")
    if race != schema["race_raw"]:
        if candidate_profile is None:
            raise ValueError("unexpected V3 race raw feature schema")
        raise ValueError("unexpected candidate race raw feature schema")
    if entry != schema["entry_raw"]:
        if candidate_profile is None:
            raise ValueError("unexpected V3 entry raw feature schema")
        raise ValueError("unexpected candidate entry raw feature schema")

    categorical = ensure_string_list(
        cfg.get("categorical_passthrough"),
        "categorical_passthrough",
    )
    if categorical != schema["categorical"]:
        if candidate_profile is None:
            raise ValueError("unexpected V3 categorical schema")
        raise ValueError("unexpected candidate categorical schema")

    validate_shared_transform_contract(cfg)

    schema["expected_root_hash"] = root_hash
    schema["categorical"] = categorical
    schema["numeric"] = list(EXPECTED_NUMERIC_OUTPUTS)
    return schema


def checkpoint_months(checkpoint, schema=None):
    schema = resolve_schema(schema)
    if checkpoint.get("format_version") != 1:
        raise ValueError("checkpoint format version mismatch")
    if checkpoint.get("dataset") != schema["input_dataset"]:
        raise ValueError("checkpoint dataset mismatch")
    if schema["candidate_profile"] is not None:
        if checkpoint.get("candidate_profile") != schema["candidate_profile"]:
            raise ValueError("checkpoint candidate_profile mismatch")

    period = checkpoint.get("period")
    if not isinstance(period, dict):
        raise ValueError("checkpoint period missing")
    start = period.get("start_ym")
    end = period.get("end_ym")
    months = list(month_range(start, end))
    if period.get("months") != len(months):
        raise ValueError("checkpoint month count mismatch")
    return months


def validate_candidate_checkpoint_identity(checkpoint, schema):
    if checkpoint.get("dataset") != schema["input_dataset"]:
        raise ValueError("checkpoint dataset mismatch")
    if checkpoint.get("candidate_profile") != schema["candidate_profile"]:
        raise ValueError("checkpoint candidate_profile mismatch")

    period = checkpoint.get("period")
    if not isinstance(period, dict):
        raise ValueError("checkpoint period missing")
    if period.get("start_ym") != schema["checkpoint_start_ym"]:
        raise ValueError("checkpoint period start mismatch")
    if period.get("end_ym") != schema["checkpoint_end_ym"]:
        raise ValueError("checkpoint period end mismatch")
    if period.get("months") != schema["checkpoint_months"]:
        raise ValueError("checkpoint month count mismatch")

    audit = checkpoint.get("audit")
    if not isinstance(audit, dict):
        raise ValueError("checkpoint audit missing")
    if audit.get("full_dataset_audit") != "PASS":
        raise ValueError("checkpoint audit is not PASS")
    if audit.get("locked_oot_open") is not False:
        raise ValueError("checkpoint locked/OOT open is not false")
    source_ym_max = audit.get("source_ym_max")
    if (
        not isinstance(source_ym_max, str)
        or source_ym_max > CANDIDATE_DEV_END_YM
    ):
        raise ValueError(
            "checkpoint source_ym_max exceeds DEVELOPMENT window"
        )


def sidecar_path(dataset_root, ym):
    return (
        dataset_root
        / "monthly"
        / ym[:4]
        / f"{ym}_entries.manifest.json"
    )


def source_path(dataset_root, ym):
    return (
        dataset_root
        / "monthly"
        / ym[:4]
        / f"{ym}_entries.csv.gz"
    )


def expected_raw_header(schema=None):
    schema = resolve_schema(schema)
    return [
        *META_FIELDS,
        *schema["race_raw"],
        *schema["entry_raw"],
        *LABEL_FIELDS,
    ]


def assert_sidecar_feature_contract(ym, feature_columns, schema):
    expected = schema["race_raw"] + schema["entry_raw"]
    if feature_columns != expected:
        raise ValueError(f"{ym}: sidecar feature schema mismatch")
    if schema["candidate_profile"] == CANDIDATE_PROFILE_SEX1:
        if "feature_entry__性" not in feature_columns:
            raise ValueError(f"{ym}: sex feature missing")
        if "feature_entry__齢" in feature_columns:
            raise ValueError(f"{ym}: deferred 齢 must not appear")
    leaked = [
        name
        for name in feature_columns
        if name in FORBIDDEN_RAW_INPUT_FEATURES or name in LABEL_FIELDS
    ]
    if leaked:
        raise ValueError(
            f"{ym}: result/deferred feature leaked into input features"
        )


def verify_sidecar(dataset_root, ym, schema=None):
    schema = resolve_schema(schema)
    sc_path = sidecar_path(dataset_root, ym)
    data_path = source_path(dataset_root, ym)

    if not sc_path.is_file():
        raise FileNotFoundError(sc_path)
    if not data_path.is_file():
        raise FileNotFoundError(data_path)

    text = sc_path.read_text(encoding="utf-8")
    if "C:\\" in text or "C:/" in text:
        raise ValueError(f"{ym}: absolute path leaked into sidecar")

    sc = json.loads(text)
    if sc.get("format_version") != 1:
        raise ValueError(f"{ym}: sidecar format mismatch")
    if sc.get("dataset") != schema["input_dataset"]:
        raise ValueError(f"{ym}: sidecar dataset mismatch")
    if schema["candidate_profile"] is not None:
        if sc.get("candidate_profile") != schema["candidate_profile"]:
            raise ValueError(f"{ym}: sidecar candidate_profile mismatch")
    if sc.get("source_ym") != ym:
        raise ValueError(f"{ym}: sidecar source_ym mismatch")
    if sc.get("output_file") != data_path.name:
        raise ValueError(f"{ym}: sidecar output file mismatch")
    assert_sidecar_feature_contract(
        ym,
        sc.get("feature_columns"),
        schema,
    )
    if sc.get("label_columns") != LABEL_FIELDS:
        raise ValueError(f"{ym}: sidecar label schema mismatch")
    if sc.get("output_bytes") != data_path.stat().st_size:
        raise ValueError(f"{ym}: source byte count mismatch")
    if sc.get("output_sha256") != sha256_file(data_path):
        raise ValueError(f"{ym}: source SHA-256 mismatch")
    return sc


def compute_dataset_root_hash(dataset_root, months):
    lines = []
    for ym in months:
        p = sidecar_path(dataset_root, ym)
        if not p.is_file():
            raise FileNotFoundError(p)
        rel = p.relative_to(dataset_root).as_posix()
        lines.append(f"{rel}\t{sha256_file(p)}")
    canonical = ("\n".join(sorted(lines)) + "\n").encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def validate_checkpoint(
    dataset_root,
    checkpoint_path,
    expected_root_hash,
    schema=None,
):
    schema = resolve_schema(schema)
    checkpoint = load_json(checkpoint_path)
    if schema["candidate_profile"] is not None:
        validate_candidate_checkpoint_identity(checkpoint, schema)
    months = checkpoint_months(checkpoint, schema)

    dataset_hash = checkpoint.get("dataset_root_hash")
    if not isinstance(dataset_hash, dict):
        raise ValueError("checkpoint dataset_root_hash missing")
    if dataset_hash.get("algorithm") != "sha256":
        raise ValueError("checkpoint root hash algorithm mismatch")
    if dataset_hash.get("value") != expected_root_hash:
        raise ValueError("checkpoint/config root hash mismatch")

    actual_root_hash = compute_dataset_root_hash(
        dataset_root,
        months,
    )
    if actual_root_hash != expected_root_hash:
        raise ValueError(
            "dataset sidecar root hash does not match audited checkpoint"
        )

    for ym in months:
        verify_sidecar(dataset_root, ym, schema)

    return checkpoint, months


def categorical_values(row, schema=None):
    schema = resolve_schema(schema)
    result = {}
    for name in schema["categorical"]:
        result[name] = strict_text(
            row[name],
            name,
        )
    return result


def numeric_values(row):
    race_date = parse_date8(
        row["feature_race__競走年月日"],
        "race date",
    )
    birth_date = parse_date8(
        row["feature_entry__生年月日"],
        "birth date",
    )
    age_days = (race_date - birth_date).days
    if age_days < 0:
        raise ValueError("birth date is after race date")

    return {
        "race_month": race_date.month,
        "race_day_of_year": race_date.timetuple().tm_yday,
        "race_weekday_mon0": race_date.weekday(),
        "age_days": age_days,
    }


def validate_reader_header(reader, ym, schema=None):
    if reader.fieldnames != expected_raw_header(schema):
        raise ValueError(f"{ym}: raw CSV header mismatch")


def fit_category_dictionaries(dataset_root, all_months, cfg, schema=None):
    schema = resolve_schema(schema)
    fit_split = cfg["categorical_dictionary"]["fit_split"]
    values = {
        name: set()
        for name in schema["categorical"]
    }
    fit_rows = 0
    fit_months = []

    for ym in all_months:
        if split_for_year(int(ym[:4])) != fit_split:
            continue
        fit_months.append(ym)
        p = source_path(dataset_root, ym)
        with gzip.open(
            p,
            "rt",
            encoding="utf-8",
            errors="strict",
            newline="",
        ) as f:
            reader = csv.DictReader(f)
            validate_reader_header(reader, ym, schema)
            for row in reader:
                if row["source_ym"] != ym:
                    raise ValueError(f"{ym}: row source_ym mismatch")
                if row["split"] != fit_split:
                    raise ValueError(f"{ym}: non-train row in dictionary fit")
                fit_rows += 1
                transformed = categorical_values(row, schema)
                for name, value in transformed.items():
                    if value != "":
                        values[name].add(value)

    if not fit_months or fit_rows == 0:
        raise ValueError("no train rows available for category fit")

    dictionary = cfg["categorical_dictionary"]
    start = dictionary["known_id_start"]
    features = {}

    for name in schema["categorical"]:
        ordered = sorted(values[name])
        features[name] = {
            "known_count": len(ordered),
            "value_to_id": {
                value: start + index
                for index, value in enumerate(ordered)
            },
        }

    result = {
        "version": cfg["version"],
        "fit_split": fit_split,
        "fit_months": fit_months,
        "fit_rows": fit_rows,
        "missing_id": dictionary["missing_id"],
        "unknown_id": dictionary["unknown_id"],
        "known_id_start": start,
        "known_value_order": dictionary["known_value_order"],
        "normalization": dictionary["normalization"],
        "features": features,
    }
    if schema["candidate_profile"] is not None:
        result["candidate_profile"] = schema["candidate_profile"]
        result["input_dataset"] = schema["input_dataset"]
    return result


def encode_category(value, name, dictionaries):
    if value == "":
        return dictionaries["missing_id"], "missing"
    mapping = dictionaries["features"][name]["value_to_id"]
    if value in mapping:
        return mapping[value], "known"
    return dictionaries["unknown_id"], "unknown"


def write_json_atomic(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    part = Path(str(path) + ".part")
    part.unlink(missing_ok=True)
    text = (
        json.dumps(
            obj,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )
    with part.open("w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(part, path)


def open_gzip_writer(path, fields):
    path.parent.mkdir(parents=True, exist_ok=True)
    part = Path(str(path) + ".part")
    part.unlink(missing_ok=True)
    raw = part.open("wb")
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
    writer = csv.DictWriter(
        text,
        fieldnames=fields,
        lineterminator="\n",
    )
    writer.writeheader()
    return part, raw, gz, text, writer


def close_gzip_writer(part, final, raw, gz, text):
    text.flush()
    text.close()
    if not gz.closed:
        gz.close()
    if not raw.closed:
        raw.close()
    os.replace(part, final)


def build_feature_order(cfg, schema=None):
    schema = resolve_schema(schema)
    if cfg.get("categorical_passthrough") != schema["categorical"]:
        raise ValueError("feature-order categorical schema mismatch")
    numeric = list(schema["numeric"])
    categorical = list(schema["categorical"])
    features = [
        *numeric,
        *categorical,
    ]
    return {
        "version": cfg["version"],
        "feature_count": len(features),
        "numeric_feature_count": len(numeric),
        "categorical_feature_count": len(categorical),
        "features": [
            {
                "index": index,
                "name": name,
                "type": (
                    "numeric"
                    if name in numeric
                    else "categorical"
                ),
            }
            for index, name in enumerate(features)
        ],
        "categorical_feature_indices": [
            index
            for index, name in enumerate(features)
            if name in categorical
        ],
        "categorical_feature_names": list(categorical),
    }


def transform_month(
    ym,
    dataset_root,
    out_root,
    cfg_path,
    dictionaries,
    dictionary_path,
    feature_order,
    feature_order_path,
    schema=None,
):
    schema = resolve_schema(schema)
    if schema["candidate_profile"] is not None:
        assert_candidate_development_period([ym])

    source = source_path(dataset_root, ym)
    source_sidecar = sidecar_path(dataset_root, ym)
    source_sc = verify_sidecar(dataset_root, ym, schema)

    out_path = (
        out_root
        / "monthly"
        / ym[:4]
        / f"{ym}_model.csv.gz"
    )
    out_sidecar = (
        out_root
        / "monthly"
        / ym[:4]
        / f"{ym}_model.manifest.json"
    )

    numeric = list(schema["numeric"])
    categorical = list(schema["categorical"])
    fields = [
        *META_FIELDS,
        *numeric,
        *categorical,
        *LABEL_FIELDS,
    ]

    counts = Counter()
    seen_entries = set()

    part, raw, gz, text, writer = open_gzip_writer(
        out_path,
        fields,
    )

    try:
        with gzip.open(
            source,
            "rt",
            encoding="utf-8",
            errors="strict",
            newline="",
        ) as f:
            reader = csv.DictReader(f)
            validate_reader_header(reader, ym, schema)

            for row in reader:
                if row["source_ym"] != ym:
                    raise ValueError(f"{ym}: source_ym mismatch")
                expected_split = split_for_year(int(ym[:4]))
                if row["split"] != expected_split:
                    raise ValueError(f"{ym}: split mismatch")

                entry_id = row["entry_id"]
                if entry_id in seen_entries:
                    raise ValueError(f"{ym}: duplicate entry_id")
                seen_entries.add(entry_id)

                numeric_row = numeric_values(row)
                categorical_raw = categorical_values(row, schema)

                out = {
                    name: row[name]
                    for name in META_FIELDS
                }

                for name in numeric:
                    out[name] = str(numeric_row[name])

                for name in categorical:
                    encoded, state = encode_category(
                        categorical_raw[name],
                        name,
                        dictionaries,
                    )
                    out[name] = str(encoded)
                    counts[f"category_{state}"] += 1

                for name in LABEL_FIELDS:
                    out[name] = row[name]

                writer.writerow(out)
                counts["rows"] += 1

        close_gzip_writer(
            part,
            out_path,
            raw,
            gz,
            text,
        )
    except Exception:
        try:
            text.close()
        except Exception:
            pass
        try:
            if not gz.closed:
                gz.close()
        except Exception:
            pass
        try:
            if not raw.closed:
                raw.close()
        except Exception:
            pass
        part.unlink(missing_ok=True)
        raise

    if counts["rows"] != source_sc["counts"]["output_entries"]:
        raise ValueError(f"{ym}: transformed row count mismatch")

    sidecar = {
        "format_version": 1,
        "dataset": schema["output_dataset"],
        "source_ym": ym,
        "source_file": source.name,
        "source_sha256": sha256_file(source),
        "source_sidecar_file": source_sidecar.name,
        "source_sidecar_sha256": sha256_file(source_sidecar),
        "transform_config_file": cfg_path.name,
        "transform_config_sha256": sha256_file(cfg_path),
        "category_dictionary_file": dictionary_path.name,
        "category_dictionary_sha256": sha256_file(dictionary_path),
        "feature_order_file": feature_order_path.name,
        "feature_order_sha256": sha256_file(feature_order_path),
        "output_file": out_path.name,
        "output_bytes": out_path.stat().st_size,
        "output_sha256": sha256_file(out_path),
        "feature_columns": [
            *numeric,
            *categorical,
        ],
        "label_columns": list(LABEL_FIELDS),
        "counts": dict(sorted(counts.items())),
    }
    if schema["candidate_profile"] is not None:
        sidecar["candidate_profile"] = schema["candidate_profile"]
    write_json_atomic(out_sidecar, sidecar)

    return out_path, out_sidecar, counts


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--candidate-profile",
        default=None,
        help=(
            "explicit candidate schema opt-in; "
            "default keeps production schema"
        ),
    )
    return parser.parse_args(argv)


def apply_runtime_defaults(args):
    if args.candidate_profile is not None:
        schema_for_profile(args.candidate_profile)
        if args.candidate_profile == CANDIDATE_PROFILE_SEX1:
            if args.config is None:
                args.config = Path(
                    "config/candidates/nar-v3-sex1/transform.json"
                )
            if args.checkpoint is None:
                args.checkpoint = Path(
                    "data-manifests/candidates/nar-v3-sex1/"
                    "dataset/checkpoint.json"
                )
        if args.dataset_root is None:
            raise ValueError(
                "candidate mode requires explicit --dataset-root"
            )
        if args.out is None:
            raise ValueError(
                "candidate mode requires explicit --out"
            )
        return args

    if args.config is None:
        args.config = Path("config/nar-v3-transform.json")
    if args.checkpoint is None:
        args.checkpoint = Path(
            "data-manifests/nar-v3-dataset/checkpoint.json"
        )
    if args.dataset_root is None:
        args.dataset_root = Path("/workspaces/nar-v3-dataset")
    if args.out is None:
        args.out = Path("/workspaces/nar-v3-transformed")
    return args


def run_transform(args):
    months = list(month_range(args.start, args.end))

    if args.candidate_profile is not None:
        # Fail closed before any candidate dataset source is opened.
        schema_for_profile(args.candidate_profile)
        assert_candidate_development_period(months)
        if args.out is None:
            raise ValueError(
                "candidate mode requires explicit --out"
            )
        assert_candidate_out_root_allowed(args.out)
        if args.dataset_root is None:
            raise ValueError(
                "candidate mode requires explicit --dataset-root"
            )

    args = apply_runtime_defaults(args)
    cfg = load_json(args.config)
    schema = validate_config(cfg, args.candidate_profile)

    checkpoint, all_months = validate_checkpoint(
        args.dataset_root,
        args.checkpoint,
        schema["expected_root_hash"],
        schema,
    )

    checkpoint_period = checkpoint["period"]
    if args.start < checkpoint_period["start_ym"]:
        raise SystemExit("transform start is before checkpoint period")
    if args.end > checkpoint_period["end_ym"]:
        raise SystemExit("transform end is after checkpoint period")

    dictionaries = fit_category_dictionaries(
        args.dataset_root,
        all_months,
        cfg,
        schema,
    )

    artifact_dir = args.out / "artifacts"
    dictionary_path = artifact_dir / "category-dictionaries.json"
    feature_order_path = artifact_dir / "feature-order.json"

    write_json_atomic(
        dictionary_path,
        dictionaries,
    )
    feature_order = build_feature_order(cfg, schema)
    write_json_atomic(
        feature_order_path,
        feature_order,
    )

    print("numeric features =", len(schema["numeric"]))
    print("categorical features =", len(schema["categorical"]))
    print(
        "total model features =",
        len(schema["numeric"]) + len(schema["categorical"]),
    )
    print("category indices =", feature_order["categorical_feature_indices"])
    print("category fit split =", dictionaries["fit_split"])
    print("category fit months =", len(dictionaries["fit_months"]))
    print("category fit rows =", dictionaries["fit_rows"])
    if args.candidate_profile is not None:
        print("candidate profile =", args.candidate_profile)
    print()
    print("=== ARTIFACTS ===")
    print("category dictionaries =", dictionary_path)
    print("sha256 =", sha256_file(dictionary_path))
    print("feature order =", feature_order_path)
    print("sha256 =", sha256_file(feature_order_path))

    totals = Counter()

    for index, ym in enumerate(months, 1):
        out_path, out_sidecar, counts = transform_month(
            ym=ym,
            dataset_root=args.dataset_root,
            out_root=args.out,
            cfg_path=args.config,
            dictionaries=dictionaries,
            dictionary_path=dictionary_path,
            feature_order=feature_order,
            feature_order_path=feature_order_path,
            schema=schema,
        )

        print()
        print(f"=== {ym} ===")
        print("output =", out_path)
        print("manifest =", out_sidecar)
        print("bytes =", out_path.stat().st_size)
        print("sha256 =", sha256_file(out_path))
        for key in sorted(counts):
            print(key, "=", counts[key])
            totals[key] += counts[key]

        if index % 12 == 0 or index == len(months):
            print(
                "transformed progress =",
                index,
                "/",
                len(months),
            )

    print()
    print("=== TOTAL ===")
    print("months =", len(months))
    for key in sorted(totals):
        print(key, "=", totals[key])
    print()
    print("NAR V3 TRANSFORM OK")
    return dictionaries, feature_order, totals


def main(argv=None):
    args = parse_args(argv)
    run_transform(args)


if __name__ == "__main__":
    main()
