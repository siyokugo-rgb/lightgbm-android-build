#!/usr/bin/env python3

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import zipfile
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

EXPECTED_ZIP_KINDS = (
    "racelist",
    "horselist",
    "payback",
)

EXPECTED_PRODUCTION_RACE_FEATURES = (
    "競馬場",
    "競走年月日",
)

EXPECTED_PRODUCTION_HORSE_FEATURES = (
    "毛色",
    "生年月日",
    "父馬名",
    "母馬名",
    "母父馬名",
)

EXPECTED_LABEL_HORSE_SOURCES = (
    "着順",
    "タイム",
    "着差",
)

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_MEMBER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_FIELD_CHARS = 1024 * 1024

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


def sha256_file(path):
    digest = hashlib.sha256()

    with path.open("rb") as f:
        for chunk in iter(
            lambda: f.read(1024 * 1024),
            b"",
        ):
            digest.update(chunk)

    return digest.hexdigest()


def valid_ym(value):
    if (
        len(value) != 6
        or not value.isdigit()
    ):
        raise ValueError(
            f"invalid YYYYMM: {value!r}"
        )

    year = int(value[:4])
    month = int(value[4:])

    if not 1 <= month <= 12:
        raise ValueError(
            f"invalid YYYYMM: {value!r}"
        )

    return year, month


def month_range(start, end):
    year, month = valid_ym(start)
    end_year, end_month = valid_ym(end)

    if (year, month) > (end_year, end_month):
        raise ValueError(
            "start is after end"
        )

    while (year, month) <= (end_year, end_month):
        yield f"{year:04d}{month:02d}"

        month += 1

        if month == 13:
            year += 1
            month = 1


def split_for_year(year):
    # Preserve the existing V1/V2 time split exactly.
    # Split policy is a model-evaluation decision and is not changed here.
    if 2021 <= year <= 2023:
        return "train"

    if year == 2024:
        return "validation"

    if year == 2025:
        return "test"

    if year == 2026:
        return "out_of_time"

    return "other"


def race_key(row):
    track = strict_key_text(
        row["競馬場"],
        "競馬場",
    )
    date_text = strict_date8(
        row["競走年月日"],
        "競走年月日",
    )
    race_number = strict_positive_int_text(
        row["レース番号"],
        "レース番号",
    )

    return (
        track,
        date_text,
        race_number,
    )


def race_id(key):
    return "|".join(key)


def entry_id(key, horse_number):
    return f"{race_id(key)}|{horse_number}"


def strict_key_text(value, label):
    if "\x00" in value:
        raise ValueError(
            f"NUL in {label}"
        )

    if value == "" or value != value.strip():
        raise ValueError(
            f"invalid {label}: {value!r}"
        )

    return value


def strict_date8(value, label):
    strict_key_text(value, label)

    if (
        len(value) != 8
        or not value.isdigit()
    ):
        raise ValueError(
            f"invalid {label}: {value!r}"
        )

    try:
        date(
            int(value[:4]),
            int(value[4:6]),
            int(value[6:8]),
        )
    except ValueError as exc:
        raise ValueError(
            f"invalid {label}: {value!r}"
        ) from exc

    return value


def strict_positive_int_text(value, label):
    strict_key_text(value, label)

    if (
        not value.isdigit()
        or int(value) <= 0
    ):
        raise ValueError(
            f"invalid {label}: {value!r}"
        )

    return str(int(value))


def ensure_unique_strings(values, label):
    if (
        not isinstance(values, list)
        or any(
            not isinstance(item, str)
            for item in values
        )
    ):
        raise ValueError(
            f"{label} must be a string list"
        )

    if len(values) != len(set(values)):
        raise ValueError(
            f"duplicate value in {label}"
        )

    return list(values)


def load_json(path):
    with path.open(
        encoding="utf-8",
    ) as f:
        return json.load(f)


