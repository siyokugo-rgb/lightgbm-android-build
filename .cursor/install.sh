#!/usr/bin/env bash
#
# Idempotent development-environment bootstrap for lightgbm-android-build.
#
# Sets up both project components:
#   1. Android app  (Gradle + Kotlin + native LightGBM built via NDK/CMake)
#   2. Python tooling (NAR dataset/training scripts under tools/)
#
# Safe to run repeatedly: every step checks for existing state before acting.
#
# Security posture:
#   - The Android command-line tools archive is verified against a pinned
#     SHA-256 and fails closed on any mismatch.
#   - All downloads use HTTPS with certificate validation (TLS is never
#     disabled).
#   - No secrets are embedded; none are required by this script.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- Configuration ---------------------------------------------------------

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
# SHA-256 of commandlinetools-linux-11076708_latest.zip.
# Cross-checked against Google's official SHA-1 (d313adb7aedccf6cf0cfca51ec180f0059f5f8f8,
# size 153607504) published in dl.google.com/android/repository/repository2-3.xml.
CMDLINE_TOOLS_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"

EXPECTED_PYTHON="python3.12"

# Python dependency version contract (complete closure, mirrors the official
# nar-v3-training-requirements.lock). Installed the same on every platform.
REQUIREMENTS="$REPO_ROOT/requirements.txt"

# Offline wheelhouse assets. Each wheelhouse pairs a directory of wheels with a
# SHA-256 manifest CSV whose confirmed schema is: "Name","Length","SHA256".
#
# wheelhouse-v3 is a WINDOWS (win_amd64) wheelhouse and must never be installed
# on Linux. A separate Linux wheelhouse can be added later as an independent
# asset and will be used automatically on Linux when present.
WIN_WHEELHOUSE="$REPO_ROOT/wheelhouse-v3"
WIN_WHEELHOUSE_SHA="$REPO_ROOT/wheelhouse-v3-sha256.csv"
LINUX_WHEELHOUSE="$REPO_ROOT/wheelhouse-v3-linux"
LINUX_WHEELHOUSE_SHA="$REPO_ROOT/wheelhouse-v3-linux-sha256.csv"

CURL=(curl --proto '=https' --tlsv1.2 -fsSL)

# Strict, fail-closed verification of a wheelhouse against its SHA-256 manifest.
# Manifest schema (confirmed): header "Name","Length","SHA256"; one wheel per row.
# Fails closed on: unknown schema, missing file, size mismatch, or SHA mismatch.
verify_wheelhouse_manifest() {
  local wheelhouse="$1" manifest="$2"
  python - "$wheelhouse" "$manifest" <<'PY'
import csv, hashlib, os, sys
wheelhouse, manifest = sys.argv[1], sys.argv[2]
with open(manifest, newline="", encoding="utf-8-sig") as fh:
    rows = list(csv.reader(fh))
if not rows:
    sys.exit("FATAL: empty manifest (fail-closed)")
header = [c.strip() for c in rows[0]]
if header != ["Name", "Length", "SHA256"]:
    sys.exit(f"FATAL: unknown manifest schema {header!r} (fail-closed)")
count = 0
for row in rows[1:]:
    if not row or all(not c.strip() for c in row):
        continue
    if len(row) != 3:
        sys.exit(f"FATAL: malformed manifest row {row!r} (fail-closed)")
    name, length, sha = row[0].strip(), row[1].strip(), row[2].strip().lower()
    path = os.path.join(wheelhouse, name)
    if not os.path.isfile(path):
        sys.exit(f"FATAL: missing wheel {name} (fail-closed)")
    actual_size = os.path.getsize(path)
    if str(actual_size) != length:
        sys.exit(f"FATAL: size mismatch for {name}: manifest={length} actual={actual_size} (fail-closed)")
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest().lower() != sha:
        sys.exit(f"FATAL: SHA-256 mismatch for {name} (fail-closed)")
    count += 1
if count == 0:
    sys.exit("FATAL: manifest lists no wheels (fail-closed)")
print(f"    wheelhouse manifest OK: {count} wheels verified")
PY
}

# --- System packages -------------------------------------------------------

echo "==> Installing system packages (JDK 17, Python venv, tooling)"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  openjdk-17-jdk-headless \
  python3-venv \
  python3-pip \
  unzip \
  curl \
  ca-certificates

# --- Android command-line tools (checksum-verified, fail-closed) -----------

