#!/usr/bin/env python3

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import re
from collections import Counter
from datetime import date
from decimal import Decimal
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

WEIGHT_RE = re.compile(
    r"^(?P<mark>[^0-9.+-]*)(?P<value>[0-9]+(?:\.[0-9]+)?)$"
)

TIME_RE = re.compile(
    r"^(?P<min>\d+):(?P<sec>\d{2})\.(?P<tenth>\d)$"
)

GOOD_TIME_RE = re.compile(
    r"^良(?P<min>\d+):(?P<sec>\d{2})\.(?P<tenth>\d)$"
)


def sha256_file(path):
    h = hashlib.sha256()

    with path.open("rb") as f:
        for chunk in iter(
            lambda: f.read(1024 * 1024),
            b"",
        ):
            h.update(chunk)

    return h.hexdigest()


def valid_ym(value):
    if len(value) != 6 or not value.isdigit():
        raise ValueError(f"invalid YYYYMM: {value}")

    year = int(value[:4])
    month = int(value[4:])

    if not 1 <= month <= 12:
        raise ValueError(f"invalid YYYYMM: {value}")

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


def parse_date8(value):
    if len(value) != 8 or not value.isdigit():
        raise ValueError(
            f"invalid YYYYMMDD: {value!r}"
        )

    return date(
        int(value[:4]),
        int(value[4:6]),
        int(value[6:8]),
    )


def source_prefix(source):
    return (
        source
        .replace("feature_entry__", "")
        .replace("feature_race__", "")
    )


def parse_weight(raw, cfg):
    m = WEIGHT_RE.fullmatch(raw)

    if not m:
        raise ValueError(
            f"invalid burden weight: {raw!r}"
        )

    mark = m.group("mark")

    if not mark:
        mark = cfg["mark_none_value"]

    value = Decimal(m.group("value"))
    scaled = value * Decimal(
        cfg["numeric_scale"]
    )

    if scaled != scaled.to_integral_value():
        raise ValueError(
            f"weight cannot be represented exactly: {raw!r}"
        )

    return int(scaled), mark


def parse_record4(raw):
    if raw == "":
        return (0, 0, 0, 0, 1)

    parts = raw.split("-")

    if (
        len(parts) != 4
        or any(not x.isdigit() for x in parts)
    ):
        raise ValueError(
            f"invalid record4: {raw!r}"
        )

    first, second, third, other = map(
        int,
        parts,
    )

    return (
        first,
        second,
        third,
        other,
        0,
    )


def parse_time_deciseconds(raw, regex):
    if raw == "":
        return 0, 1

    m = regex.fullmatch(raw)

    if not m:
        raise ValueError(
            f"invalid best time: {raw!r}"
        )

    minute = int(m.group("min"))
    second = int(m.group("sec"))
    tenth = int(m.group("tenth"))

    if second >= 60:
        raise ValueError(
            f"invalid seconds: {raw!r}"
        )

    return (
        (minute * 60 + second) * 10 + tenth,
        0,
    )


def derive_surface(row, cfg):
    value = row[cfg["source"]].strip()
    venue = row[cfg["venue_source"]].strip()

    if value:
        return value

    if venue == "帯広ば":
        return cfg["rules"]["帯広ば_and_blank"]

    return cfg["rules"]["unexpected_blank"]


def build_feature_names(cfg):
    numeric = list(
        cfg["numeric_passthrough"]
    )

    numeric.extend(
        cfg["race_date"]["outputs"]
    )

    numeric.extend(
        cfg["birth_date"]["outputs"]
    )

    numeric.append(
        cfg["burden_weight"][
            "numeric_output"
        ]
    )

    for source in cfg["record4"]["sources"]:
        prefix = source_prefix(source)

        for suffix in cfg[
            "record4"
        ]["outputs_per_source"]:
            numeric.append(
                f"{prefix}_{suffix}"
            )

        if cfg["record4"]["blank_policy"][
            "add_missing_flag"
        ]:
            numeric.append(
                f"{prefix}_missing"
            )

    numeric.extend([
        cfg["best_time"]["output"],
        cfg["best_time"]["missing_output"],
        cfg["best_time_good"]["output"],
        cfg["best_time_good"]["missing_output"],
    ])

    categorical = list(
        cfg["categorical_passthrough"]
    )

    categorical.extend([
        cfg["surface"]["output"],
        cfg["burden_weight"][
            "mark_output"
        ],
    ])

    if len(numeric) != len(set(numeric)):
        raise ValueError(
            "duplicate numeric feature name"
        )

    if len(categorical) != len(set(categorical)):
        raise ValueError(
            "duplicate categorical feature name"
        )

    if set(numeric) & set(categorical):
        raise ValueError(
            "numeric/categorical feature overlap"
        )

    return numeric, categorical


