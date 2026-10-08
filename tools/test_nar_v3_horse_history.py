import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone


from nar_v3_horse_history import (
    EntryAsOf,
    HistoricalOutcome,
    HorseHistoryError,
    FEATURE_ORDER,
    build_horse_history_features,
)




BASE = datetime(2024, 1, 1, tzinfo=timezone.utc)
SHA = "a" * 64




def entry(horse_uid="verified-horse-A", **overrides):
    defaults = dict(
        entry_id="entry-target",
        race_id="race-target",
        horse_uid=horse_uid,
        prediction_as_of=BASE + timedelta(days=12),
        race_start_at=BASE + timedelta(days=12, hours=1),
        identity_evidence_at=BASE,
        coverage_evidence_at=BASE + timedelta(days=11),
    )
    defaults.update(overrides)
    return EntryAsOf(**defaults)




def result(day, race_id, horse_uid="verified-horse-A",
           status="FINISHED", position=1, **overrides):
    started = BASE + timedelta(days=day)
    defaults = dict(
        horse_uid=horse_uid,
        race_id=race_id,
        race_started_at=started,
        result_available_at=started + timedelta(hours=2),
        evidence_captured_at=started + timedelta(hours=3),
        evidence_sha256=SHA,
        status=status,
        finish_position=position,
    )
    defaults.update(overrides)
    return HistoricalOutcome(**defaults)




class HorseHistoryTest(unittest.TestCase):


    def test_past_results_and_feature_order(self):
        outcomes = [
            result(8, "r3", position=3),
            result(1, "r1", position=1),
            result(
                5, "r2",
                status="DID_NOT_FINISH",
                position=None,
            ),
        ]
        features = build_horse_history_features(
            [entry()], outcomes
        )["entry-target"]


        self.assertEqual(tuple(features), FEATURE_ORDER)
        self.assertEqual(features["horse_history_past_starts"], 3)
        self.assertAlmostEqual(
            features["horse_history_last3_win_rate"], 1 / 3
        )
        self.assertAlmostEqual(
            features["horse_history_last5_top3_rate"], 2 / 3
        )
        self.assertEqual(
            features["horse_history_days_since_last_start"], 4
        )


    def test_future_evidence_cannot_enter_history(self):
        old = result(1, "old")
        late = result(
            4, "late",
            evidence_captured_at=BASE + timedelta(days=13),
        )
        features = build_horse_history_features(
            [entry()], [late, old]
        )["entry-target"]
        self.assertEqual(features["horse_history_past_starts"], 1)


    def test_current_race_outcome_never_enters_history(self):
        target = result(
            12, "race-target",
            result_available_at=BASE + timedelta(days=12, hours=2),
            evidence_captured_at=BASE + timedelta(days=12, hours=3),
        )
        features = build_horse_history_features(
            [entry()], [target]
        )["entry-target"]
        self.assertEqual(features["horse_history_past_starts"], 0)


    def test_horse_identity_is_strict(self):
        other_horse = result(
            1, "other-race",
            horse_uid="verified-horse-B",
        )
        features = build_horse_history_features(
            [entry()], [other_horse]
        )["entry-target"]
        self.assertEqual(features["horse_history_past_starts"], 0)


    def test_non_start_does_not_count_as_start(self):
        scratched = result(
            1, "r1", status="SCRATCHED", position=None
        )
        features = build_horse_history_features(
            [entry()], [scratched]
        )["entry-target"]
        self.assertEqual(features["horse_history_past_starts"], 0)
        self.assertIsNone(
            features["horse_history_last3_win_rate"]
        )


    def test_duplicate_outcome_fails_closed(self):
        one = result(1, "r1")
        with self.assertRaises(HorseHistoryError):
            build_horse_history_features(
                [entry()], [one, one]
            )


    def test_unknown_status_fails_closed(self):
        with self.assertRaises(HorseHistoryError):
            build_horse_history_features(
                [entry()], [result(1, "r1", status="UNKNOWN")]
            )


    def test_naive_datetime_fails_closed(self):
        invalid = replace(
            entry(),
            prediction_as_of=datetime(2024, 1, 13),
        )
        with self.assertRaises(HorseHistoryError):
            build_horse_history_features([invalid], [])


    def test_future_coverage_proof_fails_closed(self):
        invalid = replace(
            entry(),
            coverage_evidence_at=BASE + timedelta(days=14),
        )
        with self.assertRaises(HorseHistoryError):
            build_horse_history_features([invalid], [])


    def test_invalid_evidence_hash_fails_closed(self):
        invalid = result(1, "r1", evidence_sha256="bad")
        with self.assertRaises(HorseHistoryError):
            build_horse_history_features([entry()], [invalid])


    def test_boolean_finish_position_fails_closed(self):
        invalid = result(1, "r1", position=True)
        with self.assertRaises(HorseHistoryError):
            build_horse_history_features([entry()], [invalid])




if __name__ == "__main__":
    unittest.main()