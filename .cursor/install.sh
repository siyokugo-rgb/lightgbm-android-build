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

# Optional authoritative offline training assets (used when present).
LOCKFILE="$REPO_ROOT/nar-v3-training-requirements.lock"
WHEELHOUSE="$REPO_ROOT/wheelhouse-v3"
WHEELHOUSE_SHA="$REPO_ROOT/wheelhouse-v3-sha256.csv"

CURL=(curl --proto '=https' --tlsv1.2 -fsSL)

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

# Authoritative offline dependency path.
#
# It requires ALL THREE assets to be present together:
#   - nar-v3-training-requirements.lock
#   - wheelhouse-v3/
#   - wheelhouse-v3-sha256.csv
# If any one of them is missing, the authoritative path must NOT be used.
#
# NOTE: the real schema of wheelhouse-v3-sha256.csv has not been confirmed yet.
# We intentionally do NOT guess a CSV schema or implement its verification here.
# Until the real file is available and its schema is confirmed, the authoritative
# path is fail-closed and this script refuses to install from it.
present=0
[ -f "$LOCKFILE" ]      && present=$((present + 1))
[ -d "$WHEELHOUSE" ]    && present=$((present + 1))
[ -f "$WHEELHOUSE_SHA" ] && present=$((present + 1))

if [ "$present" -eq 0 ]; then
  echo "==> NOTE: authoritative offline assets not present" \
       "(nar-v3-training-requirements.lock / wheelhouse-v3 / wheelhouse-v3-sha256.csv)."
  echo "          Installing PROVISIONAL, UNVERIFIED versions from requirements.txt."
  echo "          These are NOT confirmed as the official training environment."
  python -m pip install -r requirements.txt
elif [ "$present" -eq 3 ]; then
  echo "FATAL: authoritative offline assets are present, but the" >&2
  echo "       wheelhouse-v3-sha256.csv schema has not been confirmed." >&2
  echo "       Refusing to install unverified dependencies (fail-closed)." >&2
  echo "       Confirm the CSV schema and implement verified offline" >&2
  echo "       installation before enabling this path." >&2
  exit 1
else
  echo "FATAL: incomplete authoritative offline dependency assets (fail-closed)." >&2
  echo "       The authoritative path requires ALL of:" >&2
  echo "         - nar-v3-training-requirements.lock  ($([ -f "$LOCKFILE" ] && echo present || echo MISSING))" >&2
  echo "         - wheelhouse-v3/                      ($([ -d "$WHEELHOUSE" ] && echo present || echo MISSING))" >&2
  echo "         - wheelhouse-v3-sha256.csv            ($([ -f "$WHEELHOUSE_SHA" ] && echo present || echo MISSING))" >&2
  exit 1
fi

echo "==> Development environment setup complete"
