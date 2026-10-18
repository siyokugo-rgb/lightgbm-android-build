"""Synthetic regression; only 1998/2021 examples, no locked TEST/OOT."""
import csv
from hashlib import sha256
import io
import tempfile
import unittest
from pathlib import Path
import zipfile

from audit_nar_horse_identity_2021_2023 import REQUIRED
from nar_v3_source_byte_replay import replay_months


class ArchiveReplayReceiptQA(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "manifests").mkdir()
        self.entries = {}

    def put(self, ym, *, rows=1, bad_member=False):
        path = self.root / "monthly" / ym[:4] / (ym + "_race.zip")
        path.parent.mkdir(parents=True, exist_ok=True)
        buf = io.StringIO(newline="")
        w = csv.writer(buf)
        w.writerow(REQUIRED)
        for i in range(rows):
            values = {
                "競馬場": "大井", "競走年月日": ym + "15",
                "レース番号": "1", "馬番": str(i + 1),
                "馬名": "馬A", "生年月日": "19940101",
                "父馬名": "父A", "母馬名": "母A", "母父馬名": "母父A",
            }
            w.writerow([values[k] for k in REQUIRED])
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as z:
            z.writestr(ym + "_racelist.csv", "stub")
            z.writestr(ym + "_horselist.csv", buf.getvalue().encode("utf-8-sig"))
            z.writestr(ym + "_payback.csv", "stub")
        if bad_member:
            data = path.read_bytes()
            needle = "馬A".encode()
            assert needle in data
            data = data.replace(needle, b"ABC", 1)
            path.write_bytes(data)
        self.entries[ym] = {
            "ym": ym, "status": "OK", "bytes": str(path.stat().st_size),
            "sha256": sha256(path.read_bytes()).hexdigest(),
            "horselist_rows": str(rows),
        }
        return path

    def manifest_sha(self):
        p = self.root / "manifests" / "months.csv"
        with p.open("w", encoding="utf-8", newline="") as file:
            w = csv.DictWriter(file, fieldnames=[
                "ym", "status", "bytes", "sha256", "horselist_rows"])
            w.writeheader()
            w.writerows(self.entries[k] for k in sorted(self.entries))
        return sha256(p.read_bytes()).hexdigest()

    def test_two_month_receipt_is_reproducible_and_not_production(self):
        self.put("199801")
        self.put("199802", rows=2)
        pin = self.manifest_sha()
        a = replay_months(self.root, "199801", "199802", pin)
        b = replay_months(self.root, "199801", "199802", pin)
        self.assertEqual(a, b)
        self.assertEqual(a["horselist_rows"], 3)
        self.assertEqual(a["verified_months"], 2)
        self.assertEqual(a["monthly_zip_shas_digest"], sha256(
            "".join(ym + ":" + self.entries[ym]["sha256"] + "\n"
                    for ym in ("199801", "199802")).encode()).hexdigest())
        for key in ("individual_horse_uid_verified",
                    "historical_2021_2024_asof_verified",
                    "all_authority_history_coverage_verified",
                    "production_four_features_authorized",
                    "locked_2025_test_or_2026_oot_used"):
            self.assertFalse(a[key])

    def test_2025_test_and_2026_oot_rejected_without_opening(self):
        for first, last in (("202501", "202501"),
                            ("202601", "202612"),
                            ("202412", "202501")):
            with self.subTest(first=first, last=last):
                with self.assertRaisesRegex(ValueError, "TEST/OOT"):
                    replay_months(self.root, first, last, "0" * 64)

    def test_missing_year_month_rejected(self):
        self.put("199801")
        pin = self.manifest_sha()
        with self.assertRaisesRegex(ValueError, "missing/invalid OK manifest"):
            replay_months(self.root, "199801", "199802", pin)

    def test_expected_manifest_digest_must_match_exactly(self):
        self.put("199801")
        self.manifest_sha()
        with self.assertRaisesRegex(ValueError, "source-manifest SHA mismatch"):
            replay_months(self.root, "199801", "199801", "0" * 64)
        with self.assertRaisesRegex(ValueError, "invalid source-manifest"):
            replay_months(self.root, "199801", "199801", "bad")

    def test_source_zip_digest_corruption_rejected(self):
        path = self.put("199801")
        pin = self.manifest_sha()
        path.write_bytes(path.read_bytes() + b"x")
        with self.assertRaisesRegex(ValueError, "ZIP size"):
            replay_months(self.root, "199801", "199801", pin)

    def test_crc_failure_even_with_current_outer_sha(self):
        self.put("199801", bad_member=True)
        pin = self.manifest_sha()
        with self.assertRaisesRegex(ValueError, "CRC failure"):
            replay_months(self.root, "199801", "199801", pin)

    def test_wrong_row_count_rejected(self):
        self.put("199801", rows=1)
        self.entries["199801"]["horselist_rows"] = "2"
        pin = self.manifest_sha()
        with self.assertRaisesRegex(ValueError, "row-count mismatch"):
            replay_months(self.root, "199801", "199801", pin)


if __name__ == "__main__":
    unittest.main()
