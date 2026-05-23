#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="${1:-${SCRIPT_DIR}/images.conf}"
DOCKER_DIR="${SCRIPT_DIR}"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Config file not found: $CONFIG_FILE"
  echo ""
  echo "Usage: $0 [config-file-path]"
  echo "Default: ${SCRIPT_DIR}/images.conf"
  exit 1
fi

echo "=== Docker Image Build Start ==="
echo "Config: $CONFIG_FILE"
echo ""

# --- Phase 1: Build base image(s) synchronously ---
echo "--- Phase 1: Base image(s) ---"
base_built=0

while IFS= read -r line || [ -n "$line" ]; do
  line="$(echo "$line" | sed 's/#.*//' | xargs)"
  [ -z "$line" ] && continue

  read -r image_tag node_version java_version jdk_dist dockerfile <<< "$line"
  [ -z "$image_tag" ] || [ -z "$node_version" ] && continue

  dockerfile="${dockerfile:-Dockerfile}"
  [ "$dockerfile" != "base.Dockerfile" ] && continue

  echo "Building base: $image_tag"
  docker build -t "$image_tag" -f "${DOCKER_DIR}/${dockerfile}" "$DOCKER_DIR"
  echo "OK: $image_tag"
  base_built=$((base_built + 1))
done < "$CONFIG_FILE"

echo "Base image(s) built: $base_built"
echo ""

# --- Phase 2: Build remaining images in parallel ---
echo "--- Phase 2: Remaining images (parallel) ---"

LOG_DIR="$(mktemp -d)"
pids=()
tags=()

while IFS= read -r line || [ -n "$line" ]; do
  line="$(echo "$line" | sed 's/#.*//' | xargs)"
  [ -z "$line" ] && continue

  read -r image_tag node_version java_version jdk_dist dockerfile <<< "$line"

  if [ -z "$image_tag" ] || [ -z "$node_version" ]; then
    echo "Invalid format (skipped): $line"
    continue
  fi

  # Skip base images (already built in Phase 1)
  dockerfile="${dockerfile:-Dockerfile}"
  [ "$dockerfile" = "base.Dockerfile" ] && continue

  # - means skip/default
  [ "${java_version:-}" = "-" ] && java_version=""
  [ "${jdk_dist:-}" = "-" ] && jdk_dist=""
  jdk_dist="${jdk_dist:-temurin}"

  desc="$image_tag (Node ${node_version}"
  [ -n "${java_version:-}" ] && desc="$desc, Java $java_version, JDK $jdk_dist"
  [ "$dockerfile" != "Dockerfile" ] && desc="$desc, $dockerfile"
  desc="$desc)"
  echo "Starting: $desc"

  build_args=(--build-arg "NODE_VERSION=${node_version}")
  if [ -n "${java_version:-}" ]; then
    build_args+=(--build-arg "JAVA_VERSION=${java_version}")
    build_args+=(--build-arg "JDK_DIST=${jdk_dist}")
  fi

  log_file="${LOG_DIR}/${image_tag//[:\/]/_}.log"
  docker build "${build_args[@]}" -t "$image_tag" -f "${DOCKER_DIR}/${dockerfile}" "$DOCKER_DIR" \
    > "$log_file" 2>&1 &
  pids+=($!)
  tags+=("$image_tag")
done < "$CONFIG_FILE"

echo ""
echo "Waiting for ${#pids[@]} build(s)..."
echo ""

built=0
failed=0

for i in "${!pids[@]}"; do
  pid="${pids[$i]}"
  tag="${tags[$i]}"
  log_file="${LOG_DIR}/${tag//[:\/]/_}.log"

  if wait "$pid"; then
    echo "OK: $tag"
    built=$((built + 1))
  else
    echo "FAILED: $tag (see log below)"
    echo "--- $tag log ---"
    tail -20 "$log_file"
    echo "---"
    failed=$((failed + 1))
  fi
done

rm -rf "$LOG_DIR"

echo ""
echo "=== Build Done: $((base_built + built)) succeeded, ${failed} failed ==="
[ "$failed" -eq 0 ]
