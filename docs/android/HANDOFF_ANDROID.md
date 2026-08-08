# Jerreader Android 交接技术手册

版本：1.0.2（versionCode 43，与 iOS 内部构建号对齐）
最后更新：2026-08-02（含 UI 重构与点按翻译对齐）

本文面向接手这份 Android 工程的开发者，覆盖工程结构、构建、验收、与 iOS 的对应关系，以及已知边界。

---

## 1. 一句话现状

Android 版与 iOS 1.0.2 在**除备份中心以外**的全部功能上对齐：EPUB/PDF/DOCX/TXT 导入、Readium 阅读、点按翻译、划线批注、目录/搜索/书签、查词与生词本、学习导出、完整设置与操作指南。视觉体系与 iOS 同源。当前交付物是可直接侧载的 debug 签名 APK，不是商店发布包。

---

## 2. 工程结构

仓库是 iOS 与 Android 共存的单仓库：

```
电子书翻译阅读/
  Jerreader.xcodeproj      iOS 工程（本轮完全未改动）
  Jerreader/               iOS 源码
  JerreaderTests/          iOS 测试
  shared/                  Kotlin Multiplatform 共享层
  androidApp/              Android 应用
  docs/android/            Android 文档（本文所在目录）
  scripts/                 打包脚本
  dist/iOS/                iOS 安装包、源码包与交付文档
  dist/Android/            Android APK 与校验文件
```

### shared（共享层，commonMain）

| 包 | 职责 |
| --- | --- |
| `domain` | `BookFormat`、`LanguageCode` 等跨平台枚举 |
| `library` | 书架模型、仓储协议、阅读外观 `ReaderAppearance`、标记色 `ReaderAnnotationColor` |
| `translation` | 翻译协议与模型、输入策略、跨页句段扩展、Mock |
| `lexical` | 查词协议、词形分析、内置基础词典、Mock |
| `ui` | Compose Multiplatform 界面：书架、阅读器组件、译文卡、词卡、操作指南、`JerreaderTheme` |

`shared/src/iosMain` 目前只有一个占位 `MainViewController`，**尚未接入 iOS 工程**。iOS 端仍是纯 Swift，两端目前是「同一套设计与协议、各自实现」，不是「共享二进制」。

### androidApp

| 包 | 职责 |
| --- | --- |
| `library` | SAF 导入、不可变出版物存储、DOCX/TXT→EPUB 转换、完整性校验 |
| `data` | Room 实体与 DAO：书籍、阅读记录（书签/批注）、学习记录、翻译缓存 |
| `reader` | `ReaderActivity`、Readium 环境、点按翻译、点按查词、PDF 翻译、偏好映射 |
| `translation` | 翻译服务组合、直连 AI/代理适配、本机 ML Kit、加密凭据存储、缓存 |
| `lexical` | 中文维基词典适配、内置词典优先的生产组合 |
| `learning` | 生词本与译文收藏仓储 |
| `settings` | 应用级偏好 |
| `speech` | TTS，**与 iOS 一致保持关闭**，未开放入口 |
| `ui` | 底部导航、学习页、设置页 |

依赖注入是手写的 `AppGraph`（`JerreaderApplication.kt`），没有 DI 框架。

---

## 3. 构建环境

- JDK 17：仓库内自带，`JAVA_HOME=$PWD/.tools/jdk17/Contents/Home`（注意必须带 `Contents/Home`）。
- Android SDK：通过 `ANDROID_SDK_ROOT` 或本机 `local.properties` 配置；需要 Platform 36 与 Build-Tools 36.0.0。
- 首次构建需要能访问 Google Maven、Maven Central 和 JitPack（JitPack 只用于 Readium 的 PDFium 原生依赖）。
- 运行时**不依赖 Google Play Services**，可在无 GMS 的国内机型上运行；ML Kit 使用 bundled 模型形式，首次翻译需联网下载语言模型。

### 常用命令

```bash
JAVA_HOME=$PWD/.tools/jdk17/Contents/Home ./gradlew :androidApp:assembleDebug
```

```bash
JAVA_HOME=$PWD/.tools/jdk17/Contents/Home ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug
```

