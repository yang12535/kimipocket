"use strict";
// kimbox-deb-rewrite 的 node 侧：从 stdin（或 --list 指定的文件）读 NUL 分隔的路径
// 列表，对整棵 deb 解包树做等长 com.termux → com.kimbox 替换（两串同为 10 字节）。
//
// 要点：
// - 单进程、单遍扫描：分块读（默认 4 MiB），块间保留 OLD.length-1 字节重叠，跨块边界
//   的命中也能找到；命中就地改写（等长替换，文件大小不变）。内存占用恒为
//   CHUNK+OVERLAP，不再把整个文件读进内存——旧版在命中时 readFileSync 整个文件，
//   大文件（几十上百 MB 的 .so / 二进制包）在手机上会直接被 LMK 杀掉。
// - 分块扫描后把命中区间合并成有上限的若干段写回，短写会循环补齐；未命中文件零写。
// - 解包出来的文件可能是 0444（dpkg-deb -R 保留权限）：临时加写位，改完恢复原权限；
//   写入清掉的 setuid/setgid 位也会恢复。
// - 给了 --md5sums 时顺带修补 DEBIAN/md5sums：路径随目录改名替换，内容被改过的文件
//   重算 md5。不再整棵树重算（省一遍全量读盘）。修补失败就删掉该文件（md5sums 在
//   deb 里是可选的，缺失不影响 dpkg 安装）。
// - 单文件失败记 stderr 并继续处理后续文件；结尾只要有一个失败就 exit 1，由 bash 侧
//   放弃该 deb（绝不放行没重写的 termux deb）。
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const OLD = Buffer.from("com.termux");
const NEW = Buffer.from("com.kimbox"); // 与 OLD 等长，原地覆盖
const OVERLAP = OLD.length - 1;
const CHUNK_DEFAULT = 4 * 1024 * 1024;
const CHUNK_MIN = 64;
const CHUNK_MAX = 64 * 1024 * 1024;
// 同一块内相邻命中合并成一段写回；段数超限就合并成一整段，保证每块写调用有上限。
const WRITE_GAP = 4096;
const WRITE_MAX_RANGES = 32;

// 硬链接：同一个 inode 挂多个路径时（tar 里会还原成硬链接），改写其中一个就等于改了
// 全部路径的同一份数据，md5sums 里这些条目都得重算。只记 nlink>1 的，开销可忽略。
const inodeGroups = new Map(); // "dev:ino" -> [绝对路径]
let lastKey = "";              // 最近处理文件的 inode 键

const opts = { list: null, md5sums: null, label: "", chunk: CHUNK_DEFAULT, progress: true };

function log(msg) {
    process.stderr.write("kimbox-deb-rewrite: " + msg + "\n");
}

function parseArgs(argv) {
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        const need = () => {
            if (i + 1 >= argv.length) {
                log("参数缺少取值: " + a);
                process.exit(2);
            }
            return argv[++i];
        };
        switch (a) {
            case "--list": opts.list = need(); break;
            case "--md5sums": opts.md5sums = need(); break;
            case "--label": opts.label = need(); break;
            case "--chunk": opts.chunk = Number(need()); break;
            case "--no-progress": opts.progress = false; break;
            case "-h":
            case "--help":
                process.stdout.write("用法: kimbox-deb-rewrite.js [--list FILE] [--md5sums FILE]" +
                    " [--label NAME] [--chunk BYTES] [--no-progress]\n");
                process.exit(0);
                break;
            default:
                log("未知参数: " + a);
                process.exit(2);
        }
    }
    if (!Number.isFinite(opts.chunk) || opts.chunk < CHUNK_MIN || opts.chunk > CHUNK_MAX) {
        log("--chunk 取值不合理（" + opts.chunk + "），回退默认 " + CHUNK_DEFAULT);
        opts.chunk = CHUNK_DEFAULT;
    }
    return opts;
}

// 把一段缓冲区写回文件。writeSync 可能短写（只写一部分），必须循环补齐，
// 不能假设一次调用就写完。
function writeAll(fd, buf, offset, length, position) {
    let done = 0;
    while (done < length) {
        const n = fs.writeSync(fd, buf, offset + done, length - done, position + done);
        if (n <= 0) throw new Error("写入没有进展（偏移 " + (position + done) + "）");
        done += n;
    }
}

