#!/usr/bin/env bash
# 把 dpkg 元数据合并进 staging-final/usr/var/lib/dpkg/，修复两个历史问题：
#   1) 60 个旧包（extract-phone-runtime.sh 从手机抽取）在 status 里的 stanza
#      曾是 add-pkg-manager.sh 运行当天的索引内容，与手机实际文件脱钩
#      （只换 Version 会造成 Version: 8.20.0 + Depends: libcurl (= 8.22.0)
#       之类的缝合 stanza，依赖永不满足、apt 全局锁死）；
#      本脚本把旧包 stanza 整个换成 phone-dpkg-info/status.phone 里的手机原文
#      （Package/Status/Version/Depends/Breaks/Replaces/Conffiles 等全套），
#      唯一改写是字段文本里的 com.termux 等长替换为 com.kimbox
#      （Conffiles 的绝对路径会含它；md5 不动）。
#   2) var/lib/dpkg/info/ 曾是空目录；本脚本补齐全部 81 个包的
#      .list/.conffiles/.md5sums 与 maintainer 脚本：
#        - 旧包：复制自 phone-dpkg-info/info/（从手机 scp 而来）；
#        - 新包（add-pkg-manager.sh 添加的 21 个）：status stanza 保持索引
#          原文（版本与 deb 一致，是对的）；info 文件从 pkgadd/debs/*.deb 提取
#          （.list 由 data.tar 清单生成；Termux 的 deb 不含 DEBIAN/md5sums，
#           故 .md5sums 由 data.tar 内容现算原始哈希；conffiles 取 DEBIAN/conffiles
#           并写入 status——staging 里被我们改过的 etc/apt/sources.list 因而被
#           dpkg 视为用户修改，升级时保留，这是想要的效果）。
#   所有写入内容的 com.termux 一律等长替换为 com.kimbox（各 10 字符）。
#   改 status 前备份到 phone-dpkg-info/status.orig（已存在则不覆盖）。
#
# 幂等：全部输出由输入数据确定性生成，可重复执行。
#
# phone-dpkg-info/ 的刷新方法（手机侧导出已并入 extract-phone-runtime.sh）：
#   ssh -p 8022 -i /root/.ssh/kimi_pocket u0_a388@<手机IP> \
#     'cat /data/data/com.termux/files/usr/var/lib/dpkg/status' > phone-dpkg-info/status.phone
#   ssh ... '/data/data/com.termux/files/usr/bin/dpkg-query -W \
#     -f "${Package}\t${Version}\t${Architecture}\t${db:Status-Abbrev}\n"' > phone-dpkg-info/phone-versions.tsv
#   scp -r -P 8022 -i /root/.ssh/kimi_pocket \
#     'u0_a388@<手机IP>:/data/data/com.termux/files/usr/var/lib/dpkg/info' phone-dpkg-info/info
set -euo pipefail
cd "$(dirname "$0")"

exec python3 - <<'PYEOF'
import io, os, re, sys, shutil, tarfile, hashlib, subprocess
from pathlib import Path

OLD  = b"com.termux"; NEW = b"com.kimbox"
PREFIX_ABS = "/data/data/com.kimbox/files/usr/"
SUFFIXES   = [".list", ".conffiles", ".md5sums",
              ".preinst", ".postinst", ".prerm", ".postrm"]
SCRIPT_SUFS = {".preinst", ".postinst", ".prerm", ".postrm"}

STAGING  = Path("staging-final/usr")
DPKG     = STAGING / "var/lib/dpkg"
INFO_DST = DPKG / "info"
PDI      = Path("phone-dpkg-info")
DEBS     = Path("pkgadd/debs")
STATUS   = DPKG / "status"

def die(m):
    print(f"!! {m}", file=sys.stderr)
    sys.exit(1)

# ── 输入检查 ─────────────────────────────────────────────────
for p in (PDI/"status.phone", PDI/"info", DEBS, STATUS):
    if not p.exists():
        die(f"缺少输入: {p}（先按脚本头注释从手机刷新 phone-dpkg-info/）")
INFO_DST.mkdir(parents=True, exist_ok=True)

# ── 名单：旧 60 包（extract.log，与 add-pkg-manager.sh 同一来源）──
old_pkgs = set()
for line in open("extract.log", errors="replace"):
    m = re.match(r"PKGS\(\d+\):\s+(.*)", line)
    if m:
        old_pkgs = set(m.group(1).split())
        break
if not old_pkgs:
    die("extract.log 中找不到 PKGS(n) 行")

