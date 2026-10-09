#!/usr/bin/env python3
"""Isolated offline tests for audit_nar_horse_identity_2021_2023.py.


Synthetic 2021 only; no live NAR access, no TEST/OOT, no model changes.
"""
import csv
import hashlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path


from audit_nar_horse_identity_2021_2023 import audit


HEADER = [
    "競馬場", "競走年月日", "レース番号", "馬番",
    "馬名", "生年月日", "父馬名", "母馬名", "母父馬名"
]




def horse(**changes):
    value = dict(zip(HEADER, (
        "大井", "20210110", "01", "001", "サンプル",
        "20170101", "父A", "母A", "母父A"
    )))
    value.update(changes)
    return value




def csv_bytes(header, rows):
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=header, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue().encode("utf-8")




class IdentityAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "nar-history"


    def source(self, rows, extra=False, stated_rows=None):
        ym = "202101"
        zip_path = self.root / "monthly" / "2021" / f"{ym}_race.zip"
        zip_path.parent.mkdir(parents=True, exist_ok=True)
        values = {
            f"{ym}_racelist.csv": b"\xef\xbb\xbf" + csv_bytes(
                ["競馬場", "競走年月日", "レース番号"],
                [{"競馬場": "大井", "競走年月日": "20210110",
                  "レース番号": "1"}]
            ),
            f"{ym}_horselist.csv": csv_bytes(HEADER, rows),
            f"{ym}_payback.csv": b"test-header\n",
        }
        if extra:
            values["unwanted.txt"] = b"bad"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, content in values.items():
                archive.writestr(name, content)


        manifest = self.root / "manifests" / "months.csv"
        manifest.parent.mkdir(parents=True, exist_ok=True)
        with manifest.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=[
                "ym", "status", "bytes", "sha256", "racelist_rows",
                "horselist_rows", "payback_rows"
            ])
            writer.writeheader()
            writer.writerow({
                "ym": ym,
                "status": "OK",
                "bytes": str(zip_path.stat().st_size),
                "sha256": hashlib.sha256(zip_path.read_bytes()).hexdigest(),
                "racelist_rows": "1",
                "horselist_rows": str(len(rows) if stated_rows is None
                                       else stated_rows),
                "payback_rows": "0",
            })
        return zip_path


    def check(self):
        return audit(self.root, ("202101",))


    def test_leading_zero_keys_accepted(self):
        self.source([horse()])
        result = self.check()
        self.assertEqual(1, result["rows_audited"])
        self.assertEqual(1, result["rows_with_complete_candidate_identity"])


    def test_leading_zero_duplicate_caught(self):
        self.source([horse(), horse(レース番号="1", 馬番="1")])
        with self.assertRaisesRegex(ValueError, "duplicate race entry"):
            self.check()


    def test_same_identity_two_races_same_day_flagged(self):
        self.source([horse(), horse(レース番号="02")])
        result = self.check()
        self.assertEqual(1, result["candidate_conflict_groups"][
            "same_complete_identity_multiple_races_same_day"])


    def test_missing_biography_not_accepted(self):
        self.source([horse(母馬名="")])
        result = self.check()
        self.assertEqual(0, result["rows_with_complete_candidate_identity"])
        self.assertEqual(1, result["rows_missing_identity_fields"]["母馬名"])


    def test_invalid_birthday_rejected_from_candidates(self):
        self.source([horse(生年月日="20190229")])
        result = self.check()
        self.assertEqual(1, result["rows_invalid_identity_fields"]["生年月日"])
        self.assertEqual(0, result["rows_with_complete_candidate_identity"])


    def test_future_birthday_rejected_from_candidates(self):
        self.source([horse(生年月日="20210111")])
        result = self.check()
        self.assertEqual(1, result["rows_invalid_identity_fields"]["生年月日が出走日以降"])


    def test_same_name_distinct_birthdates_flagged(self):
        self.source([
            horse(),
            horse(馬番="2", 生年月日="20180101"),
        ])
        result = self.check()
        self.assertEqual(1, result["candidate_conflict_groups"][
            "name_multiple_birthdates"])


    def test_same_name_birthdate_different_sire_flagged(self):
        self.source([
            horse(),
            horse(馬番="2", 父馬名="父B"),
        ])
        result = self.check()
        self.assertEqual(1, result["candidate_conflict_groups"][
            "same_name_birthdate_multiple_pedigrees"])


    def test_zip_sha_mismatch_fails_closed(self):
        path = self.source([horse()])
        with path.open("ab") as stream:
            stream.write(b"tampered")
        with self.assertRaisesRegex(ValueError, "SHA mismatch|size"):
            self.check()


    def test_extra_zip_member_fails_closed(self):
        self.source([horse()], extra=True)
        with self.assertRaisesRegex(ValueError, "ZIP members"):
            self.check()


    def test_manifest_count_mismatch_fails_closed(self):
        self.source([horse()], stated_rows=2)
        with self.assertRaisesRegex(ValueError, "row-count mismatch"):
            self.check()


    def test_2025_test_forbidden(self):
        with self.assertRaisesRegex(ValueError, "restricted"):
            audit(self.root, ("202501",))


    def test_duplicate_month_forbidden(self):
        with self.assertRaisesRegex(ValueError, "restricted"):
            audit(self.root, ("202101", "202101"))


    def test_does_not_certify_identity_or_pit(self):
        self.source([horse()])
        result = self.check()
        self.assertEqual("IDENTITY_NOT_PROVEN", result["status"])
        self.assertEqual("NAR HISTORICAL_MONTHLY 2021-2023 ONLY",
                         result["scope"])
        self.assertTrue(any("NOT established" in s
                            for s in result["limitations"]))


    def test_zero_horse_number_fails_closed(self):
        self.source([horse(馬番="000")])
        with self.assertRaisesRegex(ValueError, "invalid 馬番"):
            self.check()




if __name__ == "__main__":
    unittest.main(verbosity=2)