// 分块就地替换，返回替换次数；文件大小/权限/属主不变。
// 每块先把命中区间合并成有上限的若干段，再按段写回：避免逐命中 10 字节
// writeSync 产生海量 syscall，也避免短写把文件改坏。
function patchInPlace(fd, size, state) {
    const chunk = opts.chunk;
    const buf = Buffer.allocUnsafe(chunk + OVERLAP);
    let carry = 0;   // buf[0..carry) 是上一块尾部留下的重叠字节
    let off = 0;     // 下一次读的文件偏移
    while (off < size) {
        const want = Math.min(chunk, size - off);
        const n = fs.readSync(fd, buf, carry, want, off);
        if (n <= 0) throw new Error("读取提前结束（偏移 " + off + "，已读 " + n + " 字节）");
        const total = carry + n;
        const base = off - carry; // buf[0] 对应的文件偏移
        const ranges = [];
        let i = 0;
        while (i + OLD.length <= total) {
            const j = buf.indexOf(OLD, i);
            // 越过 total 的命中属于“块尾残缺”，留给下一块（靠 carry 重叠）处理
            if (j === -1 || j + OLD.length > total) break;
            NEW.copy(buf, j);
            state.hits++;
            const start = j;
            const end = j + OLD.length;
            const last = ranges[ranges.length - 1];
            if (last && start <= last[1] + WRITE_GAP) {
                if (end > last[1]) last[1] = end;
            } else {
                ranges.push([start, end]);
                // 命中太分散时合并成一整段，保证每块的写调用数有硬上限
                if (ranges.length > WRITE_MAX_RANGES) {
                    const first = ranges[0][0];
                    const stop = ranges[ranges.length - 1][1];
                    ranges.length = 0;
                    ranges.push([first, stop]);
                }
            }
            i = j + OLD.length;
        }
        if (ranges.length > 0) {
            state.wrote = true;
            for (const r of ranges) {
                writeAll(fd, buf, r[0], r[1] - r[0], base + r[0]);
            }
        }
        carry = Math.min(OVERLAP, total);
        if (carry > 0) buf.copy(buf, 0, total - carry, total);
        off += n;
    }
    return state.hits;
}

// 返回 >0 命中字节串数，0 未命中/跳过，-1 失败
function patchFile(p) {
    let st;
    try {
        st = fs.statSync(p);
    } catch (e) {
        log("无法访问: " + p.toString("latin1") + ": " + e.message);
        return -1;
    }
    if (!st.isFile() || st.size < OLD.length) return 0;
    const origMode = st.mode & 0o7777;

    lastKey = st.dev + ":" + st.ino;
    if (st.nlink > 1) {
        let g = inodeGroups.get(lastKey);
        if (!g) {
            g = [];
            inodeGroups.set(lastKey, g);
        }
        g.push(p.toString("latin1"));
    }

    let fd = null;
    let restoreMode = false; // 临时加过写位，改完（或失败）要还回去
    try {
        fd = fs.openSync(p, "r+");
    } catch (e) {
        if (e.code !== "EACCES" && e.code !== "EPERM") {
            log("打开失败: " + p.toString("latin1") + ": " + e.message);
            return -1;
        }
        restoreMode = true;
        try {
            fs.chmodSync(p, origMode | 0o200);
            fd = fs.openSync(p, "r+");
        } catch (e2) {
            let msg = e2.message;
            try {
                fs.chmodSync(p, origMode);
            } catch (e3) {
                msg += "；恢复权限也失败: " + e3.message;
            }
            log("无法以可写方式打开: " + p.toString("latin1") + ": " + msg);
            return -1;
        }
    }

    const state = { hits: 0, wrote: false };
    try {
        const size = fs.fstatSync(fd).size;
        patchInPlace(fd, size, state);
    } catch (e) {
        log("重写失败: " + p.toString("latin1") + ": " + e.message);
        state.hits = -1;
    } finally {
        try { fs.closeSync(fd); } catch (e) { /* ignore */ }
        // 写入会清掉 setuid/setgid 位；临时加的写位也要恢复。没写过就不动模式。
        if (state.wrote || restoreMode) {
            try {
                fs.chmodSync(p, origMode);
            } catch (e) {
                log("恢复权限失败: " + p.toString("latin1") + ": " + e.message);
                state.hits = -1;
            }
        }
    }
    return state.hits;
}

