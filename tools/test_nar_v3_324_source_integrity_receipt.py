"""Offline receipt consistency: NOT an Actions replay of any 324 raw ZIP bytes."""
import copy
import csv
from hashlib import sha256
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
RECEIPT = ROOT / "tools" / "phase08_source_324_integrity_receipt_v1.json"
MANIFEST = ROOT / "data-manifests" / "nar-history" / "months.csv"


def assert_receipt_consistent(r, manifest_bytes):
    assert set(r)=={
        "schema_version", "scope", "source_manifest_sha256",
        "source_manifest_git_blob_sha", "verified_from_month",
        "verified_through_month", "verified_zip_count", "verified_zip_bytes",
        "verified_horselist_rows", "monthly_zip_sha256_digest",
        "newly_verified_gap", "previously_verified_60", "verified_checks",
        "audit_environment", "drive_original_acquired_after_historical_races",
        "repository_ci_loaded_324_original_zip_bytes",
        "generated_source_manifest_is_historical_asof_evidence",
        "horse_uid_identity_approved", "cross_authority_history_coverage_approved",
        "historical_2021_2024_pit_approved",
        "four_horse_features_production_authorized",
        "test_2025_or_oot_2026_training_evaluated",
    }
    assert r["schema_version"] == 1
    assert r["scope"] == "PHASE08_ARCHIVED_NAR_MONTHLY_ZIP_INTEGRITY_ONLY"
    assert r["source_manifest_sha256"] == sha256(manifest_bytes).hexdigest()
    assert r["source_manifest_sha256"] == (
        "7275040fc666407382c28eee47e2ef9882dbfc2781dd42b778ccd0a88c2c26af")
    assert r["source_manifest_git_blob_sha"] == (
        "3eea220e8e2dfe558fcb6c542de4dd94602939a7")
    for key in ("repository_ci_loaded_324_original_zip_bytes",
                "generated_source_manifest_is_historical_asof_evidence",
                "horse_uid_identity_approved", "cross_authority_history_coverage_approved",
                "historical_2021_2024_pit_approved",
                "four_horse_features_production_authorized",
                "test_2025_or_oot_2026_training_evaluated"):
        assert r[key] is False, key
    assert r["drive_original_acquired_after_historical_races"] is True
    import io
    rows=list(csv.DictReader(io.StringIO(manifest_bytes.decode("utf-8-sig"))))
    def scoped(start,end):
        return [x for x in rows if start<=x["ym"]<=end]
    def actual(start,end):
        sub=scoped(start,end)
        assert all(x["status"]=="OK" for x in sub)
        assert len({x["ym"] for x in sub})==len(sub)
        h=sha256()
        for x in sub:
            assert len(x["sha256"])==64
            h.update((x["ym"]+":"+x["sha256"]+"\n").encode("ascii"))
        return len(sub),sum(int(x["bytes"]) for x in sub),(
            sum(int(x["horselist_rows"]) for x in sub)),h.hexdigest()
    assert r["verified_from_month"]=="199801"
    assert r["verified_through_month"]=="202412"
    assert actual("199801","202412")==(
        r["verified_zip_count"],r["verified_zip_bytes"],
        r["verified_horselist_rows"],r["monthly_zip_sha256_digest"])
    new=r["newly_verified_gap"]
    assert (new["from_month"],new["through_month"])==("199901","202012")
    assert actual("199901","202012")==(
        new["zip_count"],new["zip_bytes"],new["horselist_rows"],
        new["monthly_zip_sha256_digest"])
    first=actual("199801","199812")
    second=actual("202101","202412")
    old=r["previously_verified_60"]
    assert (first[0]+second[0],first[1]+second[1],first[2]+second[2])==(
        old["zip_count"],old["zip_bytes"],old["horselist_rows"])
    h=sha256()
    for x in scoped("199801","199812")+scoped("202101","202412"):
        h.update((x["ym"]+":"+x["sha256"]+"\n").encode("ascii"))
    assert h.hexdigest()==old["monthly_zip_sha256_digest"]
    assert len(rows)>324  # Historical archive manifest retains 2025/26, unread.
    assert "202501" not in [x["ym"] for x in scoped("199801","202412")]
    return True


class FullArchiveReceiptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.receipt=json.loads(RECEIPT.read_text(encoding="utf-8"))
        cls.manifest=MANIFEST.read_bytes()

    def test_receipt_matches_exact_pinned_source_manifest(self):
        self.assertTrue(assert_receipt_consistent(self.receipt,self.manifest))

    def test_reject_modified_archive_sha_digest(self):
        x=copy.deepcopy(self.receipt)
        x["monthly_zip_sha256_digest"]="0"*64
        with self.assertRaises(AssertionError):
            assert_receipt_consistent(x,self.manifest)

    def test_reject_false_full_pit_or_production_authorization(self):
        for k in ("historical_2021_2024_pit_approved",
                  "horse_uid_identity_approved",
                  "four_horse_features_production_authorized",
                  "repository_ci_loaded_324_original_zip_bytes"):
            with self.subTest(k=k):
                x=copy.deepcopy(self.receipt)
                x[k]=True
                with self.assertRaises(AssertionError):
                    assert_receipt_consistent(x,self.manifest)

    def test_reject_wrong_gap_counts(self):
        x=copy.deepcopy(self.receipt)
        x["newly_verified_gap"]["horselist_rows"]-=1
        with self.assertRaises(AssertionError):
            assert_receipt_consistent(x,self.manifest)

    def test_reject_modified_manifest_byte(self):
        with self.assertRaises(AssertionError):
            assert_receipt_consistent(self.receipt,self.manifest+b"\n")


if __name__=="__main__":
    unittest.main()
