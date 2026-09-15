#!/usr/bin/env bash
# Shared helpers for ForecastWeather secondary backup/restore scripts.
# No credentials are read or logged here.

set -euo pipefail

readonly EXPECTED_DRIVE_ROOT_FOLDER_ID='1Qz1QAX58jrekp80kyH5jrRmHaggH2iJb'
readonly DEFAULT_REMOTE_REL_PATH='weather/forecast'

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_env() {
  local name="$1"
  local value="${!name-}"
  [[ -n "${value}" ]] || die "required environment variable is empty: ${name}"
}

abs_canonical() {
  local path="$1"
  [[ -n "${path}" ]] || die "path is empty"
  [[ "${path}" == /* ]] || die "path must be absolute: ${path}"
  if [[ -e "${path}" || -L "${path}" ]]; then
    readlink -f -- "${path}"
  else
    local parent
    parent="$(dirname -- "${path}")"
    local base
    base="$(basename -- "${path}")"
    [[ -d "${parent}" ]] || die "parent directory does not exist: ${parent}"
    printf '%s/%s\n' "$(readlink -f -- "${parent}")" "${base}"
  fi
}

assert_outside_git_tree() {
  local target="$1"
  local repo_root
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
  case "${target}" in
    "${repo_root}"|"${repo_root}"/*)
      die "path must be outside the Git working tree: ${target}"
      ;;
  esac
}

assert_dirs_distinct() {
  local a="$1"
  local b="$2"
  [[ "${a}" != "${b}" ]] || die "paths must be distinct: ${a}"
  case "${a}" in
    "${b}"|"${b}"/*) die "path nesting is forbidden: ${a} under ${b}" ;;
  esac
  case "${b}" in
    "${a}"|"${a}"/*) die "path nesting is forbidden: ${b} under ${a}" ;;
  esac
}

resolve_rclone_bin() {
  if [[ -n "${RCLONE_BIN-}" ]]; then
    [[ -x "${RCLONE_BIN}" ]] || die "RCLONE_BIN is not executable: ${RCLONE_BIN}"
    printf '%s\n' "${RCLONE_BIN}"
    return
  fi
  require_cmd rclone
  command -v rclone
}

assert_drive_root_folder_id() {
  local configured="${KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID-}"
  [[ -n "${configured}" ]] || die "KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID is required"
  [[ "${configured}" == "${EXPECTED_DRIVE_ROOT_FOLDER_ID}" ]] || \
    die "KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID mismatch (name-based folder search is forbidden; fixed ROOT_FOLDER_ID required)"
}

# Every Drive rclone invocation must pass this fixed id. Do not rely on remote
# config alone; never resolve the folder by display name.
drive_root_folder_id_flag() {
  printf -- '--drive-root-folder-id\n%s\n' "${EXPECTED_DRIVE_ROOT_FOLDER_ID}"
}

assert_rclone_remote_is_drive() {
  # Fail-closed: remote backend must be Google Drive (type=drive).
  # Prefer env-only Cloud Agent remotes for the fixed remote name `gdrive`
  # via RCLONE_CONFIG_GDRIVE_TYPE (no rclone.conf required).
  # Other remote names do not invent env mappings; they use config-file fallback.
  # Never echo token / client_secret / redacted config bodies.
  local rclone_bin="$1"
  local remote_name="${KEIBA_WEATHER_RCLONE_REMOTE-}"
  [[ -n "${remote_name}" ]] || die "KEIBA_WEATHER_RCLONE_REMOTE is required"
  case "${remote_name}" in
    *:*)
      die "KEIBA_WEATHER_RCLONE_REMOTE must be the remote name only (no colon/path): ${remote_name}"
      ;;
  esac

  # Fixed Cloud Agent remote name -> fixed env var (no fuzzy conversion).
  if [[ "${remote_name}" == "gdrive" ]]; then
    local env_type="${RCLONE_CONFIG_GDRIVE_TYPE-}"
    if [[ -n "${env_type}" ]]; then
      [[ "${env_type}" == "drive" ]] || \
        die "RCLONE_CONFIG_GDRIVE_TYPE must be exactly 'drive' (non-drive env backend refused)"
      printf 'INFO: rclone remote gdrive validated via RCLONE_CONFIG_GDRIVE_TYPE (env-only)\n'
      return 0
    fi
  fi

  # Fallback: config-file remote via `rclone config redacted` (secrets stay redacted).
  local redacted_out=""
  local rc=0
  set +e
  redacted_out="$("${rclone_bin}" config redacted "${remote_name}" 2>&1)"
  rc=$?
  set -euo pipefail

  if [[ "${rc}" -ne 0 ]]; then
    redacted_out=""
    die "rclone remote config lookup failed for '${remote_name}' (exit ${rc}); refusing to proceed"
  fi

  local type_value=""
  local line
  while IFS= read -r line || [[ -n "${line}" ]]; do
    # Match exact key `type` only; ignore unrelated keys.
    if [[ "${line}" =~ ^[[:space:]]*type[[:space:]]*=[[:space:]]*([^[:space:]]+)[[:space:]]*$ ]]; then
      type_value="${BASH_REMATCH[1]}"
    fi
  done <<< "${redacted_out}"

  # Drop config body immediately so it cannot be accidentally printed later.
  redacted_out=""

  [[ -n "${type_value}" ]] || die "rclone remote '${remote_name}' has no type field; refusing to proceed"
  [[ "${type_value}" == "drive" ]] || die "rclone remote '${remote_name}' type must be exactly 'drive' (non-drive backend refused)"
}


remote_rel_path() {
  local rel="${KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH-${DEFAULT_REMOTE_REL_PATH}}"
  rel="${rel#/}"
  rel="${rel%/}"
  [[ -n "${rel}" ]] || die "KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH is empty"
  case "${rel}" in
    *..*|*" "*|*\\*|*:*)
      die "invalid KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH: ${rel}"
      ;;
  esac
  printf '%s\n' "${rel}"
}

remote_spec() {
  local remote="${KEIBA_WEATHER_RCLONE_REMOTE-}"
  [[ -n "${remote}" ]] || die "KEIBA_WEATHER_RCLONE_REMOTE is required (example: gdrive)"
  case "${remote}" in
    *:*)
      die "KEIBA_WEATHER_RCLONE_REMOTE must be the remote name only (no colon/path): ${remote}"
      ;;
  esac
  local rel
  rel="$(remote_rel_path)"
  printf '%s:%s\n' "${remote}" "${rel}"
}

require_primary_archive_root() {
  require_env KEIBA_NAR_WEATHER_ARCHIVE_ROOT
  local root
  root="$(abs_canonical "${KEIBA_NAR_WEATHER_ARCHIVE_ROOT}")"
  assert_outside_git_tree "${root}"
  [[ -d "${root}" ]] || die "primary archive root is not a directory: ${root}"
  printf '%s\n' "${root}"
}

require_restore_root() {
  require_env KEIBA_NAR_WEATHER_RESTORE_ROOT
  local root
  root="$(abs_canonical "${KEIBA_NAR_WEATHER_RESTORE_ROOT}")"
  assert_outside_git_tree "${root}"
  printf '%s\n' "${root}"
}

assert_restore_root_empty() {
  local root="$1"
  if [[ -d "${root}" ]]; then
    if find "${root}" -mindepth 1 -print -quit | grep -q .; then
      die "restore root must be empty (refusing to merge into non-empty tree): ${root}"
    fi
  fi
}

count_snapshots() {
  local root="$1"
  find "${root}" -type f -name forecast.json | wc -l | tr -d ' '
}

refuse_secret_env_echo() {
  # Guardrail: never print credential-bearing env values.
  local name
  for name in \
    RCLONE_CONFIG \
    RCLONE_CONFIG_PASS \
    RCLONE_CONFIG_GDRIVE_CLIENT_ID \
    RCLONE_CONFIG_GDRIVE_CLIENT_SECRET \
    RCLONE_CONFIG_GDRIVE_TOKEN \
    GOOGLE_CLIENT_SECRET \
    GOOGLE_REFRESH_TOKEN \
    CLIENT_SECRET \
    REFRESH_TOKEN \
    AUTHORIZATION \
    COOKIE
  do
    if [[ -n "${!name-}" ]]; then
      printf 'INFO: %s is set (value intentionally not printed)\n' "${name}"
    fi
  done
}
