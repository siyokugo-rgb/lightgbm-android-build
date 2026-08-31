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

EXPECTED_CATEGORICAL = [
    "feature_race__競馬場",
    "feature_entry__毛色",
    "feature_entry__父馬名",
    "feature_entry__母馬名",
    "feature_entry__母父馬名",
]

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


def validate_config(cfg):
    if cfg.get("version") != 1:
        raise ValueError("nar-v3 transform version must be 1")
    if cfg.get("input_dataset") != "nar-v3-pit-safe":
        raise ValueError("unexpected input dataset")

    checkpoint = cfg.get("input_checkpoint")
    if not isinstance(checkpoint, dict):
        raise ValueError("input checkpoint missing")
    if checkpoint.get("start_ym") != "202101":
        raise ValueError("unexpected checkpoint start")
    if checkpoint.get("end_ym") != "202607":
        raise ValueError("unexpected checkpoint end")
    if checkpoint.get("months") != 67:
        raise ValueError("unexpected checkpoint month count")
    root_hash = checkpoint.get("dataset_root_sha256")
    if (
        not isinstance(root_hash, str)
        or len(root_hash) != 64
        or any(c not in "0123456789abcdef" for c in root_hash)
    ):
        raise ValueError("invalid expected dataset root SHA-256")

    dictionary = cfg.get("categorical_dictionary")
    if dictionary != {
        "fit_split": "train",
        "normalization": "none",
        "missing_id": 0,
        "unknown_id": 1,
        "known_id_start": 2,
        "known_value_order": "unicode_lexicographic",
    }:
        raise ValueError("unexpected categorical dictionary contract")

    inputs = cfg.get("input_features")
    if not isinstance(inputs, dict):
        raise ValueError("input_features missing")
    race = ensure_string_list(inputs.get("race"), "input_features.race")
    entry = ensure_string_list(inputs.get("entry"), "input_features.entry")
    if race != EXPECTED_RACE_RAW_FEATURES:
        raise ValueError("unexpected V3 race raw feature schema")
    if entry != EXPECTED_ENTRY_RAW_FEATURES:
        raise ValueError("unexpected V3 entry raw feature schema")

    categorical = ensure_string_list(
        cfg.get("categorical_passthrough"),
        "categorical_passthrough",
    )
    if categorical != EXPECTED_CATEGORICAL:
        raise ValueError("unexpected V3 categorical schema")

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

    return {
        "categorical": categorical,
        "numeric": list(EXPECTED_NUMERIC_OUTPUTS),
        "expected_root_hash": root_hash,
    }


def checkpoint_months(checkpoint):
    if checkpoint.get("format_version") != 1:
        raise ValueError("checkpoint format version mismatch")
    if checkpoint.get("dataset") != "nar-v3-pit-safe":
        raise ValueError("checkpoint dataset mismatch")

    period = checkpoint.get("period")
    if not isinstance(period, dict):
        raise ValueError("checkpoint period missing")
    start = period.get("start_ym")
    end = period.get("end_ym")
    months = list(month_range(start, end))
    if period.get("months") != len(months):
        raise ValueError("checkpoint month count mismatch")
    return months


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


def expected_raw_header():
    return [
        *META_FIELDS,
        *EXPECTED_RACE_RAW_FEATURES,
        *EXPECTED_ENTRY_RAW_FEATURES,
        *LABEL_FIELDS,
    ]


def verify_sidecar(dataset_root, ym):
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
    if sc.get("dataset") != "nar-v3-pit-safe":
        raise ValueError(f"{ym}: sidecar dataset mismatch")
    if sc.get("source_ym") != ym:
        raise ValueError(f"{ym}: sidecar source_ym mismatch")
    if sc.get("output_file") != data_path.name:
        raise ValueError(f"{ym}: sidecar output file mismatch")
    if sc.get("feature_columns") != (
        EXPECTED_RACE_RAW_FEATURES
        + EXPECTED_ENTRY_RAW_FEATURES
    ):
        raise ValueError(f"{ym}: sidecar feature schema mismatch")
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


def validate_checkpoint(dataset_root, checkpoint_path, expected_root_hash):
    checkpoint = load_json(checkpoint_path)
    months = checkpoint_months(checkpoint)

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
        verify_sidecar(dataset_root, ym)

    return checkpoint, months


def categorical_values(row):
    result = {}
    for name in EXPECTED_CATEGORICAL:
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


def validate_reader_header(reader, ym):
    if reader.fieldnames != expected_raw_header():
        raise ValueError(f"{ym}: raw CSV header mismatch")