def categorical_values(row, cfg):
    result = {
        col: row[col].strip()
        for col in cfg[
            "categorical_passthrough"
        ]
    }

    result[
        cfg["surface"]["output"]
    ] = derive_surface(
        row,
        cfg["surface"],
    )

    _, mark = parse_weight(
        row[
            cfg["burden_weight"]["source"]
        ].strip(),
        cfg["burden_weight"],
    )

    result[
        cfg["burden_weight"]["mark_output"]
    ] = mark

    return result


def load_manifest(path):
    result = {}

    with path.open(
        encoding="utf-8",
        newline="",
    ) as f:
        for row in csv.DictReader(f):
            ym = row["ym"]

            if ym in result:
                raise ValueError(
                    f"duplicate manifest month: {ym}"
                )

            result[ym] = row

    return result


def input_path(dataset_root, manifest_row):
    return dataset_root / manifest_row["file"]


def fit_category_dictionaries(
    dataset_root,
    manifest,
    cfg,
    categorical_names,
):
    fit_split = cfg[
        "categorical_dictionary"
    ]["fit_split"]

    values = {
        name: set()
        for name in categorical_names
    }

    fit_months = [
        ym
        for ym, row in sorted(
            manifest.items()
        )
        if row["split"] == fit_split
    ]

    if not fit_months:
        raise ValueError(
            f"no months for fit split: {fit_split}"
        )

    row_count = 0

    for index, ym in enumerate(
        fit_months,
        1,
    ):
        p = input_path(
            dataset_root,
            manifest[ym],
        )

        with gzip.open(
            p,
            "rt",
            encoding="utf-8",
            newline="",
        ) as f:
            reader = csv.DictReader(f)

            for row in reader:
                if row["split"] != fit_split:
                    raise ValueError(
                        f"{ym}: unexpected split "
                        f"{row['split']!r}"
                    )

                row_count += 1

                transformed = categorical_values(
                    row,
                    cfg,
                )

                for name, value in (
                    transformed.items()
                ):
                    if (
                        value == ""
                        or value == "__MISSING__"
                    ):
                        continue

                    values[name].add(value)

        if (
            index % 12 == 0
            or index == len(fit_months)
        ):
            print(
                "category fit",
                index,
                "/",
                len(fit_months),
                "through",
                ym,
            )

    dict_cfg = cfg[
        "categorical_dictionary"
    ]

    start_id = dict_cfg[
        "known_id_start"
    ]

    features = {}

    for name in categorical_names:
        ordered = sorted(values[name])

        mapping = {
            value: start_id + index
            for index, value in enumerate(
                ordered
            )
        }

        features[name] = {
            "known_count": len(mapping),
            "value_to_id": mapping,
        }

    result = {
        "version": cfg["version"],
        "fit_split": fit_split,
        "fit_rows": row_count,
        "missing_id": dict_cfg[
            "missing_id"
        ],
        "unknown_id": dict_cfg[
            "unknown_id"
        ],
        "known_id_start": start_id,
        "known_value_order": dict_cfg[
            "known_value_order"
        ],
        "features": features,
    }

    return result


def encode_category(
    value,
    feature_name,
    dictionaries,
):
    if (
        value == ""
        or value == "__MISSING__"
    ):
        return (
            dictionaries["missing_id"],
            "missing",
        )

    mapping = dictionaries[
        "features"
    ][feature_name]["value_to_id"]

    if value in mapping:
        return mapping[value], "known"

    return dictionaries["unknown_id"], "unknown"


