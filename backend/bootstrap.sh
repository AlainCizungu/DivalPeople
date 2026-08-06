#!/usr/bin/env bash
#
# One-time bootstrap for the DIP backend.
#
# Generates the Gradle wrapper (./gradlew) so nothing else in this repo depends on Gradle being
# installed globally. Safe to re-run.
#
# Two different JDKs are in play, which is worth understanding:
#   * the JDK Gradle itself RUNS on — must be a version this Gradle release supports
#   * the JDK the project COMPILES with — pinned to 21 by the toolchain in build.gradle.kts
# This script runs Gradle on a JDK 21 when it can find one, because that combination is the most
# widely supported. Your default `java` can be anything.
#
# Usage:  ./bootstrap.sh

set -euo pipefail

GRADLE_VERSION="8.14.3"
REQUIRED_MAJOR=21

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

info() { printf '\033[0;34m•\033[0m %s\n' "$1"; }
ok()   { printf '\033[0;32m✓\033[0m %s\n' "$1"; }
warn() { printf '\033[0;33m!\033[0m %s\n' "$1"; }
fail() { printf '\033[0;31m✗\033[0m %s\n' "$1" >&2; }

java_major_of() {
    # "21.0.5" -> 21   /   "1.8.0_402" -> 8
    local raw major
    raw="$("$1" -version 2>&1 | head -1 | sed -E 's/.*version "([^"]+)".*/\1/')"
    major="$(printf '%s' "$raw" | cut -d. -f1)"
    if [ "$major" = "1" ]; then
        major="$(printf '%s' "$raw" | cut -d. -f2)"
    fi
    printf '%s' "$major"
}

# ---------------------------------------------------------------------------
# 1. Find a JDK to run Gradle on — prefer exactly 21
# ---------------------------------------------------------------------------
if [ -x /usr/libexec/java_home ]; then
    # macOS: ask the system for a 21 specifically, ignoring whatever the default is.
    if JAVA_21_HOME="$(/usr/libexec/java_home -v "$REQUIRED_MAJOR" 2>/dev/null)"; then
        export JAVA_HOME="$JAVA_21_HOME"
        export PATH="$JAVA_HOME/bin:$PATH"
        ok "Using JDK $REQUIRED_MAJOR at $JAVA_HOME"
    fi
fi

if ! command -v java >/dev/null 2>&1; then
    fail "Java is not installed."
    cat <<EOF

Install a JDK $REQUIRED_MAJOR, then re-run this script.

  macOS, Homebrew:   brew install --cask temurin@$REQUIRED_MAJOR
  macOS, no brew:    https://adoptium.net/temurin/releases/?version=$REQUIRED_MAJOR
  SDKMAN (any OS):   sdk install java $REQUIRED_MAJOR-tem

EOF
    exit 1
fi

JAVA_MAJOR="$(java_major_of java)"

if [ "$JAVA_MAJOR" -lt "$REQUIRED_MAJOR" ] 2>/dev/null; then
    fail "Java $JAVA_MAJOR found, but this project needs Java $REQUIRED_MAJOR or newer."
    cat <<EOF

  macOS, Homebrew:   brew install --cask temurin@$REQUIRED_MAJOR
  SDKMAN (any OS):   sdk install java $REQUIRED_MAJOR-tem

EOF
    exit 1
fi

if [ "$JAVA_MAJOR" -gt "$REQUIRED_MAJOR" ]; then
    warn "Running Gradle on Java $JAVA_MAJOR. Gradle $GRADLE_VERSION supports it, but a JDK $REQUIRED_MAJOR"
    warn "is the better-tested combination. Install one and re-run to switch:"
    warn "  brew install --cask temurin@$REQUIRED_MAJOR"
else
    ok "Java $JAVA_MAJOR"
fi

# ---------------------------------------------------------------------------
# 2. Gradle wrapper
# ---------------------------------------------------------------------------
if [ -f "./gradlew" ] && [ -f "./gradle/wrapper/gradle-wrapper.jar" ]; then
    ok "Gradle wrapper already present"
else
    generate_wrapper() {
        # `wrapper` is the one task that must configure cleanly on a bare project, so exclude
        # everything else: a compile error elsewhere must not block bootstrapping.
        "$1" wrapper --gradle-version "$GRADLE_VERSION" --no-daemon --quiet
    }

    if command -v gradle >/dev/null 2>&1 && [ "$(java_major_of java)" -le 24 ]; then
        info "Generating the wrapper with your installed Gradle..."
        generate_wrapper gradle
    else
        info "Downloading Gradle $GRADLE_VERSION temporarily..."

        TMP_DIR="$(mktemp -d)"
        # shellcheck disable=SC2064
        trap "rm -rf '$TMP_DIR'" EXIT

        ZIP_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
        if ! curl -fsSL -o "$TMP_DIR/gradle.zip" "$ZIP_URL"; then
            fail "Could not download Gradle from $ZIP_URL"
            echo "Check your network, or install Gradle manually: brew install gradle" >&2
            exit 1
        fi

        unzip -q "$TMP_DIR/gradle.zip" -d "$TMP_DIR"
        info "Generating the wrapper..."
        generate_wrapper "$TMP_DIR/gradle-${GRADLE_VERSION}/bin/gradle"
    fi

    chmod +x ./gradlew
    ok "Created ./gradlew (Gradle $GRADLE_VERSION)"
fi

# ---------------------------------------------------------------------------
# 3. Docker, needed by the integration tests
# ---------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok "Docker is running"
else
    warn "Docker is not running. Unit tests will pass; Testcontainers integration tests will not."
fi

cat <<'EOF'

Bootstrap complete. Next:

  ./gradlew test      run the tests (integration tests need Docker)
  ./gradlew bootRun   start the API on :8080

EOF
