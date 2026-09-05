#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# ── 0. 清理上次产物 ──────────────────────────────────────────────
if [ -d pkgadd ]; then rm -r pkgadd; fi
mkdir -p pkgadd/debs pkgadd/tree pkgadd/usr

# ── 1. 下载 / 刷新 Packages 索引 ─────────────────────────────────
PKGS_GZ=pkgadd/Packages.gz
if [ ! -f "$PKGS_GZ" ]; then
  echo "[1] 下载 Packages.gz ..."
  curl -fSL -o "$PKGS_GZ" \
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"
else
  echo "[1] 使用已有 Packages.gz"
fi
gunzip -kf "$PKGS_GZ"
echo "    Packages 大小: $(wc -c < pkgadd/Packages) bytes"

# ── 2-9. Python 做重活 ───────────────────────────────────────────
exec python3 - pkgadd staging-final/usr <<'PYEOF'
import sys, os, re, subprocess, shutil, struct, hashlib
from pathlib import Path

PKGADD   = sys.argv[1]          # runtime/pkgadd
STAGING  = sys.argv[2]          # runtime/staging-final/usr
PACKAGES = os.path.join(PKGADD, "Packages")
EXTRACT_LOG = os.path.abspath("extract.log")
OLD = b"com.termux"
NEW = b"com.kimbox"
ABS_PREFIX = "/data/data/com.kimbox/files/usr/"

# ── 读旧 60 包 ───────────────────────────────────────────────────
old_pkgs = set()
with open(EXTRACT_LOG) as f:
    for line in f:
        m = re.match(r'PKGS\(\d+\):\s+(.*)', line)
        if m:
            old_pkgs = set(m.group(1).strip().split())
            break
print(f"旧包: {len(old_pkgs)} 个")

# ── 解析 Packages 索引 ───────────────────────────────────────────
def parse_packages(path):
    pkgs = {}
    current = {}
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.rstrip('\n')
            if line == '':
                if current.get('Package'):
                    pkgs[current['Package']] = current
                current = {}
            elif line.startswith(' '):
                current.setdefault('_desc_lines', []).append(line)
            else:
                k, _, v = line.partition(': ')
                current[k] = v
    if current.get('Package'):
        pkgs[current['Package']] = current
    return pkgs

all_pkgs = parse_packages(PACKAGES)
print(f"索引包: {len(all_pkgs)} 个")

# ── 构建 Provides 反查表 ─────────────────────────────────────────
providers = {}  # virtual_name -> [real_pkg, ...]
for name, info in all_pkgs.items():
    provides_str = info.get('Provides', '')
    if provides_str:
        for entry in provides_str.split(','):
            vname = entry.strip().split()[0]
            providers.setdefault(vname, []).append(name)

# ── 解析依赖字符串 ───────────────────────────────────────────────
def parse_depends(dep_str):
    """返回 list of list of pkg_name (每个内层 list 是一个 | 选择组)"""
    if not dep_str:
        return []
    result = []
    for group in dep_str.split(','):
        alts = []
        for alt in group.split('|'):
            alt = alt.strip()
            m = re.match(r'<?([a-zA-Z0-9_][a-zA-Z0-9_.+-]*)>?', alt)
            if m:
                alts.append(m.group(1))
        if alts:
            result.append(alts)
    return result

def resolve_dep(alts, available_set):
    """从 | 选项里选一个。优先 available_set 里已有的。"""
    for a in alts:
        if a in available_set:
            return a
    for a in alts:
        if a in all_pkgs:
            return a
    # 尝试虚拟包
    for a in alts:
        if a in providers:
            for provider in providers[a]:
                if provider in available_set:
                    return provider
            for provider in providers[a]:
                if provider in all_pkgs:
                    return provider
    return None

# ── 依赖闭包 ─────────────────────────────────────────────────────
seeds = ['apt', 'dpkg', 'termux-keyring', 'gpgv']
needed = set(seeds)
skipped_virtual = []
queue = list(seeds)
while queue:
    pkg = queue.pop(0)
    info = all_pkgs.get(pkg)
    if not info:
        continue
    for field in ('Depends', 'Pre-Depends'):
        dep_str = info.get(field, '')
        for alts in parse_depends(dep_str):
            resolved = resolve_dep(alts, old_pkgs | needed)
            if resolved is None:
                # 虚拟包或找不到
                virtual_names = [a for a in alts if a.startswith('<') or (a not in all_pkgs and a not in providers)]
                if virtual_names or (alts and all(a not in all_pkgs and a not in providers for a in alts)):
                    skipped_virtual.append(' | '.join(alts))
                else:
                    skipped_virtual.append(' | '.join(alts))
                continue
            if resolved not in needed and resolved not in old_pkgs:
                needed.add(resolved)
                queue.append(resolved)

