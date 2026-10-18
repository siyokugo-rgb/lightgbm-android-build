"""Offline bounded 1998-2024 NAR monthly CSV -> development horse candidate registry.

Reads the existing immutable monthly ZIP manifest; writes a NEW output directory
with a SQLite registry and JSON report only on complete success.
Never reads TEST2025 or OOT2026, connects production features, or touches
existing Ledger / Snapshot files. No JRA links are inferred.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile

from audit_nar_horse_identity_2021_2023 import (
    load_manifest, read_month, checked_archive, file_sha256,
)
from nar_v3_internal_horse_id import (
    HorseEntryCandidate, _prepare, _candidate_id, CANDIDATE_ONLY,
    AMBIGUOUS, UNMATCHABLE, VERSION,
)

FIRST_MONTH = "199801"
LAST_MONTH = "202412"
SCHEMA_VERSION = 1


def _strict_json(path: Path):
    def no_duplicates(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                raise ValueError("duplicate JSON field in discipline map")
            out[key] = value
        return out
    with path.open("r", encoding="utf-8-sig") as stream:
        return json.load(stream, object_pairs_hook=no_duplicates)


def read_discipline_map(path: Path) -> dict[str, str]:
    """An explicitly attested mapping, not inference from venue names."""
    obj = _strict_json(path)
    if (not isinstance(obj, dict) or set(obj) != {"version", "records"}
            or obj["version"] != 1 or type(obj["version"]) is not int
            or not isinstance(obj["records"], list) or not obj["records"]):
        raise ValueError("invalid discipline evidence map")
    result = {}
    for item in obj["records"]:
        if (not isinstance(item, dict)
                or set(item) != {"track", "discipline", "evidence_ref"}
                or not isinstance(item["track"], str)
                or not item["track"] or item["track"] != item["track"].strip()
                or len(item["track"]) > 128
                or item["discipline"] not in ("FLAT", "BANEI")
                or not isinstance(item["evidence_ref"], str)
                or not item["evidence_ref"].strip()):
            raise ValueError("unverified discipline classification")
        track = item["track"]
        if track in result:
            raise ValueError("duplicate track in discipline map")
        result[track] = item["discipline"]
    return result


def months_between(first: str, last: str) -> tuple[str, ...]:
    for ym in (first, last):
        if (not isinstance(ym, str) or not re.fullmatch(r"20[0-9]{4}|19[0-9]{4}", ym)
                or not (1 <= int(ym[4:]) <= 12)):
            raise ValueError("invalid month")
    if not FIRST_MONTH <= first <= last <= LAST_MONTH:
        raise ValueError("1998-2024 only; TEST/OOT are forbidden")
    y, m = int(first[:4]), int(first[4:])
    result = []
    while (y, m) <= (int(last[:4]), int(last[4:])):
        result.append(f"{y}{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return tuple(result)


def _entry_id(discipline, track, date8, race_no, horse_no) -> str:
    parts = ["NAR-RACE-ENTRY-V1", discipline, track, date8, race_no, horse_no]
    raw = json.dumps(parts, ensure_ascii=False, separators=(",", ":"))
    return "nar-entry-v1-" + hashlib.sha256(raw.encode("utf-8")).hexdigest()


DDL = """
CREATE TABLE entries(
  entry_id TEXT PRIMARY KEY NOT NULL,
  source_ym TEXT NOT NULL,
  source_zip_sha256 TEXT NOT NULL,
  source_row_number INTEGER NOT NULL,
  race_date TEXT NOT NULL,
  track TEXT NOT NULL,
  discipline TEXT NOT NULL CHECK(discipline IN ('FLAT','BANEI')),
  race_number TEXT NOT NULL,
  horse_number TEXT NOT NULL,
  name TEXT, dob TEXT, pedigree_hash TEXT, name_birth_hash TEXT,
  bloodline_hash TEXT, identity_hash TEXT,
  candidate_id TEXT,
  status TEXT NOT NULL,
  reason TEXT NOT NULL,
  first_observed_date TEXT,
  first_observed_entry_id TEXT
);
CREATE INDEX idx_identity ON entries(identity_hash);
CREATE INDEX idx_name_birth ON entries(name_birth_hash);
CREATE INDEX idx_bloodline ON entries(bloodline_hash);
CREATE INDEX idx_day ON entries(identity_hash,race_date);
"""


def _h(*components: str) -> str:
    encoded = json.dumps(components, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _insert_row(raw, ym, rownum, zip_sha, disciplines):
    track = raw["競馬場"]
    if track not in disciplines:
        raise ValueError(f"missing verified discipline mapping for track {track!r}")
    if not isinstance(raw["競走年月日"], str) or not raw["競走年月日"].startswith(ym):
        raise ValueError("race date/source month mismatch")
    discipline = disciplines[track]
    entry = HorseEntryCandidate(
        entry_id="temp", race_date=raw["競走年月日"],
        track=track, race_number=raw["レース番号"],
        horse_number=raw["馬番"], discipline=discipline,
        horse_name=raw["馬名"], birth_date=raw["生年月日"],
        sire=raw["父馬名"], dam=raw["母馬名"], damsire=raw["母父馬名"],
        source_sha256=zip_sha,
        new_horse_race_label=False,  # No verified debut indicator in horselist.
    )
    p = _prepare(entry)
    uid = _entry_id(*p.race)
    key = p.key
    if key is None:
        name = dob = pedigree_hash = name_birth = bloodline = identity = candidate = None
        status, reason = UNMATCHABLE, p.reason
    else:
        disc, name, dob, sire, dam, damsire = key
        pedigree_hash = _h(sire, dam, damsire)
        name_birth = _h(disc, name, dob)
        bloodline = _h(disc, dob, sire, dam, damsire)
        identity = _h(*key)
        candidate = _candidate_id(key)
        status, reason = CANDIDATE_ONLY, ""
    return (uid, ym, zip_sha, rownum, p.race_date, p.race[1], discipline,
            p.race[3], p.race[4], name, dob, pedigree_hash,
            name_birth, bloodline, identity, candidate, status, reason, None, None)


def _quarantine(conn):
    """Quarantine ALL conflicting entries, including those seen first."""
    conflict_queries = [
        ("name_birth", "name_birth_with_conflicting_pedigree",
         "SELECT name_birth_hash FROM entries WHERE name_birth_hash IS NOT NULL "
         "GROUP BY name_birth_hash HAVING COUNT(DISTINCT pedigree_hash) > 1"),
        ("bloodline", "same_birth_pedigree_different_names",
         "SELECT bloodline_hash FROM entries WHERE bloodline_hash IS NOT NULL "
         "GROUP BY bloodline_hash HAVING COUNT(DISTINCT name) > 1"),
        ("same_day", "same_candidate_multiple_same_day_entries",
         "SELECT identity_hash, race_date FROM entries WHERE identity_hash IS NOT NULL "
         "GROUP BY identity_hash, race_date HAVING COUNT(*) > 1"),
    ]
    for kind, reason, query in conflict_queries:
        if kind == "name_birth":
            conn.execute("UPDATE entries SET status=?, candidate_id=NULL, "
                         "reason=CASE WHEN reason='' THEN ? ELSE reason||';'||? END "
                         "WHERE name_birth_hash IN ("+query+")",
                         (AMBIGUOUS, reason, reason))
        elif kind == "bloodline":
            conn.execute("UPDATE entries SET status=?, candidate_id=NULL, "
                         "reason=CASE WHEN reason='' THEN ? ELSE reason||';'||? END "
                         "WHERE bloodline_hash IN ("+query+")",
                         (AMBIGUOUS, reason, reason))
        else:
            conn.execute("UPDATE entries SET status=?, candidate_id=NULL, "
                         "reason=CASE WHEN reason='' THEN ? ELSE reason||';'||? END "
                         "WHERE (identity_hash,race_date) IN ("+query+")",
                         (AMBIGUOUS, reason, reason))
    # An immutable (per registry build) retrospective first-observed anchor.
    conn.execute("""
       UPDATE entries SET
         first_observed_date = (
           SELECT MIN(other.race_date) FROM entries other
           WHERE other.identity_hash=entries.identity_hash
         )
       WHERE status=?
       """, (CANDIDATE_ONLY,))
    conn.execute("""
       UPDATE entries SET
         first_observed_entry_id = (
           SELECT MIN(other.entry_id) FROM entries other
           WHERE other.identity_hash=entries.identity_hash
             AND other.race_date=entries.first_observed_date
         )
       WHERE status=?
       """, (CANDIDATE_ONLY,))


def build_registry(history_root: Path, map_path: Path, output: Path,
                   first: str = FIRST_MONTH, last: str = LAST_MONTH) -> dict:
    months = months_between(first, last)
    disciplines = read_discipline_map(map_path)
    manifest, manifest_sha = load_manifest(history_root)
    map_sha = file_sha256(map_path)
    output = Path(output)
    if output.exists():
        raise FileExistsError("registry output exists; overwrite forbidden")
    if not output.parent.is_dir():
        raise ValueError("registry output parent directory must already exist")
    totals = Counter()
    per_month = []
    with tempfile.TemporaryDirectory(prefix=".horse-id-build-", dir=output.parent) as tempdir:
        # Keep the TemporaryDirectory itself in place for safe cleanup.
        staging = Path(tempdir) / "registry"
        staging.mkdir()
        db = staging / "candidate-registry.sqlite"
        with sqlite3.connect(db) as conn:
            conn.execute("PRAGMA journal_mode=DELETE")
            conn.execute("PRAGMA synchronous=FULL")
            conn.executescript(DDL)
            query = "INSERT INTO entries VALUES (" + ",".join("?" * 20) + ")"
            for ym in months:
                _, zip_sha, expected = checked_archive(history_root, ym, manifest)
                batch = []
                count = 0
                for raw, _ym, rownum in read_month(history_root, ym, manifest):
                    batch.append(_insert_row(raw, ym, rownum, zip_sha, disciplines))
                    count += 1
                    if len(batch) == 1000:
                        conn.executemany(query, batch)
                        batch.clear()
                if batch:
                    conn.executemany(query, batch)
                if count != expected:
                    raise ValueError("manifest and loaded row counts disagree")
                totals["rows"] += count
                per_month.append({"ym": ym, "source_sha256": zip_sha, "rows": count})
            _quarantine(conn)
            for status, count in conn.execute(
                    "SELECT status, COUNT(*) FROM entries GROUP BY status"):
                totals[status] = count
            totals["unique_candidate_ids"] = conn.execute(
                "SELECT COUNT(DISTINCT candidate_id) FROM entries "
                "WHERE status=?", (CANDIDATE_ONLY,)
            ).fetchone()[0]
            h = hashlib.sha256()
            for values in conn.execute(
                "SELECT entry_id,source_ym,source_zip_sha256,source_row_number,"
                "race_date,track,discipline,race_number,horse_number,"
                "candidate_id,status,reason,first_observed_date,"
                "first_observed_entry_id FROM entries ORDER BY entry_id"
            ):
                h.update((json.dumps(values, ensure_ascii=False, separators=(",", ":"))
                          + "\n").encode("utf-8"))
            content_sha = h.hexdigest()
            conn.commit()
        report = {
            "status": "DEVELOPMENT_CANDIDATE_ONLY_NOT_PRODUCTION_AUTHORIZED",
            "schema_version": SCHEMA_VERSION,
            "candidate_id_version": VERSION,
            "scope": {"first_month": first, "last_month": last},
            "source_manifest_sha256": manifest_sha,
            "discipline_evidence_map_sha256": map_sha,
            "entries_fingerprint_sha256": content_sha,
            "counts": dict(sorted(totals.items())),
            "monthly_sources": per_month,
            "pit": "HISTORICAL_AS_OF_NOT_ESTABLISHED",
            "horse_history_four_features": "NOT_PRODUCTION_APPROVED",
            "notes": [
                "Earliest NAR observation is not lifetime debut.",
                "Unknown JRA/overseas history is not counted as zero.",
                "Track/discipline mapping must be independently reviewed.",
                "Later archive snapshots are not original historical prediction-time evidence.",
                "Retrospective candidate registration must not rewrite earlier prediction snapshots.",
            ]
        }
        with (staging / "report.json").open("x", encoding="utf-8") as out:
            json.dump(report, out, ensure_ascii=False, indent=2, sort_keys=True)
            out.write("\n")
            out.flush()
            os.fsync(out.fileno())
        if output.exists():
            raise FileExistsError("registry output was created concurrently")
        staging.rename(output)
        # Only its child was renamed; TemporaryDirectory cleanup remains valid.
    return report


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--history-root", type=Path, required=True)
    p.add_argument("--discipline-map", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--first", default=FIRST_MONTH)
    p.add_argument("--last", default=LAST_MONTH)
    args = p.parse_args()
    result = build_registry(args.history_root, args.discipline_map,
                            args.out, args.first, args.last)
    print(json.dumps({
        "status": result["status"], "counts": result["counts"],
        "fingerprint": result["entries_fingerprint_sha256"],
        "output": str(args.out)
    }, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
