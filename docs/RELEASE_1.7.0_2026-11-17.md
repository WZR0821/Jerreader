# Jerreader 1.7.0（69）维护记录 · 2026-11-17

状态：**双端 1.7.0（69）已构建、验收并打包（2026-11-17）。** Android：Release 构建（单元测试、Lint）、完整设备测试与 Release 冒烟通过，正式 APK 已打出；iOS：host-test、analyze、模拟器 XCTest 与 UI 测试通过，Release IPA 已打出。两个包都已复制到桌面。没有真机验证，没有用真实 API Key，没有提交 git。

## 1. 版本与标识

- 双端版本 **1.7.0**，build / versionCode **69**（1.6.0 是 68）。这次有新功能（两种阅读布局、按格式导入），按版本规则升中间位。也避开了统一版 `JerreaderUnified` 用过的 1.6.1、1.6.2。
- 只改 iOS App 目标的 Debug / Release（`CURRENT_PROJECT_VERSION`、`MARKETING_VERSION`），测试目标仍为 1 / 1.0。Android 在 `androidApp/build.gradle.kts`。
- bundle ID（`com.raydon.Jerreader`）、applicationId（`com.jerreader.android`）和签名证书都没变。Android 沿用原来的签名，可以覆盖安装 1.6.0。

## 2. 用户 11-17 的三条反馈（双端）

- **按格式导入**：书架的导入弹层先选格式（全部、EPUB、PDF、Word、TXT）。选定某种格式后只把它交给系统选择器（iOS 传内容类型，安卓传 MIME 类型），系统「文件」里其他格式是灰的、点不了；选“全部”时仍可选所有支持的格式。导入弹层全高显示。
- **选一句不再翻成整段**：
  - iOS：系统选区回退后，再拖手柄一律按精确选区处理，不再被智能补全扩大。
  - iOS：智能补全的句子最长 220 字（`ReaderSmartSelection.maximumCompletedSentenceLength`），超过时保留原选区。
  - 日文对白「」后面即使没有句号也算句末，跨段落的对白不再连成一句（两端规则相同：安卓在共享的 `ReaderCrossPageExpansion.kt`，iOS 在 `ReaderSentenceSegmentation.swift`）。
  - iOS 直连 AI 的提示用 `<source>` / `<context>` 分开原文和上下文，缓存身份升为 `jerreader-direct-api-v2`，旧的整段结果不再命中。
- **两种阅读布局**，在 阅读设置 → 阅读界面 → 界面布局 切换，对所有书生效，随备份导出和恢复（`reader.layoutMode`）：
  - 「标准」（默认）：上下栏变小、常驻，翻页键融进页面，正文排在两栏之间。
  - 「沉浸」：正文全屏，上下栏默认收起；轻点正文上下的空白条或没有文字的地方呼出，4 秒后自动收起。沉浸下系统状态栏始终隐藏，「隐藏状态栏」开关只在标准布局下显示。
  - 只有切换布局会让 Readium 重新分页，沉浸下显示、隐藏上下栏都不会。
- 实现细节见 `docs/ARCHITECTURE.md` 的「2026-11-17 界面布局、选句与导入筛选」一节。

## 3. 安卓界面按 iOS 逐页对齐

用户要求两端 UI 一模一样、一二三级菜单都查。做法是两端都用 @3x 模拟器截图逐页量像素，以 iOS 为准改安卓：

- 安卓阅读设置重做成 iOS 那样的「阅读与翻译」弹层。
- 各弹层的顶部位置、圆角、抓手、标题和按钮位置；设置首页的图标和颜色；箭头大小和位置；列表行高和组距。
- AI 大模型各页，含换模型弹层的首组标题和「在用」对勾。
- 学习页（练习、生词本、查询历史）的行距、分组标题，以及生词本和查询历史搜索栏下的分隔线。
- 困惑地图卡、收藏译文卡的颜色和间距。
- 修了「学习 → 翻译」页日文词结果丢「清空 / 翻译」按钮的老问题。
- 技术上：Compose Multiplatform 从 1.11.1 升到 1.12.2；行距统一用 `shared/.../ui/JerreaderTheme.kt` 的 `ExactSystemLineHeight`（默认模式会把单独一行中文撑到约 1.45 em，比 iOS 松 2–4 点）。
- 认可保留的差别：多行中文行距安卓每行约小 2 点（引擎差异）；402 与 440 屏宽带来的折行不同；状态栏和导航栏；Noto 与苹方字形；两端测试夹具的数据不同；大字号下生词本分组标题与说明的间距安卓小 1.4 点。
- iOS「默认阅读排版 → 间距」里的「分别设置」（上 / 下 / 左右边距）删了：这三个值只存不读，阅读器一直只用统一的「页边距」，安卓也从没有这一项。

## 4. 其他

- **安卓发给 AI 代理的请求改成和 iOS 一样**（11-16 晚改的源码，1.7.0 是第一个带上它的安装包）：请求体、`X-Jerreader-Translation-Protocol: 1` 头、翻译请求的 `X-Jerreader-Idempotency-Key`，自动重试沿用同一个请求和同一个键。1.6.0 及更早的安卓包仍是旧格式。
- **iPad 版暂停开发**（用户 11-17 决定，写进 `AGENTS.md`）：只做 iPhone 和安卓手机，iPad 不做专门适配和验收；`TARGETED_DEVICE_FAMILY` 暂不改。

## 5. 验证状态

日志都在 `.build/SafeValidationLogs/`（analyze 和打 IPA 在后台入口的源码副本里，日志在副本的同名目录）。

iOS（模拟器 iPhone 16 Pro Max，iOS 18.2）：

