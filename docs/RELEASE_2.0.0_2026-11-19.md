# Jerreader 2.0.0（71）维护记录 · 2026-11-19

状态：**双端 2.0.0（71）已构建、验收并打包（2026-11-19）。** iOS：host-test、analyze、模拟器 XCTest 与 UI 测试通过，未签名 IPA 已打出；Android：Release 构建（单元测试、Lint）、完整设备测试与 Release 冒烟通过，正式 APK 已打出。两个包都已复制到桌面。验证中发现 Android 1.7.0 起的正式包打不开 PDF（当时的冒烟假通过），这一版已修好并补了检查，见第 5 节。没有真机验证，没有用真实账号或 API Key。

## 1. 版本与标识

- 双端版本 **2.0.0**，build / versionCode **71**。1.7.0 是 69，1.7.1（70）只打了 Android 包、没有单独发布；2.0.0 起两端重新同号。这次新增 AI 套餐，界面整体重做，按版本规则升主版本。
- 只改 iOS App 目标的 Debug / Release（`CURRENT_PROJECT_VERSION`、`MARKETING_VERSION`），测试目标仍为 1 / 1.0。Android 在 `androidApp/build.gradle.kts`。
- bundle ID（`com.raydon.Jerreader`）、applicationId（`com.jerreader.android`）和签名证书都没变。Android 沿用原来的签名，可以覆盖安装 1.7.x。

## 2. AI 套餐

「设置 → AI 大模型 → 添加供应商」的模式选择从「预设 / 自定义」变成「预设 / 套餐 / 自定义」。套餐页只放供应商条款允许第三方阅读应用使用的两种：

- **ChatGPT 套餐（实验）**：OpenAI 为开源应用提供的 Sign in with ChatGPT。在系统浏览器里登录 ChatGPT 后，翻译和语法解释走用户 Plus、Pro 等订阅的额度，不需要 API Key。额度用完就停下并提示，不会改成按量付费。手机上的回调方式 OpenAI 还没写进文档，所以标“实验”，界面上也说明登录不上时改用 API Key。
  - 登录后供应商列表里多一个「ChatGPT 套餐」，可以像别的供应商一样选模型（模型列表来自 OpenAI，只列这个账号能用的）；套餐页的「账号与模型」可以重新登录、退出登录、刷新模型，「管理用量」打开 ChatGPT 网站的用量设置。正在用它时，「学习 → 翻译」页显示“正在使用 ChatGPT 套餐 · 管理用量”。
  - 令牌只存本机（iOS 钥匙串、安卓系统密钥库加密），不进备份，不写日志。安卓的 HTTP 客户端不跟随重定向；吊销地址只接受 `https` 且主机是 `openai.com` 或其子域。
- **Claude 套餐额度**：Claude Max / Ultra 每月附带的 API 额度（Max 每月 $100、Ultra 每月 $200，每个账单周期刷新、不累积，只能用于 API）挂在 Console 组织上，用法和普通 API Key 相同。所以不新增供应商，只分三步说明怎样领额度、建 Key，再用现有的 Claude 供应商。
- 页脚说明：Coding Plan、Token Plan 这类编程套餐的条款只允许在编程工具里使用，阅读翻译不在其列，所以不提供。
- 操作指南（设置 → 操作指南）加了「AI 套餐」一节。
- 协议细节（授权与令牌地址、PKCE、回环回调、刷新、事件流、错误码）见 [架构说明](ARCHITECTURE.md) 的「2.0.0 AI 套餐与「纸墨」界面」一节。
- 代码：iOS `Jerreader/Data/Services/ChatGPTPlan.swift`、`ChatGPTPlanLoopback.swift`，`Jerreader/App/ChatGPTPlanSignIn.swift`、`AIPlanViews.swift`；安卓 `androidApp/.../translation/chatgptplan/`、`ui/AIPlanScreens.kt`。

## 3. 界面「纸墨」

静态视觉和动效一起重做，两端按同一份 [设计规范](DESIGN_2.0.md) 逐页实现。规范先定颜色、字体、间距圆角阴影和动效令牌，再写公共组件和每页的布局；参考的 GitHub 设计 skill 列在规范末尾（heyimjames/ios-design-skills、emilkowalski/skills、chrisbanes/skills、android/skills、aldefy/compose-skill、anthropics/skills）。

