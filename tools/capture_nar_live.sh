#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
BRANCH="$(git branch --show-current)"

if [[ "$BRANCH" != audit/nar-live-* ]]; then
  echo "refusing to run outside audit/nar-live-* branch: $BRANCH" >&2
  exit 1
fi

if ! git diff --quiet ||
   ! git diff --cached --quiet ||
   [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  echo "refusing to run with a dirty working tree" >&2
  git status --short
  exit 1
fi
DAY="$(TZ=Asia/Tokyo date +%Y%m%d)"
TS="$(TZ=Asia/Tokyo date +%Y%m%dT%H%M%S%z)"
BASE="$ROOT/audit-data/nar-live/$DAY"
DIR="$BASE/$TS"

URL='https://www.keiba.go.jp/KeibaWeb/DataDownload/RaceDataDownload?type=daily'

mkdir -p "$DIR"

CAPTURE_OK=0

cleanup() {
  if [[ "$CAPTURE_OK" -ne 1 ]]; then
    git reset -q -- "$DIR" 2>/dev/null || true
    rm -rf "$DIR"
  fi
}

trap cleanup EXIT

echo "capture: $TS"

curl -fsSL \
  -w 'http_code=%{http_code}\ncontent_type=%{content_type}\nurl_effective=%{url_effective}\n' \
  -o "$DIR/race.zip" \
  "$URL" > "$DIR/http-meta.txt"

sha256sum "$DIR/race.zip" > "$DIR/zip-sha256.txt"

unzip -q "$DIR/race.zip" -d "$DIR"

# ZIPはGit管理しない。展開済みCSVとハッシュを証跡として残す。
rm "$DIR/race.zip"

for kind in racelist horselist payback; do
  file="$DIR/${DAY}_${kind}.csv"

  if [[ ! -f "$file" ]]; then
    echo "missing: $file" >&2
    exit 1
  fi
done

sha256sum \
  "$DIR/${DAY}_racelist.csv" \
  "$DIR/${DAY}_horselist.csv" \
  "$DIR/${DAY}_payback.csv" \
  > "$DIR/csv-sha256.txt"

PREV="$(
  find "$BASE" \
    -mindepth 1 -maxdepth 1 -type d \
    ! -path "$DIR" \
    -printf '%f\n' |
  sort |
  tail -1
)"

{
  echo "snapshot=$TS"

  if [[ -n "$PREV" ]]; then
    echo "previous=$PREV"

    for kind in racelist horselist payback; do
      old="$BASE/$PREV/${DAY}_${kind}.csv"
      new="$DIR/${DAY}_${kind}.csv"

      if [[ -f "$old" ]] && cmp -s "$old" "$new"; then
        echo "$kind=identical"
      else
        echo "$kind=changed"
      fi
    done
  else
    echo "previous=none"
  fi
} | tee "$DIR/comparison.txt"

git add \
  "$DIR" \
  tools/capture_nar_live.sh

if git diff --cached --quiet; then
  echo "nothing to commit"
  exit 0
fi

git commit -m "Capture NAR live snapshot at $TS"

CAPTURE_OK=1
trap - EXIT

echo
echo "saved: $DIR"
echo "commit: $(git rev-parse --short HEAD)"
echo
git status --short --branch
