#!/usr/bin/env bash
# 从 GitHub Releases 下载运行时包到 android/app/src/main/assets/
#
# 用法: ./fetch-assets.sh [版本号]
#   不传版本号时自动从 android/app/build.gradle 的 versionName 推导。
#
# 资产名从 Release API 解析（支持 runtime-X.Y.Z.pkg / kimihome-X.Y.Z.pkg
# 以及无版本号的 runtime.pkg / kimihome.pkg 两种命名），下载后统一
# 存为 runtime.pkg / kimihome.pkg（构建系统只认这个名字）。
#
# 校验：若有 .sha256 资产则下载校验；否则校验文件非空 + gzip 魔数。
# 网络走环境已有代理（http_proxy/https_proxy），脚本不写死代理。
set -euo pipefail

REPO="yang12535/kimipocket"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEST="$SCRIPT_DIR/android/app/src/main/assets"

# ── 推导版本号 ────────────────────────────────────────────────────
if [ $# -ge 1 ]; then
    VERSION="$1"
else
    GRADLE="$SCRIPT_DIR/android/app/build.gradle"
    if [ ! -f "$GRADLE" ]; then
        GRADLE="$SCRIPT_DIR/android/app/build.gradle.kts"
    fi
    if [ ! -f "$GRADLE" ]; then
        echo "!! 找不到 build.gradle，请手动指定版本号: $0 v0.2.0" >&2
        exit 1
    fi
    VERSION=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE" 2>/dev/null || true)
    if [ -z "$VERSION" ]; then
        echo "!! build.gradle 中找不到 versionName，请手动指定版本号" >&2
        exit 1
    fi
    VERSION="v$VERSION"
fi
echo "目标版本: $VERSION"

# ── 从 GitHub API 解析资产下载链接 ────────────────────────────────
API_URL="https://api.github.com/repos/$REPO/releases/tags/$VERSION"
echo "查询 Release 元数据: $API_URL"

RELEASE_JSON=$(curl -fsSL --retry 3 "$API_URL") || {
    echo "!! 无法获取 Release 信息（网络问题或版本不存在: $VERSION）" >&2
    exit 1
}

# 解析资产的 browser_download_url；优先带版本号的资产名，回退到无版本号
find_asset_url() {
    local prefix="$1"
    echo "$RELEASE_JSON" | python3 -c "
import sys, json
data = json.load(sys.stdin)
prefix = sys.argv[1]
versioned = None
plain = None
for a in data.get('assets', []):
    name = a['name']
    if name.startswith(prefix) and name.endswith('.pkg'):
        if '-' in name[len(prefix):]:
            versioned = a['browser_download_url']
        else:
            plain = a['browser_download_url']
print(versioned or plain or '')
" "$prefix" 2>/dev/null
}

RUNTIME_URL=$(find_asset_url "runtime")
KIMIHOME_URL=$(find_asset_url "kimihome")

if [ -z "$RUNTIME_URL" ]; then
    echo "!! Release 中找不到 runtime-*.pkg 资产" >&2
    echo "   可用资产:" >&2
    echo "$RELEASE_JSON" | python3 -c "
import sys, json
for a in json.load(sys.stdin).get('assets', []):
    print(f'   {a[\"name\"]}  ({a[\"size\"]} bytes)')
" >&2
    exit 1
fi

if [ -z "$KIMIHOME_URL" ]; then
    echo "!! Release 中找不到 kimihome-*.pkg 资产" >&2
    exit 1
fi

echo "  runtime: $RUNTIME_URL"
echo "  kimihome: $KIMIHOME_URL"

# ── 下载 ──────────────────────────────────────────────────────────
mkdir -p "$DEST"

download_and_verify() {
    local url="$1" dest="$2" label="$3"

    echo "下载 $label ..."
    curl -fL --retry 3 -o "$dest" "$url"

    # 校验文件非空
    local size
    size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null)
    if [ "$size" -eq 0 ]; then
        echo "!! $label: 下载文件为空" >&2
        return 1
    fi
    echo "  大小: $(numfmt --to=iec "$size" 2>/dev/null || echo "${size} bytes")"

    # gzip 魔数校验（1f 8b）
    local magic
    magic=$(od -A n -t x1 -N 2 "$dest" 2>/dev/null | tr -d ' \n')
    if [ "${magic:0:4}" != "1f8b" ]; then
        echo "!! $label: 非 gzip 格式（魔数: $magic）" >&2
        return 1
    fi

    # 尝试下载 sha256 校验文件
    local sha_url="${url}.sha256"
    local sha_dest="${dest}.sha256"
    if curl -fsSL --retry 1 -o "$sha_dest" "$sha_url" 2>/dev/null; then
        local expected_sha
        expected_sha=$(awk '{print $1}' "$sha_dest")
        local actual_sha
        actual_sha=$(sha256sum "$dest" | awk '{print $1}')
        if [ "$expected_sha" = "$actual_sha" ]; then
            echo "  SHA256 校验通过 ✓"
        else
            echo "!! $label: SHA256 不匹配" >&2
            echo "   期望: $expected_sha" >&2
            echo "   实际: $actual_sha" >&2
            return 1
        fi
    else
        echo "  无 .sha256 资产，跳过哈希校验（gzip 魔数已通过）"
    fi

    echo "  $label ✓"
}

download_and_verify "$RUNTIME_URL" "$DEST/runtime.pkg" "runtime.pkg"
download_and_verify "$KIMIHOME_URL" "$DEST/kimihome.pkg" "kimihome.pkg"

echo ""
echo "下载完成 ($VERSION)，资产位于: $DEST/"
echo "可以执行: cd android && ./gradlew assembleDebug"
