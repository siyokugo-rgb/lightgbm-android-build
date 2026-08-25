#!/usr/bin/env bash
set -euo pipefail

YEAR="${1:?usage: $0 YEAR [END_MONTH]}"
END_MONTH="${2:-12}"

if ! [[ "$YEAR" =~ ^[0-9]{4}$ ]]; then
  echo "invalid year: $YEAR" >&2
  exit 2
fi

if ! [[ "$END_MONTH" =~ ^[0-9]+$ ]] ||
   (( END_MONTH < 1 || END_MONTH > 12 )); then
  echo "invalid end month: $END_MONTH" >&2
  exit 2
fi

ROOT="${NAR_HISTORY_ROOT:-/workspaces/nar-history}"
REPO="$(git rev-parse --show-toplevel)"

cd "$REPO"

BRANCH="$(git branch --show-current)"

if [[ "$BRANCH" != "main" ]]; then
  echo "refusing to run outside main: $BRANCH" >&2
  exit 1
fi

if ! git diff --quiet ||
   ! git diff --cached --quiet ||
   [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  echo "refusing to run with a dirty working tree" >&2
  git status --short
  exit 1
fi

START="${YEAR}01"
END="$(printf '%04d%02d' "$YEAR" "$END_MONTH")"

echo "=== NAR $START -> $END ==="

python3 tools/download_nar_history.py \
  --start "$START" \
  --end "$END" \
  --root "$ROOT"

python3 - "$YEAR" "$END_MONTH" "$ROOT" <<'PY'
import csv
import sys
from pathlib import Path

year = int(sys.argv[1])
end_month = int(sys.argv[2])
root = Path(sys.argv[3])

manifest = root / "manifests/months.csv"

with manifest.open(encoding="utf-8", newline="") as f:
    all_rows = list(csv.DictReader(f))

prefix = f"{year:04d}"
rows = [r for r in all_rows if r["ym"].startswith(prefix)]

expected = [
    f"{year:04d}{month:02d}"
    for month in range(1, end_month + 1)
]
actual = [r["ym"] for r in rows]
bad = [r for r in rows if r["status"] != "OK"]

zip_dir = root / "monthly" / f"{year:04d}"
zips = sorted(zip_dir.glob(f"{year:04d}??_race.zip"))

print("months =", len(rows))
print("actual =", actual)
print("failures =", len(bad))
print("zip_count =", len(zips))
print("zip_bytes =", sum(p.stat().st_size for p in zips))

for row in bad:
    print("FAIL", row["ym"], row["error"])

if actual != expected:
    raise SystemExit("ERROR: month list incomplete")

if bad:
    raise SystemExit("ERROR: failed month exists")

if len(zips) != end_month:
    raise SystemExit("ERROR: ZIP count mismatch")

print(f"{year} VALIDATION OK")
PY

mkdir -p data-manifests/nar-history

cp \
  "$ROOT/manifests/months.csv" \
  data-manifests/nar-history/months.csv

python3 - <<'PY'
from pathlib import Path

p = Path("data-manifests/nar-history/months.csv")
b = p.read_bytes()

if b"\r" in b:
    raise SystemExit("ERROR: CRLF detected in repository manifest")

print("manifest LF check: OK")
PY

git --no-pager diff --check

git add data-manifests/nar-history/months.csv

git --no-pager diff --cached --check

if ! git diff --cached --quiet; then
  git commit -m "Record NAR ${YEAR} history manifest"
else
  echo "manifest already current"
fi

# 前回pushだけ失敗したケースでも再実行でpushする。
AHEAD="$(git rev-list --count origin/main..HEAD)"

if (( AHEAD > 0 )); then
  if [[ -z "${GITHUB_TOKEN:-}" && -z "${GH_TOKEN:-}" ]]; then
    echo "GitHub token is not present; commit remains local" >&2
    exit 1
  fi

  echo "pushing $AHEAD commit(s)"

  GIT_TERMINAL_PROMPT=0 \
  timeout 30 \
  git push origin main
else
  echo "nothing to push"
fi

echo
git status --short --branch
git --no-pager log -2 --oneline --decorate