# ── 名单：新 21 包（deb 文件名 <pkg>_<ver>_<arch>.deb）──
debs = {}
for f in sorted(DEBS.glob("*.deb")):
    debs[f.name.rsplit("_", 2)[0]] = f
new_pkgs = set(debs)
if old_pkgs & new_pkgs:
    die(f"新旧名单重叠: {sorted(old_pkgs & new_pkgs)}")
want = old_pkgs | new_pkgs
print(f"旧包 {len(old_pkgs)} 个, 新包 {len(new_pkgs)} 个, 合计 {len(want)}")

# ── 解析 status 类文件 ───────────────────────────────────────
def parse_status(path):
    """返回 [{f: {字段: 值}, conf: [(路径, md5)], raw: [原始行]}]"""
    stanzas, cur, in_conf = [], None, False
    for line in path.read_text(errors="replace").split("\n"):
        if not line:
            if cur:
                stanzas.append(cur)
            cur, in_conf = None, False
            continue
        if cur is None:
            cur = {"f": {}, "conf": [], "raw": []}
        cur["raw"].append(line)
        if line.startswith(" "):
            if in_conf:
                parts = line.split()
                if len(parts) >= 2:
                    cur["conf"].append((parts[0], parts[1]))
            continue
        in_conf = (line == "Conffiles:")
        if not in_conf:
            k, _, v = line.partition(": ")
            cur["f"][k] = v
    if cur:
        stanzas.append(cur)
    return stanzas

phone = {}
for s in parse_status(PDI/"status.phone"):
    pkg = s["f"].get("Package")
    if pkg:
        phone[pkg] = s
print(f"手机 status: {len(phone)} 个 stanza")

miss_phone = sorted(p for p in old_pkgs if p not in phone)
if miss_phone:
    die(f"手机 status 缺旧包: {miss_phone}")
notinst = sorted(p for p in old_pkgs
                 if phone[p]["f"].get("Status") != "install ok installed")
if notinst:
    print(f"  警告: 手机上非 install ok installed 状态: {notinst}")

# ── 读入 staging status（保留原始行，做外科手术式改写）────────
blocks, cur = [], []
for line in STATUS.read_text(errors="replace").split("\n"):
    if not line:
        if cur:
            blocks.append(cur)
            cur = []
    else:
        cur.append(line)
if cur:
    blocks.append(cur)

def block_pkg(b):
    for ln in b:
        if ln.startswith("Package: "):
            return ln[9:]
    return None

staging_pkgs = {block_pkg(b) for b in blocks}
missing = sorted(want - staging_pkgs)
if missing:
    die(f"staging status 缺包: {missing}")
extra = sorted(p for p in staging_pkgs - want if p)
if extra:
    print(f"  警告: staging status 多出包（原样保留）: {extra}")

# ── 备份原 status（只在首次）──
if not (PDI/"status.orig").exists():
    shutil.copy2(STATUS, PDI/"status.orig")
    print(f"已备份原 status -> {PDI/'status.orig'}")
else:
    print("status.orig 已存在，保留首次备份")

# ── 旧包：复制手机 info 文件（内容等长重写）──────────────────
n_old_info = 0
for pkg in sorted(old_pkgs):
    for suf in SUFFIXES:
        src = PDI/"info"/f"{pkg}{suf}"
        if not src.exists():
            continue
        dst = INFO_DST/f"{pkg}{suf}"
        dst.write_bytes(src.read_bytes().replace(OLD, NEW))
        dst.chmod(0o755 if suf in SCRIPT_SUFS else 0o644)
        n_old_info += 1
print(f"旧包 info 文件写入 {n_old_info} 个")

# ── 新包：从 deb 提取 DEBIAN 元数据 + data.tar 清单 ──────────
def ar_member(deb, member):
    return subprocess.check_output(["ar", "p", str(deb), member])