def fit_category_dictionaries(dataset_root, all_months, cfg):
    fit_split = cfg["categorical_dictionary"]["fit_split"]
    values = {
        name: set()
        for name in EXPECTED_CATEGORICAL
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
            validate_reader_header(reader, ym)
            for row in reader:
                if row["source_ym"] != ym:
                    raise ValueError(f"{ym}: row source_ym mismatch")
                if row["split"] != fit_split:
                    raise ValueError(f"{ym}: non-train row in dictionary fit")
                fit_rows += 1
                transformed = categorical_values(row)
                for name, value in transformed.items():
                    if value != "":
                        values[name].add(value)

    if not fit_months or fit_rows == 0:
        raise ValueError("no train rows available for category fit")

    dictionary = cfg["categorical_dictionary"]
    start = dictionary["known_id_start"]
    features = {}

    for name in EXPECTED_CATEGORICAL:
        ordered = sorted(values[name])
        features[name] = {
            "known_count": len(ordered),
            "value_to_id": {
                value: start + index
                for index, value in enumerate(ordered)
            },
        }

    return {
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


def build_feature_order(cfg):
    features = [
        *EXPECTED_NUMERIC_OUTPUTS,
        *EXPECTED_CATEGORICAL,
    ]
    return {
        "version": cfg["version"],
        "feature_count": len(features),
        "numeric_feature_count": len(EXPECTED_NUMERIC_OUTPUTS),
        "categorical_feature_count": len(EXPECTED_CATEGORICAL),
        "features": [
            {
                "index": index,
                "name": name,
                "type": (
                    "numeric"
                    if name in EXPECTED_NUMERIC_OUTPUTS
                    else "categorical"
                ),
            }
            for index, name in enumerate(features)
        ],
        "categorical_feature_indices": [
            index
            for index, name in enumerate(features)
            if name in EXPECTED_CATEGORICAL
        ],
        "categorical_feature_names": list(EXPECTED_CATEGORICAL),
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
):
    source = source_path(dataset_root, ym)
    source_sidecar = sidecar_path(dataset_root, ym)
    source_sc = verify_sidecar(dataset_root, ym)

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

    fields = [
        *META_FIELDS,
        *EXPECTED_NUMERIC_OUTPUTS,
        *EXPECTED_CATEGORICAL,
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
            validate_reader_header(reader, ym)

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

                numeric = numeric_values(row)
                categorical_raw = categorical_values(row)

                out = {
                    name: row[name]
                    for name in META_FIELDS
                }

                for name in EXPECTED_NUMERIC_OUTPUTS:
                    out[name] = str(numeric[name])

                for name in EXPECTED_CATEGORICAL:
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
        "dataset": "nar-v3-model-input",
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
        "output_sha256": sha256_file(out_path),
        "output_bytes": out_path.stat().st_size,
        "feature_columns": [
            *EXPECTED_NUMERIC_OUTPUTS,
            *EXPECTED_CATEGORICAL,
        ],
        "label_columns": list(LABEL_FIELDS),
        "counts": dict(sorted(counts.items())),
    }
    write_json_atomic(out_sidecar, sidecar)

    return out_path, out_sidecar, counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path("/workspaces/nar-v3-dataset"),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("/workspaces/nar-v3-transformed"),
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("config/nar-v3-transform.json"),
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=Path(
            "data-manifests/nar-v3-dataset/checkpoint.json"
        ),
    )
    args = parser.parse_args()

    months = list(month_range(args.start, args.end))
    cfg = load_json(args.config)
    validated = validate_config(cfg)

    checkpoint, all_months = validate_checkpoint(
        args.dataset_root,
        args.checkpoint,
        validated["expected_root_hash"],
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
    )

    artifact_dir = args.out / "artifacts"
    dictionary_path = artifact_dir / "category-dictionaries.json"
    feature_order_path = artifact_dir / "feature-order.json"

    write_json_atomic(
        dictionary_path,
        dictionaries,
    )
    feature_order = build_feature_order(cfg)
    write_json_atomic(
        feature_order_path,
        feature_order,
    )

    print("numeric features =", len(EXPECTED_NUMERIC_OUTPUTS))
    print("categorical features =", len(EXPECTED_CATEGORICAL))
    print(
        "total model features =",
        len(EXPECTED_NUMERIC_OUTPUTS) + len(EXPECTED_CATEGORICAL),
    )
    print("category fit split =", dictionaries["fit_split"])
    print("category fit months =", len(dictionaries["fit_months"]))
    print("category fit rows =", dictionaries["fit_rows"])
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


if __name__ == "__main__":
    main()