function md5File(p) {
    const h = crypto.createHash("md5");
    const buf = Buffer.allocUnsafe(256 * 1024);
    const fd = fs.openSync(p, "r");
    try {
        let off = 0;
        for (;;) {
            const n = fs.readSync(fd, buf, 0, buf.length, off);
            if (n <= 0) break;
            h.update(buf.subarray(0, n));
            off += n;
        }
    } finally {
        fs.closeSync(fd);
    }
    return h.digest("hex");
}

// 路径统一按 latin1 处理（一字节一字符），与磁盘上的文件名逐字节对应。
// dirty = 内容已经变了（含硬链接兄弟路径）的相对路径集合。
function rewriteMd5sums(md5Path, treeRoot, dirty) {
    const raw = fs.readFileSync(md5Path).toString("latin1");
    const lines = raw.split("\n");
    let updated = 0;
    let renamed = 0;
    let skipped = 0;
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line === "") continue;
        if (line[0] === "\\") { skipped++; continue; } // 含转义字符的畸形文件名，原样保留
        const m = /^([0-9a-fA-F]{32})[ \t]+(.*)$/.exec(line);
        if (!m) { skipped++; continue; }
        let hash = m[1];
        const before = m[2];
        const after = before.replace(/com\.termux/g, "com.kimbox");
        if (dirty.has(after)) {
            hash = md5File(Buffer.from(treeRoot + "/" + after, "latin1"));
            updated++;
        }
        if (after !== before) renamed++;
        lines[i] = hash + "  " + after;
    }
    fs.writeFileSync(md5Path, lines.join("\n"), "latin1");
    log(`md5sums: 路径改名 ${renamed} 条，重算内容 ${updated} 条` +
        (skipped > 0 ? `，跳过无法解析 ${skipped} 条` : ""));
}

function main() {
    parseArgs(process.argv.slice(2));

    let data;
    try {
        data = fs.readFileSync(opts.list === null ? 0 : opts.list);
    } catch (e) {
        log("读取文件列表失败: " + e.message);
        process.exit(2);
    }

    const paths = [];
    let start = 0;
    for (let i = 0; i <= data.length; i++) {
        if (i < data.length && data[i] !== 0) continue;
        if (i > start) paths.push(data.subarray(start, i));
        start = i + 1;
    }

    const total = paths.length;
    const step = Math.max(500, Math.ceil(total / 10));
    const changed = new Set();
    const changedKeys = new Set();
    let patched = 0;
    let failed = 0;

    for (let i = 0; i < total; i++) {
        const r = patchFile(paths[i]);
        if (r < 0) {
            failed++;
        } else if (r > 0) {
            patched++;
            changed.add(paths[i].toString("latin1"));
            changedKeys.add(lastKey);
        }
        if (opts.progress && (i + 1) % step === 0) log(`扫描进度 ${i + 1}/${total}`);
    }

    log(`扫描 ${total} 个文件，重写 ${patched} 个，失败 ${failed} 个`);

    if (opts.md5sums && fs.existsSync(opts.md5sums)) {
        const treeRoot = path.dirname(path.dirname(path.resolve(opts.md5sums)));
        const dirty = new Set();
        for (const abs of changed) {
            if (abs.startsWith(treeRoot + "/")) dirty.add(abs.slice(treeRoot.length + 1));
        }
        // 硬链接兄弟路径的内容也一起变了，md5sums 里同样要重算
        for (const key of changedKeys) {
            const group = inodeGroups.get(key);
            if (!group) continue;
            for (const abs of group) {
                if (abs.startsWith(treeRoot + "/")) dirty.add(abs.slice(treeRoot.length + 1));
            }
        }
        try {
            rewriteMd5sums(opts.md5sums, treeRoot, dirty);
        } catch (e) {
            // md5sums 是可选的：修不好就删掉，绝不因此放行未重写的 deb，也不让安装失败
            log("md5sums 修补失败，改为删除该文件: " + e.message);
            try { fs.unlinkSync(opts.md5sums); } catch (e2) { /* ignore */ }
        }
    }

    process.exit(failed > 0 ? 1 : 0);
}

main();
