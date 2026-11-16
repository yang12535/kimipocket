#!/usr/bin/env bash
# runtime/tests/run-hook-tests.sh —— deb 重写钩子的 PC 侧回归测试（不需要手机/ADB）。
#
# 用合成 deb 驱动真实的 bash 钩子与 node 脚本：不复制逻辑、不 mock dpkg-deb，
# 只把钩子要用的工具软链进一个假前缀（KIMBOX_PREFIX），让它按设备上的方式跑。
#
# 覆盖：改写正确性（路径/内容/符号链接/权限）、跨块边界二进制、md5sums、
# 压缩保真、幂等、stdin 行为、安全失败（损坏 deb / 缺 node / 重打包异常）、
# 信号中止与原子性。
#
# 用法：bash runtime/tests/run-hook-tests.sh [-k]     -k 保留工作目录便于排查
set -uo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "$0")" && pwd)
RUNTIME=$(cd "$HERE/.." && pwd)
HOOK="$RUNTIME/hooks/usr/libexec/kimbox-deb-rewrite"
HOOK_JS="$RUNTIME/hooks/usr/libexec/kimbox-deb-rewrite.js"

KEEP=0
[ "${1:-}" = "-k" ] && KEEP=1

# 工作目录要能执行脚本（有几条用例把 dpkg-deb 换成 wrapper 来模拟重打包失败/卡住），
# 所以先探测：/tmp、/dev/shm 之类常挂 noexec，都不可用就退到本仓库目录下（用完即删）。
pick_workdir() {
    local base d
    for base in "${TMPDIR:-/tmp}" /dev/shm "$HERE"; do
        [ -d "$base" ] && [ -w "$base" ] || continue
        d=$(mktemp -d "$base/kimbox-hook-tests.XXXXXX" 2>/dev/null) || continue
        printf '#!/bin/sh\nexit 7\n' > "$d/.exec-probe"
        chmod 755 "$d/.exec-probe" 2>/dev/null
        "$d/.exec-probe" >/dev/null 2>&1
        if [ $? -eq 7 ]; then
            rm -f "$d/.exec-probe"
            printf '%s\n' "$d"
            return 0
        fi
        rm -rf "$d"
    done
    return 1
}

WORK=$(pick_workdir) || { printf '找不到可执行的临时目录（/tmp、/dev/shm、仓库目录都试过了）\n' >&2; exit 1; }
PASSED=0
FAILED=0
SKIPPED=0

cleanup_all() {
    if [ "$KEEP" -eq 0 ]; then
        rm -rf "$WORK"
    else
        printf '\n工作目录保留在: %s\n' "$WORK"
    fi
}
trap cleanup_all EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ok()   { PASSED=$((PASSED + 1)); printf '  ok   %s\n' "$*"; }
bad()  { FAILED=$((FAILED + 1)); printf '  FAIL %s\n' "$*"; }
skip() { SKIPPED=$((SKIPPED + 1)); printf '  skip %s\n' "$*"; }

assert_eq() {
    if [ "$1" = "$2" ]; then ok "$3"; else bad "$3（期望 [$1]，实际 [$2]）"; fi
}
assert_ne() {
    if [ "$1" != "$2" ]; then ok "$3"; else bad "$3（两者相同：[$1]）"; fi
}
assert_file_eq() {
    if cmp -s "$1" "$2"; then ok "$3"; else bad "$3（$1 与 $2 内容不同）"; fi
}
assert_match() {
    if grep -q -- "$1" "$2" 2>/dev/null; then ok "$3"; else bad "$3（日志里没有 [$1]）"; fi
}
assert_not_match() {
    if grep -q -- "$1" "$2" 2>/dev/null; then bad "$3（日志里出现了 [$1]）"; else ok "$3"; fi
}

# 进程是否还活着（僵尸算已死，避免 init 回收慢导致误判）
pid_alive() {
    local line
    [ -r "/proc/$1/stat" ] || return 1
    read -r line < "/proc/$1/stat" || return 1
    set -- $line
    [ "${3:-}" != "Z" ]
}

sha() { sha256sum "$1" | cut -d' ' -f1; }

# ── 假前缀 ───────────────────────────────────────────────────────
# 钩子会把 PATH 设成 $P/bin（设备上就该如此），所以测试里把要用的宿主工具全软链进去。
TOOLS="bash sh env node dpkg-deb dpkg xz gzip zstd bzip2 tar find mktemp mv rm mkdir
       chmod chown ln cp cat head tail tr dd cut basename dirname readlink md5sum wc
       sort xargs sed grep sleep touch stat cmp date uname true false printf"

make_prefix() { # $1 = 目录；回显前缀路径
    local p=$1 t c
    mkdir -p "$p/bin" "$p/libexec" "$p/lib" "$p/tmp"
    cp "$HOOK_JS" "$p/libexec/kimbox-deb-rewrite.js"
    for t in $TOOLS; do
        c=$(command -v "$t" 2>/dev/null) || continue
        [ -e "$p/bin/$t" ] || ln -s "$c" "$p/bin/$t"
    done
    printf '%s\n' "$p"
}

# ── 合成 deb 工具 ────────────────────────────────────────────────
new_tree() { # $1 = 目录
    mkdir -p "$1/DEBIAN"
    printf 'Package: kimbox-test\nVersion: 1.0\nArchitecture: all\nMaintainer: test <test@example.com>\nDescription: synthetic deb for kimbox hook tests\n' \
        > "$1/DEBIAN/control"
}

gen_md5sums() { # $1 = 树根；格式与 dpkg 一致（hash + 两空格 + 相对路径）
    ( cd "$1" && find . -path ./DEBIAN -prune -o -type f -print0 |
        LC_ALL=C sort -z | xargs -0 md5sum | sed 's|  \./|  |' ) > "$1/DEBIAN/md5sums"
}

