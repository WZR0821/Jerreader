# Jerreader 1.5.0（67）维护记录 · 2026-11-12

状态：**Android 已全部通过并打出正式包（2026-11-12）：单元测试、Lint、Debug/Release 构建、模拟器设备测试与 Release 冒烟。iOS 的 `doctor`、`host-test`（含两端对拍）、Debug `build`、模拟器 XCTest（2026-11-13，259 通过 / 3 跳过 / 0 失败）与 UI 测试（9 通过 / 11 跳过 / 0 失败；跳过的是要真实书籍或 iPad 的项）已通过；Release IPA 尚未打包。** 2026-11-11 本机在 Moji 的 Gradle 构建期间出现 WindowServer watchdog，用户随后要求"先改代码，构建前逐阶段征得同意"。下文"验证状态"一节列出已跑和待跑的阶段；没跑过的阶段所涉及的改动不能视为已通过。

## 1. 版本与标识

- 双端版本 **1.5.0**，build / versionCode **67**。61–66 被跳过：`JerreaderUnified` 与本仓库共用 Android `applicationId`，统一版已经发到 versionCode 66，装过它的手机拒绝更小的 versionCode。下一个可分发构建用 68。
- iOS bundle ID 改为 `com.raydon.Jerreader`（此前为占位的 `com.example.Jerreader`）。手机上会是另一个 App：旧书库用"备份 → 恢复"迁移；API Key 只在旧 App 的钥匙串里，不进备份，需要重新填写。
- Android `minSdk` 23 → **24**（Android 7.0 起）。

## 2. Android 体积与工具链

- Release 打开 R8，但**只压缩、不混淆**（`androidApp/proguard-rules.pro` 关闭混淆，保留没有自带 consumer rules 的 Readium 与 PDF 渲染库），同时开启资源压缩。
- ABI 拆分：交付物只有 `arm64-v8a`；`x86_64` 用同一份 DEX 与资源构建，只供本机模拟器装 Release 候选包验收。不再提供 32 位 ARM（旧通用包里约 53 MB 属于 32 位 ABI）。
- **compileSdk 36 → 37，targetSdk 仍为 36。** Readium 3.4.0、Compose 1.12.1、androidx.core 1.19.0 和 Lifecycle 2.11.0 的 AAR 元数据要求 compileSdk 至少 37（2026-11-12 第一次完整构建在 `checkDebugAarMetadata` 报了 17 项）。compileSdk 只决定编译时可用的 API，不改变运行行为；targetSdk 37 的行为变化在设备复核之前不打开。本机 SDK 新装了 `platforms;android-37.0`（AGP 9.5.1 把 `compileSdk = 37` 解析为 `android-37.0`）。core 改为直接声明 1.19.0（原先依赖关系已经间接拉到 1.19.0），所以不再需要 `lint.xml` 里的忽略项。
- 工具链：AGP 9.5.1、Kotlin 2.4.21、Compose Multiplatform 1.12.2、Gradle 9.9.1（wrapper 固定 SHA-256）、KSP 2.3.13、Room 2.8.5、coroutines 1.11.0、androidx.core 1.19.0、AppCompat 1.8.0、Fragment 1.9.1、Lifecycle 2.11.0、org.json 20260814。
- **Readium 3.3.0 → 3.4.0。** 3.4.0 修正了 PDFium 导航器把每一页报高一页的问题，因此 1.4.1 及更早写下的 PDF 进度、书签、批注和学习记录都带着旧的偏移。`PdfLocatorFormat` 不一次性改库（之后恢复的旧备份会把它改回去），而是给新写入的 PDF locator 加标记，读到没有标记的旧 locator 时就地换算。回归：`PdfLocatorFormatTest`（设备）、`PdfLegacyPositionIndexTest`（JVM）。

## 3. 双端规则一致性

- 新增 `shared/parity/`：`word_review.json`、`sentence_practice.json`、`backup_prune.json`、`reading_companion.json`。同一批用例由两端各自执行：
  - Android：`:shared:testAndroidHostTest` 里的 `ParityFixturesTest`（JVM，org.json 解析）。
  - iOS：`scripts/safe_xcodebuild.sh host-test` 里的 `parity-fixtures` 套件（`scripts/test_parity_fixtures.swift`）。