```bash
JAVA_HOME=$PWD/.tools/jdk17/Contents/Home ./gradlew :androidApp:connectedDebugAndroidTest
```

一键打包（跑测试 + lint + 出 APK 与校验和到 `dist/Android/`）：

```bash
scripts/package_android_parity.sh
```

### 三个必须知道的构建坑

1. **iCloud 重复文件**。工程位于 iCloud 同步的 `Documents` 下，文件供应器偶尔会在 `build/` 里生成 `xxx 2.dex` 这类副本，导致 `mergeProjectDexDebug` 报「Type … is defined multiple times」。解决：`find androidApp/build shared/build -name "* [0-9].*" -delete`，打包脚本已内置这一步。
2. **`--no-daemon` 首次构建假失败**。Kotlin 2.3 的 Build Tools API 在 `--no-daemon` 下偶发报「Compilation error」却不打印任何 `e:` 行，重跑即过。**用 Gradle 守护进程构建**（不加 `--no-daemon`）可规避。
3. **陈旧 R.jar / lint 模型**。偶见 `compile_and_runtime_r_class_jar/.../R.jar` 或 `shared/build/intermediates/unit_test_lint_model` 缺失导致任务失败，删掉对应目录重跑即可。

---

## 4. 设备验收

设备测试是 Android 侧唯一能真正验证 Readium 行为的手段，**改动阅读器后必须跑**。

已有 AVD：`JerreaderM0_API36`（API 36 x86_64）。无头启动：

```bash
"$ANDROID_SDK_ROOT/emulator/emulator" -avd JerreaderM0_API36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
```

`connectedDebugAndroidTest` 当前 13 项，覆盖：

- 导入去重、Room 持久化、删除；**源文件与 App 内副本的 SHA-256 和修改时间在整个阅读会话前后不变**。
- 目录跳转、字号/主题、Locator 跨会话恢复。
- 英语/日语点按查词命中、基本形、中文释义。
- 点句/点段翻译，以及**以设备像素输入**走完整换算链路的点按翻译。
- DOCX/TXT 导入转换、翻译缓存。

真实词典与翻译服务在测试中一律使用注入的 Stub / Mock，不访问外网。

---

## 5. 与 iOS 的关键对应关系

| iOS | Android |
| --- | --- |
| Readium Swift Toolkit `3d8bcdc` | Readium Kotlin Toolkit 3.3.0 |
| SwiftData | Room（`androidApp/schemas` 保存导出的 schema） |
| Apple Translation（免 Key 默认） | ML Kit 本机翻译（免 Key 默认） |
| Vision OCR | ML Kit Text Recognition（含日文识别器） |
| Keychain | Android Keystore 加密的 SharedPreferences |
| `UIDocumentPicker` 导入副本 | Storage Access Framework |
| `JerreaderTheme.swift` | `shared/ui/JerreaderTheme.kt`（accent RGB 与 canvas/paper 公式同源） |
| `ReadingAnnotationColor` | `shared/library/ReaderAnnotationColor.kt`（标识/名称/透明度同源） |
| `ReaderCrossPage*` | `shared/translation/ReaderCrossPageExpansion.kt` |
| `AppGuideView` | `shared/ui/AppGuideScreen.kt`（去掉备份一节） |

### 不变量：导入的书是只读输入

与 iOS 同一条铁律。导入后的副本以只读权限交给 Readium，阅读、选区、点按、翻译、退出都不得改写文件字节或修改时间；阅读进度只写 Room。任何改动导入、Readium 打开或阅读器关闭生命周期的代码，都必须保留并跑通「内容不变＋修改时间不变」的设备回归。

---

## 5.5 界面结构