build_deb() { # $1 树根, $2 输出, $3 压缩（xz|gzip|zstd|none，默认 xz）
    dpkg-deb --nocheck "-Z${3:-xz}" -b "$1" "$2" >/dev/null 2>&1
}

deb_data_member() { # 打印 deb 里 data.tar.* 的成员名
    # control.tar 可能有 10KB+（-Znone 时尤其大），多读一点再找 data.tar
    head -c 1048576 "$1" | grep -ao 'data\.tar[.a-z0-9]*' | head -1
}

run_hook() { # $1 前缀, $2 列表文件, $3 日志；返回钩子退出码
    KIMBOX_PREFIX=$1 bash "$HOOK" < "$2" > "$3" 2>&1
}

# 生成指定大小的文件，并在若干偏移写入 "com.termux"（模拟跨块边界的二进制命中）
make_padded_file() { # $1 路径, $2 大小, 其余为偏移
    local f=$1 size=$2
    shift 2
    head -c "$size" /dev/zero | tr '\0' 'A' > "$f"
    local off
    for off in "$@"; do
        printf 'com.termux' | dd of="$f" bs=1 seek="$off" conv=notrunc status=none
    done
}

# ══════════════════════════════════════════════════════════════════
test_rewrite_basic() {
    local t=$WORK/basic p list log before after out
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin" "$t/tree/data/data/com.termux/files/usr/share/doc"
    new_tree "$t/tree"
    printf '#!/data/data/com.termux/files/usr/bin/bash\necho %s\n' '/data/data/com.termux/files/usr' \
        > "$t/tree/data/data/com.termux/files/usr/bin/demo"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/demo"
    printf 'path=/data/data/com.termux/files/usr/bin\n' \
        > "$t/tree/data/data/com.termux/files/usr/share/doc/readme"
    chmod 0444 "$t/tree/data/data/com.termux/files/usr/share/doc/readme"
    # setuid 位：写入内容会把它清掉，钩子必须恢复原模式
    printf '#!/data/data/com.termux/files/usr/bin/sh\nexit 0\n' \
        > "$t/tree/data/data/com.termux/files/usr/bin/setuid-demo"
    chmod 4755 "$t/tree/data/data/com.termux/files/usr/bin/setuid-demo"
    ln -s /data/data/com.termux/files/usr/bin/demo "$t/tree/data/data/com.termux/files/usr/bin/demo-alias"
    printf '#!/data/data/com.termux/files/usr/bin/sh\nexit 0\n' > "$t/tree/DEBIAN/postinst"
    chmod 0700 "$t/tree/DEBIAN/postinst"
    gen_md5sums "$t/tree"
    # 合法的 md5sums 也允许 ./ 前缀；改写后仍必须重算对应文件的内容哈希
    sed -i 's|  |  ./|' "$t/tree/DEBIAN/md5sums"
    build_deb "$t/tree" "$t/demo.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/demo.deb" > "$list"
    before=$(sha "$t/demo.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "基础改写：钩子退出 0"
    after=$(sha "$t/demo.deb")
    assert_ne "$before" "$after" "基础改写：deb 被重写"

    if dpkg-deb -c "$t/demo.deb" | grep -q 'com\.termux'; then
        bad "基础改写：deb 路径里仍有 com.termux"
    else
        ok "基础改写：deb 路径里已无 com.termux"
    fi
    if dpkg-deb -c "$t/demo.deb" | grep -q 'data/data/com\.kimbox/files/usr/bin/demo$'; then
        ok "基础改写：路径已换成 com.kimbox"
    else
        bad "基础改写：没找到 com.kimbox 路径"
    fi

    out="$t/out"
    dpkg-deb -R "$t/demo.deb" "$out" 2>/dev/null
    if grep -rIl 'com\.termux' "$out" 2>/dev/null | grep -q .; then
        bad "基础改写：解包后仍有文件内容含 com.termux"
    else
        ok "基础改写：解包后文件内容已无 com.termux"
    fi
    assert_eq "755" "$(stat -c %a "$out/data/data/com.kimbox/files/usr/bin/demo")" "基础改写：可执行位保留"
    assert_eq "444" "$(stat -c %a "$out/data/data/com.kimbox/files/usr/share/doc/readme")" "基础改写：只读文件权限保留"
    assert_eq "4755" "$(stat -c %a "$out/data/data/com.kimbox/files/usr/bin/setuid-demo")" \
        "基础改写：setuid 位在写入后恢复"
    assert_eq "/data/data/com.kimbox/files/usr/bin/demo" \
        "$(readlink "$out/data/data/com.kimbox/files/usr/bin/demo-alias")" "基础改写：符号链接目标已重写"
    assert_eq "755" "$(stat -c %a "$out/DEBIAN/postinst")" "基础改写：0700 维护脚本放宽到 0755"
    if ( cd "$out" && md5sum -c DEBIAN/md5sums --quiet ) >/dev/null 2>&1; then
        ok "基础改写：md5sums 与重写后的树一致"
    else
        bad "基础改写：md5sums 校验失败"
    fi
    if grep -q 'com\.termux' "$out/DEBIAN/md5sums"; then
        bad "基础改写：md5sums 里仍有 com.termux 路径"
    else
        ok "基础改写：md5sums 里的路径已改名"
    fi
    assert_eq "data.tar.xz" "$(deb_data_member "$t/demo.deb")" "基础改写：沿用原 deb 的 xz 压缩"
    assert_match '扫描 .* 个文件，重写 ' "$log" "基础改写：node 报告了处理统计"
    assert_match '\[1/1\] 完成' "$log" "基础改写：进度行可见"

    # 幂等：再跑一次应当原样放行，字节不变
    local before2
    before2=$(sha "$t/demo.deb")
    run_hook "$p" "$list" "$log.2"
    assert_eq 0 "$?" "幂等：第二次退出 0"
    assert_eq "$before2" "$(sha "$t/demo.deb")" "幂等：第二次不再改动 deb"
    assert_match '不含 com.termux 前缀，原样放行' "$log.2" "幂等：第二次走的是原样放行"

    assert_eq "" "$(ls -A "$p/tmp")" "基础改写：临时目录已清理"
    assert_eq "demo.deb" "$(ls -A "$t" | grep '\.deb$' | tr '\n' ' ' | sed 's/ $//')" \
        "基础改写：没有残留 .kimbox-new 之类的临时 deb"
}

test_unrelated_passthrough() {
    local t=$WORK/unrelated p list log before
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    mkdir -p "$t/tree/usr/share/demo"
    new_tree "$t/tree"
    # 内容里提到 com.termux 但没有任何 termux 前缀路径：不在重写范围内，必须原样放行
    printf 'see /data/data/com.termux/files/usr/docs\n' > "$t/tree/usr/share/demo/readme"
    gen_md5sums "$t/tree"
    build_deb "$t/tree" "$t/plain.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/plain.deb" > "$list"
    before=$(sha "$t/plain.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "无关 deb：退出 0"
    assert_eq "$before" "$(sha "$t/plain.deb")" "无关 deb：字节未变"
    assert_match '不含 com.termux 前缀，原样放行' "$log" "无关 deb：日志说明原样放行"
    assert_eq "" "$(ls -A "$p/tmp")" "无关 deb：临时目录已清理"
}

test_cross_chunk_binary() {
    local t=$WORK/cross p list log out big size=9300000 b1 b2 b3 b4
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    mkdir -p "$t/tree/data/data/com.termux/files/usr/lib"
    new_tree "$t/tree"
    # 4 MiB 与 8 MiB 边界上各放若干命中：一个完整落在边界前、一个正好跨边界
    b1=$((4 * 1024 * 1024 - 11))
    b2=$((4 * 1024 * 1024 - 5))
    b3=$((8 * 1024 * 1024 - 9))
    b4=$((8 * 1024 * 1024 - 1))
    big="$t/tree/data/data/com.termux/files/usr/lib/blob.bin"
    make_padded_file "$big" "$size" "$b1" "$b2" "$b3" "$b4" 0 $((size - 10))
    # 参考文件：同样的字节布局，但把 termux 换成 kimbox
    make_padded_file "$t/expect.bin" "$size" "$b1" "$b2" "$b3" "$b4" 0 $((size - 10))
    node -e '
        const fs = require("fs");
        const p = process.argv[1];
        const s = fs.readFileSync(p).toString("latin1");
        fs.writeFileSync(p, s.split("com.termux").join("com.kimbox"), "latin1");
    ' "$t/expect.bin"
    gen_md5sums "$t/tree"
    build_deb "$t/tree" "$t/big.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/big.deb" > "$list"

    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "跨块：钩子退出 0"
    out="$t/out"
    dpkg-deb -R "$t/big.deb" "$out" 2>/dev/null
    local got="$out/data/data/com.kimbox/files/usr/lib/blob.bin"
    assert_file_eq "$t/expect.bin" "$got" "跨块：4MiB/8MiB 边界上的二进制替换逐字节正确"
    assert_eq "$size" "$(stat -c %s "$got")" "跨块：文件大小不变（等长替换）"
    if ( cd "$out" && md5sum -c DEBIAN/md5sums --quiet ) >/dev/null 2>&1; then
        ok "跨块：md5sums 与改写后的大文件一致"
    else
        bad "跨块：md5sums 校验失败"
    fi
}

test_node_chunk_sizes() {
    local t=$WORK/chunks c f expect n
    mkdir -p "$t"
    for c in 64 128 4096 4194304; do
        f="$t/f-$c.bin"
        # 长度为 4*chunk+37，命中铺在块边界前后、首尾与连续命中处
        n=$((4 * c + 37))
        make_padded_file "$f" "$n" \
            0 $((c - 10)) $((c - 9)) $((c - 1)) $c $((c + 1)) \
            $((2 * c - 10)) $((2 * c - 5)) $((3 * c - 1)) \
            $((n - 10)) $((n - 1))
        cp "$f" "$t/expect-$c.bin"
        node -e '
            const fs = require("fs");
            const p = process.argv[1];
            const s = fs.readFileSync(p).toString("latin1");
            fs.writeFileSync(p, s.split("com.termux").join("com.kimbox"), "latin1");
        ' "$t/expect-$c.bin"
        printf '%s\0' "$f" > "$t/list-$c"
        node "$HOOK_JS" --list "$t/list-$c" --chunk "$c" --no-progress >/dev/null 2>&1
        assert_eq 0 "$?" "分块 --chunk=$c：node 退出 0"
        assert_file_eq "$t/expect-$c.bin" "$f" "分块 --chunk=$c：跨块替换逐字节正确"
    done
    # 连续命中（com.termuxcom.termux）与相邻命中不能被漏掉
    f="$t/adjacent.bin"
    printf 'com.termuxcom.termuxXcom.termux' > "$f"
    cp "$f" "$t/expect-adjacent.bin"
    printf 'com.kimboxcom.kimboxXcom.kimbox' > "$t/expect-adjacent.bin"
    printf '%s\0' "$f" > "$t/list-adjacent"
    node "$HOOK_JS" --list "$t/list-adjacent" --chunk 64 --no-progress >/dev/null 2>&1
    assert_file_eq "$t/expect-adjacent.bin" "$f" "连续命中：相邻/紧邻的 com.termux 全部替换"
}

# 用 --require 预加载把 readSync/writeSync 限成每次 3 字节，验证短读/短写循环补齐。
test_node_short_write() {
    local t=$WORK/shortwrite f preload
    mkdir -p "$t"
    preload="$t/preload.js"
    cat > "$preload" <<'EOS'
"use strict";
const fs = require("fs");
const cap = Number(process.env.KIMBOX_CAP || 0);
const oread = fs.readSync, owrite = fs.writeSync;
fs.readSync = function (fd, buf, off, len, pos) {
    if (cap > 0) len = Math.min(len, cap);
    return oread.call(fs, fd, buf, off, len, pos);
};
fs.writeSync = function (fd, buf, off, len, pos) {
    if (cap > 0) len = Math.min(len, cap);
    return owrite.call(fs, fd, buf, off, len, pos);
};
EOS
    f="$t/f.bin"
    printf 'com.termux%.0s' {1..1000} > "$f"
    printf 'com.kimbox%.0s' {1..1000} > "$t/expect.bin"
    printf '%s\0' "$f" > "$t/list"
    KIMBOX_CAP=3 node --require "$preload" "$HOOK_JS" --list "$t/list" --chunk 64 --no-progress \
        > "$t/log" 2>&1
    assert_eq 0 "$?" "短写：node 退出 0"
    assert_file_eq "$t/expect.bin" "$f" "短写：每次只读写 3 字节也能逐字节写全"
}

# 统计 writeSync 调用次数：5000 个连续命中应合并成每块一次写，而不是逐命中 5000 次。
test_node_write_batching() {
    local t=$WORK/batch f preload count
    mkdir -p "$t"
    preload="$t/count.js"
    cat > "$preload" <<'EOS'
"use strict";
const fs = require("fs");
const writeFileSync = fs.writeFileSync;
const owrite = fs.writeSync;
let count = 0;
fs.writeSync = function (...args) { count++; return owrite.apply(fs, args); };
process.on("exit", () => {
    if (process.env.KIMBOX_COUNT_FILE) {
        writeFileSync.call(fs, process.env.KIMBOX_COUNT_FILE, String(count));
    }
});
EOS
    f="$t/f.bin"
    printf 'com.termux%.0s' {1..5000} > "$f"
    printf 'com.kimbox%.0s' {1..5000} > "$t/expect.bin"
    printf '%s\0' "$f" > "$t/list"
    KIMBOX_COUNT_FILE="$t/count" node --require "$preload" "$HOOK_JS" --list "$t/list" --chunk 4096 --no-progress \
        > "$t/log" 2>&1
    assert_eq 0 "$?" "批量写：node 退出 0"
    count=$(cat "$t/count" 2>/dev/null || echo MISSING)
    if [ "$count" != "MISSING" ] && [ "$count" -le 100 ]; then
        ok "批量写：5000 个命中只产生 $count 次 writeSync（不是逐命中写）"
    else
        bad "批量写：writeSync 调用次数异常（$count）"
    fi
    assert_file_eq "$t/expect.bin" "$f" "批量写：内容逐字节正确"
}

# 单文件打不开时 node 必须 exit 1，并由 bash 侧整体中止，不能放行未重写的 deb。
test_node_error() {
    local t=$WORK/nodeerr f
    mkdir -p "$t"
    if [ "$(id -u)" -eq 0 ]; then
        skip "错误传播：root 不受 0000 权限限制，跳过"
        return
    fi
    f="$t/f"
    printf 'com.termux' > "$f"
    chmod 000 "$f"
    printf '%s\0' "$f" > "$t/list"
    node "$HOOK_JS" --list "$t/list" --no-progress > "$t/log" 2>&1
    assert_eq 1 "$?" "错误传播：单个文件不可写时 node 退出 1"
    assert_match '无法以可写方式打开' "$t/log" "错误传播：日志说明是哪个文件失败"
    assert_eq "0" "$(stat -c %a "$f")" "错误传播：失败后临时加的写位已恢复"
    chmod 600 "$f"
}

test_md5sums_absent() {
    local t=$WORK/nomd5 p list log out
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' \
        > "$t/tree/data/data/com.termux/files/usr/bin/x"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/x"
    # 故意不放 md5sums：md5sums 在 deb 里可选，缺了也要能正常重写
    build_deb "$t/tree" "$t/nomd5.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/nomd5.deb" > "$list"
    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "无 md5sums：退出 0"
    dpkg-deb -R "$t/nomd5.deb" "$t/out" 2>/dev/null
    assert_eq "" "$(ls "$t/out/DEBIAN" | grep '^md5sums$')" "无 md5sums：产物里也不会凭空多出 md5sums"
    if grep -q 'com\.termux' "$t/out/data/data/com.kimbox/files/usr/bin/x"; then
        bad "无 md5sums：内容没重写"
    else
        ok "无 md5sums：内容已重写"
    fi
}

test_hardlinks() {
    local t=$WORK/hardlinks p list log out
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    local dir="$t/tree/data/data/com.termux/files/usr/share/hl"
    mkdir -p "$dir"
    new_tree "$t/tree"
    printf 'prefix=/data/data/com.termux/files/usr\n' > "$dir/a"
    ln "$dir/a" "$dir/b"   # 硬链接：tar 会还原成同一 inode
    chmod 0444 "$dir/a"    # 只读硬链接：临时加写位后必须恢复
    gen_md5sums "$t/tree"
    assert_eq "2" "$(stat -c %h "$dir/a")" "硬链接：合成树里确实是同一 inode"
    build_deb "$t/tree" "$t/hl.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/hl.deb" > "$list"

    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "硬链接：钩子退出 0"
    out="$t/out"
    dpkg-deb -R "$t/hl.deb" "$out" 2>/dev/null
    local a="$out/data/data/com.kimbox/files/usr/share/hl/a"
    local b="$out/data/data/com.kimbox/files/usr/share/hl/b"
    assert_eq "$(stat -c %i "$a")" "$(stat -c %i "$b")" "硬链接：重打包后仍是同一 inode"
    assert_eq "2" "$(stat -c %h "$a")" "硬链接：链接数保留"
    assert_eq "444" "$(stat -c %a "$a")" "硬链接：只读模式保留"
    if grep -q 'com\.termux' "$a"; then bad "硬链接：内容没重写"; else ok "硬链接：内容已重写"; fi
    if ( cd "$out" && md5sum -c DEBIAN/md5sums --quiet ) >/dev/null 2>&1; then
        ok "硬链接：md5sums 两条兄弟路径都重算过"
    else
        bad "硬链接：md5sums 校验失败（兄弟路径的哈希没更新）"
    fi
}

test_compression_fidelity() {
    local t=$WORK/comp p list log z built=
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    list="$t/list"; log="$t/log"
    : > "$list"
    for z in gzip none zstd; do
        local tree="$t/tree-$z"
        mkdir -p "$tree/data/data/com.termux/files/usr/bin"
        new_tree "$tree"
        printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' \
            > "$tree/data/data/com.termux/files/usr/bin/y"
        chmod 0755 "$tree/data/data/com.termux/files/usr/bin/y"
        gen_md5sums "$tree"
        if build_deb "$tree" "$t/$z.deb" "$z"; then
            printf '%s\n' "$t/$z.deb" >> "$list"
            built="$built $z"
        else
            skip "压缩保真：本机 dpkg-deb 不能生成 $z"
        fi
    done
    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "压缩保真：退出 0"
    case " $built " in
        *" gzip "*) assert_eq "data.tar.gz" "$(deb_data_member "$t/gzip.deb")" "压缩保真：原 gzip 仍是 gzip" ;;
    esac
    case " $built " in
        *" none "*) assert_eq "data.tar" "$(deb_data_member "$t/none.deb")" "压缩保真：原未压缩仍是未压缩" ;;
    esac
    case " $built " in
        *" zstd "*) assert_eq "data.tar.zst" "$(deb_data_member "$t/zstd.deb")" "压缩保真：原 zstd 仍是 zstd" ;;
    esac
}