- iOS 新增 `LibraryBackupPruner`，对应共享 Kotlin 的备份清理规则；`LibraryBackupPolicy` 改为调用它。
- 改任何一端的复习、原句练习、备份清理或伴读规则时，先改 `shared/parity/` 里的用例，再让两端都通过。

## 4. 结构整理（行为不变）

- iOS：`EPUBReaderViewModel` 从 `ObservableObject` 改为 Observation 的 `@Observable`；书内搜索拆到 `ReaderSearchModel`，伴读拆到 `ReaderCompanionModel`。
- iOS：`ReaderModels.swift` 3789 → 1137 行，拆出 `ReaderAppearanceModels`、`ReaderNavigationModels`、`ReaderSelectionModels`、`PDFTextSelection`、`ReaderSentenceSegmentation`；`EPUBReaderHost.swift` 3671 → 2279 行，注入脚本移到 `EPUBReaderScripts.swift`。
- Android：新增 `ReaderSessionViewModel` 与 `ReadingSessionRecorder`。阅读计时和尚未保存的位置不再随旋转等配置变化丢失；导航器、选区和翻译界面仍留在 `ReaderActivity`。回归：`ReadingSessionRecorderTest`。

## 5. 从统一版移植的阅读学习功能

统一版（`JerreaderUnified`，只读参考）1.6.x 的阅读驱动学习功能移植到两端；规则在共享 Kotlin `ReadingCompanion.kt` / `ReadingLens.kt` 与 iOS 的 `ReadingCompanion.swift` / `ReadingLens.swift` 中各有一份，由 `reading_companion.json` 对拍。

- **AI 阅读透镜**：选区菜单"AI 阅读透镜"，四种问法：读懂这段、语境词义、省略与指代、日语语法。只发送选区和有限前后文，提示词要求不剧透；结果头部注明依据（选区、章节、伴读模式、阅读辅助等级）。"日语语法"沿用用户自己的语法分析模板。原有"AI 句子结构分析"不变，缓存键也不变（只有透镜请求才追加透镜标识）。
- **伴读模式**：每本书单独记住"小说 / 非虚构"。
- **AI 困惑地图**：只根据重复查词、真实原句和复习结果在本机生成，不联网；阅读器伴读面板列出本书条目，学习页列出全部书籍。
- **续读回顾**与 **90 秒章节闭环**：不自动弹出。重新打开有进度的书、或读进下一章时，阅读栏的"AI"按钮亮一个小点；点开才显示。
- **发音 / 跟读**：只在单词复习卡里、只对日语词提供"朗读单词"和"朗读原句·跟读"。iOS 的全局语音开关 `SpeechFeatureAvailability.isEnabled` 仍为关闭，新增的 `learningReviewIsEnabled` 只打开复习卡这一处。

未移植或有差异：

- 统一版复习页的"按阅读困难优先"排序未移植。
- 本仓库的词条没有原文 Locator，"回到原文"实现为书内搜索该词。
- 统一版困惑地图中的 CONTEXT 类条目未移植。
- 章节闭环由自动弹出改为小点提示。
- iOS 的透镜结果页可以切换透镜问法，但不能在页内切换伴读模式（伴读模式在伴读面板里改）；Android 的选择面板两者都有。

## 6. 短按查整词

此前 iOS 的"正文短按命中完整单词并直接写入学习记录"一直是未完成的正式目标（见 `CONTEXTUAL_LOOKUP_2026-09-10.md`）。

- **iOS（EPUB）**：关闭"轻点正文快速翻译"后，轻点正文中的词语会直接打开"语境查词"并写入学习记录，所在句子作为原句；轻点空白处仍然显示或隐藏阅读界面，左右边缘仍然翻页。词语范围由新的 `ReaderWordSegmenter`（`NLTokenizer`）决定：日语会把被切开的词尾并回原词（"食べ＋ました"→"食べました"，点在"まし"上同样得到"食べました"），但"ない / た / て"只并到以平假名结尾的词干上，避免"問題ない"这类名词被并错。必须点在字形 28pt 范围内才算命中。临时高亮在查词页关闭时清除，不改写 EPUB。PDF 保持原样。
- **Android**：移植统一版的两处修复。命中脚本在同一次计算中直接返回词面与前后文，不再读取可能滞后一拍的 Readium 原生选区（旧路径偶尔首击无响应或查到上一个词）；Readium 坐标的 DPR 换算放在产生坐标的同一份 WebView 文档里；点在空白处不再查最近的词。另外，查词卡片成功返回时即写入学习记录（查词次数 +1），之后点"加入生词本"只加星标，不重复计数。伴读的困惑地图因此能用到短按查词。
- 两端设置页的说明文字已同步更新。
- 新增 XCTest：`testShortTapWordSegmentation*` 四项（英文整词与原句、数字与空白、日语词尾合并、名词后的"ない"不合并）。
- **仍待正式验收**：真机或模拟器上的命中准确度、ruby 注音、竖排，以及与翻页手势的冲突。降级入口不变：长按选词后从卡片查词，或"学习 → 翻译 → 词语"。

