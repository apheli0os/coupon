#!/usr/bin/env bash
set -euo pipefail

with_containers=false
for arg in "$@"; do
  case "$arg" in
    --with-containers)
      with_containers=true
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ "$with_containers" == true ]]; then
  echo "[1/3] Running Maven unit tests, integration tests, Checkstyle, and SpotBugs..."
  mvn -B verify checkstyle:check spotbugs:check
else
  echo "[1/3] Running Maven unit tests, IT compile check, Checkstyle, and SpotBugs..."
  mvn -B test checkstyle:check spotbugs:check
fi

echo "[2/3] Running formal TLA+ verification..."
bash "$repo_root/scripts/verify-tla.sh"

echo "[3/3] Running frontend typecheck and build..."
cd "$repo_root/web"
if [[ ! -d node_modules ]]; then
  npm ci
fi
npm run build

echo "All local verification gates passed."
