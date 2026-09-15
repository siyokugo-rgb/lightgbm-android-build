#!/usr/bin/env bash
# Verify primary ForecastWeather archive matches Drive secondary backup.
# Fail-closed on any checksum/path mismatch. Never logs secrets.
#
# Required env: same as backup_to_drive.sh

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

"${RCLONE}" check \
  "${PRIMARY}/" \
  "${REMOTE}/" \
  --one-way \
  --checksum

printf 'OK: primary matches secondary backup (one-way checksum check)\n'