new_meta = {}   # pkg -> {version, conf: [(path, md5)]}
n_new_files = 0
for pkg in sorted(new_pkgs):
    deb = debs[pkg]
    ctl = tarfile.open(fileobj=io.BytesIO(ar_member(deb, "control.tar.xz")),
                       mode="r:xz")
    control, conffile_paths, scripts = {}, [], {}
    for m in ctl.getmembers():
        base = os.path.basename(m.name)
        if base == "control":
            for ln in ctl.extractfile(m).read().decode().split("\n"):
                if ln and not ln.startswith(" "):
                    k, _, v = ln.partition(": ")
                    control[k] = v
        elif base == "conffiles":
            conffile_paths = [l for l in
                              ctl.extractfile(m).read().decode().split("\n") if l]
        elif f".{base}" in SCRIPT_SUFS:
            scripts[base] = ctl.extractfile(m).read()
    if not control.get("Version"):
        die(f"{pkg}: deb 的 control 缺 Version")

    data = tarfile.open(fileobj=io.BytesIO(ar_member(deb, "data.tar.xz")),
                        mode="r:xz")
    entries, md5map = set(), {}
    for m in data.getmembers():
        rel = m.name[2:] if m.name.startswith("./") else m.name
        rel = rel.rstrip("/")
        entries.add("/." if rel == "" else
                    ("/" + rel).replace("com.termux", "com.kimbox"))
        if m.isreg():
            md5map[rel] = hashlib.md5(data.extractfile(m).read()).hexdigest()

    (INFO_DST/f"{pkg}.list").write_text(
        "\n".join(sorted(entries)) + "\n")
    (INFO_DST/f"{pkg}.md5sums").write_text(
        "".join(f"{h}  {rel.replace('com.termux', 'com.kimbox')}\n"
                for rel, h in sorted(md5map.items())))
    n_new_files += 2

    conf_records = []
    if conffile_paths:
        out = []
        for cp in conffile_paths:
            rp = cp.replace("com.termux", "com.kimbox")
            out.append(rp)
            h = md5map.get(cp.lstrip("/"))
            if h is None:
                print(f"  警告: {pkg} 的 conffile {cp} 不在 data.tar 中")
            else:
                conf_records.append((rp, h))   # 保留 deb 原始 md5
        (INFO_DST/f"{pkg}.conffiles").write_text("\n".join(out) + "\n")
        n_new_files += 1
    for name, content in scripts.items():
        dst = INFO_DST/f"{pkg}.{name}"
        dst.write_bytes(content.replace(OLD, NEW))
        dst.chmod(0o755)
        n_new_files += 1
    new_meta[pkg] = {"version": control["Version"], "conf": conf_records}
print(f"新包 info 文件生成 {n_new_files} 个（含 list/md5sums/conffiles/脚本）")

# ── 重算 .md5sums（基于重写后的 staging 实际文件）────────────
# issue #10: deb 原始 .md5sums 记录的是 com.termux 路径下的文件哈希，
# 但 staging 里的文件内容已经做了 com.termux→com.kimbox 等长重写，
# 导致 dpkg -V 校验时报大量 MD5 不匹配。
# 此段遍历全部包（旧+新），根据 .list 里记录的绝对路径在 staging-final
# 中实算 md5 并覆盖 .md5sums。
# 注意：Conffiles（status 中）和 .conffiles 文件里的基准哈希**不动**——
# 它们用于 dpkg 判定用户是否改过配置文件（升级时决定是否保留用户版本），
# 保持 deb 原始值才能让 dpkg 正确识别「用户修改」。
# .md5sums 仅用于 dpkg -V 完整性校验，与 Conffiles 追踪语义不同。
print("\n── 重算 .md5sums（基于 staging 实际文件）──")
n_md5_regen = 0
PREFIX_ABS_PATH = "/data/data/com.kimbox/files/usr/"
for pkg in sorted(want):
    list_file = INFO_DST / f"{pkg}.list"
    if not list_file.exists():
        continue
    md5_lines = []
    for line in list_file.read_text().split("\n"):
        line = line.strip()
        if not line or not line.startswith(PREFIX_ABS_PATH):
            continue
        rel = line[len(PREFIX_ABS_PATH):]
        if not rel:
            continue
        fp = STAGING / rel
        # 只算普通文件（非符号链接、非目录）
        if fp.is_file() and not fp.is_symlink():
            h = hashlib.md5(fp.read_bytes()).hexdigest()
            md5_lines.append(f"{h}  {rel}\n")
    md5sums_file = INFO_DST / f"{pkg}.md5sums"
    md5sums_file.write_text("".join(md5_lines))
    n_md5_regen += 1
print(f"重算 {n_md5_regen} 个包的 .md5sums ✓")