def numeric_values(row, cfg):
    result = {}

    for name in cfg[
        "numeric_passthrough"
    ]:
        raw = row[name].strip()

        if raw == "":
            raise ValueError(
                f"blank numeric feature: {name}"
            )

        result[name] = int(raw)

    race_date = parse_date8(
        row[
            cfg["race_date"]["source"]
        ].strip()
    )

    result["race_month"] = (
        race_date.month
    )

    result["race_day_of_year"] = (
        race_date.timetuple().tm_yday
    )

    result["race_weekday_mon0"] = (
        race_date.weekday()
    )

    birth_date = parse_date8(
        row[
            cfg["birth_date"]["source"]
        ].strip()
    )

    age_days = (
        race_date - birth_date
    ).days

    if age_days < 0:
        raise ValueError(
            "birth date is after race date"
        )

    result["age_days"] = age_days

    weight, _ = parse_weight(
        row[
            cfg["burden_weight"]["source"]
        ].strip(),
        cfg["burden_weight"],
    )

    result[
        cfg["burden_weight"][
            "numeric_output"
        ]
    ] = weight

    for source in cfg["record4"]["sources"]:
        (
            first,
            second,
            third,
            other,
            missing,
        ) = parse_record4(
            row[source].strip()
        )

        prefix = source_prefix(source)

        result[f"{prefix}_first"] = first
        result[f"{prefix}_second"] = second
        result[f"{prefix}_third"] = third
        result[f"{prefix}_other"] = other
        result[
            f"{prefix}_total"
        ] = (
            first
            + second
            + third
            + other
        )

        result[
            f"{prefix}_missing"
        ] = missing

    value, missing = parse_time_deciseconds(
        row[
            cfg["best_time"]["source"]
        ].strip(),
        TIME_RE,
    )

    result[
        cfg["best_time"]["output"]
    ] = value

    result[
        cfg["best_time"][
            "missing_output"
        ]
    ] = missing

    (
        value,
        missing,
    ) = parse_time_deciseconds(
        row[
            cfg["best_time_good"]["source"]
        ].strip(),
        GOOD_TIME_RE,
    )

    result[
        cfg["best_time_good"]["output"]
    ] = value

    result[
        cfg["best_time_good"][
            "missing_output"
        ]
    ] = missing

    return result


def write_json_atomic(path, obj):
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    part = Path(str(path) + ".part")

    text = json.dumps(
        obj,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    ) + "\n"

    part.write_text(
        text,
        encoding="utf-8",
    )

    os.replace(part, path)


def open_gzip_writer(path, fields):
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

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


def close_gzip_writer(
    part,
    final,
    raw,
    gz,
    text,
):
    text.flush()
    text.close()

    if not gz.closed:
        gz.close()

    if not raw.closed:
        raw.close()

    os.replace(part, final)


