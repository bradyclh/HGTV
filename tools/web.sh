#!/usr/bin/env bash
# 在 Mac 上啟動紅果TV 網頁版並用預設瀏覽器打開。
# 用法：tools/web.sh [--no-open] [--port 8686] [--host 0.0.0.0] [--token <x-tt-token>]
#                    [--access-team <團隊名> --access-aud <AUD tag>]   # Cloudflare Access，見 docs/CLOUDFLARE.md
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
open_flag=(--open)
args=()
for arg in "$@"; do
  if [[ "$arg" == "--no-open" ]]; then open_flag=(); else args+=("$arg"); fi
done
"$root/android/gradlew" -q -p "$root/android" :web:installDist
# ${x[@]+...}：macOS 內建 bash 3.2 在 set -u 下展開空陣列會報錯。
exec "$root/android/web/build/install/web/bin/web" ${open_flag[@]+"${open_flag[@]}"} ${args[@]+"${args[@]}"}