test_stdin_streaming() {
    local t=$WORK/stdin p log
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    local tree="$t/tree"
    mkdir -p "$tree/data/data/com.termux/files/usr/bin"
    new_tree "$tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$tree/data/data/com.termux/files/usr/bin/z"
    chmod 0755 "$tree/data/data/com.termux/files/usr/bin/z"
    gen_md5sums "$tree"
    build_deb "$tree" "$t/a.deb" xz
    cp "$t/a.deb" "$t/b.deb"
    log="$t/log"
    # 用真实管道喂列表（apt 就是这么干的），确认两个 deb 都被处理、列表没被吃
    printf '%s\n%s\n' "$t/a.deb" "$t/b.deb" | KIMBOX_PREFIX="$p" bash "$HOOK" > "$log" 2>&1
    assert_eq 0 "$?" "stdin：管道喂列表退出 0"
    assert_match '处理 2 个 deb' "$log" "stdin：两个 deb 都算上了"
    assert_eq "2" "$(grep -c '完成' "$log")" "stdin：两个 deb 都处理完"
    for f in "$t/a.deb" "$t/b.deb"; do
        if dpkg-deb -c "$f" | grep -q 'com\.termux'; then bad "stdin：$(basename "$f") 没被重写"; else ok "stdin：$(basename "$f") 已重写"; fi
    done
    # 空列表：apt 有时会空跑钩子，应当立刻退出
    : | KIMBOX_PREFIX="$p" bash "$HOOK" > "$log.empty" 2>&1
    assert_eq 0 "$?" "stdin：空列表退出 0"
    assert_match '没有待安装的 deb' "$log.empty" "stdin：空列表直接跳过"
    # 不存在的 deb：跳过而不是崩掉（dpkg 自己会报错）
    printf '%s\n' "$t/nope.deb" | KIMBOX_PREFIX="$p" bash "$HOOK" > "$log.missing" 2>&1
    assert_eq 0 "$?" "stdin：不存在的 deb 不致命"
    assert_match '文件不存在，跳过' "$log.missing" "stdin：不存在的 deb 有提示"
    # stdin 是终端（手工执行）时不能卡住等人输入
    if command -v script >/dev/null 2>&1; then
        local t0 t1
        t0=$(date +%s)
        script -qec "KIMBOX_PREFIX='$p' bash '$HOOK'" /dev/null > "$log.tty" 2>&1
        t1=$(date +%s)
        assert_eq 0 "$?" "stdin：终端调用退出 0"
        if [ $((t1 - t0)) -le 5 ]; then ok "stdin：终端调用没有阻塞"; else bad "stdin：终端调用阻塞了 $((t1 - t0))s"; fi
    else
        skip "stdin：本机没有 script(1)，跳过终端 stdin 用例"
    fi
}

