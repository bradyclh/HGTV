#!/usr/bin/env bash
# 在 Mac 上啟動紅果TV 網頁版並用預設瀏覽器打開。
# 用法：tools/web.sh [--port 8686] [--host 0.0.0.0] [--token <x-tt-token>]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
"$root/android/gradlew" -q -p "$root/android" :web:installDist
exec "$root/android/web/build/install/web/bin/web" --open "$@"
