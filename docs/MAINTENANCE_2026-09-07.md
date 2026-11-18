# Jerreader 维护记录与后续开发建议

日期：2026-09-07。起点：仓库提交 `b2ae39b`，Android 与 iOS 源码均为 1.3.5 / build 56。本次是维护修复，不推进新功能里程碑，也不宣称已发布新版本。

## 本次修复

| 问题与触发方式 | 原因 | 改动 |
| --- | --- | --- |
| 翻译 A 尚未完成时编辑为 B，旧译文仍可能出现 | iOS 没有监听输入变化；Android 只清空显示，没有取消原请求 | iOS 沿用请求 UUID 并在编辑时失效；Android 抽出 `TranslationToolSession`，输入、语言、模式、服务变化时统一取消附属工作，并在异步返回后检查取消状态 |
| Android 已清空译文后，旧词典/AI 解析或错误重新出现 | `runCatching` 把取消当成普通错误，任务与当前输入的生命周期分离 | 用 `runCatchingActive` 保留取消语义，拒绝取消后迟到的成功和失败；新增 4 个无网络并发回归 |
| Android 翻译已经成功，却仍显示“翻译中”等待词典 | 翻译加载状态覆盖了可选词典请求 | 先显示译文，再补词典详情；服务取消时恢复可操作状态 |
| Android 粘贴超长原文，或句子切换词语模式后文字丢失 | 输入回调和切换模式直接调用 `take(limit)` | 保留全文，超长提示且禁止提交，允许切回句子模式 |
| iOS 收藏失败仍显示“已收藏” | `try?` 丢弃保存错误后无条件设置成功状态 | 只有保存成功才显示收藏状态，失败提示；复制/收藏反馈随当前结果清理，移除跨结果的延时重置任务 |
| 小屏幕、大字体下操作行拥挤，搜索/分段控件裁切 | 固定高度与单行布局 | iOS 输入操作自动换行并在辅助大字体下纵向排列；Android 操作用 FlowRow，搜索与分段控件允许增高，语言菜单保留箭头空间 |
| 新 Android 包被命名为 1.0.2，维护入口仍提示直接运行 xcodebuild | 构建脚本和文档没有随版本维护 | 打包名读取 APK 元数据；使用 UTF-8、单 worker；README 改用 iOS 保护脚本；区分历史验收与本次结果 |

此次未改动导入、Readium 打开、关闭和 EPUB 文件管理链路。阅读进度、正文渲染和文件不可变规则继续沿用现有实现。

## 验证

- Android：在 UTF-8 环境下完成一次串行离线验证，共享层 69 项、应用 12 项（含新增 4 项）全部通过，共 81 项，0 失败、0 跳过。`lintDebug` 完成，0 错误、20 条警告、11 条提示；不把 lint 任务完成写成“零警告”。`assembleDebug` 成功，产物元数据确认为 1.3.5 / build 56。
- 首次 Android 尝试在 Gradle 启动前因非 UTF-8 环境无法启动中文路径中的 Java 子进程而失败。用独立 ProcessBuilder 小程序复现并验证 UTF-8 修正后，才执行上述验证；没有循环重跑。
- iOS：通过 `scripts/safe_xcodebuild.sh build` 完成唯一一次 generic iOS device 未签名 Debug / arm64 构建，iPhoneOS 18.2 SDK，最终 `BUILD SUCCEEDED`。使用最多 2 个 job，未启动模拟器，未运行 analyze 或再次构建。
- `git diff --check`、打包脚本 shell 语法和 Xcode 工程 plist 语法检查通过；打包文件名读取已生成 APK 的元数据验证通过。未再次执行完整打包脚本，以免重复整轮构建；未复制 APK 到分发目录，也未生成新的 IPA。
- 未启动模拟器，未执行 iOS 单元/UI 测试、Android instrumentation 或两端视觉截图验收。本次修改不触及阅读文件生命周期；Android 出版物内容与修改时间检测的本地单元测试已运行，Readium 真正打开/阅读/退出的设备回归尚未在本轮运行。