new_pkgs = needed - old_pkgs
print(f"依赖闭包: {len(needed)} 个, 新增: {len(new_pkgs)} 个")
print(f"新增包: {sorted(new_pkgs)}")
print(f"跳过虚拟依赖: {skipped_virtual}")

# ── 3. 下载 .deb 并解压 ──────────────────────────────────────────
PKGADD   = os.path.abspath(PKGADD)
STAGING  = os.path.abspath(STAGING)

debs_dir = os.path.join(PKGADD, "debs")
tree_dir = os.path.join(PKGADD, "tree")
usr_dir  = os.path.join(PKGADD, "usr")

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

def download_deb(pkg, info, deb_path, url):
    """下载并按 Packages 索引里的 SHA256 校验；缓存文件同样要过校验。"""
    expected = info.get('SHA256', '').strip().lower()
    for attempt in (1, 2):
        if not os.path.exists(deb_path):
            print(f"  下载 {pkg} {info['Version']} ...")
            subprocess.run(["curl", "-fSL", "-o", deb_path, url], check=True, capture_output=True)
        if not expected:
            print(f"  !! {pkg}: 索引缺 SHA256，无法校验，中止")
            sys.exit(1)
        actual = sha256_of(deb_path)
        if actual == expected:
            return
        print(f"  !! {pkg}: SHA256 不匹配 (期望 {expected}, 实际 {actual})")
        os.remove(deb_path)
        if attempt == 2:
            print(f"  !! {pkg}: 重下后仍不匹配，中止")
            sys.exit(1)
        print(f"  重新下载 {pkg} ...")

for pkg in sorted(new_pkgs):
    info = all_pkgs[pkg]
    fn = info['Filename']
    url = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/" + fn
    deb_path = os.path.join(debs_dir, os.path.basename(fn))
    download_deb(pkg, info, deb_path, url)

    # ar x
    extract_subdir = os.path.join(tree_dir, pkg)
    os.makedirs(extract_subdir, exist_ok=True)
    subprocess.run(["ar", "x", deb_path], cwd=extract_subdir, check=True, capture_output=True)

    # 找 data.tar.*
    data_tar = None
    for f in os.listdir(extract_subdir):
        if f.startswith("data.tar"):
            data_tar = f
            break
    if not data_tar:
        print(f"  !! {pkg}: 没找到 data.tar.*")
        continue

    # 解压 data.tar
    subprocess.run(["tar", "xf", os.path.join(extract_subdir, data_tar), "-C", extract_subdir],
                   check=True, capture_output=True)

# ── 把解出的文件规整到 pkgadd/usr/ ──────────────────────────────
# deb 里路径: data/data/com.termux/files/usr/... -> usr/...
TERMUX_PREFIX = "data/data/com.termux/files/usr"

for pkg in sorted(new_pkgs):
    pkg_dir = os.path.join(tree_dir, pkg)
    src = os.path.join(pkg_dir, TERMUX_PREFIX)
    if not os.path.isdir(src):
        # 有些包可能路径不同
        alt = os.path.join(pkg_dir, "data", "data", "com.termux", "files", "usr")
        if os.path.isdir(alt):
            src = alt
        else:
            print(f"  !! {pkg}: 找不到 usr 路径，列出内容:")
            for root, dirs, files in os.walk(pkg_dir):
                for f in files[:5]:
                    print(f"    {os.path.relpath(os.path.join(root, f), pkg_dir)}")
            continue

    # 复制文件
    for root, dirs, files in os.walk(src, followlinks=False):
        rel = os.path.relpath(root, src)
        dst_dir = os.path.join(usr_dir, rel) if rel != '.' else usr_dir
        os.makedirs(dst_dir, exist_ok=True)
        for name in files:
            s = os.path.join(root, name)
            d = os.path.join(dst_dir, name)
            if os.path.islink(s):
                tgt = os.readlink(s)
                if os.path.exists(d) or os.path.islink(d):
                    os.unlink(d)
                os.symlink(tgt, d)
            else:
                shutil.copy2(s, d)
        # 处理指向目录的符号链接（os.walk 把它们放 dirs 里）
        for d_name in list(dirs):
            s = os.path.join(root, d_name)
            if os.path.islink(s):
                tgt = os.readlink(s)
                d = os.path.join(dst_dir, d_name)
                if os.path.islink(d):
                    os.unlink(d)
                os.symlink(tgt, d)