## 7. 验证状态

Android 构建记录（2026-11-12，日志都在 `.build/SafeValidationLogs/`）：

- `20261112-094731-_u05576o`：Gradle 配置阶段被安全中止。另一个 Claude 会话的 shell 进程被认成了外部 Gradle 任务。
- `20261112-100331-x0a4l1br`：排队约 54 分钟等降频解除；开始编译约 3 分钟后再次遇到降频，安全中止。`:shared:compileAndroidMain` 已通过。
- `20261112-142603-42fmmn78`：**构建失败**（3 分 51 秒），失败在 `:androidApp:checkDebugAarMetadata`，原因是 compileSdk 36 低于依赖要求的 37，已按第 2 节修正。在此之前，`:shared:testAndroidHostTest` 的 14 组共 81 项测试全部通过（含 `ParityFixturesTest`），`:androidApp:compileDebugKotlin` 也已通过（只有已知的弃用警告）。
- `20261112-144232-8cwf8240`：改为 compileSdk 37 后的第一次，开始约 2 分半后遇到降频，安全中止。
- `20261112-144639-u4dnvxww`：**通过**，`BUILD SUCCESSFUL in 19m 27s`。用户同意只对这一次放行降频检查（`JERREADER_SESSION_ALLOW_THERMAL_THROTTLING=1`），负载、外部构建和模拟器检查照常；另加了一个守护：WindowServer 连续 15 秒超过 80% CPU 就中止。全程 WindowServer 最高 55%，1 分钟负载最高 9.72，`CPU_Speed_Limit` 最低 76，守护没有触发。
  - `:shared:testAndroidHostTest`：14 组 81 项，0 失败（含 `ParityFixturesTest`）。
  - `:androidApp:testDebugUnitTest`：21 组 82 项，0 失败（含 `PdfLegacyPositionIndexTest`、`ReadingSessionRecorderTest`）。
  - `lintDebug` / `lintRelease`：各 0 错误、4 警告（1.4.1 为 28 项）。剩下的 4 项：`LibraryViewModel.kt` 的 EmptySuperCall；OldTargetApi（targetSdk 有意停在 36）；androidx.core 有 1.19.1；`ReaderActivity.kt` 中新加的 `recordReaderLookup` 用了平台 `Log.w`（LogNotTimber，与文件里其他日志相同，只是没加 `@SuppressLint`）。这 4 项都不影响行为，改动留到下一个版本，以免让已构建的包和源码对不上。
  - Release APK：`arm64-v8a` 42,013,847 字节（1.4.1 通用包 144,092,470 字节，约 −71%）；`x86_64` 43,237,399 字节，只供模拟器。badging：versionCode 67、versionName 1.5.0、compileSdkVersion 37、minSdk 24、targetSdk 36、native-code 只有 arm64-v8a。
  - 签名证书 SHA-256 与 1.4.1 Release 包一致，可以覆盖安装。
- `20261112-151600-2rsg4tdi`（设备测试）：**没有开始**。启动命令漏了 `ANDROID_HOME`，`android_device_validation.py` 在检查 `platform-tools/adb` 时就退出了，模拟器没有启动，也没装任何包。
- `20261112-152402-uzflko27`（设备测试）：**通过**，约 11 分钟。模拟器 `JerreaderQA_API35_20261007`（API 35，x86_64，2 核 2 GB，swiftshader），范围 `full`，没有放行降频，负载最高 12.3，WindowServer 最高 24%，`CPU_Speed_Limit` 一直是 100。
  - 仪器测试 25 项全过：回归 20 项（含 `PdfLocatorFormatTest` 3 项、`ReadiumPublicationIntegrityTest`、`ReaderTapTranslationTest`、`BackupInheritanceTest`、`DocumentImportTest`）和界面 5 项（含千条学习记录检索与滚动）。没有 ANR，logcat 里没有崩溃。
  - Release 冒烟：x86_64 Release 包（SHA-256 与构建产物一致）安装启动成功，打开 EPUB 和 PDF 都进了 `ReaderActivity` 并回到书架，学习、设置、书架三个标签都能打开。R8 保留规则在这一层没有漏掉 Readium 需要的类。
  - 没覆盖：短按查整词（`ReaderTapTranslationTest` 测的是轻点翻译句子/段落，不是查词）、ruby、竖排、真机。