def validate_feature_contract(cfg):
    if cfg.get("version") != 4:
        raise ValueError(
            "nar-v3 feature contract version must be 4"
        )

    if (
        cfg.get("prediction_point_policy")
        != "runtime_prediction_as_of"
    ):
        raise ValueError(
            "unexpected prediction point policy"
        )

    if (
        cfg.get("snapshot_selection_rule")
        != (
            "pit_evidence_at_epoch_millis "
            "<= prediction_as_of_epoch_millis"
        )
    ):
        raise ValueError(
            "unexpected snapshot selection rule"
        )

    if (
        cfg.get("production_model_feature_rule")
        != (
            "feature_must_be_allowed_in_"
            "training_and_serving_contexts"
        )
    ):
        raise ValueError(
            "unexpected production feature rule"
        )

    contexts = ensure_unique_strings(
        cfg.get("data_contexts"),
        "data_contexts",
    )

    if set(contexts) != {
        "HISTORICAL_MONTHLY",
        "LIVE_PIT_SNAPSHOT",
    }:
        raise ValueError(
            "unexpected data contexts"
        )

    keys = cfg.get("keys")

    if not isinstance(keys, dict):
        raise ValueError(
            "feature contract keys missing"
        )

    race_keys = ensure_unique_strings(
        keys.get("race"),
        "keys.race",
    )
    entry_keys = ensure_unique_strings(
        keys.get("entry"),
        "keys.entry",
    )

    if race_keys != [
        "競馬場",
        "競走年月日",
        "レース番号",
    ]:
        raise ValueError(
            "unexpected race key contract"
        )

    if entry_keys != [
        "競馬場",
        "競走年月日",
        "レース番号",
        "馬番",
    ]:
        raise ValueError(
            "unexpected entry key contract"
        )

    historical = cfg.get(
        "historical_monthly_allowed_features"
    )

    if not isinstance(historical, dict):
        raise ValueError(
            "historical allowed feature section missing"
        )

    allowed_race = ensure_unique_strings(
        historical.get("racelist"),
        "historical racelist features",
    )
    allowed_horse = ensure_unique_strings(
        historical.get("horselist"),
        "historical horselist features",
    )

    if (
        allowed_race
        != list(
            EXPECTED_PRODUCTION_RACE_FEATURES
        )
    ):
        raise ValueError(
            "unexpected production racelist feature schema"
        )

    if (
        allowed_horse
        != list(
            EXPECTED_PRODUCTION_HORSE_FEATURES
        )
    ):
        raise ValueError(
            "unexpected production horselist feature schema"
        )

    semantic = cfg.get(
        "historical_monthly_semantic_pending_live_allowed"
    )
    live_only = cfg.get(
        "live_pit_snapshot_only_features"
    )
    dynamic = cfg.get(
        "deferred_dynamic_sources"
    )
    audit = cfg.get(
        "deferred_pending_audit"
    )
    never = cfg.get(
        "never_prediction_features"
    )

    for name, section in [
        ("semantic pending", semantic),
        ("live only", live_only),
        ("deferred dynamic", dynamic),
        ("deferred audit", audit),
        ("never prediction", never),
    ]:
        if not isinstance(section, dict):
            raise ValueError(
                f"{name} section missing"
            )

    denied_race = set()
    denied_horse = set()

    for section_name, section in [
        ("semantic pending", semantic),
        ("live only", live_only),
        ("deferred dynamic", dynamic),
        ("deferred audit", audit),
        ("never prediction", never),
    ]:
        race_values = section.get(
            "racelist",
            [],
        )
        horse_values = section.get(
            "horselist",
            [],
        )

        if race_values != ["*"]:
            denied_race.update(
                ensure_unique_strings(
                    race_values,
                    f"{section_name}.racelist",
                )
            )

        if horse_values != ["*"]:
            denied_horse.update(
                ensure_unique_strings(
                    horse_values,
                    f"{section_name}.horselist",
                )
            )

    overlap_race = (
        set(allowed_race)
        & denied_race
    )
    overlap_horse = (
        set(allowed_horse)
        & denied_horse
    )

    if overlap_race:
        raise ValueError(
            "historical racelist feature also denied: "
            + ",".join(sorted(overlap_race))
        )

    if overlap_horse:
        raise ValueError(
            "historical horselist feature also denied: "
            + ",".join(sorted(overlap_horse))
        )

    payback_never = never.get(
        "payback"
    )

    if payback_never != ["*"]:
        raise ValueError(
            "payback must be forbidden wholesale"
        )

    label_sources = cfg.get(
        "label_sources"
    )

    if not isinstance(label_sources, dict):
        raise ValueError(
            "label_sources missing"
        )

    label_horse = set(
        ensure_unique_strings(
            label_sources.get(
                "horselist"
            ),
            "label_sources.horselist",
        )
    )

    if (
        label_horse
        != set(
            EXPECTED_LABEL_HORSE_SOURCES
        )
    ):
        raise ValueError(
            "unexpected label source contract"
        )

    if label_horse & set(
        allowed_horse
    ):
        raise ValueError(
            "label source overlaps prediction feature"
        )

    never_horse = set(
        ensure_unique_strings(
            never.get(
                "horselist"
            ),
            "never prediction horselist",
        )
    )

    if not label_horse.issubset(
        never_horse
    ):
        raise ValueError(
            "label source is not forbidden as prediction feature"
        )

    aliases = cfg.get(
        "source_anomaly_aliases"
    )

    if not isinstance(aliases, dict):
        raise ValueError(
            "source anomaly aliases missing"
        )

    exclusions = ensure_unique_strings(
        cfg.get(
            "v1_training_exclusions"
        ),
        "training exclusions",
    )

    return {
        "allowed_race": allowed_race,
        "allowed_horse": allowed_horse,
        "aliases": dict(aliases),
        "exclusions": set(exclusions),
    }