test_safe_fail_corrupt() {
    local t=$WORK/corrupt p list log before
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/c"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/c"
    build_deb "$t/tree" "$t/good.deb" xz
    head -c 300 "$t/good.deb" > "$t/broken.deb"
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/broken.deb" > "$list"
    before=$(sha "$t/broken.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 1 "$?" "损坏 deb：钩子以非 0 退出（apt 会中止，不放行）"
    assert_eq "$before" "$(sha "$t/broken.deb")" "损坏 deb：原文件未动"
    assert_match '解包失败' "$log" "损坏 deb：日志说明解包失败"
    assert_eq "" "$(ls -A "$p/tmp")" "损坏 deb：临时目录已清理"
}

test_safe_fail_no_node() {
    local t=$WORK/nonode p list log before
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    rm -f "$p/bin/node"
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/n"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/n"
    build_deb "$t/tree" "$t/n.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/n.deb" > "$list"
    before=$(sha "$t/n.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 1 "$?" "缺 node：含 termux 前缀的 deb 被拒绝安装"
    assert_eq "$before" "$(sha "$t/n.deb")" "缺 node：原 deb 未动"
    assert_match '缺少 node' "$log" "缺 node：日志给出原因"

    # 与本应用无关的 deb 不该因为缺 node 被拦
    local t2="$WORK/nonode-plain"
    mkdir -p "$t2/tree/usr/share/x"
    new_tree "$t2/tree"
    printf 'plain\n' > "$t2/tree/usr/share/x/f"
    build_deb "$t2/tree" "$t2/p.deb" xz
    printf '%s\n' "$t2/p.deb" > "$t2/list"
    run_hook "$p" "$t2/list" "$t2/log"
    assert_eq 0 "$?" "缺 node：无关 deb 照常放行"
}

# node 在但执行失败（例如内部文件错误）时，bash 必须中止整个安装，不能继续重打包。
test_safe_fail_node_error() {
    local t=$WORK/nodefail p list log before
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    rm -f "$p/bin/node"
    cat > "$p/bin/node" <<'EOS'
#!/bin/sh
echo "fake node failure" >&2
exit 9
EOS
    chmod 0755 "$p/bin/node"
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/e"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/e"
    build_deb "$t/tree" "$t/e.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/e.deb" > "$list"
    before=$(sha "$t/e.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 1 "$?" "node 失败：钩子非 0 退出（apt 中止）"
    assert_eq "$before" "$(sha "$t/e.deb")" "node 失败：原 deb 未动"
    assert_match '内容重写失败' "$log" "node 失败：日志说明内容重写失败"
    assert_eq "" "$(ls -A "$t" | grep 'kimbox-new' || true)" "node 失败：没有残留临时 deb"
    assert_eq "" "$(ls -A "$p/tmp")" "node 失败：临时目录已清理"
}

# 造一个带 com.termux 前缀但重打包会失败的 deb：用 wrapper 让 dpkg-deb -b 两次都失败
test_safe_fail_repack_fails() {
    local t=$WORK/repackfail p list log before real
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    rm -f "$p/bin/dpkg-deb"   # 先删掉软链，否则 cat > 会写穿到宿主 /usr/bin
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
last=
for a in "\$@"; do last=\$a; done
case "\$1" in
    -b|--build) : > "\$last"; echo "fake build failure" >&2; exit 1 ;;
esac
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/r"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/r"
    build_deb "$t/tree" "$t/r.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/r.deb" > "$list"
    before=$(sha "$t/r.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 1 "$?" "重打包失败：钩子非 0 退出"
    assert_eq "$before" "$(sha "$t/r.deb")" "重打包失败：原 deb 未动（原子性）"
    assert_match '重打包失败' "$log" "重打包失败：日志说明原因"
    assert_eq "" "$(ls -A "$t" | grep 'kimbox-new' || true)" "重打包失败：没有残留 .kimbox-new 文件"
    assert_eq "" "$(ls -A "$p/tmp")" "重打包失败：临时目录已清理"
}

# 原格式不可用时只允许用原格式重试；绝不能退回 dpkg-deb 默认压缩后放行。
test_no_compression_fallback() {
    local t=$WORK/nocompfallback p list log before real
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    rm -f "$p/bin/dpkg-deb"
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
case "\$1" in
    -b|--build)
        for a in "\$@"; do
            case "\$a" in
                -Z*) echo "fake unsupported compression \$a" >&2; exit 1 ;;
            esac
        done
        ;;
esac
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/n"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/n"
    build_deb "$t/tree" "$t/n.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/n.deb" > "$list"
    before=$(sha "$t/n.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 1 "$?" "压缩格式不可用：钩子非 0 退出，不退回默认压缩"
    assert_eq "$before" "$(sha "$t/n.deb")" "压缩格式不可用：原 deb 未动"
    assert_match '用原压缩格式' "$log" "压缩格式不可用：日志说明按原格式重试"
    assert_eq "" "$(ls -A "$t" | grep 'kimbox-new' || true)" "压缩格式不可用：没有残留临时 deb"
}

# 可选参数（--root-owner-group/--threads-max）导致失败时，去掉可选参数、
# 保留原压缩格式重试应当成功。
test_repack_retry_preserves_compression() {
    local t=$WORK/repackretry p list log real
    if ! dpkg-deb --help 2>&1 | grep -q -e '--threads-max' -e '--root-owner-group'; then
        skip "重打包重试：本机 dpkg-deb 没有可选参数，跳过"
        return
    fi
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    rm -f "$p/bin/dpkg-deb"
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
case "\$1" in
    -b|--build)
        for a in "\$@"; do
            case "\$a" in
                --root-owner-group|--threads-max=*)
                    echo "fake optional flag failure: \$a" >&2
                    exit 1
                    ;;
            esac
        done
        ;;
esac
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/o"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/o"
    build_deb "$t/tree" "$t/o.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/o.deb" > "$list"

    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "重打包重试：去掉可选参数后成功"
    assert_match '用原压缩格式' "$log" "重打包重试：日志说明走了原格式重试"
    assert_eq "data.tar.xz" "$(deb_data_member "$t/o.deb")" "重打包重试：压缩格式仍是 xz"
    if dpkg-deb -c "$t/o.deb" | grep -q 'com\.termux'; then
        bad "重打包重试：路径里仍有 com.termux"
    else
        ok "重打包重试：路径已重写"
    fi
}