- **令牌**：暖白纸面（深色为暖黑）、墨色文字；强调色只用在主要操作、选中和进度上，另有金、朱、青三种辅助色。层次分底色 → 纸卡 → 浮起三级，浅色模式用染色阴影，深色改用描边。
- **动效**：按压、状态切换、选择、首次出现、主卡共用一套弹簧参数，两端数值相同，都可中断；系统「减弱动态效果」（Android 为「移除动画」）打开时一律换成 0.18 秒交叉淡化。数字变化用滚动，图标确认用一次弹跳。
- **公共组件**：两端同名（iOS `Jerreader/App/JerreaderDesignSystem.swift`，安卓 `shared/.../ui/JerreaderDesignSystem.kt`）：图标块、统计数字、进度条、进度环、滑动胶囊分段、主按钮、纸卡、主卡背景、封面修饰、无封面书的生成封面等。
- **书架**：日期副标题；「继续阅读」主卡（封面、书名、作者、进度、“继续”按钮）；阅读统计卡（累计阅读、已开始、平均进度，数字滚动）；三列网格，每本是带书脊暗影的封面、封面下的进度条、书名、作者和格式标签，读完的书右上角有对勾；网格第一次出现时依次浮现；空书架有插图式图标、主按钮和“从备份恢复”。打开书：iOS 从封面放大进入阅读器、关闭时缩回封面；安卓从点下的封面处放大打开阅读器（单独的 Activity，关闭时是系统默认动画）。底部标签栏两端同为纸面底色加顶部发丝线、选中项强调色。
- **阅读器**：标准布局的上下栏保持 1.7.0 的高度、常驻（用户 11-17 要求“上下栏变小、常驻”）；顶栏是圆形图标按钮和“AI”胶囊，“格式”换成“Aa”；底栏是自绘进度条（拖动时章节名换成目标位置）和居中的 40pt 圆形翻译按钮；上下栏显隐带位移淡入；加书签时图标弹跳并有触感。阅读设置面板改成分组卡片，主题是一行圆形色板，字号数值滚动，分段控件统一成滑动胶囊；译文卡片和 AI 伴读重新排版，译文卡顶部的眉题写明语言方向。
- **学习**：四个分区用滑动胶囊切换；翻译工作台是语言条、输入、结果三张纸卡，交换语言时按钮旋转（仍刚好一屏）；「练习与工具」顶部是今日练习主卡（环形进度、收藏数、“开始复习”）；生词本词条卡、查询历史按天分组带类型圆点、复习卡（进度条、答案揭示、分色评分按钮、完成对勾）、原句练习、语法知识库、阅读成长报告（柱状图生长）都重新设计。
- **设置**：每类设置配彩色图标块，分组标题、行高和 AI 供应商图标统一；设置首页仍刚好一屏；操作指南和备份中心一并换新。
- 新增文件：iOS `JerreaderDesignSystem.swift`、`LibraryShelfViews.swift`、`ReaderChromeViews.swift`、`LearningDesignViews.swift`（已登记进 `project.pbxproj`）；安卓 `shared/.../ui/JerreaderDesignSystem.kt`。安卓其余页面在原文件里重写（书架 `LibraryComponents.kt`、阅读器 `ReaderChrome.kt` / `ReaderSettingsSheet.kt` / `ReaderInterfaceControls.kt`、学习各页、设置各页），底部标签栏换成共享的 `JerreaderTabBar`。
- 套餐相关的新增文件：iOS 见第 2 节；安卓 `androidApp/.../translation/chatgptplan/`（登录流程、回环回调、登录回跳页、令牌会话、协议、翻译，共 7 个文件）、`ui/AIPlanScreens.kt`，单元测试 `ChatGPTPlan{Protocol,Session,Settings,Completion}Test.kt`。

## 4. 议题 #2

1.7.1 只打了 Android 包、没有单独发布的两个修复（滚动阅读横滑不再切换章节；选区占满页面时译文浮窗保留最小高度并留在上下栏之间）随 2.0.0 一起发布，阅读器重做时保留了原有行为。iOS 本来就没有这两个问题。细节见 [1.7.1 维护记录](RELEASE_1.7.1_2026-11-18.md)。