- 视觉令牌全在 `shared/ui/JerreaderTheme.kt`：五种 accent、canvas/paper/mutedSurface 计算式、显式排版比例（20sp → 11sp）、22dp 卡片圆角。**不要用 Material 默认字号**，在手机上普遍偏大。
- 通用组件在 `shared/ui/JerreaderComponents.kt`：`JerreaderCard` / `JerreaderSection` / `JerreaderRow` / `JerreaderSegmented` / `JerreaderStepper`。新增设置项一律用 `JerreaderRow` + 分段控件或开关，不要再堆填充按钮。
- 阅读器：顶栏只有返回/书名/书签，控制集中在底部栏（进度 + 目录/翻章/翻页/Aa）。译文卡与词卡是阅读器内的悬浮层 `ReaderFloatingLayer`，按选区矩形放到空间更大的一侧，不是对话框。
- 主界面的书架/学习/设置/操作指南都是 Compose 状态，不是 Activity，**任何新增二级页面都必须同时加 `BackHandler`**，否则返回键会直接退到桌面。
- `ReaderAppearancePanel` 在设置页内要传 `embedded = true`，否则会出现嵌套滚动并被截断。

## 6. 三个曾经踩过、值得记住的缺陷

### 6.1 点按翻译的坐标单位

Readium 的 `TapEvent.point` 是 **Navigator 视图像素**，而注入脚本里的 `document.caretRangeFromPoint` 用的是 **CSS 像素**。可重排 EPUB 页面是 `width=device-width`，所以 1 CSS px = 1 dp，换算就是除以 `displayMetrics.density`（实测模拟器 `dpr = 2.75`、`innerWidth = 393`）。

不换算的后果是：坐标落到视口外，命不中任何文本节点，**点按后什么都不发生**，而且没有任何报错。

更危险的是，原有设备测试直接把 `getBoundingClientRect()` 得到的 CSS 坐标喂给内部方法，绕过了这一步，所以测试一直是绿的。现已新增 `translateAtDevicePointForTesting`，用设备像素走真实链路断言译文。**新增任何依赖坐标的 WebView 交互时，测试必须从设备像素入口进。**

### 6.2 Compose 修饰符顺序会吃掉可用高度

悬浮译文卡曾被压扁到只剩标题：`Modifier.heightIn(max = H).padding(top = X)` 的实际总高是 `X + H`，而父容器最大高度有限，于是内容被挤没。正确写法是先 `padding` 再 `heightIn`，并显式把可用高度算出来。

### 6.3 Compose 里读 `StateFlow.value`

阅读器工具栏曾直接读 `graph.appSettings.preferences.value`，导致在设置里改「显示阅读进度」或主题后阅读器不刷新。lint 的 `StateFlowValueCalledInComposition` 会抓到这类问题，所以**保持 lint 零 error**。

---

## 7. 已知边界与后续项

- **iOS 的 170ms/280ms 选区稳定去抖没有对应实现**：Android 长按走系统 ActionMode 菜单，没有连续选区回调，因此这条 iOS 行为在 Android 上不适用。点按翻译的其余保障（请求 token 防迟到、语义去重、30 秒看门狗、5 秒手动重试冷却、触感、加入生词本）都已对齐。
- **备份中心未实现**（用户明确要求本轮不做）。iOS 的 SAF 目录授权 + 版本归档 + 保留策略在 Android 侧没有对应实现，操作指南里也刻意没写这一节。
- **PDF 与 OCR 只在合成用例上验过**，没有做真实论文/扫描件的批量 QA。
- **竖排与 ruby 注音的真书选区矩阵未做设备 QA**。iOS 侧在这块有大量针对 WebKit 的特化处理（注音 `pointer-events: none`、基文节点恢复、竖排坐标补偿），Android 的 WebView 行为不同，遇到真实日文竖排书需要单独验收。
- **共享层尚未接入 iOS**。若将来要让 iOS 复用 `shared`，需要先评估 Compose Multiplatform 对现有 SwiftUI 界面的侵入程度，并在获得明确批准后再动 iOS 工程。
- **当前 APK 是 debug 签名**，约 148 MB（未拆分 ABI，且打包了 ML Kit 模型）。商店发布前需要：建立 Jerreader 专用签名库（签名库、密码、指纹不进 Git）、开启 ABI split 或改用 AAB、开启 R8 压缩。

---

## 8. 安全与隐私边界

- 工程、日志和安装包中不含任何 API Key。用户填写的 Key 按服务商分别保存在 Android Keystore 加密的存储中。
- 翻译只发送用户主动选中的文字与必要上下文，不上传书籍文件。
- 自动化测试不访问外网。
- 不做 DRM 绕过：受保护出版物直接拒绝打开。