# 重打包"成功"但产物仍是没重写的原 deb：校验闸门必须拦住
test_verify_gate() {
    local t=$WORK/verify p list log before real
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/v"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/v"
    build_deb "$t/tree" "$t/v.deb" xz
    rm -f "$p/bin/dpkg-deb"
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
last=
for a in "\$@"; do last=\$a; done
case "\$1" in
    -b|--build) cp "$t/v.deb" "\$last"; exit 0 ;;
esac
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/v.deb" > "$list"
    before=$(sha "$t/v.deb")

    run_hook "$p" "$list" "$log"
    assert_eq 1 "$?" "校验闸门：产物仍有 com.termux 时拒绝替换"
    assert_eq "$before" "$(sha "$t/v.deb")" "校验闸门：原 deb 未动"
    assert_match '校验不通过' "$log" "校验闸门：日志说明校验失败"
    assert_eq "" "$(ls -A "$t" | grep 'kimbox-new' || true)" "校验闸门：没有残留临时 deb"
}

# dpkg-deb -c 的目录清单也走 run_child：校验卡住时必须能立即取消，且原 deb 不动
test_signal_verify_stops() {
    local t=$WORK/signal-verify p list log before real pid marker t0 t1 rc i=0
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/v"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/v"
    gen_md5sums "$t/tree"
    build_deb "$t/tree" "$t/v.deb" xz
    rm -f "$p/bin/dpkg-deb"
    marker="$t/verify.started"
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
if [ "\$1" = "-c" ]; then
    : > "$marker"
    exec sleep 300
fi
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/v.deb" > "$list"
    before=$(sha "$t/v.deb")

    KIMBOX_PREFIX="$p" bash "$HOOK" < "$list" > "$log" 2>&1 &
    pid=$!
    while [ ! -f "$marker" ] && [ "$i" -lt 100 ]; do
        sleep 0.1
        i=$((i + 1))
    done
    if [ ! -f "$marker" ]; then
        bad "校验取消：钩子没能走到目录清单校验阶段"
        kill -KILL "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
        return
    fi
    t0=$(date +%s)
    kill -TERM "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    rc=$?
    t1=$(date +%s)
    assert_eq 143 "$rc" "校验取消：TERM 后以 143 退出"
    if [ $((t1 - t0)) -le 5 ]; then ok "校验取消：TERM 后 5 秒内退出"; else bad "校验取消：超过 5 秒才退出"; fi
    assert_eq "$before" "$(sha "$t/v.deb")" "校验取消：原 deb 未动"
    assert_eq "" "$(ls -A "$p/tmp")" "校验取消：临时目录已清理"
}