def validate_label_contract(cfg):
    if cfg.get("version") != 1:
        raise ValueError(
            "NAR label contract version must be 1"
        )

    if cfg.get(
        "numeric_finish"
    ) != {
        "source_column": "着順",
        "valid_when": "decimal_integer",
    }:
        raise ValueError(
            "unexpected numeric finish contract"
        )

    if cfg.get(
        "entry_status_source"
    ) != {
        "column": "着差",
    }:
        raise ValueError(
            "unexpected entry status source contract"
        )

    prestart = ensure_unique_strings(
        cfg.get(
            "prestart_no_order_label"
        ),
        "prestart labels",
    )

    started_special = ensure_unique_strings(
        cfg.get(
            "started_special_no_order_label"
        ),
        "started special labels",
    )

    void_statuses = ensure_unique_strings(
        cfg.get(
            "race_void_statuses"
        ),
        "race void statuses",
    )

    status_groups = [
        set(prestart),
        set(started_special),
        set(void_statuses),
    ]
    seen_statuses = set()

    for group in status_groups:
        if seen_statuses & group:
            raise ValueError(
                "label status groups overlap"
            )

        seen_statuses.update(
            group
        )

    if cfg.get(
        "race_exclusion_rules"
    ) != {
        "exclude_if_any_void_status": True,
        "exclude_if_numeric_finish_count_is_zero": True,
    }:
        raise ValueError(
            "unexpected race exclusion contract"
        )

    expected_derived_labels = {
        "numeric_finish_position": {
            "numeric_finish": "source_numeric_value",
            "prestart_no_order_label": None,
            "started_special_no_order_label": None,
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
    }

    if cfg.get(
        "derived_labels"
    ) != expected_derived_labels:
        raise ValueError(
            "unexpected derived label contract"
        )

    if cfg.get(
        "dead_heat_policy"
    ) != {
        "preserve_official_equal_finish_positions": True,
    }:
        raise ValueError(
            "unexpected dead heat contract"
        )

    pit_policy = cfg.get(
        "pit_policy"
    )

    if (
        not isinstance(pit_policy, dict)
        or pit_policy.get(
            "final_prestart_status_is_not_a_prediction_feature"
        )
        is not True
        or pit_policy.get(
            "prestart_status_may_only_mask_result_labels"
        )
        is not True
    ):
        raise ValueError(
            "label PIT policy is not fail-closed"
        )

    return {
        "prestart": set(prestart),
        "started_special": set(
            started_special
        ),
        "void_statuses": set(
            void_statuses
        ),
    }


def load_anomalies(path, aliases):
    result = defaultdict(set)

    with path.open(
        encoding="utf-8",
        newline="",
    ) as f:
        reader = csv.DictReader(f)

        required = {
            "anomaly_type",
            "競馬場",
            "競走年月日",
            "レース番号",
        }

        if (
            reader.fieldnames is None
            or not required.issubset(
                set(reader.fieldnames)
            )
        ):
            raise ValueError(
                "anomaly manifest header mismatch"
            )

        if (
            len(reader.fieldnames)
            != len(set(reader.fieldnames))
        ):
            raise ValueError(
                "duplicate anomaly manifest header"
            )

        for row in reader:
            key = race_key(row)

            anomaly = strict_key_text(
                row["anomaly_type"],
                "anomaly_type",
            )
            anomaly = aliases.get(
                anomaly,
                anomaly,
            )

            result[key].add(
                anomaly
            )

    return result


def load_history_manifest(path):
    result = {}

    with path.open(
        encoding="utf-8",
        newline="",
    ) as f:
        reader = csv.DictReader(f)

        required = {
            "ym",
            "status",
            "bytes",
            "sha256",
            "racelist_rows",
            "horselist_rows",
            "payback_rows",
        }

        if (
            reader.fieldnames is None
            or not required.issubset(
                set(reader.fieldnames)
            )
        ):
            raise ValueError(
                "history manifest header mismatch"
            )

        if (
            len(reader.fieldnames)
            != len(set(reader.fieldnames))
        ):
            raise ValueError(
                "duplicate history manifest header"
            )

        for row in reader:
            ym = row["ym"]

            valid_ym(ym)

            if ym in result:
                raise ValueError(
                    f"duplicate history manifest month: {ym}"
                )

            result[ym] = row

    return result


def validate_source_archive(
    zip_path,
    ym,
    manifest_row,
):
    if not zip_path.is_file():
        raise FileNotFoundError(
            zip_path
        )

    archive_size = (
        zip_path.stat().st_size
    )

    if (
        archive_size <= 0
        or archive_size > MAX_ARCHIVE_BYTES
    ):
        raise ValueError(
            "source archive size outside limit"
        )

    if manifest_row is None:
        raise ValueError(
            f"{ym}: history manifest entry missing"
        )

    if manifest_row["status"] != "OK":
        raise ValueError(
            f"{ym}: source history manifest is not OK"
        )

    expected_size = int(
        manifest_row["bytes"]
    )

    if archive_size != expected_size:
        raise ValueError(
            f"{ym}: source archive byte count mismatch"
        )

    digest = sha256_file(
        zip_path
    )

    if digest != manifest_row["sha256"]:
        raise ValueError(
            f"{ym}: source archive SHA-256 mismatch"
        )

    expected_names = {
        f"{ym}_{kind}.csv"
        for kind in EXPECTED_ZIP_KINDS
    }

    with zipfile.ZipFile(
        zip_path,
        "r",
    ) as zf:
        infos = [
            info
            for info in zf.infolist()
            if not info.is_dir()
        ]

        names = [
            info.filename
            for info in infos
        ]

        if len(names) != len(set(names)):
            raise ValueError(
                f"{ym}: duplicate ZIP member name"
            )

        if set(names) != expected_names:
            raise ValueError(
                f"{ym}: unexpected ZIP members"
            )

        total_uncompressed = 0

        for info in infos:
            if (
                Path(info.filename).name
                != info.filename
            ):
                raise ValueError(
                    f"{ym}: nested ZIP member rejected"
                )

            if info.flag_bits & 0x1:
                raise ValueError(
                    f"{ym}: encrypted ZIP member rejected"
                )

            if info.compress_type not in {
                zipfile.ZIP_STORED,
                zipfile.ZIP_DEFLATED,
            }:
                raise ValueError(
                    f"{ym}: unsupported ZIP compression"
                )

            if (
                info.file_size < 0
                or info.file_size > MAX_MEMBER_BYTES
            ):
                raise ValueError(
                    f"{ym}: ZIP member too large"
                )

            total_uncompressed += (
                info.file_size
            )

            if (
                total_uncompressed
                > MAX_TOTAL_UNCOMPRESSED_BYTES
            ):
                raise ValueError(
                    f"{ym}: ZIP uncompressed total too large"
                )

        bad = zf.testzip()

        if bad is not None:
            raise ValueError(
                f"{ym}: ZIP CRC failure: {bad}"
            )

    return digest


def read_csv_strict(
    zf,
    member,
    required_columns,
):
    info = zf.getinfo(
        member
    )

    if info.file_size > MAX_MEMBER_BYTES:
        raise ValueError(
            f"{member}: member too large"
        )

    with zf.open(
        info,
        "r",
    ) as raw:
        with io.TextIOWrapper(
            raw,
            encoding="utf-8-sig",
            errors="strict",
            newline="",
        ) as text:
            reader = csv.reader(
                text
            )

            try:
                header = next(
                    reader
                )
            except StopIteration as exc:
                raise ValueError(
                    f"{member}: CSV empty"
                ) from exc

            if not header:
                raise ValueError(
                    f"{member}: header empty"
                )

            if any(
                name == ""
                or "\x00" in name
                for name in header
            ):
                raise ValueError(
                    f"{member}: invalid header"
                )

            if (
                len(header)
                != len(set(header))
            ):
                raise ValueError(
                    f"{member}: duplicate header"
                )

            missing = (
                set(required_columns)
                - set(header)
            )

            if missing:
                raise ValueError(
                    f"{member}: missing columns: "
                    + ",".join(
                        sorted(missing)
                    )
                )

            rows = []
            row_count = 0

            for row_number, values in enumerate(
                reader,
                2,
            ):
                row_count += 1

                if len(values) != len(header):
                    raise ValueError(
                        f"{member}: field count mismatch "
                        f"at row {row_number}"
                    )

                if any(
                    "\x00" in value
                    or len(value) > MAX_FIELD_CHARS
                    for value in values
                ):
                    raise ValueError(
                        f"{member}: invalid field "
                        f"at row {row_number}"
                    )

                rows.append(
                    dict(
                        zip(
                            header,
                            values,
                            strict=True,
                        )
                    )
                )

    return rows, row_count


def open_gzip_writer(
    path,
    fieldnames,
):
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    part = Path(
        str(path) + ".part"
    )
    part.unlink(
        missing_ok=True
    )

    raw = part.open(
        "wb"
    )
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
        fieldnames=fieldnames,
        lineterminator="\n",
        extrasaction="raise",
    )
    writer.writeheader()

    return (
        part,
        raw,
        gz,
        text,
        writer,
    )


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

    os.replace(
        part,
        final,
    )


