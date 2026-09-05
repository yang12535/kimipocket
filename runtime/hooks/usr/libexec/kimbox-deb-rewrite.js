"use strict";
// kimbox-deb-rewrite 的 node 侧：从 stdin 读 NUL 分隔的文件路径列表，单进程处理整棵
// deb 解包树。先分块扫描 com.termux 字节串（块间保留 9 字节重叠防跨界漏检），命中才
// 读全文件做等长替换并写回；未命中的文件只有读开销、零写操作。
// 单文件失败记 stderr 并继续处理后续文件；结尾只要有一个失败就 exit 1，由 bash 侧
// 决定放弃该 deb（保留原 deb 不动）。
const fs = require("fs");

const OLD = Buffer.from("com.termux");
const NEW = Buffer.from("com.kimbox"); // 与 OLD 等长（10 字节），原地覆盖
const CHUNK = 4 * 1024 * 1024;
const OVERLAP = OLD.length - 1;

function scanHit(fd, size) {
    const buf = Buffer.allocUnsafe(CHUNK + OVERLAP);
    let carry = 0;
    let off = 0;
    while (off < size) {
        const want = Math.min(CHUNK, size - off);
        const n = fs.readSync(fd, buf, carry, want, off);
        if (n <= 0) break;
        const total = carry + n;
        if (buf.subarray(0, total).indexOf(OLD) !== -1) return true;
        carry = Math.min(OVERLAP, total);
        buf.copy(buf, 0, total - carry, total);
        off += n;
    }
    return false;
}

// 返回 0=未命中/跳过，1=已重写，-1=失败
function patchFile(p) {
    let fd;
    try {
        fd = fs.openSync(p, "r");
    } catch (e) {
        console.error("kimbox-deb-rewrite: open 失败: " + p + ": " + e.message);
        return -1;
    }
    let hit = false;
    try {
        const size = fs.fstatSync(fd).size;
        hit = size >= OLD.length && scanHit(fd, size);
    } catch (e) {
        console.error("kimbox-deb-rewrite: 扫描失败: " + p + ": " + e.message);
        fs.closeSync(fd);
        return -1;
    }
    fs.closeSync(fd);
    if (!hit) return 0;
    try {
        const b = fs.readFileSync(p);
        let i = 0;
        while ((i = b.indexOf(OLD, i)) !== -1) {
            NEW.copy(b, i);
            i += NEW.length;
        }
        fs.writeFileSync(p, b); // 截断写回，权限/属主不变
        return 1;
    } catch (e) {
        console.error("kimbox-deb-rewrite: 重写失败: " + p + ": " + e.message);
        return -1;
    }
}

const chunks = [];
process.stdin.on("data", (c) => chunks.push(c));
process.stdin.on("end", () => {
    const data = Buffer.concat(chunks);
    let scanned = 0, patched = 0, failed = 0;
    let start = 0;
    for (let i = 0; i <= data.length; i++) {
        if (i < data.length && data[i] !== 0) continue;
        const p = data.subarray(start, i); // Buffer 路径，避免文件名编码问题
        start = i + 1;
        if (p.length === 0) continue;
        scanned++;
        const r = patchFile(p);
        if (r > 0) patched++;
        else if (r < 0) failed++;
    }
    console.error(`kimbox-deb-rewrite: 扫描 ${scanned} 个文件，重写 ${patched} 个，失败 ${failed} 个`);
    process.exit(failed > 0 ? 1 : 0);
});
