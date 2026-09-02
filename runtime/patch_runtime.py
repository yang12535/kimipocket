#!/usr/bin/env python3
"""把抽取出的 Termux 运行时从 com.termux 补丁为等长的 com.kimbox，并重新布局为 usr/ 顶层。"""
import os, sys, tarfile, shutil

SRC_TAR = "phone-runtime.tar.gz"
OUT_DIR = "staging-final"          # 内含 usr/
OLD = b"com.termux"
NEW = b"com.kimbox"
assert len(OLD) == len(NEW)

TMP = "staging-phone"
shutil.rmtree(TMP, ignore_errors=True)
shutil.rmtree(OUT_DIR, ignore_errors=True)
os.makedirs(TMP)

print("[1/4] 解包 ...", flush=True)
with tarfile.open(SRC_TAR, "r:gz") as tf:
    tf.extractall(TMP, filter="tar")

# data/data/com.termux/files/usr -> staging-final/usr
src_usr = os.path.join(TMP, "data/data/com.termux/files/usr")
os.makedirs(OUT_DIR)
shutil.move(src_usr, os.path.join(OUT_DIR, "usr"))
shutil.rmtree(TMP)

print("[2/4] 补丁文件内容 ...", flush=True)
n_files = n_patched = 0
for root, dirs, files in os.walk(os.path.join(OUT_DIR, "usr"), followlinks=False):
    for name in files:
        p = os.path.join(root, name)
        if os.path.islink(p):
            continue
        try:
            with open(p, "rb") as f:
                data = f.read()
        except OSError:
            continue
        n_files += 1
        if OLD in data:
            data = data.replace(OLD, NEW)
            with open(p, "wb") as f:
                f.write(data)
            n_patched += 1
print(f"      扫描 {n_files} 个文件，补丁 {n_patched} 个", flush=True)

print("[3/4] 补丁符号链接目标 ...", flush=True)
n_links = 0
ABS_PREFIX = "/data/data/com.kimbox/files/"
for root, dirs, files in os.walk(os.path.join(OUT_DIR, "usr"), followlinks=False):
    # os.walk 把"指向目录的符号链接"放在 dirs 里，必须一起检查
    for name in files + [d for d in dirs if os.path.islink(os.path.join(root, d))]:
        p = os.path.join(root, name)
        if os.path.islink(p):
            tgt = os.readlink(p)
            new_tgt = None
            if "com.termux" in tgt:
                new_tgt = tgt.replace("com.termux", "com.kimbox")
            # 绝对路径目标（/data/data/<pkg>/files/...）换机/多用户下会失效，统一转相对
            if (new_tgt or tgt).startswith(ABS_PREFIX):
                src = new_tgt or tgt
                on_disk = os.path.join(OUT_DIR, src[len(ABS_PREFIX):])
                new_tgt = os.path.relpath(on_disk, os.path.dirname(p))
            if new_tgt is not None and new_tgt != tgt:
                os.unlink(p)
                os.symlink(new_tgt, p)
                n_links += 1
print(f"      补丁 {n_links} 个符号链接", flush=True)

print("[4/4] 检查残留 ...", flush=True)
leftover = []
for root, dirs, files in os.walk(os.path.join(OUT_DIR, "usr"), followlinks=False):
    for name in files + [d for d in dirs if os.path.islink(os.path.join(root, d))]:
        p = os.path.join(root, name)
        if os.path.islink(p):
            if "com.termux" in os.readlink(p):
                leftover.append(p + " (link)")
            continue
        try:
            with open(p, "rb") as f:
                if OLD in f.read():
                    leftover.append(p)
        except OSError:
            pass
if leftover:
    print("残留 com.termux 引用:", *leftover[:20], sep="\n  ")
    sys.exit(1)
print("      无残留 ✓", flush=True)
print("完成：staging-final/usr")
