#!/usr/bin/env bash
# 从 GitHub Releases 下载运行时包到 android/app/src/main/assets/
# 用法: ./fetch-assets.sh [版本号，默认 v0.1.0]
set -euo pipefail

VERSION="${1:-v0.1.0}"
REPO="yang12535/kimipocket"
DEST="$(cd "$(dirname "$0")" && pwd)/android/app/src/main/assets"
mkdir -p "$DEST"

for f in runtime.pkg kimihome.pkg; do
    echo "下载 $f ($VERSION) ..."
    curl -fL --retry 3 -o "$DEST/$f" \
        "https://github.com/$REPO/releases/download/$VERSION/$f"
done
echo "完成，可以执行: cd android && ./gradlew assembleDebug"