# ── 4. 补丁 com.termux -> com.kimbox ────────────────────────────
print("\n[4] 补丁文件内容 com.termux -> com.kimbox ...")
n_files = n_patched = 0
for root, dirs, files in os.walk(usr_dir, followlinks=False):
    for name in files:
        p = os.path.join(root, name)
        if os.path.islink(p):
            continue
        try:
            with open(p, 'rb') as f:
                data = f.read()
        except OSError:
            continue
        n_files += 1
        if OLD in data:
            data = data.replace(OLD, NEW)
            with open(p, 'wb') as f:
                f.write(data)
            n_patched += 1
print(f"    扫描 {n_files} 文件，补丁 {n_patched} 个")

print("[4b] 补丁符号链接目标 ...")
n_links = 0
for root, dirs, files in os.walk(usr_dir, followlinks=False):
    for name in files + [d for d in dirs if os.path.islink(os.path.join(root, d))]:
        p = os.path.join(root, name)
        if os.path.islink(p):
            tgt = os.readlink(p)
            new_tgt = None
            if "com.termux" in tgt:
                new_tgt = tgt.replace("com.termux", "com.kimbox")
            effective = new_tgt if new_tgt is not None else tgt
            if effective.startswith(ABS_PREFIX):
                on_disk = os.path.join(usr_dir, effective[len(ABS_PREFIX):])
                new_tgt = os.path.relpath(on_disk, os.path.dirname(p))
            if new_tgt is not None and new_tgt != tgt:
                os.unlink(p)
                os.symlink(new_tgt, p)
                n_links += 1
print(f"    补丁 {n_links} 个符号链接")

# 检查残留
print("[4c] 检查 com.termux 残留 ...")
ret = subprocess.run(["grep", "-rl", "com.termux", usr_dir],
                     capture_output=True, text=True)
leftover = [l for l in ret.stdout.strip().split('\n') if l]
if leftover:
    print(f"  !! 残留 {len(leftover)} 个文件含 com.termux:")
    for l in leftover[:20]:
        print(f"    {l}")
else:
    print("    无残留 ✓")

# ── 5. 合并到 staging-final/usr ──────────────────────────────────
print("\n[5] 合并到 staging-final/usr ...")
conflicts = []
merged = 0
for root, dirs, files in os.walk(usr_dir, followlinks=False):
    rel = os.path.relpath(root, usr_dir)
    dst_dir = os.path.join(STAGING, rel) if rel != '.' else STAGING
    os.makedirs(dst_dir, exist_ok=True)
    for name in files:
        s = os.path.join(root, name)
        d = os.path.join(dst_dir, name)
        # 如果目标是已存在的真实文件（非符号链接），算冲突
        if os.path.exists(d) and not os.path.islink(d):
            conflicts.append(os.path.relpath(d, STAGING))
            continue
        # 如果目标是已存在的符号链接，检查是否指向有效目标
        if os.path.islink(d):
            # 如果是断链（broken symlink），替换它
            if not os.path.exists(d):
                os.unlink(d)
            else:
                conflicts.append(os.path.relpath(d, STAGING))
                continue
        if os.path.islink(s):
            tgt = os.readlink(s)
            os.symlink(tgt, d)
        else:
            shutil.copy2(s, d)
        merged += 1
    # 指向目录的符号链接
    for d_name in list(dirs):
        s = os.path.join(root, d_name)
        if os.path.islink(s):
            d = os.path.join(dst_dir, d_name)
            if os.path.islink(d):
                if not os.path.exists(d):
                    os.unlink(d)
                else:
                    conflicts.append(os.path.relpath(d, STAGING))
                    continue
            elif os.path.exists(d):
                conflicts.append(os.path.relpath(d, STAGING))
                continue
            os.symlink(os.readlink(s), d)
            merged += 1

print(f"    新增 {merged} 个文件/链接")
if conflicts:
    print(f"    !! 冲突 {len(conflicts)} 个:")
    for c in conflicts[:30]:
        print(f"      {c}")
else:
    print("    无冲突 ✓")