- `20261117-214630-0dbnmqm8`（host-test）：**通过**，14 组全过，含 `ai-providers` 和 `parity-fixtures`。
- `20261117-214807-sxfscisy`（analyze，后台入口）：**通过**，没有编译警告。
- `20261117-214930-1sj6k9fj`（test）：**通过**，XCTest 275 通过、3 跳过、0 失败（跳过的 3 项照旧要外部真实书籍）。
- `20261117-215632-r7g1jw7y`（ui-test）：**通过**，16 通过、11 跳过、0 失败（跳过的是要真实注音 EPUB 的 10 项和 iPad 专用的 1 项）。11-17 前一轮失败的布局测试（点到了灵动岛，已改测试）这次在整套里通过。「默认阅读排版」两页截图与安卓同页对照过：「间距」组只剩行间距、段间距、页边距。
- 以上四次都在升版本之前，之后只改了版本号。
- `20261117-221935-jp516xdk`（`scripts/background_build.py submit ipa`，Release，generic iOS device，不签名，在 launchd 后台域跑）：**通过**（`** BUILD SUCCEEDED **`），约 7 分钟。没有源码警告；23 条 `.pcm: No such file or directory` 是 dsymutil 找模块缓存时的警告，另有 1 条原有的 scheme 构建顺序警告，都同 1.6.0。
- iOS 正式包：`dist/iOS/Jerreader-1.7.0-build69-20261117-222644-unsigned.ipa`，14,355,188 字节（1.6.0 是 14,332,199），SHA-256 `0fe8e63553bc37bd04648c1870d781178bd251223d5c840f3dbda09ad8fba0c9`。bundle ID `com.raydon.Jerreader`、CFBundleShortVersionString 1.7.0、CFBundleVersion 69、最低 iOS 18.0、只有 arm64、不含测试夹具。

Android：

- `20261117-211748-kb1_4c09`（设备测试，范围 `full`，升版本前界面对齐的最后一轮）：回归 37 项、界面 12 项全过。
- `20261117-222712-n6qyl0y9`（Release 构建，第一次）：**没有结果**。22:37:51 本机会话被踢回登录界面（WindowServer 重启），构建跑到 `minifyReleaseWithR8` 时随会话被杀，当时负载约 6。不是编译失败。
- `20261117-224013-18kf1b6u`（`JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh`，用户说继续后重跑一次）：**通过**，`BUILD SUCCESSFUL in 7m 33s`。
  - `:shared:testAndroidHostTest` 88 项、`:androidApp:testDebugUnitTest` 95 项，都是 0 失败。这两项被 Gradle 判为 UP-TO-DATE，沿用输入相同的上一次结果：shared 是 21:14 那一轮，app 是被杀的那一轮在 22:28 已按 1.7.0 跑完的。
  - `lintDebug` / `lintRelease`：各 0 错误、7 警告、1 提示。比 1.6.0 多的 1 条是 `res/values/dimens.xml` 的 UnusedResources：那个 dimen 覆盖 Readium 的 `readium_navigator_epub_vertical_padding`（40dp → 14dp，与 iOS 一致），由 Readium 在运行时按名字读取，lint 看不到，属误报。其余 6 条同 1.6.0。
- `20261117-224836-agrxdkvt`（设备测试，范围 `full`，带 Release 冒烟）：**失败，一项测试都没跑**。模拟器开机时 Android 系统的 `com.android.phone` 没能按时完成启动（`am_anr`，22:51:11），验收脚本遇到开机 ANR 就停了。当时主机在降频。到这天为止本机留有日志的 47 次设备测试里，因开机 ANR 停下的只有这次和 2026-10-07 的一次。按规则没有自动重跑，用户同意后重跑一次。
- `20261117-230427-2m8qk41r`（设备测试，范围 `full`，带 Release 冒烟，重跑）：**通过**，约 15 分钟，负载最高 11.8，没有降频，没有 ANR。模拟器 `JerreaderQA_API35_20261007`（API 35，x86_64，1206×2622、480 dpi）。
  - 回归 37 项、界面 12 项全过，含 `thousandLearningRecordsRemainSearchableAndScrollable`。
  - Release 冒烟：x86_64 Release 包安装启动成功，EPUB、PDF 都能打开并回到书架，三个标签都能打开；装上去的包与构建产物的 SHA-256 一致。
- Android 正式包：`scripts/package_release_apk.py` 通过，`dist/Android/Jerreader-Android-1.7.0-build69-20261117-231952-release.apk`，42,186,555 字节（1.6.0 是 42,137,219），SHA-256 `309f26f308c3ba1b19167a31b2cdc0c71d7d7ce42f8e279c991c36f6ed613b47`。badging：versionCode 69、versionName 1.7.0、compileSdk 37、minSdk 24、targetSdk 36、只有 arm64-v8a、不可调试。与模拟器验过的 x86_64 包除原生库外完全相同；签名与 1.6.0 一致，可覆盖安装；10,735 个类无重复，不含调试夹具。
  - 同 1.6.0：这台 Mac 的 `/usr/bin/java` 找不到 Java 运行时，打包时给这一条命令临时设了 `JAVA_HOME` / `PATH`，指向仓库的 `.tools/jdk17`。

## 6. 没验证的

- 双端都没有真机验证。
- iOS UI 测试里要真实注音 EPUB 的 10 项和 iPad 的 1 项照旧跳过；阅读器的长按、翻译浮窗、竖横排等真实交互在这 10 项里。
- 真实 API Key 和真实服务商接口没有参与任何验证（测试都用 Mock）；安卓 AI 代理请求的幂等键是照 iOS 算法另算的期望值，没有拿 iOS 实际生成的键对过。
- 写这份记录时还没提交 git；后来已提交，11-18 推到 GitHub 并发了 v1.7.0 Release。
