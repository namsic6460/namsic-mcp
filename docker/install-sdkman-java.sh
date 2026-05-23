#!/bin/bash
set -e

JAVA_VERSION="$1"
JDK_DIST="$2"

if [ -z "$JAVA_VERSION" ]; then
  echo "Usage: install-sdkman-java.sh <java-version> <jdk-dist>"
  exit 1
fi

# Map JDK_DIST to SDKMAN vendor identifier
case "$JDK_DIST" in
  corretto) VENDOR=amzn ;;
  temurin)  VENDOR=tem ;;
  graalvm)  VENDOR=graalce ;;
  *)        VENDOR="$JDK_DIST" ;;
esac

source "$SDKMAN_DIR/bin/sdkman-init.sh"

# Find the latest matching version (e.g., 21 → 21.0.10-amzn)
JAVA_ID="$(sdk list java 2>/dev/null | grep -oP "\S+-${VENDOR}" | grep "^${JAVA_VERSION}" | head -1)"

if [ -z "$JAVA_ID" ]; then
  echo "ERROR: No SDKMAN match for Java ${JAVA_VERSION} with vendor ${VENDOR}"
  echo "Available versions:"
  sdk list java 2>/dev/null | grep "${VENDOR}" || true
  exit 1
fi

echo "Installing JDK via SDKMAN: $JAVA_ID"
sdk install java "$JAVA_ID"
sdk default java "$JAVA_ID"
echo "JDK installed: $(java -version 2>&1 | head -1)"
