#!/usr/bin/env bash
# Append-only secondary backup: primary ForecastWeather archive -> Google Drive.
# Never overwrites/deletes remote objects. Never logs secrets.
#
# Required env:
#   KEIBA_NAR_WEATHER_ARCHIVE_ROOT
#   KEIBA_WEATHER_RCLONE_REMOTE
#   KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID   (= fixed Drive root folder id)
# Optional:
#   KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH  (default: weather/forecast)
#   RCLONE_BIN / RCLONE_CONFIG          (values never printed)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

refuse_secret_env_echo
assert_drive_root_folder_id

PRIMARY="$(require_primary_archive_root)"
REMOTE="$(remote_spec)"
RCLONE="$(resolve_rclone_bin)"

snapshot_count="$(count_snapshots "${PRIMARY}")"
[[ "${snapshot_count}" -gt 0 ]] || die "primary archive has no forecast.json snapshots: ${PRIMARY}"

printf 'INFO: primary_archive=%s\n' "${PRIMARY}"
printf 'INFO: remote_spec=%s\n' "${REMOTE}"
printf 'INFO: snapshot_count=%s\n' "${snapshot_count}"
printf 'INFO: drive_root_folder_id=%s\n' "${EXPECTED_DRIVE_ROOT_FOLDER_ID}"

# Copy only missing destinations. Existing remote paths are never overwritten.
"${RCLONE}" copy \
  "${PRIMARY}/" \
  "${REMOTE}/" \
  --ignore-existing \
  --create-empty-src-dirs=false \
  --checksum \
  --error-on-no-transfer=false

# Fail-closed: every primary file must exist remotely with matching checksum.
# If a remote path already existed with different content, this check fails.
"${RCLONE}" check \
  "${PRIMARY}/" \
  "${REMOTE}/" \
  --one-way \
  --checksum

printf 'OK: secondary backup copy+check passed (append-only, no overwrite)\n'
