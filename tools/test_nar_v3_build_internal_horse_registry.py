"""Synthetic offline only: no real 2025/2026, network or user data."""
from __future__ import annotations
import csv
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

from nar_v3_build_internal_horse_registry import (
    build_registry, months_between, read_discipline_map,
)

HEADER = ["競馬場", "競走年月日", "レース番号", "馬番",
          "馬名", "生年月日", "父馬名", "母馬名", "母父馬名"]


def record(**kwargs):
    fields = dict(zip(
        HEADER, ["大井", "19980114", "1", "1",
                 "馬A", "19950101", "父A", "母A", "母父A"]
    ))
    fields.update(kwargs)
    return fields


def csv_bytes(rows):
    sio = io.StringIO()
    w = csv.DictWriter(sio, fieldnames=HEADER, lineterminator="\n")
    w.writeheader()
    w.writerows(rows)
    return sio.getvalue().encode("utf-8-sig")


class InternalNARRegistryImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "nar-history"
        (self.archive / "manifests").mkdir(parents=True)
        self.map_file = self.root / "approved-map.json"
        self.map_file.write_text(json.dumps({
            "version": 1,
            "records": [
                {"track": "大井", "discipline": "FLAT",
                 "evidence_ref": "synthetic-test-only"},
                {"track": "川崎", "discipline": "FLAT",
                 "evidence_ref": "synthetic-test-only"},
                {"track": "帯広ば", "discipline": "BANEI",
                 "evidence_ref": "synthetic-test-only"},
            ]
        }, ensure_ascii=False), encoding="utf-8")
        self.out = self.root / "candidate-out"

    def write_months(self, months, *, corrupt_sha=None, stated_rows=None):
        meta = []
        for ym, entries in sorted(months.items()):
            location = self.archive / "monthly" / ym[:4]
            location.mkdir(parents=True, exist_ok=True)
            zpath = location / f"{ym}_race.zip"
            with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr(f"{ym}_racelist.csv", "raceheader\n".encode())
                z.writestr(f"{ym}_horselist.csv", csv_bytes(entries))
                z.writestr(f"{ym}_payback.csv", "paybackheader\n".encode())
            digest = hashlib.sha256(zpath.read_bytes()).hexdigest()
            if corrupt_sha == ym:
                digest = "0" * 64
            meta.append(dict(
                ym=ym, status="OK", bytes=str(zpath.stat().st_size),
                sha256=digest, horselist_rows=str(
                    stated_rows if stated_rows is not None else len(entries)
                ),
            ))
        with (self.archive / "manifests" / "months.csv").open(
                "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=[
                "ym", "status", "bytes", "sha256", "horselist_rows"])
            w.writeheader()
            w.writerows(meta)

    def run_import(self, first="199801", last="199802"):
        return build_registry(self.archive, self.map_file,
                              self.out, first, last)

    def fetch(self, sql):
        with sqlite3.connect(self.out / "candidate-registry.sqlite") as conn:
            return conn.execute(sql).fetchall()

    def test_two_months_oldest_entry_anchor_and_immutable_output(self):
        self.write_months({
            "199801": [record()],
            "199802": [record(競馬場="川崎", 競走年月日="19980210",
                              レース番号="07", 馬番="03")]
        })
        report = self.run_import()
        self.assertEqual(2, report["counts"]["rows"])
        self.assertEqual(2, report["counts"]["CANDIDATE_ONLY"])
        self.assertEqual(1, report["counts"]["unique_candidate_ids"])
        self.assertEqual(64, len(report["entries_fingerprint_sha256"]))
        self.assertEqual("HISTORICAL_AS_OF_NOT_ESTABLISHED", report["pit"])
        rows = self.fetch(
            "SELECT candidate_id,status,first_observed_date,"
            "first_observed_entry_id FROM entries ORDER BY race_date"
        )
        self.assertEqual(rows[0][0], rows[1][0])
        self.assertTrue(rows[0][0].startswith("nar-candidate-v1-"))
        self.assertTrue(all(r[1] == "CANDIDATE_ONLY" for r in rows))
        self.assertTrue(all(r[2] == "19980114" for r in rows))
        self.assertEqual(rows[0][3], rows[1][3])
        self.assertTrue((self.out / "report.json").is_file())
        self.assertEqual(report, json.loads(
            (self.out / "report.json").read_text(encoding="utf-8")))
        with self.assertRaises(FileExistsError):
            self.run_import()

    def test_conflicting_pedigree_quarantines_both_months(self):
        self.write_months({
            "199801": [record()],
            "199802": [record(競走年月日="19980210", 父馬名="父B", 馬番="5")]
        })
        report = self.run_import()
        self.assertEqual(2, report["counts"]["AMBIGUOUS"])
        self.assertEqual(0, report["counts"]["unique_candidate_ids"])
        for uid, reason in self.fetch(
                "SELECT candidate_id,reason FROM entries"):
            self.assertIsNone(uid)
            self.assertIn("name_birth_with_conflicting_pedigree", reason)

    def test_cross_sport_names_never_join(self):
        self.write_months({
            "199801": [record(), record(競馬場="帯広ば", 馬番="2")],
            "199802": [],
        })
        report = self.run_import()
        self.assertEqual(2, report["counts"]["unique_candidate_ids"])
        self.assertEqual(2, len(set(x[0] for x in self.fetch(
            "SELECT candidate_id FROM entries"))))

    def test_same_candidate_multiple_races_one_day_quarantined(self):
        self.write_months({
            "199801": [record(), record(競馬場="川崎", レース番号="2", 馬番="3")],
            "199802": []
        })
        report = self.run_import()
        self.assertEqual(2, report["counts"]["AMBIGUOUS"])

    def test_missing_identity_is_unknown_not_zero(self):
        self.write_months({
            "199801": [record(母馬名=""), record(馬番="2", 生年月日="20180101")],
            "199802": []
        })
        report = self.run_import()
        self.assertEqual(2, report["counts"]["UNMATCHABLE"])
        self.assertEqual([(None, "UNMATCHABLE"), (None, "UNMATCHABLE")],
                         self.fetch("SELECT candidate_id,status FROM entries ORDER BY horse_number"))

    def test_real_archive_style_missing_sire_and_spaced_dam_not_auto_joined(self):
        # 1998-2001 source audit surfaced one empty sire and a leading-
        # whitespace dam value, both of which must remain UNKNOWN.
        # Synthetic rows reproduce only shapes, not original horse data.
        self.write_months({
            "199801": [
                record(父馬名=""),
                record(馬番="2", 馬名="別馬", 母馬名=" 初"),
                record(馬番="3", 馬名="同馬", 母馬名="母A"),
            ],
            "199802": [
                record(競走年月日="19980210", 馬番="7",
                       馬名="同馬", 母馬名=" 初"),
            ],
        })
        report = self.run_import()
        self.assertEqual(3, report["counts"]["UNMATCHABLE"])
        self.assertEqual(1, report["counts"]["CANDIDATE_ONLY"])
        self.assertEqual(1, report["counts"]["unique_candidate_ids"])
        self.assertEqual(3, self.fetch(
            "SELECT COUNT(*) FROM entries WHERE candidate_id IS NULL"
        )[0][0])
        self.assertEqual(4, report["counts"]["rows"])
        self.assertEqual([(None, None)] * 3, self.fetch(
            "SELECT first_observed_date,first_observed_entry_id "
            "FROM entries WHERE status='UNMATCHABLE' ORDER BY entry_id"
        ))
        self.assertEqual(1, self.fetch(
            "SELECT COUNT(*) FROM entries "
            "WHERE status='CANDIDATE_ONLY' "
            "AND first_observed_date='19980114'"
        )[0][0])

    def test_undocumented_track_fails_without_any_final_output(self):
        self.write_months({
            "199801": [record(競馬場="未確認競馬場")],
            "199802": []
        })
        with self.assertRaisesRegex(ValueError, "missing verified discipline"):
            self.run_import()
        self.assertFalse(self.out.exists())

    def test_missing_month_fails_without_final_output(self):
        self.write_months({"199801": [record()]})
        with self.assertRaisesRegex(ValueError, "manifest"):
            self.run_import()
        self.assertFalse(self.out.exists())

    def test_bad_archive_sha_fails_without_final_output(self):
        self.write_months({"199801": [record()], "199802": []},
                          corrupt_sha="199802")
        with self.assertRaisesRegex(ValueError, "SHA mismatch"):
            self.run_import()
        self.assertFalse(self.out.exists())

    def test_manifest_rowcount_fails_without_final_output(self):
        self.write_months({"199801": [record()], "199802": []},
                          stated_rows=5)
        with self.assertRaisesRegex(ValueError, "row-count mismatch"):
            self.run_import()
        self.assertFalse(self.out.exists())

    def test_race_month_mismatch_is_blocked(self):
        self.write_months({"199801": [record(競走年月日="19980214")],
                           "199802": []})
        with self.assertRaisesRegex(ValueError, "source month mismatch"):
            self.run_import()
        self.assertFalse(self.out.exists())

    def test_2025_2026_rejected_before_reading_data(self):
        for last in ("202501", "202612"):
            with self.subTest(last=last):
                with self.assertRaisesRegex(ValueError, "TEST/OOT"):
                    self.run_import("202412", last)
        self.assertFalse(self.out.exists())

    def test_bad_discipline_map_fails_closed(self):
        self.map_file.write_text(
            '{"version":1,"version":1,"records":[]}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate JSON field"):
            read_discipline_map(self.map_file)

    def test_historical_31_source_track_dev_evidence_map(self):
        # Real history scope was inventoried separately by raw ZIP SHA+CRC.
        # Evidence mapping is an auditable development input, NOT an
        # independently verified horse UID or production feature grant.
        mapping = Path(__file__).with_name(
            "phase08_historical_venue_dev_map_v1.json"
        )
        result = read_discipline_map(mapping)
        self.assertEqual(31, len(result))
        self.assertEqual({
            "北見ば", "岩見ば", "帯広ば", "旭川ば"
        }, {name for name, value in result.items() if value == "BANEI"})
        self.assertEqual(27, sum(value == "FLAT" for value in result.values()))
        self.assertEqual("FLAT", result["旭川"])
        self.assertEqual("BANEI", result["旭川ば"])
        self.assertNotIn("帯広", result)
        self.assertNotIn("岩見沢", result)
        obj = json.loads(mapping.read_text(encoding="utf-8"))
        self.assertTrue(all(
            "https://www.keiba.go.jp/" in row["evidence_ref"]
            for row in obj["records"]
        ))
        self.assertTrue(all(
            "city.obihiro.hokkaido.jp" in row["evidence_ref"]
            for row in obj["records"] if row["discipline"] == "BANEI"
        ))

    def test_years_include_full_1998_to_2024_in_strict_order(self):
        months = months_between("199801", "202412")
        self.assertEqual(324, len(months))
        self.assertEqual("199801", months[0])
        self.assertEqual("202412", months[-1])
        self.assertIn("200002", months)
        self.assertIn("202402", months)


if __name__ == "__main__":
    unittest.main(verbosity=2)