def transform_month(
    ym,
    source_path,
    out_root,
    cfg,
    dictionaries,
    numeric_names,
    categorical_names,
):
    out_path = (
        out_root
        / "monthly"
        / ym[:4]
        / f"{ym}_model.csv.gz"
    )

    fields = [
        *META_FIELDS,
        *numeric_names,
        *categorical_names,
        *LABEL_FIELDS,
    ]

    (
        part,
        raw,
        gz,
        text,
        writer,
    ) = open_gzip_writer(
        out_path,
        fields,
    )

    counts = Counter()

    try:
        with gzip.open(
            source_path,
            "rt",
            encoding="utf-8",
            newline="",
        ) as f:
            reader = csv.DictReader(f)

            for row in reader:
                numeric = numeric_values(
                    row,
                    cfg,
                )

                categorical_raw = (
                    categorical_values(
                        row,
                        cfg,
                    )
                )

                out = {
                    name: row[name]
                    for name in META_FIELDS
                }

                for name in numeric_names:
                    out[name] = str(
                        numeric[name]
                    )

                for name in categorical_names:
                    encoded, state = (
                        encode_category(
                            categorical_raw[
                                name
                            ],
                            name,
                            dictionaries,
                        )
                    )

                    out[name] = str(encoded)

                    counts[
                        f"category_{state}"
                    ] += 1

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

        part.unlink(missing_ok=True)
        raise

    return out_path, counts


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--start",
        required=True,
        help="first YYYYMM",
    )

    parser.add_argument(
        "--end",
        required=True,
        help="last YYYYMM",
    )

    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path(
            "/workspaces/nar-v1-dataset"
        ),
    )

    parser.add_argument(
        "--out",
        type=Path,
        default=Path(
            "/workspaces/nar-v1-transformed"
        ),
    )

    parser.add_argument(
        "--config",
        type=Path,
        default=Path(
            "config/nar-v1-transform.json"
        ),
    )

    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(
            "data-manifests/nar-v1-dataset/"
            "months.csv"
        ),
    )

    args = parser.parse_args()

    try:
        months = list(
            month_range(
                args.start,
                args.end,
            )
        )
    except ValueError as exc:
        raise SystemExit(
            str(exc)
        ) from exc

    cfg = json.loads(
        args.config.read_text(
            encoding="utf-8"
        )
    )

    manifest = load_manifest(
        args.manifest
    )

    for ym in months:
        if ym not in manifest:
            raise SystemExit(
                f"month not in manifest: {ym}"
            )

    (
        numeric_names,
        categorical_names,
    ) = build_feature_names(cfg)

    print(
        "numeric features =",
        len(numeric_names),
    )

    print(
        "categorical features =",
        len(categorical_names),
    )

    print(
        "total model features =",
        len(numeric_names)
        + len(categorical_names),
    )

    if len(numeric_names) != 54:
        raise SystemExit(
            "unexpected numeric feature count"
        )

    if len(categorical_names) != 14:
        raise SystemExit(
            "unexpected categorical feature count"
        )

    dictionaries = fit_category_dictionaries(
        args.dataset_root,
        manifest,
        cfg,
        categorical_names,
    )

    artifact_dir = (
        args.out / "artifacts"
    )

    dict_path = (
        artifact_dir
        / "category-dictionaries.json"
    )

    feature_path = (
        artifact_dir
        / "feature-order.json"
    )

    write_json_atomic(
        dict_path,
        dictionaries,
    )

    all_features = [
        *numeric_names,
        *categorical_names,
    ]

    feature_order = {
        "version": cfg["version"],
        "feature_count": len(
            all_features
        ),
        "numeric_feature_count": len(
            numeric_names
        ),
        "categorical_feature_count": len(
            categorical_names
        ),
        "features": [
            {
                "index": index,
                "name": name,
                "type": (
                    "numeric"
                    if name in numeric_names
                    else "categorical"
                ),
            }
            for index, name in enumerate(
                all_features
            )
        ],
        "categorical_feature_indices": [
            index
            for index, name in enumerate(
                all_features
            )
            if name in categorical_names
        ],
        "categorical_feature_names":
            categorical_names,
    }

    write_json_atomic(
        feature_path,
        feature_order,
    )

    print()
    print("=== ARTIFACTS ===")
    print(
        "category dictionaries =",
        dict_path,
    )
    print(
        "sha256 =",
        sha256_file(dict_path),
    )

    print(
        "feature order =",
        feature_path,
    )
    print(
        "sha256 =",
        sha256_file(feature_path),
    )

    totals = Counter()

    for ym in months:
        print()
        print(f"=== {ym} ===")

        source = input_path(
            args.dataset_root,
            manifest[ym],
        )

        out_path, counts = (
            transform_month(
                ym,
                source,
                args.out,
                cfg,
                dictionaries,
                numeric_names,
                categorical_names,
            )
        )

        print("output =", out_path)

        for key in sorted(counts):
            print(
                key,
                "=",
                counts[key],
            )
            totals[key] += counts[key]

        print(
            "bytes =",
            out_path.stat().st_size,
        )

        print(
            "sha256 =",
            sha256_file(out_path),
        )

    print()
    print("=== TOTAL ===")
    print("months =", len(months))

    for key in sorted(totals):
        print(
            key,
            "=",
            totals[key],
        )

    print()
    print(
        "NAR V1 TRANSFORM OK"
    )


if __name__ == "__main__":
    main()
