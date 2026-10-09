#!/usr/bin/env python3
"""Offline 2021-2023 NAR horse identity AUDIT (not an identity resolver).




Reads historical monthly horselist.csv without extracting archives.
Never reads 2025 TEST or 2026 OOT. Never supplies model features.
No horse_uid is issued by this audit.
"""




import argparse
import csv
import hashlib
import io
import json
import re
import zipfile
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path




VERSION = 1
FIRST = "202101"
LAST = "202312"
MAX_ZIP_BYTES = 64 * 1024 * 1024
MAX_MEMBER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_MEMBER_BYTES = 512 * 1024 * 1024
MAX_FIELD_CHARS = 1024 * 1024
IDENTITY_COLUMNS = ("馬名", "生年月日", "父馬名", "母馬名", "母父馬名")
REQUIRED = (
    "競馬場", "競走年月日", "レース番号", "馬番",
    *IDENTITY_COLUMNS,
)
KIND_NAMES = ("racelist", "horselist", "payback")








def months_in_period():
    return tuple(f"{year}{month:02d}"
                 for year in (2021, 2022, 2023)
                 for month in range(1, 13))








def file_sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()








def strict_date8(value, label):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{8}", value):
        raise ValueError(f"invalid {label}: {value!r}")
    try:
        date(int(value[:4]), int(value[4:6]), int(value[6:8]))
    except ValueError as exc:
        raise ValueError(f"invalid {label}: {value!r}") from exc
    return value








def load_manifest(root):
    path = root / "manifests" / "months.csv"
    if not path.is_file():
        raise ValueError(f"missing required source manifest: {path}")
    records = {}
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"ym", "status", "bytes", "sha256", "horselist_rows"}
        if (reader.fieldnames is None
                or len(reader.fieldnames) != len(set(reader.fieldnames))
                or not required.issubset(reader.fieldnames)):
            raise ValueError("invalid source manifest header")
        for row in reader:
            ym = row["ym"]
            if ym in records:
                raise ValueError(f"duplicate source manifest month: {ym}")
            records[ym] = row
    return records, file_sha256(path)








def checked_archive(root, ym, manifest):
    path = root / "monthly" / ym[:4] / f"{ym}_race.zip"
    row = manifest.get(ym)
    if row is None or row["status"] != "OK":
        raise ValueError(f"{ym}: missing/invalid OK manifest record")
    if not path.is_file():
        raise ValueError(f"{ym}: missing monthly ZIP")
    size = path.stat().st_size
    if not (0 < size <= MAX_ZIP_BYTES):
        raise ValueError(f"{ym}: ZIP size outside limit")
    if str(size) != row["bytes"]:
        raise ValueError(f"{ym}: ZIP size does not match manifest")
    sha = file_sha256(path)
    if not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]):
        raise ValueError(f"{ym}: invalid manifest SHA")
    if sha != row["sha256"]:
        raise ValueError(f"{ym}: ZIP SHA mismatch")
    try:
        expected_rows = int(row["horselist_rows"])
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{ym}: invalid horselist_rows") from exc
    if expected_rows < 0:
        raise ValueError(f"{ym}: negative horselist_rows")
    return path, sha, expected_rows








