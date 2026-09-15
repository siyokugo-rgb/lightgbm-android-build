#!/usr/bin/env python3
"""Synthetic (no Drive / no OAuth) tests for ForecastWeather secondary backup scripts.

Uses a fake rclone binary and temporary directories outside typical repo layout
under /tmp. Never contacts Google Drive.
"""

from __future__ import annotations

import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "tools" / "forecast_weather_secondary_backup"
FIXED_ROOT_ID = "1Qz1QAX58jrekp80kyH5jrRmHaggH2iJb"


FAKE_RCLONE = r"""#!/usr/bin/env bash
set -euo pipefail
cmd="${1:-}"
shift || true
log_file="${FAKE_RCLONE_LOG:?}"
{
  printf 'CMD=%s\n' "${cmd}"
  for a in "$@"; do
    printf 'ARG=%s\n' "${a}"
  done
} >> "${log_file}"

case "${cmd}" in
  copy)
    src=""; dst=""; ignore=0
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --ignore-existing) ignore=1; shift ;;
        --create-empty-src-dirs=false|--checksum|--error-on-no-transfer=false) shift ;;
        --*)
          printf 'unexpected flag: %s\n' "$1" >&2
          exit 90
          ;;
        *)
          if [[ -z "${src}" ]]; then src="$1"
          elif [[ -z "${dst}" ]]; then dst="$1"
          else
            printf 'extra arg: %s\n' "$1" >&2
            exit 91
          fi
          shift
          ;;
      esac
    done
    [[ -n "${src}" && -n "${dst}" ]] || { echo 'copy needs src dst' >&2; exit 92; }
    [[ "${ignore}" -eq 1 ]] || { echo 'copy must use --ignore-existing' >&2; exit 93; }

    # Map remote:weather/forecast <-> local mirror under FAKE_REMOTE_MIRROR
    mirror="${FAKE_REMOTE_MIRROR:?}"
    if [[ "${src}" == *:* ]]; then
      # remote -> local
      mkdir -p "${dst}"
      if [[ -d "${mirror}" ]]; then
        # append-only into dst: skip existing files
        while IFS= read -r -d '' f; do
          rel="${f#"${mirror}/"}"
          target="${dst%/}/${rel}"
          if [[ -e "${target}" ]]; then
            continue
          fi
          mkdir -p "$(dirname "${target}")"
          cp -a "${f}" "${target}"
        done < <(find "${mirror}" -type f -print0)
      fi
    else
      # local -> remote mirror
      mkdir -p "${mirror}"
      src_dir="${src%/}"
      while IFS= read -r -d '' f; do
        rel="${f#"${src_dir}/"}"
        target="${mirror}/${rel}"
        if [[ -e "${target}" ]]; then
          continue
        fi
        mkdir -p "$(dirname "${target}")"
        cp -a "${f}" "${target}"
      done < <(find "${src_dir}" -type f -print0)
    fi
    ;;
  check)
    left=""; right=""; oneway=0
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --one-way) oneway=1; shift ;;
        --checksum) shift ;;
        --*)
          printf 'unexpected flag: %s\n' "$1" >&2
          exit 94
          ;;
        *)
          if [[ -z "${left}" ]]; then left="$1"
          elif [[ -z "${right}" ]]; then right="$1"
          else
            printf 'extra arg: %s\n' "$1" >&2
            exit 95
          fi
          shift
          ;;
      esac
    done
    [[ "${oneway}" -eq 1 ]] || { echo 'check must use --one-way' >&2; exit 96; }
    [[ -n "${left}" && -n "${right}" ]] || { echo 'check needs two paths' >&2; exit 97; }

    mirror="${FAKE_REMOTE_MIRROR:?}"
    resolve() {
      local p="$1"
      if [[ "${p}" == *:* ]]; then
        printf '%s\n' "${mirror}"
      else
        printf '%s\n' "${p%/}"
      fi
    }
    left_dir="$(resolve "${left}")"
    right_dir="$(resolve "${right}")"
    [[ -d "${left_dir}" ]] || { echo "missing left: ${left_dir}" >&2; exit 98; }
    [[ -d "${right_dir}" ]] || { echo "missing right: ${right_dir}" >&2; exit 99; }

    while IFS= read -r -d '' f; do
      rel="${f#"${left_dir}/"}"
      other="${right_dir}/${rel}"
      if [[ ! -f "${other}" ]]; then
        echo "MISSING ${rel}" >&2
        exit 2
      fi
      if ! cmp -s "${f}" "${other}"; then
        echo "DIFF ${rel}" >&2
        exit 3
      fi
    done < <(find "${left_dir}" -type f -print0)
    ;;
  *)
    echo "unsupported rclone command: ${cmd}" >&2
    exit 100
    ;;
esac
"""


