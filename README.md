# 口袋Kimi (kimipocket)

把 Kimi Code 封进单个 Android APK：**安装 → 打开 → 用**，面向零折腾用户。

不依赖 Termux（对用户不可见），不需要 Tailscale/公网服务器，Agent 完全在手机本地运行，
WebView 通过 `127.0.0.1` 访问内置的 `kimi web` 服务。

---

## 来着作者的bb「纯手工写/我不知道后面kimi会把这段仍哪里」:

- 这个是我没事干的时候为朋友封装的，因为其电脑用的少，基本上时间都是在手机上，然后一天到晚依赖豆包，然后我就想封装个apk给他用，摆脱那个豆包「一天到晚给你发豆包的截图解释和各种东西谁受的了....」{主要是一天到晚听到豆包的那弱智发言就头疼
- 然后我封装了记忆模块，主要是为了开箱即用，体验比上来就问你一大堆问题好，而且key有更好的跨会话体验。
- app本身就是个套了个浏览器壳的终端环境，如果你想升级，完全可以自己让kimi自己升级自己，apk升级是保底选项/也有可能有bug...怕风险的话还是gh记忆备份一下好了....
- 就bb这么多了，再多我也不知道说啥了....

---

## 架构

```
APK
├── assets/runtime.pkg       # gzip tar：从真实跑通的 Termux 环境抽取的最小运行时
│                            #   (node + npm + bash/coreutils/git + kimi-code 0.40.1)
│                            #   + apt/dpkg 包管理器（清华源，81 个包）
├── assets/kimihome.pkg      # 初始 ~/.kimi-code（config.toml + region + 记忆模块提示词
│                            #   AGENTS.md + kimipocket-update skill，无凭据）
└── Kotlin App (无 androidx)
    ├── KimiService          # 前台服务：解压 runtime → 起 kimi web(127.0.0.1:17234)
    │                        #   + 变砖自愈（检测到运行时被破坏自动重解压，不动登录态）
    ├── RuntimeInstaller     # .pkg 解压器（符号链接/可执行位保留，路径逃逸防护）
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
- **包管理器**：内置 apt/dpkg（清华镜像源），App 内可 `pkg install <包>` 装软件。
  deb 内的路径硬编码 `com.termux`，由 `DPkg::Pre-Install-Pkgs` 钩子
  （`usr/libexec/kimbox-deb-rewrite`）在 dpkg 解包前等长重写为 `com.kimbox`——
  apt 下载时已按签名索引校验 deb 哈希，重写发生在校验之后，不破坏安全模型。
  首次 `pkg upgrade` 会刷新全部 81 个包（打包时点与源有几天版本差），属正常现象。
- **变砖自愈**：agent 有能力改坏自家运行时（比如手工下载 Termux bootstrap 覆盖
  系统库——真实发生过，见 issue #1）。引擎崩溃时 KimiService 读日志尾巴，
  命中链接器/模块加载错误特征（`CANNOT LINK EXECUTABLE` 等）则自动删除
  `usr/` 重解压，`home/` 登录态不动；每次服务运行只自愈一次，防死循环。
  已经过破坏性测试（删掉 libcrypto.so.3 后约 10 秒自动恢复）。
- **targetSdk 28**：规避 Android 10+ 对应用私有目录 exec 的限制（与 Termux 同策略；
  纯侧载分发，不受 Play targetSdk 要求约束）。
- **登录**：不烘凭据。kimi web 未登录时内置设备码页面（去登录/复制链接/设备码），
  授权方在任意设备打开链接、输入设备码完成授权，手机端自动轮询完成。
- **工作目录**：`Android/data/com.kimbox/files/workspace`（外部应用私有目录，
  文件管理器/MTP 可见）；`HOME` 留在应用私有目录，凭据不外泄。

## 安全边界

- **引擎认证**：`kimi web` 只绑 `127.0.0.1`，并启用内置 bearer token 认证
  （首启自动生成 `~/.kimi-code/server.token`，0600）。App 读取 token 后经
  URL fragment（`#token=...`）注入 WebView。fragment 不随 HTTP 请求发出，
  不进网络请求与服务端日志；但注意：引擎启动 banner 会把 token 明文打印进
  应用私有日志 `files/logs/kimi.log`（应用私有目录，非 root 读不到），
  崩溃界面也会展示日志尾巴——截图分享前注意给 token 打码。
- **端口固定 17234**（被占用时退随机端口）：WebView 的 localStorage 按
  scheme+host+port 隔离，固定端口保证引擎重启后 Web UI 的本地状态不丢。
- **就绪探测带指纹**：不是"端口能连上就放行"，而是要求 `GET /api/v1/healthz`
  返回 `ok`，避免误连占用端口的无关进程。
- **明文流量**：`networkSecurityConfig` 全局禁明文，仅放行 `127.0.0.1`/`localhost`。
- **WebView 收紧**：关闭 `file://`/`content://` 访问；站外链接一律交系统浏览器。
- **剩余风险（明示）**：同机其他 App 若扫到端口并发起连接，没有 token 只能拿到
  静态资源，REST/WS 全部 401；token 本身存在应用私有目录，非 root 读不到。
  root 机/调试桥（adb）下以上假设全部失效，不要在已 root 的设备上放重要凭据。
- **APK 不含任何账号凭据**；导出/分享 APK 即分享全部运行时（均为开源组件）。

## 构建

### 1. 运行时包（可选——也可以直接下载）

不想从头做运行时，可直接从 Releases 页面下载 `runtime.pkg` / `kimihome.pkg`，
放到 `android/app/src/main/assets/` 即可跳到第 2 步。

从零制作（需要一台已跑通 kimi-code 的 Termux 手机 + ssh 访问）：

```bash
cd runtime
#   1) ssh 到手机执行 extract-phone-runtime.sh > phone-runtime.tar.gz
#      （同时从手机 scp 回 dpkg-meta.tar.gz 解到 phone-dpkg-info/）
#   2) python3 patch_runtime.py        # 解包 → 补丁 com.termux→com.kimbox → staging-final/usr
#   3) ./add-pkg-manager.sh            # 从清华源抽 apt/dpkg/keyring 等 21 个包合并进 staging-final
#                                       # + 安装 deb 重写钩子（hooks/ → staging-final/usr/libexec/）
#   4) ./merge-phone-dpkg-info.sh      # 合并手机 dpkg 元数据，补齐 .list/.md5sums/.conffiles
#                                       # （md5sums 基于重写后的 staging 实算，dpkg -V 干净）
#   5) ./pre-tar-check.sh              # 前置校验：钩子、apt 配置、包清单齐全才放行
#   6) tar --numeric-owner --owner=0 --group=0 -czf runtime.pkg -C staging-final usr
#   7) 制作 kimihome.pkg（tar czf，内容是一个 .kimi-code/ 目录：
#      config.toml 选默认模型、region 标记；不要放任何 token/凭据）
cp runtime.pkg kimihome.pkg ../android/app/src/main/assets/
```

### 2. APK

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 开发自测（debug 签名）
./gradlew assembleDebug     # 产物: app/build/outputs/apk/debug/app-debug.apk

# 发布（release 签名）：先准备签名配置
keytool -genkeypair -keystore release.keystore -alias kimipocket \
  -keyalg RSA -keysize 2048 -validity 10950
cat > signing.properties <<EOF
storeFile=release.keystore
storePassword=...
keyAlias=kimipocket
keyPassword=...
EOF
./gradlew assembleRelease   # 产物: app/build/outputs/apk/release/app-release.apk
```

`release.keystore` / `signing.properties` 已在 .gitignore 中，不会进库。
**升级安装要求签名一致**——换签名后旧用户必须卸载重装（数据会丢），
正式发布后请固定使用同一 keystore。

## 升级与数据保留

- **覆盖安装（同签名）**：只动 `usr/`（运行时）；`home/`（登录态、配置、记忆库
  `~/memory`）与 `workspace/`（产出物）都保留。初始配置只在缺失时写入，
  升级永不覆盖已登录状态。
- **运行时升级**：APK 内 `runtime.pkg` 变化时（`RUNTIME_VERSION` +1），下次启动
  会清空 `usr/` 全新解压，不动 `home/`。注意：之前用 apt/pkg 装的软件包会丢，
  需要重装；agent 用 npm 自升级的引擎同理会被打包版本覆盖。
- **引擎自升级**：环境里有完整 npm + apt，理论上 agent 可以执行
  `npm i -g @moonshot-ai/kimi-code` 原地升级自身，`pkg install` 补系统依赖
  ——这也是保留 `home/` 的另一个原因。
- **签名谱系（重要）**：v0.2.0 起更换了签名证书（旧 keystore 口令丢失作废）。
  旧证书 SHA-256：`8d145982…341e3`（v0.1.x）；新证书 SHA-256：
  `541e2a1f…0d51`（v0.2.0+）。**从 v0.1.x 升级必须先卸载再装**——卸载会清掉
  应用数据：登录态要重新授权；workspace 里的文件先备份（卸载前连电脑
  拉取 `Android/data/com.kimbox/files/workspace`，或让 Kimi 先复制到
  `Download/下载` 目录）。新谱系内（v0.2.0 起）覆盖安装一切保留。

## 发版约定

- 版本提升：`versionCode` +1、`versionName` 跟上；`runtime.pkg` 内容变了就把
  `RUNTIME_VERSION` +1（触发已装机重解压，home 保留）；种子 home 内容变了把
  `SEED_VERSION` +1（老装机迁移新种子，用户改过的文件不覆盖）。
- **Release 简介标签**：简介开头的 `[标签]` 会被 App 的「设置 → 检查更新」解析
  展示（启动时自动拉取，有新版时齿轮亮红点，用户点开看过即消）。常用标签：
  `[apk底层更新-只能从更新升级]`（必须装新 APK）、`[固定升级版本-可自行升级]`、
  `[agents.md更新]`。标签只影响展示分类，不阻断任何流程。
- Release 资产：`kimipocket-X.Y.Z.apk` + `kimipocket.apk`（稳定名副本）+
  `runtime-X.Y.Z.pkg` + `kimihome-X.Y.Z.pkg`，简介附 SHA-256。

## 已知坑 / TODO

- 工作目录在 /sdcard（FUSE，noexec、不支持符号链接/chmod）：解释器方式运行
  （`node xx.js`、`bash xx.sh`）不受影响，直接 `./二进制` 不行；git 基本可用
- Android 12+ phantom process killer：若引擎频繁被杀，
  `adb shell settings put global settings_enable_monitor_phantom_procs false`
- 国产 ROM 杀后台：需把 App 加入电池优化白名单/自启动白名单
- node-pty 未打包（npm 可选依赖），Web 终端功能暂缺
- 不要手工 `dpkg -i` 装网上下载的 deb：这会绕过 `DPkg::Pre-Install-Pkgs`
  路径重写钩子，deb 里硬编码的 `com.termux` 路径没被改写，安装会失败。
  装包只走 `pkg` / `apt`
- 引擎在 10 分钟窗口内累计崩溃 5 次后停止自动重启（防烧电死循环——能启动但反复
  被杀的场景也算在内），重新打开 App 即可再试；
  若是运行时损坏（链接器/模块错误），会触发自愈自动重解压，见上文「变砖自愈」
