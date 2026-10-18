"""Offline, development-only internal horse *candidate* ID assignment.

No external service, result/outcome, model, train/test split, or Android code
is accessed. A matching 5-field identity is a hypothesis, not a verified UID.
This module never authorizes horse-history features or production training.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from hashlib import sha256
import json
import re
import unicodedata
from typing import Iterable

VERSION = 1
DEV_FIRST = "19980101"  # Historical seed archive starts January 1998
DEV_LAST = "20241231"
DISCIPLINES = frozenset({"FLAT", "BANEI"})
CANDIDATE_ONLY = "CANDIDATE_ONLY"
AMBIGUOUS = "AMBIGUOUS"
UNMATCHABLE = "UNMATCHABLE"
_NO_MATCH = "candidate attributes missing or invalid"


@dataclass(frozen=True)
class HorseEntryCandidate:
    entry_id: str
    race_date: str
    track: str
    race_number: str
    horse_number: str
    discipline: str
    horse_name: str
    birth_date: str
    sire: str
    dam: str
    damsire: str
    source_sha256: str
    new_horse_race_label: bool = False  # Unverified NAR 新馬 race metadata


@dataclass(frozen=True)
class IdentityDecision:
    entry_id: str
    status: str
    candidate_id: str | None
    reasons: tuple[str, ...]
    source_sha256: str
    first_observed_date: str | None = None
    first_observed_entry_id: str | None = None
    first_observed_origin: str | None = None


@dataclass(frozen=True)
class IdentityRegistry:
    decisions: tuple[IdentityDecision, ...]
    registry_fingerprint_sha256: str
    version: int = VERSION

    @property
    def counts(self) -> dict[str, int]:
        return {
            status: sum(d.status == status for d in self.decisions)
            for status in (CANDIDATE_ONLY, AMBIGUOUS, UNMATCHABLE)
        }


@dataclass(frozen=True)
class _Row:
    entry_id: str
    source_sha256: str
    key: tuple[str, str, str, str, str, str] | None
    race_date: str
    race: tuple[str, str, str, str, str]
    reason: str | None
    new_horse_race_label: bool


def _text(value: object, name: str, *, required: bool = True) -> str | None:
    if not isinstance(value, str) or not value or value != value.strip():
        if required:
            raise ValueError(f"invalid {name}")
        return None
    if len(value) > 128 or "\x00" in value or any(
        unicodedata.category(c).startswith("C") for c in value
    ):
        if required:
            raise ValueError(f"invalid {name}")
        return None
    return unicodedata.normalize("NFC", value)


def _date8(value: str, name: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{8}", value):
        raise ValueError(f"invalid {name}")
    try:
        date(int(value[:4]), int(value[4:6]), int(value[6:8]))
    except ValueError as exc:
        raise ValueError(f"invalid {name}") from exc
    return value


def _positive(value: object, name: str) -> str:
    if (not isinstance(value, str) or
            not re.fullmatch(r"[0-9]{1,12}", value) or int(value) < 1):
        raise ValueError(f"invalid {name}")
    return str(int(value))


def _prepare(row: HorseEntryCandidate) -> _Row:
    entry_id = _text(row.entry_id, "entry_id")
    track = _text(row.track, "track")
    if row.discipline not in DISCIPLINES:
        # Never infer FLAT/BANEI from name, track spelling, or pedigree.
        raise ValueError("unverified FLAT/BANEI discipline boundary")
    if type(row.new_horse_race_label) is not bool:
        raise ValueError("unverified new_horse_race_label type")
    race_date = _date8(row.race_date, "race_date")
    if not DEV_FIRST <= race_date <= DEV_LAST:
        raise ValueError("2025 TEST / 2026 OOT and other dates are outside development scope")
    race_number = _positive(row.race_number, "race_number")
    horse_number = _positive(row.horse_number, "horse_number")
    if not isinstance(row.source_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}", row.source_sha256
    ):
        raise ValueError("unverified source SHA256")
    race = (row.discipline, track, race_date, race_number, horse_number)

    identity = [_text(value, field, required=False) for value, field in (
        (row.horse_name, "horse_name"), (row.birth_date, "birth_date"),
        (row.sire, "sire"), (row.dam, "dam"), (row.damsire, "damsire")
    )]
    if any(x is None for x in identity):
        return _Row(entry_id, row.source_sha256, None, race_date, race, _NO_MATCH, row.new_horse_race_label)
    try:
        dob = _date8(identity[1], "birth_date")
    except ValueError:
        return _Row(entry_id, row.source_sha256, None, race_date, race,
                    "invalid birth date", row.new_horse_race_label)
    if dob >= race_date:
        return _Row(entry_id, row.source_sha256, None, race_date, race,
                    "birth date is not earlier than race", row.new_horse_race_label)
    return _Row(entry_id, row.source_sha256,
                (row.discipline, identity[0], dob, *identity[2:]),
                race_date, race, None, row.new_horse_race_label)


def _candidate_id(key: tuple[str, ...]) -> str:
    canonical = json.dumps(["NAR-CANDIDATE-IDENTITY-V1", *key],
                           ensure_ascii=False, separators=(",", ":"))
    return "nar-candidate-v1-" + sha256(canonical.encode("utf-8")).hexdigest()


def resolve_development_candidates(
    entries: Iterable[HorseEntryCandidate],
) -> IdentityRegistry:
    """Deterministic candidate links seeded from oldest available observation;
    no verified horse_uid is ever emitted.

    Caller must *separately* verify course discipline and source provenance.
    Hashes identify supplied bytes only; they do not prove a historical
    prediction-time vintage. No historical results are read by this function.
    """
    rows = [_prepare(entry) for entry in entries]
    seen_entry_ids: set[str] = set()
    seen_races: set[tuple[str, str, str, str, str]] = set()
    for row in rows:
        if row.entry_id in seen_entry_ids or row.race in seen_races:
            raise ValueError("duplicate entry_id or normalized race/horse key")
        seen_entry_ids.add(row.entry_id)
        seen_races.add(row.race)

    by_name_birth = defaultdict(set)
    by_pedigree = defaultdict(set)
    by_key_day = defaultdict(set)
    by_key_entries = defaultdict(list)
    for row in rows:
        if row.key is None:
            continue
        discipline, name, dob, sire, dam, damsire = row.key
        by_name_birth[(discipline, name, dob)].add((sire, dam, damsire))
        by_pedigree[(discipline, dob, sire, dam, damsire)].add(name)
        by_key_day[(row.key, row.race_date)].add(row.entry_id)
        by_key_entries[row.key].append(row)

    decisions = []
    id_to_key = {}
    for row in rows:
        if row.key is None:
            decisions.append(IdentityDecision(
                row.entry_id, UNMATCHABLE, None, (row.reason,), row.source_sha256))
            continue
        discipline, name, dob, sire, dam, damsire = row.key
        reasons = []
        if len(by_name_birth[(discipline, name, dob)]) > 1:
            reasons.append("name_and_birth_with_conflicting_pedigree")
        if len(by_pedigree[(discipline, dob, sire, dam, damsire)]) > 1:
            reasons.append("same_birth_and_pedigree_with_different_names")
        if len(by_key_day[(row.key, row.race_date)]) > 1:
            reasons.append("same_candidate_has_multiple_entries_on_one_day")
        debut_labels = [r for r in by_key_entries[row.key] if r.new_horse_race_label]
        earliest = min(by_key_entries[row.key], key=lambda r: (r.race_date, r.race, r.entry_id))
        if len(debut_labels) > 1 or (debut_labels and debut_labels[0] != earliest):
            reasons.append("contradictory_new_horse_race_label")
        if reasons:
            decisions.append(IdentityDecision(row.entry_id, AMBIGUOUS, None,
                                              tuple(reasons), row.source_sha256))
            continue
        cid = _candidate_id(row.key)
        if cid in id_to_key and id_to_key[cid] != row.key:
            raise ValueError("candidate ID hash collision")
        id_to_key[cid] = row.key
        decisions.append(IdentityDecision(
            row.entry_id, CANDIDATE_ONLY, cid, (), row.source_sha256,
            earliest.race_date, earliest.entry_id,
            ("NAR_NEW_HORSE_RACE_LABEL" if earliest.new_horse_race_label
             else "EARLIEST_OBSERVED_ONLY")))

    decisions.sort(key=lambda item: item.entry_id)
    lines = [
        [d.entry_id, d.status, d.candidate_id, list(d.reasons), d.source_sha256,
         d.first_observed_date, d.first_observed_entry_id,
         d.first_observed_origin]
        for d in decisions
    ]
    payload = json.dumps(["NAR-CANDIDATE-REGISTRY-V1", lines],
                         ensure_ascii=False, separators=(",", ":"))
    return IdentityRegistry(tuple(decisions), sha256(payload.encode("utf-8")).hexdigest())