## 5. 验证状态

日志都在 `.build/SafeValidationLogs/`（analyze 和打 IPA 在后台入口的源码副本里，日志在副本的同名目录）。

iOS（XCTest 用模拟器 YomiReader QA，UI 测试用 iPhone 16 Pro Max，都是 iOS 18.2）：

- `20261119-042430-hbz0ezep`（build）：**通过**。只有一条新警告（`ChatGPTPlanSignIn` 里临时对象的弱引用），随后改掉，下面的 analyze 已没有它。
- `20261119-042923-lfrztz64`（test）：**通过**，XCTest 286 通过、3 跳过、0 失败（跳过的 3 项照旧要外部真实书籍）。新增的 `ChatGPTPlanTranslationTests` 和 `BackendTranslationServiceTests` 里的套餐错误分类都在其中。
- `20261119-044122-cwlf473x`（ui-test，第一次）：误用了 402 宽的模拟器，按 440×956 设计的两项「一屏」测试失败。改用 iPhone 16 Pro Max 重跑。
- `20261119-050421-5tp5burs`（ui-test，iPhone 16 Pro Max）：16 通过、11 跳过、1 失败。跳过的是要真实注音 EPUB 的 10 项和 iPad 专用的 1 项，同 1.7.0。失败的是 `testAIModelPagesListClaudeChatGPTAndDeepSeekFirst`：套餐卡片自己的无障碍标识盖住了里面按钮的标识，测试找不到“Continue with ChatGPT”。给两张卡片加上 `.accessibilityElement(children: .contain)` 后单跑：
- `20261119-052144-fm361tx6`（ui-test，只跑上面这一项）：**通过**。套餐页和 Claude 额度说明两张截图核对过。
- 整套 UI 测试里，新加的 `testReaderOpenedFromShelfIgnoresDismissGestures`（从书架放大进入阅读器后，下滑、长拖、捏合都不会关掉阅读器）通过；「一屏」各项（设置首页、「学习 → 翻译」，含 852 / 874 / 932 点高的手机和深色模式）通过。
- 以上几次之后只改了两处：套餐卡片的无障碍容器（单跑那一项验证）；ChatGPT 套餐几条错误提示里的菜单路径文字（“设置 → AI 大模型”，下面的 analyze 和 IPA 编过）。
- `20261119-083234-5r2lpd48`（analyze，后台入口）：**通过**，没有源码警告，只有原有的 scheme 构建顺序警告。
- `20261119-083949-1n0hvgli`（host-test，后台入口）：**通过**，16 项全过（比 1.7.0 多出新组 `chatgpt-plan` 的编译和运行两项；这一组的回环监听在本机真实运行，不出本机），含 `ai-providers` 和 `parity-fixtures`。
- `20261119-084151-snaj072g`（`scripts/background_build.py submit ipa`，Release，generic iOS device，不签名，在 launchd 后台域跑）：**通过**（`** BUILD SUCCEEDED **`），约 7 分钟。没有源码警告；25 条 `.pcm: No such file or directory` 是 dsymutil 找模块缓存时的警告，另有 1 条原有的 scheme 构建顺序警告，同 1.7.0。
- iOS 正式包：`dist/iOS/Jerreader-2.0.0-build71-20261119-084847-unsigned.ipa`，14,992,270 字节（1.7.0 是 14,355,188），SHA-256 `658602cba96946069e15334ec763c418454ad38e73f749d913bcd6d2b250b335`。bundle ID `com.raydon.Jerreader`、CFBundleShortVersionString 2.0.0、CFBundleVersion 71、最低 iOS 18.0、只有 arm64、不含测试夹具。

Android（构建都是 `JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh`）：

