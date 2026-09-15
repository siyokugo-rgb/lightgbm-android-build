#!/usr/bin/env bash
# Credential-free smoke check: env-only gdrive remote is recognized by rclone.
# Does NOT contact Google APIs (listremotes is local).
set -euo pipefail
RCLONE_BIN="${RCLONE_BIN:-rclone}"
command -v "${RCLONE_BIN}" >/dev/null 2>&1 || { echo "rclone not found"; exit 1; }
export RCLONE_CONFIG_GDRIVE_TYPE=drive
# Ensure no config file is required.
unset RCLONE_CONFIG || true
remotes="$("${RCLONE_BIN}" listremotes)"
printf '%s\n' "${remotes}" | grep -Fxq 'gdrive:' || {
  echo "ERROR: expected gdrive: in listremotes (env-only); got: ${remotes}" >&2
  exit 1
}
echo "OK: env-only gdrive remote recognized by rclone (no API call)"
