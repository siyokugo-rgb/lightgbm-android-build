#!/usr/bin/env python3

import argparse
import csv
import hashlib
import os
import sys
import urllib.request
import zipfile
from datetime import UTC, datetime
from pathlib import Path


BASE_URL = (
    "https://www.keiba.go.jp/KeibaWeb/DataDownload/"
    "RaceDataDownload?k_month={month}&k_year={year}&type=monthly"
)

KINDS = ("racelist", "horselist", "payback")


def parse_ym(value: str) -> tuple[int, int]:
    if len(value) != 6 or not value.isdigit():
        raise argparse.ArgumentTypeError("YYYYMM required")

    year = int(value[:4])
    month = int(value[4:])

    if not 1 <= month <= 12:
        raise argparse.ArgumentTypeError("month must be 01..12")

    return year, month


def ym_string(year: int, month: int) -> str:
    return f"{year:04d}{month:02d}"


def iter_months(start: tuple[int, int], end: tuple[int, int]):
    year, month = start
    ey, em = end

    while (year, month) <= (ey, em):
        yield year, month

        month += 1
        if month == 13:
            year += 1
            month = 1


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()

    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)

    return digest.hexdigest()


def count_csv_rows_from_zip(zf: zipfile.ZipFile, member: str) -> int:
    with zf.open(member, "r") as raw:
        # CSVはUTF-8 BOM付き。行数だけなのでバイナリの改行数ではなく
        # CSV parserでレコード数を数える。
        import io

        text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
        reader = csv.reader(text)

        try:
            next(reader)
        except StopIteration:
            return 0

        return sum(1 for _ in reader)


def validate_zip(path: Path, ym: str):
    expected = {
        f"{ym}_racelist.csv",
        f"{ym}_horselist.csv",
        f"{ym}_payback.csv",
    }

    with zipfile.ZipFile(path, "r") as zf:
        bad = zf.testzip()
        if bad is not None:
            raise RuntimeError(f"CRC failure: {bad}")

        members = {
            Path(name).name
            for name in zf.namelist()
            if not name.endswith("/")
        }

        missing = expected - members
        if missing:
            raise RuntimeError(
                "missing ZIP member(s): " + ", ".join(sorted(missing))
            )

        rows = {}

        for kind in KINDS:
            member_name = f"{ym}_{kind}.csv"

            actual_member = next(
                name
                for name in zf.namelist()
                if Path(name).name == member_name
            )

            rows[kind] = count_csv_rows_from_zip(zf, actual_member)

    return rows


def download(url: str, destination: Path):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/zip",
            "User-Agent": "KeibaHistoryAudit/0.1",
        },
    )

    with urllib.request.urlopen(request, timeout=60) as response:
        status = getattr(response, "status", 200)

        if status != 200:
            raise RuntimeError(f"HTTP {status}")

        content_type = response.headers.get("Content-Type", "")

        if "zip" not in content_type.lower():
            raise RuntimeError(
                f"unexpected Content-Type: {content_type!r}"
            )

        with destination.open("wb") as out:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)


def write_manifest(path: Path, records: dict[str, dict]):
    path.parent.mkdir(parents=True, exist_ok=True)

    fields = [
        "ym",
        "status",
        "bytes",
        "sha256",
        "racelist_rows",
        "horselist_rows",
        "payback_rows",
        "checked_at_utc",
        "error",
    ]

    tmp = path.with_suffix(".tmp")

    with tmp.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        writer.writeheader()

        for ym in sorted(records):
            writer.writerow(records[ym])

    os.replace(tmp, path)


def load_manifest(path: Path):
    if not path.exists():
        return {}

    with path.open("r", encoding="utf-8", newline="") as f:
        return {
            row["ym"]: row
            for row in csv.DictReader(f)
        }


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--start", required=True, type=parse_ym)
    parser.add_argument("--end", required=True, type=parse_ym)
    parser.add_argument(
        "--root",
        default="/workspaces/nar-history",
    )

    args = parser.parse_args()

    if args.start > args.end:
        parser.error("--start must be <= --end")

    root = Path(args.root)
    monthly_root = root / "monthly"
    manifest_path = root / "manifests" / "months.csv"

    records = load_manifest(manifest_path)

    failures = 0

    for year, month in iter_months(args.start, args.end):
        ym = ym_string(year, month)

        year_dir = monthly_root / f"{year:04d}"
        year_dir.mkdir(parents=True, exist_ok=True)

        final_path = year_dir / f"{ym}_race.zip"
        part_path = year_dir / f".{ym}_race.zip.part"

        print(f"\n=== {ym} ===", flush=True)

        try:
            if final_path.exists():
                print("existing: validating", flush=True)
                rows = validate_zip(final_path, ym)
            else:
                url = BASE_URL.format(year=year, month=month)

                part_path.unlink(missing_ok=True)

                print("downloading", flush=True)
                download(url, part_path)

                print("validating", flush=True)
                rows = validate_zip(part_path, ym)

                os.replace(part_path, final_path)

            size = final_path.stat().st_size
            digest = sha256_file(final_path)

            now_utc = (
                datetime.now(UTC)
                .isoformat(timespec="seconds")
                .replace("+00:00", "Z")
            )

            stable_fields = {
                "status": "OK",
                "bytes": str(size),
                "sha256": digest,
                "racelist_rows": str(rows["racelist"]),
                "horselist_rows": str(rows["horselist"]),
                "payback_rows": str(rows["payback"]),
                "error": "",
            }

            previous = records.get(ym)

            if previous is not None and all(
                previous.get(key, "") == value
                for key, value in stable_fields.items()
            ):
                checked_at_utc = previous.get("checked_at_utc") or now_utc
            else:
                checked_at_utc = now_utc

            record = {
                "ym": ym,
                **stable_fields,
                "checked_at_utc": checked_at_utc,
            }

            records[ym] = record
            write_manifest(manifest_path, records)

            print(
                "OK",
                f"bytes={size}",
                f"race={rows['racelist']}",
                f"horse={rows['horselist']}",
                f"payback={rows['payback']}",
                flush=True,
            )

        except Exception as exc:
            failures += 1
            part_path.unlink(missing_ok=True)

            records[ym] = {
                "ym": ym,
                "status": "FAIL",
                "bytes": "",
                "sha256": "",
                "racelist_rows": "",
                "horselist_rows": "",
                "payback_rows": "",
                "checked_at_utc": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "error": f"{type(exc).__name__}: {exc}",
            }

            write_manifest(manifest_path, records)

            print(
                f"FAIL: {type(exc).__name__}: {exc}",
                file=sys.stderr,
                flush=True,
            )

    print()
    print(f"manifest: {manifest_path}")
    print(f"failures: {failures}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
