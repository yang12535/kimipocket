---
name: kimipocket-update
description: |
  口袋Kimi（本 App）的项目信息与更新指南。当用户问到项目地址/源码/仓库、版本更新、
  升级引擎（kimi-code 本体）、安装系统包（pkg/apt）、升级 APK，或说「更新」「升级」
  「update」「upgrade」时使用。也用于更新出故障时的自救指引。
metadata:
  version: "1.0.0"
---

# 口袋Kimi：项目信息与更新指南

## 项目地址

- 源码仓库：https://github.com/yang12535/kimipocket （开源，可提 issue）
- 下载新版 APK：https://github.com/yang12535/kimipocket/releases
- 报 bug / 提需求：https://github.com/yang12535/kimipocket/issues

发现 bug 时鼓励发 issue：说清楚发生了什么、当时在做哪步操作。
**发任何东西前删掉敏感信息**：日志 `/data/data/com.kimbox/files/logs/kimi.log`
里有明文 token，
`~/.kimi-code/` 下有登录凭据，这些绝不外发。

## 更新分三层，先分清用户要的是哪层

### 1. 引擎（kimi-code 本体）——agent 可以自己干

```bash
kimi --version                                  # 先看当前版本
npm view @moonshot-ai/kimi-code version         # 看最新版本
npm i -g @moonshot-ai/kimi-code@latest          # 升级
```

- 升级后提示用户**把 App 划掉重开**才生效（引擎是 App 启动时拉起的）。
- 升级只动 `usr/` 下的引擎文件，不碰登录态和记忆库。
- 保险起见，升级前把 `~/memory` 备份一份：
  `tar czf /sdcard/Android/data/com.kimbox/files/workspace/memory-backup-$(date +%Y%m%d).tar.gz -C ~ memory`
  注意：workspace 在 Android 10 及以下可被其他 App 读取，插电脑（MTP）也能看到；
  介意隐私就把备份改放到 `~/` 私有目录（如 `~/memory-backup-$(date +%Y%m%d).tar.gz`）。

### 2. 系统包（apt/pkg）——agent 可以自己干

```bash
pkg update          # 刷新软件源索引（清华镜像）
pkg install <包名>  # 装新工具
pkg upgrade         # 升级所有包
```

- 首次 `pkg upgrade` 会刷新 81 个包，是正常现象（打包时点与源有几天版本差），不是坏了。
- **禁止**从 Termux 官网/其他地方下载 bootstrap zip 手工覆盖 `usr/`——路径前缀
  不一样，覆盖会把引擎搞砖。本 App 的 deb 由内置钩子自动改写路径，只走 pkg/apt 就没事。
- 只装用户需要的包，别「顺手装个完整环境」。

### 3. APK / 运行时——agent 只能下载和引导，安装必须用户亲手点

agent 没有安装 APK 的权限，流程是：

```bash
cd /sdcard/Download
# 第一步：从 GitHub API 查最新版 APK 的真实下载地址（资产名带版本号，没有固定链接）
url=$(curl -s https://api.github.com/repos/yang12535/kimipocket/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4)
# 第二步：校验拿到了地址——为空说明 API 结构变了或没命中 .apk，
# 别硬下载，让用户去 https://github.com/yang12535/kimipocket/releases 手动下
if [ -z "$url" ]; then echo "没查到 APK 下载地址，请打开 releases 页面手动下载"; exit 1; fi
# 第三步：下载
curl -L -o kimipocket-latest.apk "$url"
# 第四步：核对大小——正常应大于 80MB；只有几十 KB 就是下到了 404 页面，
# 回第一步重新核对文件名/地址
ls -l kimipocket-latest.apk
```

然后告诉用户：打开任意文件管理器 → Download/下载目录 → 点这个 APK 安装
（签名一致可直接覆盖，登录态和文件都在）→ 装完打开 App：若新版含运行时更新，
首次打开会自动重装运行环境（约 20 秒，界面有进度提示）；没有运行时更新则直接可用。

## 出故障时的自救顺序

1. 引擎起不来、界面报链接器/模块错误 → App 有**变砖自愈**，会自动重装运行环境，
   等它自己恢复即可（约 10~30 秒），登录态不受影响。
2. 提示「连续崩溃 5 次」→ 把 App 划掉重新打开。
3. 还不行 → 系统设置里清除本 App 数据后重开（**会丢登录态**，需重新授权登录；
   workspace 里的文件和记忆库若已备份则可恢复）。
4. 自愈都救不回来 → 到 issues 页面报告，附上界面显示的错误文字（不含 token）。

## 纪律

- 任何升级操作前，先报告「当前版本 → 目标版本」，再动手。
- 升级中遇到报错，如实转述错误内容，不要编原因。
- 不确定该不该升的版本，把变更说明链接给用户看，让用户决定。