- `20261119-112840-siva_uht`（构建）：shared 主机测试 88 项通过，androidApp 编译失败，只有一处：`AIPlanScreens.kt` 给 Material3 `Text` 传了它没有的 `fontFeatureSettings` 参数，改成 `style = TextStyle(fontFeatureSettings = "tnum")`。
- `20261119-113115-l6xage9v`（构建）：**通过**，15 分 30 秒。shared 主机测试 88 项（Gradle 判为 UP-TO-DATE，沿用上一次同样输入的通过结果）、androidApp 单元测试 138 项全部通过（1.7.1 是 95 项，多出的 43 项是 ChatGPT 套餐的协议、令牌会话、设置和翻译四组）；lintDebug、lintRelease 没有错误；debug、androidTest 和 release 包都打出来了。编译器只有警告，其中 3 条是这次新代码带来的小问题（多余的类型转换、多余的 `?.`、多余的 `Unit`），不影响行为，留到下一次构建一并清掉。
- `20261119-114712-vj4oibps`（设备测试，full 范围 + Release 冒烟，模拟器 API 35）：脚本报**通过**，仪器测试 50 项全过（回归 38 项 + 学习界面 12 项）。但两处问题是看截图才发现的，这一轮的包因此作废：
  1. **Release 包打不开 PDF。** 冒烟截图 `android-release-reader-pdf.png` 是“无法打开这本书”，logcat 里 ML Kit 的 4 个组件注册类（通用、视觉通用、文字识别、本机翻译）都因找不到无参构造函数而创建失败。原因是 1.7.0 开启 R8 以后，R8 full mode 删掉了这些类的无参构造（ML Kit 按类名反射创建它们，firebase-components 16.1.0 自带的规则只保留类、不保留构造函数）。翻出 1.7.0 和 1.7.1 的冒烟截图，同样是这个报错，所以**这两个版本的安卓正式包都打不开 PDF，ML Kit 本机翻译也用不了**；冒烟脚本当时只检查进没进 ReaderActivity，于是报了通过。修复：`androidApp/proguard-rules.pro` 加 `-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }`。冒烟脚本 `scripts/android_device_validation.py` 同时补了两项检查：每本书打开后抓界面，出现“无法打开这本书”即失败；最后读 Release 进程自己的日志，有 `InvalidRegistrarException` 即失败。抓界面前先删掉上一次的文件，失败的抓取不会拿旧界面充数。`scripts/test_safe_ios_validation.py` 加了对应用例，75 项通过。
  2. **深色模式下主文字是黑字黑底。** 双端截图对照时，安卓 large-dark 几张里标题、词条、卡片正文几乎看不见（iOS 正常）。原因是 2.0 的页面在透明的 `Scaffold` 上自己画底色，透明容器的内容色会回落到 `LocalContentColor`，而它没人设置时是黑色。这是 2.0 开发中引入的，1.7.x 没有。修复：`JerreaderTheme` 根部提供 `LocalContentColor = onBackground`（深色为白、浅色为黑，浅色模式外观不变）。
