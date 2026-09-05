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

agent 没有安装 APK 的权限，也不能往公共目录写文件（应用无存储权限，curl 下载
到 `/sdcard/Download` 在 Android 10+ 会 EACCES）。正确做法是**调系统浏览器**
让它自己去下载——浏览器有自己的存储权限：

```bash
# 优先：直接让浏览器打开 releases 页面，用户自己点 APK 下载
am start -a android.intent.action.VIEW \
  -d "https://github.com/yang12535/kimipocket/releases"
```

如果 `am start` 报错（极少见，比如某些精简 ROM 砍了 Activity Manager CLI），
回退方案：告诉用户手动打开浏览器访问 https://github.com/yang12535/kimipocket/releases 。

**禁止**用 `curl -o /sdcard/Download/...` 或任何写公共目录的命令，一定会失败。

下载完后告诉用户：在浏览器下载记录或文件管理器里点这个 APK 安装（签名一致可直接
覆盖，登录态和文件都在）→ 装完打开 App：若新版含运行时更新，首次打开会自动重装
运行环境（约 20 秒，界面有进度提示）；没有运行时更新则直接可用。

## 文件导出（把做好的文件交给用户）

应用没有写公共目录的权限，**禁止** `cp / mv` 到 `/sdcard/Download` 等公共路径
（EACCES）。正确流程：

1. 把要导出的文件放到 `~/exports/`（即 `/data/data/com.kimbox/files/home/exports/`）。
   如果目录不存在就 `mkdir -p ~/exports`。
2. 告诉用户：点 App 右下角的 📤 按钮 → 选择文件 → 选保存位置。

这个按钮走的是系统文件选择器（SAF），系统会处理权限和写入，不需要应用有存储权限。

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