echo "==> Setting up Android command-line tools"
if [ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  "${CURL[@]}" -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"

  actual_sha="$(sha256sum "$tmp/cmdline-tools.zip" | awk '{print $1}')"
  if [ "$actual_sha" != "$CMDLINE_TOOLS_SHA256" ]; then
    echo "FATAL: cmdline-tools checksum mismatch (fail-closed)" >&2
    echo "  expected: $CMDLINE_TOOLS_SHA256" >&2
    echo "  actual:   $actual_sha" >&2
    rm -rf "$tmp"
    exit 1
  fi
  echo "    checksum OK ($actual_sha)"

  unzip -q -o "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$CMDLINE_TOOLS_DIR"
  mv "$tmp/cmdline-tools/"* "$CMDLINE_TOOLS_DIR/"
  rm -rf "$tmp"
fi
export PATH="$CMDLINE_TOOLS_DIR/bin:$PATH"

echo "==> Installing Android SDK packages (platform 35, build-tools, NDK, CMake)"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "build-tools;34.0.0" \
  "ndk;27.3.13750724" \
  "cmake;3.22.1" >/dev/null

echo "==> Writing local.properties"
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo "==> Persisting Android environment for interactive shells"
if ! grep -q 'ANDROID_HOME' "$HOME/.bashrc" 2>/dev/null; then
  {
    echo ""
    echo "# Android SDK (added by lightgbm-android-build install)"
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"'
  } >> "$HOME/.bashrc"
fi

# --- Python environment ----------------------------------------------------

echo "==> Setting up Python virtual environment (.venv)"
if ! command -v "$EXPECTED_PYTHON" >/dev/null 2>&1; then
  echo "WARNING: $EXPECTED_PYTHON not found; using $(command -v python3) ($(python3 --version 2>&1))." >&2
  echo "         Pinned dependency versions were validated on $EXPECTED_PYTHON." >&2
  PYTHON_BIN="python3"
else
  PYTHON_BIN="$EXPECTED_PYTHON"
fi

if [ ! -x "$REPO_ROOT/.venv/bin/python" ]; then
  "$PYTHON_BIN" -m venv .venv
fi
# shellcheck disable=SC1091
. .venv/bin/activate

# Keep pip deterministic: do not silently upgrade to whatever is newest on PyPI.
export PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_NO_INPUT=1

# --- Dependency installation (platform-aware, exact version contract) -------
#
# The version contract (requirements.txt) is the complete dependency closure and
# is installed identically on every platform via `--no-deps`. Only the *source*
# of the wheels differs by platform:
#   - Linux:      a Linux offline wheelhouse if present (verified), else the
#                 index (same pinned versions, Linux-compatible wheels).
#                 The Windows wheelhouse-v3 is never used on Linux.
#   - non-Linux:  the Windows wheelhouse-v3 if present (verified), else the index.

PLATFORM="$(uname -s)"
echo "==> Installing Python dependencies (platform: $PLATFORM)"

wheelhouse_pair_state() {  # echoes: both | none | partial
  local dir="$1" csv="$2" n=0
  [ -d "$dir" ] && n=$((n + 1))
  [ -f "$csv" ] && n=$((n + 1))
  case "$n" in 2) echo both;; 0) echo none;; *) echo partial;; esac
}

install_from_wheelhouse() {  # <dir> <csv>
  verify_wheelhouse_manifest "$1" "$2"
  python -m pip install --no-index --no-deps --find-links "$1" -r "$REQUIREMENTS"
}

install_from_index() {
  python -m pip install --no-deps -r "$REQUIREMENTS"
}

if [ "$PLATFORM" = "Linux" ]; then
  if [ -d "$WIN_WHEELHOUSE" ]; then
    echo "    note: wheelhouse-v3 is a Windows (win_amd64) wheelhouse; ignored on Linux."
  fi
  case "$(wheelhouse_pair_state "$LINUX_WHEELHOUSE" "$LINUX_WHEELHOUSE_SHA")" in
    both)
      echo "    source: Linux offline wheelhouse (verified)"
      install_from_wheelhouse "$LINUX_WHEELHOUSE" "$LINUX_WHEELHOUSE_SHA" ;;
    partial)
      echo "FATAL: incomplete Linux wheelhouse assets (fail-closed)." >&2
      echo "       Requires both wheelhouse-v3-linux/ and wheelhouse-v3-linux-sha256.csv." >&2
      echo "         - wheelhouse-v3-linux/            ($([ -d "$LINUX_WHEELHOUSE" ] && echo present || echo MISSING))" >&2
      echo "         - wheelhouse-v3-linux-sha256.csv  ($([ -f "$LINUX_WHEELHOUSE_SHA" ] && echo present || echo MISSING))" >&2
      exit 1 ;;
    none)
      echo "    source: package index (pinned versions, Linux-compatible wheels)"
      install_from_index ;;
  esac
else
  case "$(wheelhouse_pair_state "$WIN_WHEELHOUSE" "$WIN_WHEELHOUSE_SHA")" in
    both)
      echo "    source: Windows offline wheelhouse (verified)"
      install_from_wheelhouse "$WIN_WHEELHOUSE" "$WIN_WHEELHOUSE_SHA" ;;
    partial)
      echo "FATAL: incomplete Windows wheelhouse assets (fail-closed)." >&2
      echo "       Requires both wheelhouse-v3/ and wheelhouse-v3-sha256.csv." >&2
      exit 1 ;;
    none)
      echo "    source: package index (pinned versions)"
      install_from_index ;;
  esac
fi

echo "==> Verifying installed versions match the contract exactly (fail-closed)"
python - "$REQUIREMENTS" <<'PY'
import sys
from importlib.metadata import version, PackageNotFoundError
req = sys.argv[1]
mismatch = []
with open(req) as fh:
    for line in fh:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, want = line.partition("==")
        name, want = name.strip(), want.strip()
        try:
            got = version(name)
        except PackageNotFoundError:
            mismatch.append(f"{name}: MISSING (want {want})"); continue
        if got != want:
            mismatch.append(f"{name}: got {got}, want {want}")
if mismatch:
    sys.exit("FATAL: dependency version mismatch (fail-closed):\n  " + "\n  ".join(mismatch))
print("    all pinned versions match the contract")
PY

echo "==> Development environment setup complete"
