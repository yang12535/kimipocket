# 口袋Kimi (kimipocket)

把 Kimi Code 封进单个 Android APK：**安装 → 打开 → 用**，面向零折腾用户。

不依赖 Termux（对用户不可见），不需要 Tailscale/公网服务器，Agent 完全在手机本地运行，
WebView 通过 `127.0.0.1` 访问内置的 `kimi web` 服务。

## 架构

```
APK
├── assets/runtime.tar.gz    # 从真实跑通的 Termux 环境抽取的最小运行时
│                            #   (node 26 + npm + bash/coreutils/git + kimi-code 0.39.1)
├── assets/kimihome.tar.gz   # 初始 ~/.kimi-code（仅 config.toml + region，无凭据）
└── Kotlin App (无 androidx)
    ├── KimiService          # 前台服务：解压 runtime → 起 kimi web(127.0.0.1:随机端口)
    ├── RuntimeInstaller     # tar.gz 解压器（保留符号链接/可执行位）
    ├── MainActivity         # WebView 壳 + 首次部署进度 + 文件选择器
    └── BootReceiver         # 开机自启（尽力而为）
```

## 关键技术点

- **运行时来源**：不是从官方 bootstrap 闭门组装，而是从一台已验证能跑 kimi-code 的
  Termux 手机上按 dpkg 依赖闭包抽取（`runtime/extract-phone-runtime.sh`，60 个包，233MB）。
- **Prefix 补丁**：Termux 二进制硬编码 `/data/data/com.termux/...`。本应用包名
  `com.kimbox`（与 `com.termux` 同为 10 字符），对全部文件做等长字节替换
  （`runtime/patch_runtime.py`），无需重新编译任何 Termux 包。
- **termux-exec**：启动引擎时必须 `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so`，
  否则 `#!/usr/bin/env node` 之类的 shebang 在 Android 上无法执行。
- **targetSdk 28**：规避 Android 10+ 对应用私有目录 exec 的限制（与 Termux 同策略；
  纯侧载分发，不受 Play targetSdk 要求约束）。
- **登录**：不烘凭据。kimi web 未登录时内置设备码页面（去登录/复制链接/设备码），
  授权方在任意设备打开链接、输入设备码完成授权，手机端自动轮询完成。
- **工作目录**：`Android/data/com.kimbox/files/workspace`（外部应用私有目录，
  文件管理器/MTP 可见）；`HOME` 留在应用私有目录，凭据不外泄。

## 安全边界

- kimi web 只绑 `127.0.0.1` + 随机端口；`--dangerous-bypass-auth` 仅限 loopback，
  同机其他应用理论上可探测端口并访问（接受此风险，后续可改为 token 注入 WebView）。
- 导出/分享 APK 即分享全部运行时；APK 不含任何账号凭据。

## 构建

```bash
# 运行时（需要一台已跑通 kimi-code 的 Termux 手机 + ssh 访问）
cd runtime
#   1) ssh 到手机执行 extract-phone-runtime.sh > phone-runtime.tar.gz
#   2) python3 patch_runtime.py        # 解包→补丁→staging-final/usr
#   3) tar czf runtime.tar.gz -C staging-final usr
#   4) 制作 kimihome.tar.gz（staging-home/ 里的 .kimi-code 配置）
cp runtime/*.tar.gz android/app/src/main/assets/

# APK
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug     # 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 已知坑 / TODO

- 工作目录在 /sdcard（FUSE，noexec、不支持符号链接/chmod）：解释器方式运行
  （`node xx.js`、`bash xx.sh`）不受影响，直接 `./二进制` 不行；git 基本可用
- Android 12+ phantom process killer：若引擎频繁被杀，
  `adb shell settings put global settings_enable_monitor_phantom_procs false`
- 国产 ROM 杀后台：需把 App 加入电池优化白名单/自启动白名单
- node-pty 未打包（npm 可选依赖），Web 终端功能暂缺
- 引擎内更新：`npm i -g @moonshot-ai/kimi-code` 可原地升级（环境完整，理论可行）