# ── 6. 补空目录 ──────────────────────────────────────────────────
print("\n[6] 创建 dpkg/apt 目录结构 ...")
dirs_to_create = [
    "tmp",
    "var/lib/dpkg/info",
    "var/lib/dpkg/triggers",
    "var/lib/dpkg/updates",
    "var/lib/dpkg/alternatives",
    "var/lib/dpkg/parts",
    "var/lib/apt/lists/partial",
    "var/cache/apt/archives/partial",
    "var/log/apt",
    "etc/apt/sources.list.d",
    "etc/apt/preferences.d",
    "etc/apt/trusted.gpg.d",
]
for d in dirs_to_create:
    os.makedirs(os.path.join(STAGING, d), exist_ok=True)
print(f"    创建 {len(dirs_to_create)} 个目录")

# ── 7. 生成 dpkg status 文件 ─────────────────────────────────────
print("\n[7] 生成 dpkg status 文件 ...")
status_path = os.path.join(STAGING, "var/lib/dpkg/status")
all_installed = old_pkgs | new_pkgs
stanzas = []
for pkg in sorted(all_installed):
    info = all_pkgs.get(pkg)
    if not info:
        # 旧包可能不在索引里（旧版本），写最小 stanza
        stanzas.append(f"Package: {pkg}\nStatus: install ok installed\nArchitecture: aarch64\n")
        continue
    lines = []
    lines.append(f"Package: {pkg}")
    lines.append("Status: install ok installed")
    # Termux 索引没有 Maintainer 字段，补占位避免 dpkg 每次解析都刷警告
    lines.append(f"Maintainer: {info.get('Maintainer') or '@termux'}")
    if info.get('Priority'):
        lines.append(f"Priority: {info['Priority']}")
    if info.get('Essential'):
        lines.append(f"Essential: {info['Essential']}")
    lines.append(f"Architecture: {info.get('Architecture', 'aarch64')}")
    lines.append(f"Version: {info['Version']}")
    if info.get('Depends'):
        lines.append(f"Depends: {info['Depends']}")
    # Description 首行
    desc = info.get('Description', '')
    if desc:
        lines.append(f"Description: {desc}")
    stanzas.append('\n'.join(lines) + '\n')

with open(status_path, 'w') as f:
    f.write('\n'.join(stanzas) + '\n')
print(f"    写入 {len(stanzas)} 个 stanza")

# ── 8. sources.list ──────────────────────────────────────────────
print("\n[8] 写 sources.list ...")
sl = os.path.join(STAGING, "etc/apt/sources.list")
with open(sl, 'w') as f:
    f.write("deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n")
print(f"    ✓ {sl}")

# ── 9. 报告 keyring 落点 ─────────────────────────────────────────
print("\n[9] keyring 文件:")
for search_dir in ["share/termux-keyring", "etc/apt/trusted.gpg.d"]:
    full = os.path.join(STAGING, search_dir)
    if os.path.isdir(full):
        for fn in os.listdir(full):
            fp = os.path.join(full, fn)
            if os.path.islink(fp):
                target = os.readlink(fp)
                resolved = os.path.join(os.path.dirname(fp), target)
                if os.path.exists(resolved):
                    print(f"    {os.path.relpath(fp, STAGING)} -> {target} (symlink)")
                else:
                    print(f"    {os.path.relpath(fp, STAGING)} -> {target} (BROKEN)")
            elif os.path.isfile(fp):
                print(f"    {os.path.relpath(fp, STAGING)}  ({os.path.getsize(fp)} bytes)")

# ── 10. 汇总 ─────────────────────────────────────────────────────
print("\n" + "="*60)
print("汇总报告")
print("="*60)
print(f"\n新增包 ({len(new_pkgs)}):")
for pkg in sorted(new_pkgs):
    info = all_pkgs[pkg]
    print(f"  {pkg} {info['Version']}")

print(f"\n跳过虚拟依赖 ({len(skipped_virtual)}):")
for s in sorted(set(skipped_virtual)):
    print(f"  {s}")

print(f"\n冲突: {len(conflicts)} 个")
for c in conflicts:
    print(f"  {c}")

print(f"\nstatus 条数: {len(stanzas)}")
print(f"脚本路径: runtime/add-pkg-manager.sh")

# du
ret = subprocess.run(["du", "-sh", usr_dir], capture_output=True, text=True)
print(f"pkgadd/usr 体积: {ret.stdout.strip().split()[0]}")
ret2 = subprocess.run(["du", "-sh", STAGING], capture_output=True, text=True)
print(f"staging-final/usr 总体积: {ret2.stdout.strip().split()[0]}")

PYEOF
