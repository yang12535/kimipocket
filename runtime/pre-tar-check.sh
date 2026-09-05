#!/usr/bin/env bash
# 打包前置校验：确认钩子、apt 配置、包清单、dpkg 元数据齐全后才允许打 tar。
# 在 add-pkg-manager.sh + merge-phone-dpkg-info.sh 之后、tar 之前执行。
# 见 issue #7。
set -euo pipefail
cd "$(dirname "$0")"

STAGING="staging-final/usr"
ERRORS=0

check_file() {
    local f="$1" desc="$2"
    if [ ! -f "$STAGING/$f" ]; then
        echo "!! 缺失: $STAGING/$f ($desc)" >&2
        ERRORS=$((ERRORS + 1))
    else
        echo "  ✓ $f"
    fi
}

check_dir() {
    local d="$1" desc="$2"
    if [ ! -d "$STAGING/$d" ]; then
        echo "!! 缺失目录: $STAGING/$d ($desc)" >&2
        ERRORS=$((ERRORS + 1))
    else
        echo "  ✓ $d/"
    fi
}

echo "── 打包前置校验 ──"

# 钩子文件（issue #7）
echo "[钩子]"
check_file "libexec/kimbox-deb-rewrite" "DPkg::Pre-Install-Pkgs 入口脚本"
check_file "libexec/kimbox-deb-rewrite.js" "deb 内容重写 node 脚本"
check_file "etc/apt/apt.conf.d/99-kimbox" "注册钩子的 apt 配置"

# 可执行权限
if [ -f "$STAGING/libexec/kimbox-deb-rewrite" ] && [ ! -x "$STAGING/libexec/kimbox-deb-rewrite" ]; then
    echo "!! kimbox-deb-rewrite 缺少可执行位" >&2
    ERRORS=$((ERRORS + 1))
fi

# dpkg 基础设施
echo "[dpkg]"
check_file "var/lib/dpkg/status" "包状态数据库"
check_dir  "var/lib/dpkg/info" "包元数据目录"

# 包清单（status 中至少要有 60+ 个 installed 包）
if [ -f "$STAGING/var/lib/dpkg/status" ]; then
    N_INSTALLED=$(grep -c '^Status: install ok installed' "$STAGING/var/lib/dpkg/status" 2>/dev/null) || true
    N_INSTALLED=${N_INSTALLED:-0}
    if [ "$N_INSTALLED" -lt 60 ]; then
        echo "!! status 中仅 $N_INSTALLED 个已安装包（预期 ≥60）" >&2
        ERRORS=$((ERRORS + 1))
    else
        echo "  ✓ status: $N_INSTALLED 个已安装包"
    fi
fi

# info 目录下应有 .list 文件
if [ -d "$STAGING/var/lib/dpkg/info" ]; then
    N_LIST=$(find "$STAGING/var/lib/dpkg/info" -name '*.list' | wc -l)
    if [ "$N_LIST" -lt 60 ]; then
        echo "!! info/ 下仅 $N_LIST 个 .list 文件（预期 ≥60）" >&2
        ERRORS=$((ERRORS + 1))
    else
        echo "  ✓ info/: $N_LIST 个 .list 文件"
    fi
fi

# apt 源
echo "[apt]"
check_file "etc/apt/sources.list" "apt 源配置"

# 关键二进制
echo "[核心]"
check_file "bin/node" "node 运行时"
check_file "bin/bash" "bash"

echo ""
if [ "$ERRORS" -gt 0 ]; then
    echo "!! 前置校验失败（$ERRORS 项），不允许打 tar" >&2
    exit 1
fi
echo "前置校验通过 ✓（可以打 tar）"
