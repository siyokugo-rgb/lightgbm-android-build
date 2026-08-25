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
from pathlib import Path


def sha256_file(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def race_key(row):
    return (
        row["競馬場"],
        row["競走年月日"],
        row["レース番号"],
    )


def race_id(key):
    return "|".join(key)


def entry_id(key, horse_no):
    return f"{race_id(key)}|{horse_no}"


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


def find_member(zf, basename):
    found = [
        name
        for name in zf.namelist()
        if Path(name).name == basename
    ]

    if len(found) != 1:
        raise ValueError(
            f"{basename}: expected 1 member, got {len(found)}"
        )

    return found[0]


def read_csv(zf, member):
    with zf.open(member) as raw:
        with io.TextIOWrapper(
            raw,
            encoding="utf-8-sig",
            newline="",
        ) as text:
            yield from csv.DictReader(text)


def load_anomalies(path, aliases):
    result = defaultdict(set)

    with path.open(
        encoding="utf-8",
        newline="",
    ) as f:
        for row in csv.DictReader(f):
            key = (
                row["競馬場"],
                row["競走年月日"],
                row["レース番号"],
            )

            name = row["anomaly_type"]
            name = aliases.get(name, name)

            result[key].add(name)

    return result


def gzip_csv_writer(path, fieldnames):
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
        fieldnames=fieldnames,
        lineterminator="\n",
    )

    writer.writeheader()

    return part, raw, gz, text, writer


def close_writer(part, final, raw, gz, text):
    text.flush()
    text.close()

    if not gz.closed:
        gz.close()

    if not raw.closed:
        raw.close()

    os.replace(part, final)


