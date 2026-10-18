"""Pinned offline audit receipt, NOT a CI rerun of 324 source ZIPs.

A successful unit run here certifies code/manifest/receipt consistency ONLY.
"""
import copy
import csv
from hashlib import sha1, sha256
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
RECEIPT=ROOT/"tools/phase08_internal_horse_registry_reproduction_v1.json"
ORIGINAL_RECEIPT=ROOT/"tools/phase08_source_324_integrity_receipt_v1.json"
MANIFEST=ROOT/"data-manifests/nar-history/months.csv"
PINNED_SOURCES={
    "registry_generator_blob_sha":"tools/nar_v3_build_internal_horse_registry.py",
    "candidate_id_generator_blob_sha":"tools/nar_v3_internal_horse_id.py",
    "parser_blob_sha":"tools/audit_nar_horse_identity_2021_2023.py",
    "discipline_map_blob_sha":"tools/phase08_historical_venue_dev_map_v1.json",
    "github_manifest_blob_sha":"data-manifests/nar-history/months.csv",
}
FINGERPRINT="6c63a2e558703be9d4dd789f32b9ba805d21575946f0088561129c5aa7b67432"


def git_blob_sha(raw):
    return sha1(b"blob "+str(len(raw)).encode("ascii")+b"\x00"+raw).hexdigest()


def verify_receipt(record, original, sources):
    assert record["scope"]=="PHASE08_NAR_INTERNAL_HORSE_CANDIDATE_REGISTRY_REPRODUCTION_DEVELOPMENT_ONLY"
    assert record["schema_version"]==1
    assert record["first_month"]=="199801" and record["last_month"]=="202412"
    for key,path in PINNED_SOURCES.items():
        assert record[key]==git_blob_sha(sources[path]), (key,path)
    assert record["months_loaded"]==original["verified_zip_count"]==324
    assert record["rows_loaded"]==original["verified_horselist_rows"]==4574037
    assert record["source_manifest_drive_sha256"]==original["source_manifest_sha256"]
    assert record["github_manifest_blob_sha"]==original["source_manifest_git_blob_sha"]
    assert sum(record["counts"].values())==record["rows_loaded"]
    assert record["counts"]=={
        "CANDIDATE_ONLY":4573242,"AMBIGUOUS":354,"UNMATCHABLE":441}
    assert record["unique_candidate_ids"]==164934
    assert record["entries_fingerprint_sha256"]==FINGERPRINT
    assert record["previous_reference_fingerprint_sha256"]==FINGERPRINT
    assert record["all_candidate_first_observed_anchors_present"] is True
    assert record["quarantine_reason_counts"]=={
        "candidate attributes missing or invalid":441,
        "same_birth_pedigree_different_names":354}
    assert record["regeneration_method"]=="independent_one_off_python_sqlite_reproduction_of_recovered_GitHub_registry_logic"
    for key in ("raw_sqlite_file_committed","github_actions_loads_original_324_month_archives",
                "independent_official_horse_uid_certified",
                "cross_authority_complete_history_certified",
                "historical_2021_2024_pit_certified",
                "production_four_history_features_authorized",
                "locked_2025_test_or_2026_oot_used"):
        assert record[key] is False,key
    rows=list(csv.DictReader(sources["data-manifests/nar-history/months.csv"].decode("utf-8-sig").splitlines()))
    train_scope=[r for r in rows if "199801"<=r["ym"]<="202412"]
    assert len(train_scope)==324 and len({r["ym"] for r in train_scope})==324
    assert all(r["status"]=="OK" for r in train_scope)
    assert sum(int(r["horselist_rows"]) for r in train_scope)==record["rows_loaded"]
    hashlist=sha256()
    for row in train_scope:
        hashlist.update((row["ym"]+":"+row["sha256"]+"\n").encode("ascii"))
    assert hashlist.hexdigest()==original["monthly_zip_sha256_digest"]
    return True


class ReceiptQA(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.receipt=json.loads(RECEIPT.read_text(encoding="utf-8"))
        cls.original=json.loads(ORIGINAL_RECEIPT.read_text(encoding="utf-8"))
        cls.sources={p:(ROOT/p).read_bytes() for p in PINNED_SOURCES.values()}

    def check(self,record,sources=None):
        return verify_receipt(record,self.original,sources or self.sources)

    def test_exact_old_registry_fingerprint_and_all_counts(self):
        self.assertTrue(self.check(self.receipt))

    def test_source_code_or_discipline_map_change_is_detected(self):
        for path in PINNED_SOURCES.values():
            with self.subTest(path=path):
                new=dict(self.sources)
                new[path]=new[path]+b"#unexpected-change"
                with self.assertRaises(AssertionError):
                    self.check(self.receipt,new)

    def test_fingerprint_or_candidate_count_drift_rejected(self):
        for key,value in (
            ("entries_fingerprint_sha256","0"*64),
            ("unique_candidate_ids",164933),
            ("rows_loaded",4574036),
        ):
            with self.subTest(key=key):
                rec=copy.deepcopy(self.receipt);rec[key]=value
                with self.assertRaises(AssertionError):
                    self.check(rec)

    def test_false_production_and_pit_claims_rejected(self):
        for key in ("independent_official_horse_uid_certified",
                    "historical_2021_2024_pit_certified",
                    "production_four_history_features_authorized",
                    "github_actions_loads_original_324_month_archives",
                    "locked_2025_test_or_2026_oot_used"):
            with self.subTest(key=key):
                rec=copy.deepcopy(self.receipt);rec[key]=True
                with self.assertRaises(AssertionError):
                    self.check(rec)

    def test_missing_anchor_or_quarantine_reason_change_rejected(self):
        rec=copy.deepcopy(self.receipt);rec["all_candidate_first_observed_anchors_present"]=False
        with self.assertRaises(AssertionError):
            self.check(rec)
        rec=copy.deepcopy(self.receipt);rec["quarantine_reason_counts"]["same_birth_pedigree_different_names"]-=1
        with self.assertRaises(AssertionError):
            self.check(rec)


if __name__=="__main__":
    unittest.main()
