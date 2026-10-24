#!/usr/bin/env bash
# Run the plain-JVM unit test for the TV-side H.264 helper (H264Util).
#
#   ./tools/run-media-tests.sh
#
# No Gradle / no Android SDK required: both helpers are pure Java. A working javac/java
# (8 or newer) is discovered from JAVA_HOME, Android Studio's bundled JBR, or PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/work/media-tests"
CLASSES="$WORK/classes"
SRC_UTILS=(
  "$ROOT/app/src/app/webcast/media/H264Util.java"
)
SRC_TESTS=(
  "$ROOT/tests/media/H264UtilTest.java"
)

find_tool() {
  # find_tool <tool-name>; prints an executable that actually runs
  local tool="$1"
  local candidates=()
  if [ -n "${JAVA_HOME:-}" ]; then
    candidates+=("$JAVA_HOME/bin/$tool")
  fi
  candidates+=("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/$tool")
  local on_path
  on_path="$(command -v "$tool" 2>/dev/null || true)"
  if [ -n "$on_path" ]; then
    candidates+=("$on_path")
  fi
  local candidate
  for candidate in "${candidates[@]}"; do
    if [ -x "$candidate" ] && "$candidate" -version >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

if ! JAVAC="$(find_tool javac)"; then
  echo "ERROR: no working javac found (JDK 8+). Set JAVA_HOME." >&2
  exit 1
fi
if ! JAVA="$(find_tool java)"; then
  echo "ERROR: no working java found (JRE 8+). Set JAVA_HOME." >&2
  exit 1
fi
# Prefer javac/java from the same JDK installation.
JAVA_HOME_GUESS="$(cd "$(dirname "$JAVAC")/.." && pwd)"
if [ -x "$JAVA_HOME_GUESS/bin/java" ] && "$JAVA_HOME_GUESS/bin/java" -version >/dev/null 2>&1; then
  JAVA="$JAVA_HOME_GUESS/bin/java"
fi

for src in "${SRC_UTILS[@]}" "${SRC_TESTS[@]}"; do
  [ -f "$src" ] || { echo "ERROR: missing $src" >&2; exit 1; }
done

echo "media tests: $("$JAVAC" -version 2>&1 | head -n1) ($JAVAC)"
rm -rf "$WORK"
mkdir -p "$CLASSES"

"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -d "$CLASSES" "${SRC_UTILS[@]}" "${SRC_TESTS[@]}"

"$JAVA" -cp "$CLASSES" app.webcast.media.H264UtilTest
