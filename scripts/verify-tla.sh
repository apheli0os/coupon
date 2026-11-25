#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
models_dir="$repo_root/formal/models"
cache_dir="$repo_root/.cache"
cached_jar="$cache_dir/tla2tools.jar"
local_plugin_jar="$repo_root/.agents/plugins/pstack/.tools/tla2tools.jar"
download_url="https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar"

if [[ -n "${TLA2TOOLS:-}" && -f "${TLA2TOOLS}" ]]; then
  tla_jar="$TLA2TOOLS"
elif [[ -f "$local_plugin_jar" ]]; then
  tla_jar="$local_plugin_jar"
elif [[ -f "$cached_jar" ]]; then
  tla_jar="$cached_jar"
else
  mkdir -p "$cache_dir"
  curl -fsSL "$download_url" -o "$cached_jar"
  tla_jar="$cached_jar"
fi

if [[ -n "${JAVA:-}" && -x "${JAVA}" ]]; then
  java_cmd="$JAVA"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  java_cmd="${JAVA_HOME}/bin/java"
else
  java_cmd="java"
fi

cd "$models_dir"
for model in SeckillProtocol CouponLifecycle ActivityTemplateLifecycle StockReconcileDebounce; do
  meta_dir="$(mktemp -d)"
  echo "Verifying TLA+ model: $model"
  set +e
  "$java_cmd" -XX:+UseParallelGC -cp "$tla_jar" tlc2.TLC -workers auto -cleanup -metadir "$meta_dir" -config "${model}.cfg" "${model}.tla"
  rc=$?
  set -e
  rm -rf "$meta_dir"
  if [[ $rc -ne 0 ]]; then
    exit $rc
  fi
done