def read_month(root, ym, manifest):
    path, sha, expected_rows = checked_archive(root, ym, manifest)
    expected_names = {f"{ym}_{kind}.csv" for kind in KIND_NAMES}
    with zipfile.ZipFile(path, "r") as archive:
        infos = [item for item in archive.infolist() if not item.is_dir()]
        names = [item.filename for item in infos]
        if len(names) != len(set(names)) or set(names) != expected_names:
            raise ValueError(f"{ym}: missing/extra/duplicate ZIP members")
        total = 0
        for item in infos:
            if (item.filename != Path(item.filename).name
                    or item.flag_bits & 1
                    or item.compress_type not in (
                        zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED
                    )
                    or item.file_size < 0
                    or item.file_size > MAX_MEMBER_BYTES):
                raise ValueError(f"{ym}: invalid ZIP member")
            total += item.file_size
        if total > MAX_TOTAL_MEMBER_BYTES:
            raise ValueError(f"{ym}: ZIP expansion limit exceeded")
        # Check the CRC of EVERY member, not just the horse CSV.
        bad_member = archive.testzip()
        if bad_member is not None:
            raise ValueError(f"{ym}: ZIP CRC failure: {bad_member}")




        with archive.open(f"{ym}_horselist.csv", "r") as raw:
            with io.TextIOWrapper(
                raw, encoding="utf-8-sig", errors="strict", newline=""
            ) as text:
                reader = csv.reader(text)
                try:
                    header = next(reader)
                except StopIteration as exc:
                    raise ValueError(f"{ym}: empty horselist CSV") from exc
                if (len(header) != len(set(header))
                        or not set(REQUIRED).issubset(header)
                        or any(not name or "\x00" in name for name in header)):
                    raise ValueError(f"{ym}: invalid horselist header")
                positions = {key: header.index(key) for key in REQUIRED}
                count = 0
                for line_no, cells in enumerate(reader, 2):
                    if (len(cells) != len(header)
                            or any("\x00" in c or len(c) > MAX_FIELD_CHARS
                                   for c in cells)):
                        raise ValueError(
                            f"{ym}: malformed horselist row {line_no}"
                        )
                    count += 1
                    yield {
                        key: cells[pos] for key, pos in positions.items()
                    }, ym, line_no
                if count != expected_rows:
                    raise ValueError(
                        f"{ym}: horselist row-count mismatch "
                        f"{count} != {expected_rows}"
                    )










def normalize_positive_int(value, label):
    """Match V3 Dataset: accept positive decimal with leading zeroes."""
    if (not isinstance(value, str)
            or value == "" or value != value.strip()
            or "\x00" in value
            or len(value) > 32
            or not value.isascii() or not value.isdigit()):
        raise ValueError(f"invalid {label}: {value!r}")
    number = int(value)
    if number <= 0:
        raise ValueError(f"invalid {label}: {value!r}")
    return str(number)


def optional_identity(value, column, missing, invalid):
    if not value:
        missing[column] += 1
        return None
    if value != value.strip() or len(value) > 128:
        invalid[column] += 1
        return None
    if column == "生年月日":
        try:
            strict_date8(value, column)
        except ValueError:
            invalid[column] += 1
            return None
    return value








def preview(groups, max_examples=8):
    return [
        {"value": list(key) if isinstance(key, tuple) else key,
         "variants": [list(x) if isinstance(x, tuple) else x
                      for x in sorted(values)[:5]],
         "variant_count": len(values)}
        for key, values in sorted(
            ((key, values) for key, values in groups.items()
             if len(values) > 1),
            key=lambda pair: str(pair[0])
        )[:max_examples]
    ]