test_signal_stops() {
    local t=$WORK/signal p list log before real pid marker leader child lp cpid t0 t1 rc j
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/s"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/s"
    gen_md5sums "$t/tree"
    build_deb "$t/tree" "$t/s.deb" xz
    marker="$t/repack.started"
    leader="$t/leader.pid"
    child="$t/child.pid"
    rm -f "$p/bin/dpkg-deb"
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
case "\$1" in
    -b|--build)
        : > "$marker"
        echo \$\$ > "$leader"
        sleep 300 &
        echo \$! > "$child"
        exec sleep 300
        ;;
esac
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/s.deb" > "$list"
    before=$(sha "$t/s.deb")
    rm -f "$marker" "$leader" "$child"

    KIMBOX_PREFIX="$p" bash "$HOOK" < "$list" > "$log" 2>&1 &
    pid=$!
    local i=0
    while [ ! -f "$marker" ] && [ "$i" -lt 100 ]; do
        sleep 0.1
        i=$((i + 1))
    done
    if [ ! -f "$marker" ]; then
        bad "信号：钩子没能走到重打包阶段（前置步骤失败）"
        kill -KILL "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
        return
    fi
    t0=$(date +%s)
    kill -TERM "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    rc=$?
    t1=$(date +%s)
    assert_eq 143 "$rc" "信号：TERM 后以 143 退出"
    if [ $((t1 - t0)) -le 5 ]; then ok "信号：TERM 后 5 秒内退出"; else bad "信号：TERM 后 ${t1}-${t0}s 才退出"; fi
    assert_match '收到 SIGTERM' "$log" "信号：日志记录了收到信号"
    assert_eq "$before" "$(sha "$t/s.deb")" "信号：原 deb 未动（没有半截产物）"
    assert_eq "" "$(ls -A "$t" | grep 'kimbox-new' || true)" "信号：没有残留临时 deb"
    assert_eq "" "$(ls -A "$p/tmp")" "信号：临时目录已清理"

    # 取消必须回收当前子进程及其后代（dpkg-deb 里的 sleep），不能只杀组长
    lp=$(cat "$leader" 2>/dev/null || echo)
    cpid=$(cat "$child" 2>/dev/null || echo)
    if [ -n "$lp" ] && [ -n "$cpid" ]; then
        ok "信号：重打包子进程与后代 PID 已记录（$lp / $cpid）"
    else
        bad "信号：没记录到子进程/后代 PID"
    fi
    j=0
    while [ "$j" -lt 30 ]; do
        if { [ -z "$lp" ] || ! pid_alive "$lp"; } && { [ -z "$cpid" ] || ! pid_alive "$cpid"; }; then
            break
        fi
        sleep 0.1
        j=$((j + 1))
    done
    if [ -n "$lp" ] && pid_alive "$lp"; then
        bad "信号：组长的进程还在（$lp）"
        kill -KILL "$lp" 2>/dev/null
    else
        ok "信号：组长进程已回收"
    fi
    if [ -n "$cpid" ] && pid_alive "$cpid"; then
        bad "信号：后代进程还在（$cpid），取消没有按进程组传播"
        kill -KILL "$cpid" 2>/dev/null
    else
        ok "信号：后代进程已回收"
    fi
}

