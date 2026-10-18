"""Offline synthetic tests only: no monthly archive, TEST, OOT or model."""
from dataclasses import FrozenInstanceError, replace
import unittest

from nar_v3_internal_horse_id import (
    AMBIGUOUS, CANDIDATE_ONLY, UNMATCHABLE, HorseEntryCandidate,
    resolve_development_candidates,
)

SHA_A = "a" * 64
SHA_B = "b" * 64


def row(**values):
    base = dict(
        entry_id="race-A#1", race_date="20210110", track="大井",
        race_number="1", horse_number="1", discipline="FLAT",
        horse_name="馬A", birth_date="20180101",
        sire="父A", dam="母A", damsire="母父A",
        source_sha256=SHA_A,
    )
    base.update(values)
    return HorseEntryCandidate(**base)


def ids(*entries):
    return resolve_development_candidates(entries).decisions


class InternalHorseCandidateIdTest(unittest.TestCase):
    def test_same_five_attributes_across_tracks_have_stable_id(self):
        a = row()
        b = row(entry_id="race-B#3", race_date="20210210",
                track="川崎", race_number="7", horse_number="3",
                source_sha256=SHA_B)
        decisions = ids(b, a)
        self.assertEqual([CANDIDATE_ONLY] * 2, [d.status for d in decisions])
        self.assertEqual(decisions[0].candidate_id, decisions[1].candidate_id)
        self.assertTrue(decisions[0].candidate_id.startswith("nar-candidate-v1-"))

    def test_earliest_historical_observation_seeds_nar_id_before_training(self):
        a = row(entry_id="oldest-1998", race_date="19980131", track="大井",
                birth_date="19950101")
        b = row(entry_id="train-2021", race_date="20210110",
                birth_date="19950101",
                track="川崎", race_number="3", horse_number="7")
        registry = resolve_development_candidates([b, a])
        self.assertEqual(2, registry.counts[CANDIDATE_ONLY])
        for d in registry.decisions:
            self.assertEqual("19980131", d.first_observed_date)
            self.assertEqual("oldest-1998", d.first_observed_entry_id)
            self.assertEqual("EARLIEST_OBSERVED_ONLY", d.first_observed_origin)
        self.assertEqual(registry.decisions[0].candidate_id,
                         registry.decisions[1].candidate_id)

    def test_nar_new_horse_race_label_is_not_certified_lifetime_debut(self):
        a = row(new_horse_race_label=True)
        b = row(entry_id="next", race_date="20210211",
                track="川崎", horse_number="2")
        registry = resolve_development_candidates([b, a])
        self.assertTrue(all(d.first_observed_origin == "NAR_NEW_HORSE_RACE_LABEL"
                            for d in registry.decisions))
        self.assertEqual(registry.counts[CANDIDATE_ONLY], 2)

    def test_contradictory_later_or_repeated_new_horse_label_is_quarantined(self):
        a = row()
        b = row(entry_id="next", race_date="20210211",
                track="川崎", horse_number="2", new_horse_race_label=True)
        for pair in ((a, b), (replace(a, new_horse_race_label=True), b)):
            with self.subTest(pair=pair):
                d = ids(*pair)
                self.assertTrue(all(x.status == AMBIGUOUS for x in d))
                self.assertTrue(all("contradictory_new_horse_race_label"
                                    in x.reasons for x in d))

    def test_new_horse_label_wrong_type_rejected(self):
        with self.assertRaisesRegex(ValueError, "new_horse_race_label"):
            ids(row(new_horse_race_label="True"))

    def test_outside_available_1998_archive_rejected(self):
        with self.assertRaisesRegex(ValueError, "outside development"):
            ids(row(race_date="19971231"))

    def test_different_birth_date_never_links_on_name_alone(self):
        d = ids(row(), row(entry_id="race2", race_date="20210210",
                          birth_date="20170101", horse_number="5"))
        self.assertTrue(all(x.status == CANDIDATE_ONLY for x in d))
        self.assertNotEqual(d[0].candidate_id, d[1].candidate_id)

    def test_flat_banei_boundary_never_links_or_infers_from_track(self):
        d = ids(row(), row(entry_id="race2", race_date="20210210",
                          track="帯広ば", discipline="BANEI", horse_number="2"))
        self.assertNotEqual(d[0].candidate_id, d[1].candidate_id)
        self.assertTrue(all(x.status == CANDIDATE_ONLY for x in d))

    def test_unverified_discipline_rejected(self):
        with self.assertRaisesRegex(ValueError, "discipline"):
            ids(row(discipline="UNKNOWN"))
        with self.assertRaisesRegex(ValueError, "discipline"):
            ids(row(discipline=""))

    def test_same_name_birth_conflicting_pedigree_quarantines_both(self):
        d = ids(row(), row(entry_id="race2", race_date="20210210",
                          sire="父B", horse_number="3"))
        self.assertEqual([AMBIGUOUS, AMBIGUOUS], [x.status for x in d])
        self.assertTrue(all(x.candidate_id is None for x in d))
        self.assertTrue(all("name_and_birth_with_conflicting_pedigree"
                            in x.reasons for x in d))

    def test_possible_rename_same_pedigree_quarantines_not_auto_merges(self):
        d = ids(row(), row(entry_id="race2", race_date="20210210",
                          horse_name="馬B", horse_number="2"))
        self.assertTrue(all(x.status == AMBIGUOUS for x in d))
        self.assertTrue(all(x.candidate_id is None for x in d))
        self.assertIn("same_birth_and_pedigree_with_different_names",
                      d[0].reasons)

    def test_same_candidate_twice_on_same_day_is_ambiguous(self):
        d = ids(row(), row(entry_id="race2", race_number="2",
                          horse_number="3", track="川崎"))
        self.assertTrue(all(x.status == AMBIGUOUS for x in d))
        self.assertIn("same_candidate_has_multiple_entries_on_one_day",
                      d[0].reasons)

    def test_same_race_two_horses_matching_identity_are_ambiguous(self):
        d = ids(row(), row(entry_id="race-A#2", horse_number="2"))
        self.assertTrue(all(x.status == AMBIGUOUS for x in d))

    def test_identical_entry_id_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            ids(row(), row())

    def test_same_race_horse_normalized_leading_zero_duplicate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            ids(row(), row(entry_id="other", race_number="001", horse_number="01"))

    def test_missing_or_invalid_pedigree_yields_no_candidate_id(self):
        d = ids(row(dam=""), row(entry_id="b", race_date="20210210",
                                dam="  ", horse_number="2"))
        self.assertTrue(all(x.status == UNMATCHABLE for x in d))
        self.assertTrue(all(x.candidate_id is None for x in d))

    def test_invalid_birthdate_and_birth_after_race_are_unmatchable(self):
        d = ids(row(birth_date="20180229"),
                row(entry_id="b", birth_date="20220101", horse_number="2"))
        self.assertTrue(all(x.status == UNMATCHABLE for x in d))
        self.assertTrue(all(x.candidate_id is None for x in d))

    def test_2025_test_and_2026_oot_are_not_accepted(self):
        for out_of_scope in ("20250101", "20261231"):
            with self.subTest(date=out_of_scope):
                with self.assertRaisesRegex(ValueError, "outside development"):
                    ids(row(race_date=out_of_scope))

    def test_source_hash_is_required_not_a_pit_timestamp(self):
        with self.assertRaisesRegex(ValueError, "SHA256"):
            ids(row(source_sha256="not-a-sha"))
        orig = resolve_development_candidates([row()])
        changed = resolve_development_candidates([row(source_sha256=SHA_B)])
        self.assertEqual(orig.decisions[0].candidate_id,
                         changed.decisions[0].candidate_id)
        self.assertNotEqual(orig.registry_fingerprint_sha256,
                            changed.registry_fingerprint_sha256)

    def test_order_invariance_and_same_input_reproducibility(self):
        entries = [
            row(),
            row(entry_id="r2", race_date="20210210", track="川崎"),
            row(entry_id="r3", race_date="20210301", horse_name="別馬",
                birth_date="20180102", horse_number="4")
        ]
        a = resolve_development_candidates(entries)
        b = resolve_development_candidates(reversed(entries))
        self.assertEqual(a, b)
        self.assertEqual(a.counts[CANDIDATE_ONLY], 3)

    def test_registry_and_result_immutable(self):
        registry = resolve_development_candidates([row()])
        with self.assertRaises(FrozenInstanceError):
            registry.version = 2
        with self.assertRaises(FrozenInstanceError):
            registry.decisions[0].candidate_id = "forced"

    def test_prefix_cannot_be_confused_with_verified_horse_uid(self):
        candidate = ids(row())[0]
        self.assertNotIn("verified", candidate.candidate_id)
        self.assertNotIn("horse_uid", candidate.__dict__)
        self.assertEqual(CANDIDATE_ONLY, candidate.status)


if __name__ == "__main__":
    unittest.main(verbosity=2)