def write_json_atomic(
    path,
    value,
):
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    part = Path(
        str(path) + ".part"
    )
    part.unlink(
        missing_ok=True
    )

    text = json.dumps(
        value,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    ) + "\n"

    part.write_text(
        text,
        encoding="utf-8",
    )

    os.replace(
        part,
        path,
    )


def result_labels(
    finish,
    status,
    label_contract,
):
    prestart = label_contract[
        "prestart"
    ]
    started_special = label_contract[
        "started_special"
    ]

    if finish.isdigit():
        position = int(
            finish
        )

        if position <= 0:
            raise ValueError(
                "finish position must be positive"
            )

        return {
            "label_result_status":
                "FINISHED",
            "label_numeric_finish_position":
                str(position),
            "label_order_valid":
                "1",
            "label_started":
                "1",
            "label_finished":
                "1",
            "label_win":
                "1"
                if position == 1
                else "0",
            "label_top2":
                "1"
                if position <= 2
                else "0",
            "label_top3":
                "1"
                if position <= 3
                else "0",
        }

    if status in prestart:
        return {
            "label_result_status":
                (
                    "SCRATCHED"
                    if status == "出走取消"
                    else "EXCLUDED"
                ),
            "label_numeric_finish_position":
                "",
            "label_order_valid":
                "0",
            "label_started":
                "0",
            "label_finished":
                "0",
            "label_win":
                "",
            "label_top2":
                "",
            "label_top3":
                "",
        }

    if status in started_special:
        return {
            "label_result_status":
                (
                    "DISQUALIFIED"
                    if status == "失格"
                    else "DID_NOT_FINISH"
                ),
            "label_numeric_finish_position":
                "",
            "label_order_valid":
                "0",
            "label_started":
                "1",
            "label_finished":
                "0",
            "label_win":
                "0",
            "label_top2":
                "0",
            "label_top3":
                "0",
        }

    raise ValueError(
        "unknown result state: "
        f"finish={finish!r} "
        f"status={status!r}"
    )


