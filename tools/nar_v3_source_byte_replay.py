"""Read-only subset replay of immutable NAR monthly ZIPs for Phase08.

Development evidence ONLY. Rechecking a 2026-acquired historical archive does
NOT prove the bytes were available at a 2021-2024 prediction_as_of. Does not
build horse IDs, Features, a model, or any test/OOT dataset.
"""
from __future__ import annotations

import argparse
import csv
from hashlib import sha256
import json
from pathlib import Path
import re

from audit_nar_horse_identity_2021_2023 import (
    MAX_FIELD_CHARS, checked_archive, file_sha256, load_manifest, read_month,
)
from nar_v3_build_internal_horse_registry import months_between


def replay_months(history_root: Path, first: str, last: str,
                  expected_manifest_sha256: str) -> dict:
    months = months_between(first, last)  # 1998-2024; rejects 2025 TEST / 2026 OOT
    if not isinstance(expected_manifest_sha256, str) or not re.fullmatch(
            r"[0-9a-f]{64}", expected_manifest_sha256):
        raise ValueError("invalid source-manifest SHA pin")
    manifest, manifest_sha = load_manifest(Path(history_root))
    if manifest_sha != expected_manifest_sha256:
        raise ValueError("immutable source-manifest SHA mismatch")
    csv.field_size_limit(MAX_FIELD_CHARS)
    total_rows = total_bytes = 0
    ordered = []
    for ym in months:
        path, verified_zip_sha, reported_rows = checked_archive(
            Path(history_root), ym, manifest)
        # read_month validates ZIP member names, bounds, CRC (all 3 members),
        # required CSV header, row shape and reported row count.
        actual_rows = sum(1 for _ in read_month(
            Path(history_root), ym, manifest))
        if actual_rows != reported_rows:
            raise ValueError(f"{ym}: row-count disagreement")
        ordered.append(f"{ym}:{verified_zip_sha}\n")
        total_rows += actual_rows
        total_bytes += path.stat().st_size
    return {
        "scope": "PHASE08_NAR_ARCHIVE_BYTES_REPLAY_DEVELOPMENT_ONLY",
        "first_month": first,
        "last_month": last,
        "verified_months": len(months),
        "source_manifest_sha256": manifest_sha,
        "monthly_zip_shas_digest": sha256(
            "".join(ordered).encode("ascii")).hexdigest(),
        "total_zip_bytes": total_bytes,
        "horselist_rows": total_rows,
        "all_zip_sha256_crc_csv_rows_checked": True,
        "individual_horse_uid_verified": False,
        "historical_2021_2024_asof_verified": False,
        "all_authority_history_coverage_verified": False,
        "production_four_features_authorized": False,
        "locked_2025_test_or_2026_oot_used": False,
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--history-root", type=Path, required=True)
    p.add_argument("--first", required=True)
    p.add_argument("--last", required=True)
    p.add_argument("--expected-manifest-sha256", required=True)
    args = p.parse_args()
    print(json.dumps(replay_months(
        args.history_root, args.first, args.last,
        args.expected_manifest_sha256
    ), ensure_ascii=False, sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