- Android 正式包：`scripts/package_release_apk.py` 通过，`dist/Android/Jerreader-Android-1.5.0-build67-20261112-153559-release.apk`，42,013,847 字节，SHA-256 `8586ecd8bd6447a2052ed00470fb880e0df3e259583667d3005f94a4b4f8015a`；与模拟器验过的 x86_64 包除原生库外完全相同，签名与 1.4.1 一致，10,556 个类无重复，不含调试夹具。已复制到桌面。

iOS 记录（2026-11-12）：

- `doctor`：通过（Xcode 16.2，`DEVELOPER_DIR` 指向本机完整 Xcode）。
- `20261112-160525-l9l47xax`（host-test）：**失败**。translation-watchdog、learning-features、offline-dictionary、japanese-chinese-lexicon、model-catalog 五组编译并通过；`parity-fixtures-compile` 失败：`ReadingCompanion.swift` 里困惑地图选主类型的 `map → filter → max` 元组链式表达式超出 Swift 类型检查时限（"unable to type-check this expression in reasonable time"），也就是上面预计的 Swift 6 风险。已改为等价的循环（排序仍是 数量×10−枚举顺序，并列取先列出的，与 Kotlin `maxByOrNull` 一致），尚未重新编译。同时排查了本次新增 Swift 代码里的其他长链式表达式，没有发现同类写法。
- `20261112-164445-2sdbv1zw`（host-test，修正后）：**通过**，约 2 分钟，没有放行降频，负载最高 5.3，WindowServer 最高 41%。七组全过：translation-watchdog、learning-features、offline-dictionary、japanese-chinese-lexicon、model-catalog，以及 `parity-fixtures`：复习 8、原句练习 11、备份清理 10、阅读伴读 46，共 75 个用例与 `shared/parity/*.json` 一致（与 Android `ParityFixturesTest` 用的是同一批文件）。编译没有警告。
- `20261112-172216-czmsucps`（build，Debug，generic iOS device，不签名，单 job）：**通过**（`** BUILD SUCCEEDED **`），约 10 分钟，没有放行降频，负载最高 6.1，WindowServer 最高 29%。没有编译警告。新拆出的 `ReaderAppearanceModels` / `ReaderNavigationModels` / `ReaderSelectionModels` / `PDFTextSelection` / `ReaderSentenceSegmentation`（含 `ReaderWordSegmenter`）/ `EPUBReaderScripts`，以及 `ReaderSearchModel`、`ReaderCompanionModel`、`ReadingLens`、`ReadingCompanion` 都在这次编译里。
- `20261112-222502-ve3mr0ih`（test，模拟器 `YomiReader QA iOS 18.2`）：**中止，没有执行任何测试**。`build-for-testing` 通过（`** TEST BUILD SUCCEEDED **`，无警告，测试包含新增的 `testShortTapWordSegmentation*`），模拟器开机通过；等待系统应用就绪（`bootstatus` 第 28 秒）时，1 分钟负载在 26 秒内从 2.7 升到 129，会话外加的守护按"负载超过 100 就停"发了 SIGTERM，安全脚本只关掉了这台模拟器。WindowServer 全程不超过 34%，没有降频、没有显示异常。注意：仓库规则本身规定运行中的任务不因负载单独超标而终止，这次是会话守护更严。
- `20261112-233126-s0v0o_a0`（test，用户同意这一次去掉会话守护的负载上限）：**安全中止，没有执行任何测试**。`build-for-testing` 再次通过，模拟器开机和 `bootstatus` 都通过；进入 `before-test-execution` 冷却后，1 分钟负载在 35 秒内从 26 升到 248，安全脚本的健康检查调用 `/bin/ps -axo …` 超过 8 秒超时，按"监测不可用就停"的规则中止，并只关掉了这台模拟器。WindowServer 最高 37%，没有降频，空闲内存 69%。会话守护自己的采样在 23:33:15 到 23:34:20 之间也停顿了约 65 秒。
- 2026-11-13 用户要求把安全脚本监测命令的等待上限由 8 秒放宽到 30 秒（`MONITOR_COMMAND_TIMEOUT`），阈值与"监测不可用就停"不变；改后 `scripts/test_safe_ios_validation.py` 67 项离线回归通过。
- `20261113-005335-prmh3z5m`（test，设置同上一次，只多了这项放宽）：**通过**。XCTest 262 项：259 通过、3 跳过、0 失败，执行 69.7 秒。`build-for-testing` 15 秒（增量）。模拟器开机后负载在 00:55:10 达到 204，冷却等到 00:58:30 才连续 30 秒不超过 20（5 分钟上限内用了约 3 分 20 秒）。健康采样最长一次隔 24.8 秒（负载 129 时），按原来的 8 秒上限这次同样会中止。WindowServer 最高 36%，没有降频，结束后只关掉了这台模拟器。跳过的 3 项都要外部真实书籍：`testExternalEPUBSwitchesBetweenVerticalAndHorizontal`（需 `JERREADER_LAYOUT_EPUB_PATH`）、`testExternalRubyEPUBRecoversBaseSelectionInHorizontalAndVerticalModes`（需 `JERREADER_RUBY_EPUB_RESOURCE`）、`testExternalRubyPublicationFixtureWhenProvided`（需 `JERREADER_RUBY_FIXTURE_BASE64`）；内置样书的竖排切换测试 `testVerticalJapaneseEPUBSwitchesToHorizontalAndBackWithReadingDirection` 通过（50 秒，本次最慢）。
- `20261113-012630-xq11d85i`（ui-test，同一台模拟器；用户同意这一次也去掉会话守护的负载上限）：**通过，但只覆盖不需要真实书籍的部分**。20 项：9 通过、11 跳过、0 失败，执行 388 秒。模拟器开机后负载最高 158（01:27:54），01:30:19 回到 20 以下；健康采样最长一次隔 25.7 秒（负载 108 时），在 30 秒上限内。WindowServer 最高 38%，没有降频，结束后只关掉了这台模拟器。
  - 通过的 9 项：学习页（空状态、日语学习流程、大字号深色）、离线资源只有一个设置入口、设置二级页、备份中心（计划范围、上限、恢复）、空书架提供从备份恢复、主标签页全屏外壳、独立翻译页的方向/模式/服务商控件。也就是 `@Observable` 改造后，书架、学习、设置、翻译这些界面能正常打开和刷新。
  - 跳过的 11 项：1 项是 iPad 专用（`testIPadShellUsesWholePortraitAndLandscapeWindow`，要 iPad 模拟器）；其余 10 项都要先把一本真实日文注音 EPUB 以 `UITestRubyBook.epub` 放进 Debug App 包，并给 `.xctestrun` 加 `JERREADER_RUN_REAL_RUBY_UI_TESTS=1`（见 `DEVELOPMENT_MAINTENANCE_GUIDE.md` 8.3）。真实书籍不进仓库，`safe_xcodebuild.sh` 目前也没有注入这一步，所以这次没有跑。这 10 项正是阅读器里的真实书籍交互：轻点翻译浮窗避开原文、物理长按高亮汉字（横排、原版竖排、原生回调缺失降级）、原版竖排与横排往返、阅读器自定义与书籍管理控件、翻译页不露出临时语音控件、长段落翻译浮窗。
  - 1.5.0 新增的 iOS 短按查整词没有专门的 UI 测试；它只在 XCTest 层验证了分词（`testShortTapWordSegmentation*`）。

下列阶段尚未运行，逐阶段征得用户同意后串行执行，每阶段只跑一次：

1. iOS：Release IPA（`JERREADER_CONFIGURATION=Release scripts/safe_xcodebuild.sh build`，再 `scripts/package_unsigned_ipa.py`）。

iOS 编译期的风险已经排除（唯一的问题是上面那处类型检查超时，已修正），模拟器 XCTest 全部通过（含短按分词），不需要真实书籍的 UI 测试也通过。还没验证的是阅读器里的真实书籍交互：短按查词的实际命中、长按高亮、翻译浮窗位置、竖横排切换，以及拆分后的阅读器状态。这些要靠注入真实注音 EPUB 的 UI 测试或真机验收。真实 API Key 未参与任何验证。