# ── 改写 status stanza ───────────────────────────────────────
version_changes, out_blocks = [], []
for b in blocks:
    pkg = block_pkg(b)
    if pkg in old_pkgs:
        # 整个 stanza 照搬手机原文，仅 com.termux→com.kimbox，
        # 保证 Version/Depends/Breaks/Replaces/Conffiles 与手机文件一致
        ph = phone[pkg]
        newver = ph["f"].get("Version")
        oldver = next((ln[9:] for ln in b if ln.startswith("Version: ")), None)
        if newver and oldver != newver:
            version_changes.append((pkg, oldver, newver))
        out_blocks.append([ln.replace("com.termux", "com.kimbox")
                           for ln in ph["raw"]])
        continue
    if pkg not in new_pkgs:
        out_blocks.append(b)
        continue

    # 新包：索引 stanza 不动，只补 deb 提取的 Conffiles（幂等）
    newver = new_meta[pkg]["version"]
    conf = new_meta[pkg]["conf"]
    oldver = next((ln[9:] for ln in b if ln.startswith("Version: ")), None)
    if newver and oldver != newver:
        version_changes.append((pkg, oldver, newver))

    # 去掉已有 Conffiles 块（保证幂等）
    lines, skip = [], False
    for ln in b:
        if ln == "Conffiles:":
            skip = True
            continue
        if skip and ln.startswith(" "):
            continue
        skip = False
        lines.append(ln)
    # 改 Version
    if newver:
        lines = [f"Version: {newver}" if ln.startswith("Version: ") else ln
                 for ln in lines]
    # 插入 Conffiles（dpkg 惯例：Description 之前；无 Description 则末尾）
    if conf:
        blk = ["Conffiles:"] + [f" {p} {m}" for p, m in conf]
        for i, ln in enumerate(lines):
            if ln.startswith("Description:"):
                lines[i:i] = blk
                break
        else:
            lines += blk
    out_blocks.append(lines)

STATUS.write_text("\n\n".join("\n".join(b) for b in out_blocks) + "\n")
print(f"status 改写完成: {len(out_blocks)} 个 stanza, "
      f"版本修正 {len(version_changes)} 处")

# ── 自检 ─────────────────────────────────────────────────────
errs = []
st = parse_status(STATUS)
if len(st) != len(want):
    errs.append(f"status stanza 数 {len(st)} != {len(want)}")
for s in st:
    for k in ("Package", "Version", "Status"):
        if k not in s["f"]:
            errs.append(f"stanza {s['f'].get('Package', '?')} 缺字段 {k}")
for pkg in sorted(want):
    if not (INFO_DST/f"{pkg}.list").exists():
        errs.append(f"{pkg} 缺 .list")
st_by_pkg = {s["f"].get("Package"): s for s in st}
for pkg in sorted(old_pkgs):
    want_raw = [ln.replace("com.termux", "com.kimbox") for ln in phone[pkg]["raw"]]
    if st_by_pkg[pkg]["raw"] != want_raw:
        errs.append(f"{pkg}: 输出 stanza 与手机原文不一致")
leftover = []
for root, dirs, files in os.walk(DPKG):
    for fn in files:
        fp = Path(root)/fn
        if fp.is_symlink():
            continue
        try:
            if OLD in fp.read_bytes():
                leftover.append(str(fp))
        except OSError:
            pass
if leftover:
    errs.append(f"com.termux 残留 {len(leftover)} 个文件: {leftover[:10]}")
if errs:
    print("\n自检失败:")
    for e in errs:
        print(f"  !! {e}")
    sys.exit(1)
print("自检通过: status 可解析、81 包 .list 齐全、dpkg 库无 com.termux 残留 ✓")

# ── 报告 ─────────────────────────────────────────────────────
print(f"\n── 版本修正清单 ({len(version_changes)} 个) ──")
for pkg, ov, nv in version_changes:
    tag = "旧包" if pkg in old_pkgs else "新包"
    print(f"  {pkg}: {ov} -> {nv}  ({tag})")

print("\n── .list 对照 staging 实物 ──")
tot_missing, tot_filtered = {}, 0
for pkg in sorted(want):
    miss = []
    for line in (INFO_DST/f"{pkg}.list").read_text().split("\n"):
        if not line.startswith(PREFIX_ABS):
            continue
        rel = line[len(PREFIX_ABS):]
        if not rel or os.path.lexists(STAGING/rel):
            continue
        if re.search(r"/share/(man|doc|info)/", line) or "/var/lib/dpkg/" in line:
            tot_filtered += 1
        else:
            miss.append(rel)
    if miss:
        tot_missing[pkg] = miss
if tot_missing:
    print("手机/deb 里有但 staging 缺失的文件（非文档类）:")
    for pkg, ms in tot_missing.items():
        print(f"  {pkg}: {len(ms)} 个")
        for x in ms[:5]:
            print(f"    {x}")
        if len(ms) > 5:
            print(f"    ... 共 {len(ms)} 个")
else:
    print("  无实质缺失 ✓")
print(f"  （另有 {tot_filtered} 条 man/doc/info 或 var/lib/dpkg 路径不在 staging"
      f"——抽取时有意过滤，属正常）")
PYEOF