新增 Android 回归覆盖：忽略取消的服务迟到结果不能覆盖新结果；旧词典/解析错误不能回写；服务主动取消后退出加载；当前错误正常呈现及离开页面取消任务。既有共享层、出版物完整性和备份单元测试随应用测试一并验证。测试只使用内存回调和本地夹具，不调用外网。

构建日志保存在本机 `.build/maintenance-android-validation-utf8.log` 与 `.build/maintenance-ios-validation.log`；JUnit 报告位于 `shared/build/test-results` 与 `androidApp/build/test-results`，lint 报告位于 `androidApp/build/reports/lint-results-debug.html`。这些生成文件不提交到仓库。

## 后续建议：一次选择一个里程碑

### 1. 稳定性验收与维护版发布（优先）

先把这次维护收敛为可复验的小版本。建立一套固定、允许使用的测试书：英语横排、日语带 ruby 横排/竖排、图片与脚注、长章节；记录两端导入、恢复位置、翻页、选区、翻译与退出的结果。任何阅读生命周期改动必须验证文件内容和修改时间均不变。

界面验收覆盖小屏幕、大字体、深色、横屏及 iPad；本次特别检查“请求中编辑/切换/清空”、慢词典、离线错误和收藏失败。Apple/ML Kit 首次语言包下载、真实 File Provider/SAF 授权及备份恢复用真机补验。通过后才提升版本、生成候选包并更新功能对照表。

完成标准：两端关键流程均有明确通过/失败记录，无永久加载、旧结果回写、位置丢失或文件变化；安装升级保留现有数据。真实服务和视觉验收不能用编译通过代替。

### 2. 收敛阅读交互

重点处理点句/点段、长按词语、精确拖选与翻页之间的边界，而不是继续增加入口。把语言、横竖排、ruby、脚注、连续选区和关闭卡片组合成一份两端共用验收矩阵，平台实现仍分别使用 Readium。字符限制已在第二轮按完整字符统一；后续设备验收继续覆盖不同系统 Unicode 版本的差异。

iOS “正文短按命中完整单词并直接写入学习记录”仍是未完成的正式验收目标。现有降级是长按选词、翻译后加入生词本，或学习页词语模式。下一里程碑应明确保留该降级还是正式实现短按查词，并单独验收，不能混同短按句段翻译。

完成标准：同一触摸只触发一个主要动作，翻页不误翻译；切换选区不会显示旧卡；关闭卡片不改变原排版或阅读位置。

### 3. 按模块整理状态与共享规则

本次仅抽出了 Android 翻译页任务生命周期。后续按功能边界逐步拆分：阅读翻译请求/超时/备用服务、选区几何与布局、学习页保存与反馈；每次抽出一个模块并保留原行为。

当前 `EPUBReaderViewModel.swift` 约 2,600 行、`ReaderModels.swift` 约 3,800 行、Android `ReaderActivity.kt` 约 2,000 行、`LearningScreen.kt` 超过 1,100 行。它们适合逐步分解，但不宜同一轮全量重写。共享层优先承载可用同一输入输出验证的文本限制、缓存键语义、错误与选择策略；iOS UI 不必为形式上的共享迁移到 Compose。

完成标准：UI 不读取第三方响应；请求状态能以 Mock 独立测试；超时、取消与持久化错误有统一且可复验的处理。

### 4. 学习体验闭环（前述稳定后再立项）

围绕现有生词本与译文收藏做本地复习：从收藏回到原句/原书、筛选待复习词、简短复习与导出。先确认“读书→收藏→复习”是否方便，再决定是否恢复发音和跟读。语音目前仍关闭，恢复应独立验收。

登录、云同步、支付、更多格式和整本翻译不属于本次或上述维护工作的默认范围，需要单独定义需求与里程碑。

## 仍需注意的边界

- PRD v1.0 是原始范围；架构文档包含后来已获授权的扩展。应在下一次规划中建立当前需求基线，保留历史文档而不将旧范围当作最新发布说明。
- 功能对照表中的“已完成”主要是历史记录；应逐项附对应版本和设备，避免继承为新版本验收结论。
- 本次视觉修改来自布局代码检查；未运行设备截图验收前，不宣称所有视觉问题已消除。