def build_month(
    ym,
    history_root,
    out_root,
    feature_cfg,
    label_cfg,
    anomalies,
):
    year = int(ym[:4])

    zip_path = (
        history_root
        / "monthly"
        / ym[:4]
        / f"{ym}_race.zip"
    )

    if not zip_path.is_file():
        raise FileNotFoundError(zip_path)

    allowed_race = feature_cfg[
        "allowed_raw_feature_sources"
    ]["racelist"]

    allowed_horse = feature_cfg[
        "allowed_raw_feature_sources"
    ]["horselist"]

    aliases = feature_cfg[
        "source_anomaly_aliases"
    ]

    exclusions = set(
        feature_cfg["v1_training_exclusions"]
    )

    prestart = set(
        label_cfg["prestart_no_order_label"]
    )

    started_special = set(
        label_cfg[
            "started_special_no_order_label"
        ]
    )

    void_statuses = set(
        label_cfg["race_void_statuses"]
    )

    feature_race_fields = [
        f"feature_race__{col}"
        for col in allowed_race
    ]

    feature_horse_fields = [
        f"feature_entry__{col}"
        for col in allowed_horse
    ]

    output_fields = [
        "race_id",
        "entry_id",
        "split",
        "source_ym",
        "meta_source_anomalies",
        *feature_race_fields,
        *feature_horse_fields,
        "label_result_status",
        "label_numeric_finish_position",
        "label_order_valid",
        "label_started",
        "label_finished",
        "label_win",
        "label_top2",
        "label_top3",
    ]

    out_path = (
        out_root
        / "monthly"
        / ym[:4]
        / f"{ym}_entries.csv.gz"
    )

    counts = Counter()

    with zipfile.ZipFile(zip_path) as zf:
        race_member = find_member(
            zf,
            f"{ym}_racelist.csv",
        )

        horse_member = find_member(
            zf,
            f"{ym}_horselist.csv",
        )

        races = {}

        for row in read_csv(zf, race_member):
            key = race_key(row)

            if key in races:
                raise ValueError(
                    f"duplicate racelist key: {key}"
                )

            races[key] = {
                col: row[col]
                for col in allowed_race
            }

            counts["source_races"] += 1

        horses = defaultdict(list)

        for row in read_csv(zf, horse_member):
            key = race_key(row)

            horses[key].append({
                "features": {
                    col: row[col]
                    for col in allowed_horse
                },
                "finish": row["着順"].strip(),
                "status": row["着差"].strip(),
            })

            counts["source_entries"] += 1

    part, raw, gz, text, writer = gzip_csv_writer(
        out_path,
        output_fields,
    )

    try:
        for key, entries in horses.items():
            tags = {
                aliases.get(tag, tag)
                for tag in anomalies.get(key, set())
            }

            if key not in races:
                counts["excluded_missing_racelist_races"] += 1
                counts["excluded_entries"] += len(entries)
                continue

            if tags & exclusions:
                counts["excluded_source_anomaly_races"] += 1
                counts["excluded_entries"] += len(entries)
                continue

            numeric_count = sum(
                e["finish"].isdigit()
                for e in entries
            )

            statuses = {
                e["status"]
                for e in entries
                if e["status"]
            }

            if (
                statuses & void_statuses
                or numeric_count == 0
            ):
                counts["excluded_void_races"] += 1
                counts["excluded_entries"] += len(entries)
                continue

            positions = [
                int(e["finish"])
                for e in entries
                if e["finish"].isdigit()
            ]

            if len(positions) != len(set(positions)):
                counts["dead_heat_races"] += 1

            counts["included_races"] += 1

            race_features = races[key]

            for e in entries:
                finish = e["finish"]
                status = e["status"]

                if finish.isdigit():
                    pos = int(finish)

                    result_status = "FINISHED"
                    numeric_finish = str(pos)
                    order_valid = "1"
                    started = "1"
                    finished = "1"
                    win = "1" if pos == 1 else "0"
                    top2 = "1" if pos <= 2 else "0"
                    top3 = "1" if pos <= 3 else "0"

                    counts["numeric_labels"] += 1

                elif status in prestart:
                    if status == "出走取消":
                        result_status = "SCRATCHED"
                    else:
                        result_status = "EXCLUDED"

                    numeric_finish = ""
                    order_valid = "0"
                    started = "0"
                    finished = "0"
                    win = ""
                    top2 = ""
                    top3 = ""

                    counts["prestart_masked"] += 1

                elif status in started_special:
                    if status == "失格":
                        result_status = "DISQUALIFIED"
                    else:
                        result_status = "DID_NOT_FINISH"

                    numeric_finish = ""
                    order_valid = "0"
                    started = "1"
                    finished = "0"
                    win = "0"
                    top2 = "0"
                    top3 = "0"

                    counts["started_special"] += 1

                else:
                    raise ValueError(
                        f"unknown result state {key}: "
                        f"finish={finish!r} "
                        f"status={status!r}"
                    )

                horse_features = e["features"]
                horse_no = horse_features["馬番"]

                out = {
                    "race_id": race_id(key),
                    "entry_id": entry_id(
                        key,
                        horse_no,
                    ),
                    "split": split_for_year(year),
                    "source_ym": ym,
                    "meta_source_anomalies":
                        ";".join(sorted(tags)),
                    "label_result_status":
                        result_status,
                    "label_numeric_finish_position":
                        numeric_finish,
                    "label_order_valid":
                        order_valid,
                    "label_started": started,
                    "label_finished": finished,
                    "label_win": win,
                    "label_top2": top2,
                    "label_top3": top3,
                }

                for col in allowed_race:
                    out[
                        f"feature_race__{col}"
                    ] = race_features[col]

                for col in allowed_horse:
                    out[
                        f"feature_entry__{col}"
                    ] = horse_features[col]

                writer.writerow(out)
                counts["output_entries"] += 1

        close_writer(
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
        "--ym",
        required=True,
        help="YYYYMM",
    )

    parser.add_argument(
        "--history-root",
        type=Path,
        default=Path("/workspaces/nar-history"),
    )

    parser.add_argument(
        "--out",
        type=Path,
        default=Path(
            "/workspaces/nar-v1-dataset"
        ),
    )

    parser.add_argument(
        "--features",
        type=Path,
        default=Path(
            "config/nar-v1-features.json"
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

    args = parser.parse_args()

    if (
        len(args.ym) != 6
        or not args.ym.isdigit()
    ):
        raise SystemExit("invalid --ym")

    feature_cfg = json.loads(
        args.features.read_text(
            encoding="utf-8"
        )
    )

    label_cfg = json.loads(
        args.labels.read_text(
            encoding="utf-8"
        )
    )

    anomalies = load_anomalies(
        args.anomalies,
        feature_cfg[
            "source_anomaly_aliases"
        ],
    )

    out_path, counts = build_month(
        args.ym,
        args.history_root,
        args.out,
        feature_cfg,
        label_cfg,
        anomalies,
    )

    print()
    print("=== BUILD RESULT ===")
    print("output =", out_path)

    for key in sorted(counts):
        print(key, "=", counts[key])

    print(
        "bytes =",
        out_path.stat().st_size,
    )

    print(
        "sha256 =",
        sha256_file(out_path),
    )

    print()
    print("NAR V1 MONTH BUILD OK")


if __name__ == "__main__":
    main()