def build_month(
    ym,
    history_root,
    out_root,
    feature_cfg_path,
    label_cfg_path,
    anomaly_path,
    history_manifest,
):
    year, _ = valid_ym(
        ym
    )

    feature_cfg = load_json(
        feature_cfg_path
    )
    feature_contract = (
        validate_feature_contract(
            feature_cfg
        )
    )

    label_cfg = load_json(
        label_cfg_path
    )
    label_contract = (
        validate_label_contract(
            label_cfg
        )
    )

    anomalies = load_anomalies(
        anomaly_path,
        feature_contract[
            "aliases"
        ],
    )

    zip_path = (
        history_root
        / "monthly"
        / ym[:4]
        / f"{ym}_race.zip"
    )

    source_sha256 = (
        validate_source_archive(
            zip_path=zip_path,
            ym=ym,
            manifest_row=
                history_manifest.get(
                    ym
                ),
        )
    )

    allowed_race = (
        feature_contract[
            "allowed_race"
        ]
    )
    allowed_horse = (
        feature_contract[
            "allowed_horse"
        ]
    )
    exclusions = (
        feature_contract[
            "exclusions"
        ]
    )

    race_required = {
        "競馬場",
        "競走年月日",
        "レース番号",
        *allowed_race,
    }

    horse_required = {
        "競馬場",
        "競走年月日",
        "レース番号",
        "馬番",
        "着順",
        "着差",
        *allowed_horse,
    }

    with zipfile.ZipFile(
        zip_path,
        "r",
    ) as zf:
        race_rows, race_count = (
            read_csv_strict(
                zf,
                f"{ym}_racelist.csv",
                race_required,
            )
        )

        horse_rows, horse_count = (
            read_csv_strict(
                zf,
                f"{ym}_horselist.csv",
                horse_required,
            )
        )

    manifest_row = (
        history_manifest[ym]
    )

    if (
        race_count
        != int(
            manifest_row[
                "racelist_rows"
            ]
        )
    ):
        raise ValueError(
            f"{ym}: racelist row count mismatch"
        )

    if (
        horse_count
        != int(
            manifest_row[
                "horselist_rows"
            ]
        )
    ):
        raise ValueError(
            f"{ym}: horselist row count mismatch"
        )

    races = {}

    for row in race_rows:
        key = race_key(
            row
        )

        if not key[1].startswith(
            ym
        ):
            raise ValueError(
                f"{ym}: racelist row outside month: {key}"
            )

        if key in races:
            raise ValueError(
                f"{ym}: duplicate racelist key: {key}"
            )

        races[key] = {
            column: row[column]
            for column in allowed_race
        }

    horses = defaultdict(
        list
    )
    seen_entries = set()

    for row in horse_rows:
        key = race_key(
            row
        )

        if not key[1].startswith(
            ym
        ):
            raise ValueError(
                f"{ym}: horselist row outside month: {key}"
            )

        horse_number = (
            strict_positive_int_text(
                row["馬番"],
                "馬番",
            )
        )

        entry_key = (
            *key,
            horse_number,
        )

        if entry_key in seen_entries:
            raise ValueError(
                f"{ym}: duplicate entry key: {entry_key}"
            )

        seen_entries.add(
            entry_key
        )

        horses[key].append({
            "horse_number":
                horse_number,
            "features": {
                column: row[column]
                for column in allowed_horse
            },
            "finish":
                row["着順"].strip(),
            "status":
                row["着差"].strip(),
        })

    feature_race_fields = [
        f"feature_race__{column}"
        for column in allowed_race
    ]
    feature_horse_fields = [
        f"feature_entry__{column}"
        for column in allowed_horse
    ]

    output_fields = [
        *META_FIELDS,
        *feature_race_fields,
        *feature_horse_fields,
        *LABEL_FIELDS,
    ]

    if (
        len(output_fields)
        != len(set(output_fields))
    ):
        raise ValueError(
            "duplicate output field"
        )

    out_path = (
        out_root
        / "monthly"
        / ym[:4]
        / f"{ym}_entries.csv.gz"
    )

    sidecar_path = (
        out_root
        / "monthly"
        / ym[:4]
        / f"{ym}_entries.manifest.json"
    )

    counts = Counter()

    (
        part,
        raw,
        gz,
        text,
        writer,
    ) = open_gzip_writer(
        out_path,
        output_fields,
    )

    try:
        all_keys = (
            set(races)
            | set(horses)
        )

        for key in sorted(
            all_keys
        ):
            tags = {
                feature_contract[
                    "aliases"
                ].get(
                    tag,
                    tag,
                )
                for tag in anomalies.get(
                    key,
                    set(),
                )
            }

            if tags & exclusions:
                counts[
                    "excluded_source_anomaly_races"
                ] += 1
                counts[
                    "excluded_entries"
                ] += len(
                    horses.get(
                        key,
                        []
                    )
                )
                continue

            if key not in races:
                raise ValueError(
                    f"{ym}: untagged missing racelist: {key}"
                )

            if key not in horses:
                raise ValueError(
                    f"{ym}: untagged missing horselist: {key}"
                )

            entries = horses[
                key
            ]

            statuses = {
                entry["status"]
                for entry in entries
                if entry["status"]
            }

            known_non_numeric_statuses = (
                label_contract[
                    "prestart"
                ]
                | label_contract[
                    "started_special"
                ]
                | label_contract[
                    "void_statuses"
                ]
            )

            for entry in entries:
                if entry["finish"].isdigit():
                    continue

                if (
                    entry["status"]
                    not in known_non_numeric_statuses
                ):
                    raise ValueError(
                        f"{ym}: unknown result state {key}: "
                        f"finish={entry['finish']!r} "
                        f"status={entry['status']!r}"
                    )

            numeric_count = sum(
                entry["finish"].isdigit()
                for entry in entries
            )

            if (
                statuses
                & label_contract[
                    "void_statuses"
                ]
                or numeric_count == 0
            ):
                counts[
                    "excluded_void_races"
                ] += 1
                counts[
                    "excluded_entries"
                ] += len(entries)
                continue

            positions = [
                int(
                    entry["finish"]
                )
                for entry in entries
                if entry["finish"].isdigit()
            ]

            if (
                len(positions)
                != len(set(positions))
            ):
                counts[
                    "dead_heat_races"
                ] += 1

            counts[
                "included_races"
            ] += 1

            race_features = races[
                key
            ]

            for entry in entries:
                labels = result_labels(
                    finish=
                        entry["finish"],
                    status=
                        entry["status"],
                    label_contract=
                        label_contract,
                )

                out = {
                    "race_id":
                        race_id(key),
                    "entry_id":
                        entry_id(
                            key,
                            entry[
                                "horse_number"
                            ],
                        ),
                    "split":
                        split_for_year(
                            year
                        ),
                    "source_ym":
                        ym,
                    "meta_source_anomalies":
                        ";".join(
                            sorted(tags)
                        ),
                    **labels,
                }

                for column in allowed_race:
                    out[
                        f"feature_race__{column}"
                    ] = race_features[
                        column
                    ]

                for column in allowed_horse:
                    out[
                        f"feature_entry__{column}"
                    ] = entry[
                        "features"
                    ][column]

                writer.writerow(
                    out
                )
                counts[
                    "output_entries"
                ] += 1

        close_gzip_writer(
            part=part,
            final=out_path,
            raw=raw,
            gz=gz,
            text=text,
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

        part.unlink(
            missing_ok=True
        )
        raise

    output_sha256 = sha256_file(
        out_path
    )

    sidecar = {
        "format_version": 1,
        "dataset": "nar-v3-pit-safe",
        "source_ym": ym,
        "source_zip": str(
            zip_path.relative_to(
                history_root
            )
        ).replace(
            os.sep,
            "/",
        ),
        "source_zip_sha256":
            source_sha256,
        "feature_contract_file":
            feature_cfg_path.name,
        "feature_contract_sha256":
            sha256_file(
                feature_cfg_path
            ),
        "label_contract_file":
            label_cfg_path.name,
        "label_contract_sha256":
            sha256_file(
                label_cfg_path
            ),
        "anomaly_manifest_file":
            anomaly_path.name,
        "anomaly_manifest_sha256":
            sha256_file(
                anomaly_path
            ),
        "output_file":
            out_path.name,
        "output_sha256":
            output_sha256,
        "output_bytes":
            out_path.stat().st_size,
        "feature_columns": [
            *feature_race_fields,
            *feature_horse_fields,
        ],
        "label_columns":
            list(LABEL_FIELDS),
        "counts":
            dict(
                sorted(
                    counts.items()
                )
            ),
    }

    write_json_atomic(
        sidecar_path,
        sidecar,
    )

    return (
        out_path,
        sidecar_path,
        counts,
    )


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--ym",
        help="single YYYYMM",
    )
    parser.add_argument(
        "--start",
        help="first YYYYMM",
    )
    parser.add_argument(
        "--end",
        help="last YYYYMM",
    )
    parser.add_argument(
        "--history-root",
        type=Path,
        default=Path(
            "/workspaces/nar-history"
        ),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(
            "/workspaces/nar-v3-dataset"
        ),
    )
    parser.add_argument(
        "--features",
        type=Path,
        default=Path(
            "config/nar-v3-features.json"
        ),
    )
    parser.add_argument(
        "--labels",
        type=Path,
        default=Path(
            "config/nar-v1-labels.json"
        ),
    )
    parser.add_argument(
        "--anomalies",
        type=Path,
        default=Path(
            "data-manifests/nar-history/"
            "source-anomaly-races.csv"
        ),
    )
    parser.add_argument(
        "--history-manifest",
        type=Path,
        default=None,
    )

    args = parser.parse_args()

    if args.ym is not None:
        if (
            args.start is not None
            or args.end is not None
        ):
            parser.error(
                "--ym cannot be combined with --start/--end"
            )

        valid_ym(
            args.ym
        )
        months = [
            args.ym
        ]

    else:
        if (
            args.start is None
            or args.end is None
        ):
            parser.error(
                "specify --ym or both --start and --end"
            )

        months = list(
            month_range(
                args.start,
                args.end,
            )
        )

    history_manifest_path = (
        args.history_manifest
        if args.history_manifest is not None
        else (
            args.history_root
            / "manifests"
            / "months.csv"
        )
    )

    history_manifest = (
        load_history_manifest(
            history_manifest_path
        )
    )

    totals = Counter()

    for ym in months:
        print()
        print(
            f"=== {ym} ==="
        )

        (
            out_path,
            sidecar_path,
            counts,
        ) = build_month(
            ym=ym,
            history_root=
                args.history_root,
            out_root=
                args.out,
            feature_cfg_path=
                args.features,
            label_cfg_path=
                args.labels,
            anomaly_path=
                args.anomalies,
            history_manifest=
                history_manifest,
        )

        print(
            "output =",
            out_path,
        )
        print(
            "manifest =",
            sidecar_path,
        )
        print(
            "bytes =",
            out_path.stat().st_size,
        )
        print(
            "sha256 =",
            sha256_file(
                out_path
            ),
        )

        for key in sorted(
            counts
        ):
            print(
                key,
                "=",
                counts[key],
            )
            totals[key] += (
                counts[key]
            )

    print()
    print(
        "=== TOTAL ==="
    )
    print(
        "months =",
        len(months),
    )

    for key in sorted(
        totals
    ):
        print(
            key,
            "=",
            totals[key],
        )

    print()
    print(
        "NAR V3 PIT-SAFE DATASET BUILD OK"
    )


if __name__ == "__main__":
    main()
