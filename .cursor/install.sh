#!/usr/bin/env bash
#
# Idempotent development-environment bootstrap for lightgbm-android-build.
#
# Sets up both project components:
#   1. Android app  (Gradle + Kotlin + native LightGBM built via NDK/CMake)
#   2. Python tooling (NAR dataset/training scripts under tools/)
#
# Safe to run repeatedly: every step checks for existing state before acting.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "==> Installing system packages (JDK 17, Python venv, tooling)"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  openjdk-17-jdk-headless \
  python3-venv \
  python3-pip \
  unzip \
  curl

echo "==> Setting up Android command-line tools"
if [ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
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

echo "==> Setting up Python virtual environment (.venv) and ML dependencies"
if [ ! -x "$REPO_ROOT/.venv/bin/python" ]; then
  python3 -m venv .venv
fi
# shellcheck disable=SC1091
. .venv/bin/activate
pip install --upgrade pip -q
pip install -q -r requirements.txt

echo "==> Development environment setup complete"