- 同一轮逐页对照（书架、导入、书籍管理、学习各页及空状态、large-dark、设置各页、AI 供应商与套餐、阅读设置面板、阅读器三种布局，共 53 对）里，另外对齐了三处小差异：安卓离线资源页「中日句段翻译」卡改成和下面「英语扩展词典」卡一样的图标块、状态标签、实心“下载”和浅色“卸载”；生词复习卡的“当前待复习”和 iOS 一样居中；“Continue with ChatGPT”按钮的图标改成和 iOS 相同的人像加对勾。上面 3 条编译警告也一起清了。其余页面两端一致；文案上的差异都是平台不同（iCloud / AirDrop 对应安卓的云盘 / 分享，Apple 翻译对应 Android 本机翻译）。
- `20261119-120959-v_9j2bco`（构建）：失败在 `lintAnalyzeRelease`，lint 分析 `AISettingsComponents.kt` 时内部崩溃（“FirDeclaration was not found for class KtFunctionLiteral”）。这是我在构建进行中改了这个文件（加上面的人像图标）造成的：lint 解析的文件和读到的文件对不上。之后改成源码全部改完再开构建，构建期间不动源码；新图标里的裁剪也改成路径求交，不再用 lambda。
- `20261119-121530-6tyolfsh`（构建，含上面全部修复）：**通过**，11 分 9 秒。shared 主机测试 88 项、androidApp 单元测试 138 项全部通过；lintDebug、lintRelease 没有错误；debug、androidTest 和 release 包都打出来了（androidTest 源码没变，Gradle 沿用 11:37 打的测试包）。编译警告：这次新代码带来的其实是 4 条，清掉了 3 条。剩下的是 `shared/.../ui/ReaderChrome.kt:124` 的“多余的类型转换”：shared 模块在 `112840` 那次就编译过，`113115` 判为最新、没有重编，所以上面只数到 3 条。它后面的回落值永远用不到，不影响行为，下次改这个文件时清掉。
- `20261119-122722-iac869fn`（设备测试，full 范围 + Release 冒烟，模拟器 API 35）：**通过**。仪器测试 50 项全过（回归 38 项 + 学习界面 12 项）。Release 冒烟用的是新检查：EPUB 和 PDF 都真的打开了（`android-release-reader-pdf.png` 显示 PDF 正文“Jerreader release smoke PDF”，两本书的界面里都没有“无法打开这本书”）；Release 进程日志里没有 `InvalidRegistrarException`，ML Kit 的拉丁文和日文文字识别模块都已加载；书架、学习、设置三个标签都能打开。上一轮发现和对齐的几处逐张看过截图：large-dark 七张的标题和正文都是浅色字；离线资源页两张卡样式一致；“Continue with ChatGPT”按钮是人像加对勾的图标；生词复习的“当前待复习”居中；浅色模式外观没变。
- 打包：`scripts/package_release_apk.py` 用上面这次构建和设备测试，`--previous-apk` 用 1.7.1 的包。Android 正式包 `dist/Android/Jerreader-Android-2.0.0-build71-20261119-124529-release.apk`，42,350,706 字节（1.7.1 是 42,186,557），SHA-256 `44bcbf1b8f876b70ba23d7f46f378f88773b130a09aeaff2b500460ef1d20c66`。applicationId `com.jerreader.android`、versionName 2.0.0、versionCode 71、minSdk 24、targetSdk 36，只有 arm64-v8a；和模拟器上验过的 x86_64 包除原生库外逐项相同，签名证书和 1.7.1 相同，可以覆盖安装。打包脚本也加了一道关：冒烟记录里必须有新的 ML Kit 检查（`verify_release_smoke`），旧的冒烟日志不能再拿来打包；`scripts/test_safe_ios_validation.py` 加了对应用例，76 项通过。作废那一轮打的 `…-20261119-120444-release.apk` 已从 `dist/` 和桌面移进废纸篓。

## 6. 没验证的

- 两端都没有真机验证：iOS 只在 iOS 18.2 模拟器上测过，Android 只在 API 35 模拟器（x86_64）上测过。交给手机的 arm64-v8a 包和测过的 x86_64 包除原生库外逐项相同，但没有装到真机上。
- ChatGPT 套餐没有用真实 ChatGPT 账号登录过：授权、换令牌、刷新、撤销、额度用尽和模型列表都只用 Mock 测过（iOS 主机测试里的回环监听在本机真实运行，但不连 OpenAI）。手机上的回环回调 OpenAI 没写进文档，真实账号能不能登录要用户自己试，登录不上时改用 API Key。
- 其他 AI 供应商也都没连真实服务。「Claude 套餐额度」的金额和步骤照 Anthropic 帮助中心 11-19 的说明写，没有用真实的 Max / Ultra 账号走过。
- 动效（弹簧、依次浮现、从封面放大进入阅读器、数字滚动、柱状图生长）只核对了代码里的参数和两端是否一致。截图只能看到静止画面，没有录屏逐帧检查，也没有在真机上看过流畅度。
- Android 本机翻译：修好 ML Kit 后，Release 冒烟确认了组件都能创建、文字识别模块能加载、PDF 能打开；下载中日翻译模型要联网，测试不连外网，所以没有真的下载模型翻一句。
- iOS UI 测试里要真实注音 EPUB 的 10 项和 iPad 的 1 项照旧跳过；iPad 版已暂停，不验收。
- GitHub 上 v1.7.0 的发布说明写着 Release 包“能打开 EPUB 和 PDF”，那是当时冒烟的假通过（见第 5 节）。那份说明没有改，2.0.0 的发布说明里写明了这个问题已修复。
- 写这份记录时还没提交 git；后来已提交（`ea323c6`），11-19 推到 GitHub 并发了 [v2.0.0 Release](https://github.com/WZR0821/Jerreader/releases/tag/v2.0.0)（附双端安装包，下载回来核对过 SHA-256），在议题 #2 下回复后关闭为已完成。