def audit(root, months=None):
    if months is None:
        months = months_in_period()
    months = tuple(months)
    if (not months or len(months) != len(set(months))
            or any(not re.fullmatch(r"202[123](0[1-9]|1[0-2])", m)
                   for m in months)):
        raise ValueError("audit is restricted to unique 2021-2023 months")




    csv.field_size_limit(MAX_FIELD_CHARS)
    root = Path(root)
    manifest, manifest_sha = load_manifest(root)
    missing = Counter()
    invalid = Counter()
    name_to_bios = defaultdict(set)
    name_to_dates = defaultdict(set)
    name_birth_to_pedigrees = defaultdict(set)
    pedigree_to_names = defaultdict(set)
    seen_entries = set()
    same_day_first = {}
    same_day_conflicts = set()
    source_rows = Counter()
    complete_rows = 0




    for ym in sorted(months):
        for row, source_ym, line_no in read_month(root, ym, manifest):
            track = row["競馬場"]
            race_date = strict_date8(row["競走年月日"], "競走年月日")
            race_no = normalize_positive_int(row["レース番号"], "レース番号")
            horse_no = normalize_positive_int(row["馬番"], "馬番")
            if (not track or track != track.strip()
                    or "\x00" in track or len(track) > 128
                    or not race_date.startswith(ym)):
                raise ValueError(
                    f"{source_ym}:{line_no}: invalid race/entry key"
                )
            entry_key = (track, race_date, race_no, horse_no)
            if entry_key in seen_entries:
                raise ValueError(
                    f"{source_ym}:{line_no}: duplicate race entry"
                )
            seen_entries.add(entry_key)
            source_rows[ym] += 1




            parts = [
                optional_identity(row[col], col, missing, invalid)
                for col in IDENTITY_COLUMNS
            ]
            if any(value is None for value in parts):
                continue




            name, dob, sire, dam, damsire = parts
            if dob >= race_date:
                invalid["生年月日が出走日以降"] += 1
                continue
            complete_rows += 1
            full_key = tuple(parts)
            pedigree = (sire, dam, damsire)
            name_to_bios[name].add((dob, *pedigree))
            name_to_dates[name].add(dob)
            name_birth_to_pedigrees[(name, dob)].add(pedigree)
            pedigree_to_names[(dob, *pedigree)].add(name)




            race_key = (track, race_date, race_no)
            same_day_key = (full_key, race_date)
            existing = same_day_first.setdefault(same_day_key, race_key)
            if existing != race_key:
                same_day_conflicts.add(same_day_key)




    multi_full = {k: v for k, v in name_to_bios.items() if len(v) > 1}
    multi_dob = {k: v for k, v in name_to_dates.items() if len(v) > 1}
    same_dob_diff_pedigree = {
        k: v for k, v in name_birth_to_pedigrees.items()
        if len(v) > 1
    }
    different_names_same_bio = {
        k: v for k, v in pedigree_to_names.items() if len(v) > 1
    }
    archives = [
        {"ym": ym, "rows": source_rows[ym],
         "zip_sha256": manifest[ym]["sha256"]}
        for ym in sorted(months)
    ]
    return {
        "audit_version": VERSION,
        "scope": "NAR HISTORICAL_MONTHLY 2021-2023 ONLY",
        "status": "IDENTITY_NOT_PROVEN",
        "source_manifest_sha256": manifest_sha,
        "months_audited": len(months),
        "rows_audited": sum(source_rows.values()),
        "rows_with_complete_candidate_identity": complete_rows,
        "rows_missing_identity_fields": dict(sorted(missing.items())),
        "rows_invalid_identity_fields": dict(sorted(invalid.items())),
        "candidate_conflict_groups": {
            "name_multiple_complete_biographies": len(multi_full),
            "name_multiple_birthdates": len(multi_dob),
            "same_name_birthdate_multiple_pedigrees":
                len(same_dob_diff_pedigree),
            "same_birthdate_pedigree_multiple_names":
                len(different_names_same_bio),
            "same_complete_identity_multiple_races_same_day":
                len(same_day_conflicts),
        },
        "examples": {
            "name_multiple_birthdates": preview(multi_dob),
            "same_name_birthdate_multiple_pedigrees":
                preview(same_dob_diff_pedigree),
            "same_birthdate_pedigree_multiple_names":
                preview(different_names_same_bio),
            "same_complete_identity_multiple_races_same_day": [
                {"identity": list(identity), "race_date": racedate}
                for identity, racedate in
                sorted(same_day_conflicts)[:8]
            ],
        },
        "sources": archives,
        "limitations": [
            "Composite identity is only a candidate, never verified horse_uid.",
            "Names and pedigree in final monthly CSV are not proof of "
            "their availability at a historical prediction_as_of.",
            "Result-publication timestamps and PIT history coverage are "
            "NOT established by this audit.",
            "Name change, spelling differences, missing or corrected "
            "pedigree and same-parent births need separate resolution.",
            "2025 TEST and 2026 OOT were not scanned."
        ],
    }








def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--history-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()




    report = audit(args.history_root)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    # Never overwrite a prior audit snapshot.
    with args.out.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2,
                  sort_keys=True)
        stream.write("\n")




    print("AUDIT_READ_ONLY_COMPLETE")
    print("Months:", report["months_audited"])
    print("Rows:", report["rows_audited"])
    print("Complete candidate identity rows:",
          report["rows_with_complete_candidate_identity"])
    print("Missing:", report["rows_missing_identity_fields"])
    print("Invalid:", report["rows_invalid_identity_fields"])
    print("Candidates:", report["candidate_conflict_groups"])
    print("Identity certified: NO")
    print("Report:", args.out)








if __name__ == "__main__":
    main()