def write_executable(path: Path, body: str) -> None:
    path.write_text(body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def make_snapshot(root: Path, name: str = "snap1") -> Path:
    snap = root / name
    snap.mkdir(parents=True, exist_ok=True)
    (snap / "forecast.json").write_text('{"ok":true}\n', encoding="utf-8")
    (snap / "manifest.txt").write_text("format_version=1\n", encoding="utf-8")
    return snap


class SecondaryBackupSyntheticTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="weather-backup-synth-"))
        self.addCleanup(shutil.rmtree, self.tmpdir, ignore_errors=True)

        self.primary = self.tmpdir / "primary"
        self.restore = self.tmpdir / "restore"
        self.mirror = self.tmpdir / "remote-mirror"
        self.log = self.tmpdir / "rclone.log"
        self.fake_rclone = self.tmpdir / "fake-rclone"

        self.primary.mkdir()
        self.restore.mkdir()
        make_snapshot(self.primary)
        write_executable(self.fake_rclone, FAKE_RCLONE)
        self.log.write_text("", encoding="utf-8")

        self.base_env = {
            **os.environ,
            "KEIBA_NAR_WEATHER_ARCHIVE_ROOT": str(self.primary),
            "KEIBA_NAR_WEATHER_RESTORE_ROOT": str(self.restore),
            "KEIBA_WEATHER_RCLONE_REMOTE": "gdrive",
            "KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID": FIXED_ROOT_ID,
            "KEIBA_WEATHER_DRIVE_REMOTE_REL_PATH": "weather/forecast",
            "RCLONE_BIN": str(self.fake_rclone),
            "FAKE_REMOTE_MIRROR": str(self.mirror),
            "FAKE_RCLONE_LOG": str(self.log),
            # Ensure secret-looking values are set but must not appear in output.
            "GOOGLE_CLIENT_SECRET": "super-secret-client",
            "GOOGLE_REFRESH_TOKEN": "super-secret-refresh",
            "RCLONE_CONFIG": str(self.tmpdir / "rclone.conf"),
        }

    def run_script(self, name: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        script = SCRIPTS / name
        return subprocess.run(
            ["bash", str(script)],
            env=env if env is not None else self.base_env,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_backup_check_restore_happy_path(self) -> None:
        r1 = self.run_script("backup_to_drive.sh")
        self.assertEqual(r1.returncode, 0, r1.stderr + r1.stdout)
        self.assertIn("OK: secondary backup", r1.stdout)
        self.assertTrue((self.mirror / "snap1" / "forecast.json").is_file())

        r2 = self.run_script("check_primary_vs_remote.sh")
        self.assertEqual(r2.returncode, 0, r2.stderr + r2.stdout)

        # restore requires empty root; recreate empty
        shutil.rmtree(self.restore)
        self.restore.mkdir()
        r3 = self.run_script("restore_from_drive.sh")
        self.assertEqual(r3.returncode, 0, r3.stderr + r3.stdout)
        self.assertTrue((self.restore / "snap1" / "forecast.json").is_file())
        self.assertTrue((self.restore / "snap1" / "manifest.txt").is_file())

        combined = r1.stdout + r1.stderr + r2.stdout + r2.stderr + r3.stdout + r3.stderr
        self.assertNotIn("super-secret-client", combined)
        self.assertNotIn("super-secret-refresh", combined)
        self.assertIn("GOOGLE_CLIENT_SECRET is set (value intentionally not printed)", combined)

        log_text = self.log.read_text(encoding="utf-8")
        self.assertIn("--ignore-existing", log_text)
        self.assertIn("--one-way", log_text)
        self.assertIn("gdrive:weather/forecast", log_text)
        self.assertNotIn("delete", log_text.lower().split("cmd=")[0])

    def test_wrong_folder_id_fails(self) -> None:
        env = dict(self.base_env)
        env["KEIBA_WEATHER_DRIVE_ROOT_FOLDER_ID"] = "WRONG_ID"
        r = self.run_script("backup_to_drive.sh", env=env)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("ROOT_FOLDER_ID", r.stderr)

    def test_empty_primary_fails(self) -> None:
        empty = self.tmpdir / "empty-primary"
        empty.mkdir()
        env = dict(self.base_env)
        env["KEIBA_NAR_WEATHER_ARCHIVE_ROOT"] = str(empty)
        r = self.run_script("backup_to_drive.sh", env=env)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("no forecast.json", r.stderr)

    def test_nested_restore_rejected(self) -> None:
        nested = self.primary / "nested-restore"
        nested.mkdir()
        env = dict(self.base_env)
        env["KEIBA_NAR_WEATHER_RESTORE_ROOT"] = str(nested)
        r = self.run_script("restore_from_drive.sh", env=env)
        self.assertNotEqual(r.returncode, 0)
        self.assertTrue(
            "distinct" in r.stderr or "nesting" in r.stderr,
            r.stderr,
        )

    def test_nonempty_restore_rejected(self) -> None:
        (self.restore / "stale.txt").write_text("nope\n", encoding="utf-8")
        # seed remote so failure is specifically nonempty restore
        self.mirror.mkdir()
        make_snapshot(self.mirror)
        r = self.run_script("restore_from_drive.sh")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("must be empty", r.stderr)

    def test_checksum_mismatch_fail_closed(self) -> None:
        # Pre-seed remote with different content for same path
        make_snapshot(self.mirror)
        (self.mirror / "snap1" / "forecast.json").write_text(
            '{"ok":false}\n', encoding="utf-8"
        )
        r = self.run_script("backup_to_drive.sh")
        self.assertNotEqual(r.returncode, 0)
        self.assertTrue(
            "DIFF" in r.stderr or r.returncode != 0,
            r.stderr + r.stdout,
        )

    def test_path_inside_git_tree_rejected(self) -> None:
        inside = REPO_ROOT / "test-data" / "weather-archive-should-fail"
        inside.mkdir(parents=True, exist_ok=True)
        self.addCleanup(shutil.rmtree, inside, ignore_errors=True)
        make_snapshot(inside)
        env = dict(self.base_env)
        env["KEIBA_NAR_WEATHER_ARCHIVE_ROOT"] = str(inside)
        r = self.run_script("backup_to_drive.sh", env=env)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("outside the Git working tree", r.stderr)

    def test_remote_name_with_path_rejected(self) -> None:
        env = dict(self.base_env)
        env["KEIBA_WEATHER_RCLONE_REMOTE"] = "gdrive:weather"
        r = self.run_script("check_primary_vs_remote.sh", env=env)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("remote name only", r.stderr)

    def test_append_only_does_not_overwrite_remote(self) -> None:
        make_snapshot(self.mirror)
        original = (self.mirror / "snap1" / "forecast.json").read_text(encoding="utf-8")
        # primary has same path different content + a new snap
        (self.primary / "snap1" / "forecast.json").write_text(
            '{"changed":true}\n', encoding="utf-8"
        )
        make_snapshot(self.primary, name="snap2")
        r = self.run_script("backup_to_drive.sh")
        # check must fail because snap1 differs; snap2 may have been copied
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(
            (self.mirror / "snap1" / "forecast.json").read_text(encoding="utf-8"),
            original,
        )
        self.assertTrue((self.mirror / "snap2" / "forecast.json").is_file())


if __name__ == "__main__":
    unittest.main()