# 子进程忽略 TERM 时，取消不能无界 wait：钩子应直接 KILL 独立进程组再回收。
test_signal_kill_ignores_term() {
    local t=$WORK/signal-kill p list log before real pid marker leader child lp cpid t0 t1 rc j
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    real=$(command -v dpkg-deb)
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/k"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/k"
    gen_md5sums "$t/tree"
    build_deb "$t/tree" "$t/k.deb" xz
    marker="$t/repack.started"
    leader="$t/leader.pid"
    child="$t/child.pid"
    rm -f "$p/bin/dpkg-deb"
    cat > "$p/bin/dpkg-deb" <<EOS
#!/usr/bin/env bash
trap '' TERM
case "\$1" in
    -b|--build)
        : > "$marker"
        echo \$\$ > "$leader"
        bash -c 'trap "" TERM; exec sleep 300' &
        echo \$! > "$child"
        while :; do sleep 1; done
        ;;
esac
exec "$real" "\$@"
EOS
    chmod 0755 "$p/bin/dpkg-deb"
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/k.deb" > "$list"
    before=$(sha "$t/k.deb")
    rm -f "$marker" "$leader" "$child"

    KIMBOX_PREFIX="$p" bash "$HOOK" < "$list" > "$log" 2>&1 &
    pid=$!
    local i=0
    while [ ! -f "$marker" ] && [ "$i" -lt 100 ]; do
        sleep 0.1
        i=$((i + 1))
    done
    if [ ! -f "$marker" ]; then
        bad "忽略 TERM：钩子没能走到重打包阶段（前置步骤失败）"
        kill -KILL "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
        return
    fi
    t0=$(date +%s)
    kill -TERM "$pid" 2>/dev/null
    # 测试侧必须有界，否则实现回退成无界 wait 时测试会永久挂住。
    j=0
    while pid_alive "$pid" && [ "$j" -lt 50 ]; do
        sleep 0.1
        j=$((j + 1))
    done
    if pid_alive "$pid"; then
        bad "忽略 TERM：钩子 5 秒内未退出（可能卡在无界 wait）"
        kill -KILL "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
        lp=$(cat "$leader" 2>/dev/null || echo)
        [ -n "$lp" ] && kill -KILL -- "-$lp" 2>/dev/null
        return
    fi
    wait "$pid" 2>/dev/null
    rc=$?
    t1=$(date +%s)
    assert_eq 143 "$rc" "忽略 TERM：取消后以 143 退出"
    if [ $((t1 - t0)) -le 5 ]; then ok "忽略 TERM：取消后 5 秒内退出"; else bad "忽略 TERM：${t1}-${t0}s 才退出"; fi
    assert_match '收到 SIGTERM' "$log" "忽略 TERM：日志记录了收到信号"
    assert_eq "$before" "$(sha "$t/k.deb")" "忽略 TERM：原 deb 未动"
    assert_eq "" "$(ls -A "$t" | grep 'kimbox-new' || true)" "忽略 TERM：没有残留临时 deb"
    assert_eq "" "$(ls -A "$p/tmp")" "忽略 TERM：临时目录已清理"

    lp=$(cat "$leader" 2>/dev/null || echo)
    cpid=$(cat "$child" 2>/dev/null || echo)
    j=0
    while [ "$j" -lt 30 ]; do
        if { [ -z "$lp" ] || ! pid_alive "$lp"; } && { [ -z "$cpid" ] || ! pid_alive "$cpid"; }; then
            break
        fi
        sleep 0.1
        j=$((j + 1))
    done
    if [ -n "$lp" ] && pid_alive "$lp"; then
        bad "忽略 TERM：组长进程还在（$lp）"
        kill -KILL -- "-$lp" 2>/dev/null
    else
        ok "忽略 TERM：组长进程已回收"
    fi
    if [ -n "$cpid" ] && pid_alive "$cpid"; then
        bad "忽略 TERM：后代进程还在（$cpid）"
        kill -KILL "$cpid" 2>/dev/null
    else
        ok "忽略 TERM：后代进程已回收"
    fi
}