## 第二轮：已发现问题收尾

本节承接用户“解决目前发现的问题，再给新功能方向”的要求。新功能方案单列在 [新功能开发方向](FEATURE_DIRECTIONS.md)。

| 项目 | 本轮处理 |
| --- | --- |
| 字符计数不一致 | Android 翻译页计数、词语/句子校验和服务入口统一使用完整字符簇计数，保持原文本身不变；iOS 继续使用 Swift Character 计数。加入“𠮷”、组合假名、重音、emoji、旗帜、ZWJ 家庭 emoji 的回归。内部定位偏移仍用 UTF-16，不将显示计数用于 Readium 几何索引。 |
| 独立翻译永久等待 | iOS 抽出 Foundation 层 watchdog：Apple session 未进入时只重启一次，仍未启动则退出加载；语言准备保留 180 秒预算，网络请求 30 秒。Android 的任务作用域用独立计时器终止等待，即使服务忽略取消也不能回写。词典补全单独 15 秒、AI 解析 70 秒。 |
| Android 检查报告 | 将偏好写入改成等价 KTX 写法并保持异步 apply 语义；版本依赖移入 version catalog；拖动/进度状态改成专用数值状态。PDF 的触摸观察器始终返回 false，点击由 PDFView 处理，该 lint 是误报，局部注明理由排除，不能追加 performClick 导致双击。 |
| 图标操作无朗读名称 | 给译文更多/收藏/关闭、语言交换和清空搜索控件补动作名称与按钮语义。 |

字符分段使用平台的 Unicode 实现：[Android Pattern](https://developer.android.com/reference/java/util/regex/Pattern) 由 ICU 提供正则支持，[ICU 的 `\X`](https://unicode-org.github.io/icu/userguide/strings/regexp.html) 表示完整字符簇；共享层 iOS 适配使用 [NSString 组合字符范围](https://developer.apple.com/documentation/foundation/nsstring/rangeofcomposedcharactersequence(at:))。不同旧系统内置 Unicode 版本可能存在新 emoji 的分段差异，设备兼容性验收仍需覆盖实际最低系统。

### 本轮验证

- Android 本轮离线单元测试：共享层 72 项、应用 14 项，共 86 项，0 失败、0 跳过。新增 3 项字符边界测试和 2 项超时测试。
- Android `lintDebug` 本轮完成，报告 0 项问题。原 20 条警告和 11 条提示已处理，其中 PDF 观察器的点击警告经代码核对为委托给 PDFView 的误报，采用有解释的局部排除；其余通过代码改动消除。
- macOS 主机编译并执行生产 `StandaloneTranslationWatchdog` 的回归：4 个异步场景通过，另核对 7 组 Swift 字符计数夹具。它不是 iOS App 单元测试，也不替代设备验收。
- Android `assembleDebug` 本轮成功，APK 元数据为 1.3.5 / build 56。部分第三方预编译 `.so` 无法剥离调试符号，Gradle 按原样打包；这不是 lint 问题，也没有导致构建失败。日志：`.build/maintenance-followup-android.log`。
- iOS 本轮通过保护脚本完成唯一一次 generic iOS device / Debug / arm64 / 未签名构建，最终 `BUILD SUCCEEDED`。新增 watchdog 与翻译页面集成已编译；日志：`.build/maintenance-followup-ios.log`。未运行 iOS App 单元/UI 测试，也未打包分发 IPA。
- 模拟器/UI 验收已向用户申请，尚未获得明确同意，未启动任何模拟器；共享层 Kotlin/iOS 适配未单独构建，当前 iOS App 仍使用原生 Swift 界面与 Swift Character 计数。

主机核心回归可从仓库根目录复现（需完整 Xcode Developer 环境，且与其他构建串行）：

```sh
mkdir -p .build
xcrun swiftc -parse-as-library -target "$(uname -m)-apple-macosx13.0" \
  Jerreader/Features/Learning/StandaloneTranslationWatchdog.swift \
  scripts/test_translation_watchdog.swift -o .build/translation-watchdog-tests
.build/translation-watchdog-tests
```
