#!/usr/bin/env bash
# Restore Drive secondary backup into a SEPARATE local restore root.
# Does not write into the primary archive. Never logs secrets.
#
# Required env:
#   KEIBA_NAR_WEATHER_ARCHIVE_ROOT   (read-only reference; must stay distinct)
#   KEIBA_NAR_WEATHER_RESTORE_ROOT   (must be empty / fresh)
#   KEIBA_WEATHER_RCLONE_REMOTE
#   KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID
# Optional:
#   KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH  (default: weather/forecast)
#   RCLONE_BIN / RCLONE_CONFIG

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

refuse_secret_env_echo
assert_drive_root_folder_id

PRIMARY="$(require_primary_archive_root)"
RESTORE="$(require_restore_root)"
assert_dirs_distinct "${PRIMARY}" "${RESTORE}"
assert_restore_root_empty "${RESTORE}"

REMOTE="$(remote_spec)"
RCLONE="$(resolve_rclone_bin)"
assert_rclone_remote_is_drive "${RCLONE}"

mkdir -p -- "${RESTORE}"
RESTORE="$(abs_canonical "${RESTORE}")"
assert_outside_git_tree "${RESTORE}"
assert_dirs_distinct "${PRIMARY}" "${RESTORE}"
assert_restore_root_empty "${RESTORE}"

printf 'INFO: primary_archive=%s (read-only reference; not written)\n' "${PRIMARY}"
printf 'INFO: restore_root=%s\n' "${RESTORE}"
printf 'INFO: remote_spec=%s\n' "${REMOTE}"

# Pull remote -> restore root. Never touch primary.
# Restore root was required empty, so --ignore-existing is a safety belt only.
# Force fixed Drive ROOT_FOLDER_ID on every invocation (do not trust remote config alone).
mapfile -t _DRIVE_ROOT_ARGS < <(drive_root_folder_id_flag)
"${RCLONE}" copy \
  "${REMOTE}/" \
  "${RESTORE}/" \
  --ignore-existing \
  --create-empty-src-dirs=false \
  --checksum \
  --error-on-no-transfer=false \
  "${_DRIVE_ROOT_ARGS[@]}"

# Fail-closed: every remote file must exist in restore with matching checksum.
"${RCLONE}" check \
  "${REMOTE}/" \
  "${RESTORE}/" \
  --one-way \
  --checksum \
  "${_DRIVE_ROOT_ARGS[@]}"

snapshot_count="$(count_snapshots "${RESTORE}")"
[[ "${snapshot_count}" -gt 0 ]] || die "restore root has no forecast.json snapshots: ${RESTORE}"

printf 'OK: restore from secondary backup complete\n'
printf 'INFO: snapshot_count=%s\n' "${snapshot_count}"
printf 'INFO: next: run integrity verify via Gradle opt-in test (see runbook)\n'
printf 'INFO: cleanup may delete restore_root only; never delete primary archive or Drive backup\n'