test_no_tempdir() {
    local t=$WORK/notmp p list log
    mkdir -p "$t"
    p=$(make_prefix "$t/prefix")
    rm -rf "$p/tmp"
    mkdir -p "$t/tree/data/data/com.termux/files/usr/bin"
    new_tree "$t/tree"
    printf '#!/bin/sh\necho /data/data/com.termux/files/usr\n' > "$t/tree/data/data/com.termux/files/usr/bin/x"
    chmod 0755 "$t/tree/data/data/com.termux/files/usr/bin/x"
    build_deb "$t/tree" "$t/x.deb" xz
    list="$t/list"; log="$t/log"
    printf '%s\n' "$t/x.deb" > "$list"
    run_hook "$p" "$list" "$log"
    assert_eq 0 "$?" "缺 tmp 目录：能自己建出来并完成"
    if [ -d "$p/tmp" ]; then ok "缺 tmp 目录：已自动创建"; else bad "缺 tmp 目录：没有创建"; fi
}

# apt 配置只加超时/重试，不碰软件源；也确认没有把 apt update 说成钩子能修的事。
test_apt_config_guard() {
    local c="$RUNTIME/hooks/usr/etc/apt/apt.conf.d/99-kimbox"
    assert_match 'DPkg::Pre-Install-Pkgs' "$c" "apt 配置：保留安装钩子"
    assert_match 'Acquire::Retries "3"' "$c" "apt 配置：设置有限重试"
    assert_match 'Acquire::http::Timeout "30"' "$c" "apt 配置：设置 http 超时"
    assert_not_match '^[[:space:]]*deb[[:space:]]' "$c" "apt 配置：没有改软件源"
}

# ══════════════════════════════════════════════════════════════════
main() {
    printf 'deb 重写钩子测试（host 合成 deb）\n'
    printf '钩子: %s\n' "$HOOK"
    printf 'node: %s\n' "$(node --version 2>/dev/null || echo 缺失)"
    printf 'dpkg-deb: %s\n\n' "$(dpkg-deb --version | head -1)"

    local name
    for name in rewrite_basic unrelated_passthrough cross_chunk_binary node_chunk_sizes \
                node_short_write node_write_batching node_error md5sums_absent hardlinks \
                compression_fidelity stdin_streaming safe_fail_corrupt safe_fail_no_node \
                safe_fail_node_error safe_fail_repack_fails no_compression_fallback \
                repack_retry_preserves_compression verify_gate signal_verify_stops signal_stops \
                signal_kill_ignores_term no_tempdir apt_config_guard; do
        printf '── %s\n' "$name"
        "test_$name"
    done

    printf '\n通过 %d，失败 %d，跳过 %d\n' "$PASSED" "$FAILED" "$SKIPPED"
    [ "$FAILED" -eq 0 ]
}

main "$@"
