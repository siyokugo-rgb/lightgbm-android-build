"""PIT-safe horse history candidate features.


Development-only. This module must not be connected to a production
model until horse identity, history coverage, and source evidence are
independently verified.
"""


from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
import re




class HorseHistoryError(ValueError):
    pass




FEATURE_ORDER = (
    "horse_history_past_starts",
    "horse_history_last3_win_rate",
    "horse_history_last5_top3_rate",
    "horse_history_days_since_last_start",
)


STATUSES = {
    "FINISHED",
    "DID_NOT_FINISH",
    "DISQUALIFIED",
    "SCRATCHED",
    "EXCLUDED",
}
NON_START = {"SCRATCHED", "EXCLUDED"}




@dataclass(frozen=True)
class EntryAsOf:
    entry_id: str
    race_id: str
    horse_uid: str
    prediction_as_of: datetime
    race_start_at: datetime
    identity_evidence_at: datetime
    coverage_evidence_at: datetime




@dataclass(frozen=True)
class HistoricalOutcome:
    horse_uid: str
    race_id: str
    race_started_at: datetime
    result_available_at: datetime
    evidence_captured_at: datetime
    evidence_sha256: str
    status: str
    finish_position: int | None




def _required_text(value, field):
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or "\x00" in value
    ):
        raise HorseHistoryError(f"invalid {field}")
    return value




def _utc(value, field):
    if not isinstance(value, datetime):
        raise HorseHistoryError(f"invalid {field}")
    if value.tzinfo is None or value.utcoffset() is None:
        raise HorseHistoryError(f"naive datetime: {field}")
    return value.astimezone(timezone.utc)




def _validate_entry(entry):
    for field in ("entry_id", "race_id", "horse_uid"):
        _required_text(getattr(entry, field), field)


    asof = _utc(entry.prediction_as_of, "prediction_as_of")
    start = _utc(entry.race_start_at, "race_start_at")
    identity = _utc(
        entry.identity_evidence_at, "identity_evidence_at"
    )
    coverage = _utc(
        entry.coverage_evidence_at, "coverage_evidence_at"
    )


    if not (identity <= asof < start):
        raise HorseHistoryError("entry identity/asof boundary failed")
    if coverage > asof:
        raise HorseHistoryError("history coverage evidence is future")




def _validate_outcome(outcome):
    _required_text(outcome.horse_uid, "horse_uid")
    _required_text(outcome.race_id, "race_id")


    if not re.fullmatch(
        r"[0-9a-f]{64}", outcome.evidence_sha256
    ):
        raise HorseHistoryError("invalid evidence SHA-256")


    start = _utc(outcome.race_started_at, "race_started_at")
    available = _utc(
        outcome.result_available_at, "result_available_at"
    )
    evidence = _utc(
        outcome.evidence_captured_at, "evidence_captured_at"
    )


    if not (start <= available <= evidence):
        raise HorseHistoryError("result availability boundary failed")


    if outcome.status not in STATUSES:
        raise HorseHistoryError("unknown outcome status")


    if outcome.status == "FINISHED":
        if (
            type(outcome.finish_position) is not int
            or outcome.finish_position <= 0
        ):
            raise HorseHistoryError("invalid finish position")
    elif outcome.finish_position is not None:
        raise HorseHistoryError("non-finish has numeric position")




def build_horse_history_features(entries, outcomes):
    """Return development-only features keyed by entry_id.


    Required upstream guarantees:
      - horse_uid is from an independently verified identity source
      - coverage evidence proves the available historical record set
      - every outcome evidence digest is independently checked
      - timestamps are actual, not reconstructed from final CSV
    """
    indexed = defaultdict(list)
    seen_outcomes = set()
    seen_starts = set()


    for outcome in outcomes:
        _validate_outcome(outcome)


        identity = (outcome.horse_uid, outcome.race_id)
        if identity in seen_outcomes:
            raise HorseHistoryError("duplicate historical outcome")
        seen_outcomes.add(identity)


        if outcome.status not in NON_START:
            start_key = (
                outcome.horse_uid,
                _utc(outcome.race_started_at, "race_started_at"),
            )
            if start_key in seen_starts:
                raise HorseHistoryError("simultaneous horse starts")
            seen_starts.add(start_key)


        indexed[outcome.horse_uid].append(outcome)


    output = {}


    for entry in entries:
        _validate_entry(entry)


        if entry.entry_id in output:
            raise HorseHistoryError("duplicate prediction entry")


        asof = _utc(entry.prediction_as_of, "prediction_as_of")


        prior = []
        for outcome in indexed.get(entry.horse_uid, ()):
            # The target race's outcome is never historical input.
            if outcome.race_id == entry.race_id:
                continue


            started = _utc(
                outcome.race_started_at, "race_started_at"
            )
            available = _utc(
                outcome.result_available_at, "result_available_at"
            )
            evidence = _utc(
                outcome.evidence_captured_at,
                "evidence_captured_at",
            )


            if not (started < asof):
                continue
            if available > asof or evidence > asof:
                continue
            if outcome.status in NON_START:
                continue


            prior.append(outcome)


        prior.sort(
            key=lambda x: (
                _utc(x.race_started_at, "race_started_at"),
                x.race_id,
            )
        )


        last3 = prior[-3:]
        last5 = prior[-5:]


        last_start_days = None
        if prior:
            previous_start = _utc(
                prior[-1].race_started_at, "race_started_at"
            )
            last_start_days = (
                asof - previous_start
            ).days


        output[entry.entry_id] = {
            "horse_history_past_starts": len(prior),
            "horse_history_last3_win_rate": (
                sum(x.finish_position == 1 for x in last3)
                / len(last3)
                if last3 else None
            ),
            "horse_history_last5_top3_rate": (
                sum(
                    x.status == "FINISHED"
                    and x.finish_position <= 3
                    for x in last5
                ) / len(last5)
                if last5 else None
            ),
            "horse_history_days_since_last_start": (
                last_start_days
            ),
        }


    return output