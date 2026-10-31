# 给 AI 代理的仓库说明

口袋Kimi：把 Kimi Code 封进单个 Android APK（Kotlin 原生壳 + 内置 Termux 系运行时 + WebView）。架构、构建与发版约定见 README.md。

## Review guidelines

- 审查意见一律用**简体中文**书写（标题、正文、建议都用中文；代码、命令、标识符保持原样）。仓库作者读英文吃力。
- 重点看：运行时/种子升级是否会覆盖用户数据或登录态（`RuntimeInstaller` 的版本号与 `KNOWN_SEED_HASHES`）、deb 改写钩子能否把 `pkg install` 搞坏、WebView 只放行本机引擎端口、token 不进日志。
- 原生界面不引 androidx/Material，零件都在 `Ui.kt`；minSdk 26、targetSdk 28 是刻意的（见 build.gradle.kts 注释），不要建议升 targetSdk。
