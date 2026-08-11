# 读鼠 Jerreader · 工程维护开发交接手册

适用版本：**1.5.1 (60)**　文档修订：2026-08-10
唯一需要维护的仓库：当前 `JerreaderUnified` 仓库

---

## 怎么用这份手册

这份手册是**自洽的**：接手的人只读这一份，也能把项目跑起来、改对地方、出包给用户。
同目录下的专题文档是**历史与细账**，需要追溯时再翻：

| 文档 | 回答什么 | 什么时候看 |
| --- | --- | --- |
| **HANDOVER.md**（本文） | 全局：产品、分层、数据、架构、构建、坑、排查 | 接手第一天，之后当参考手册 |
| [MAINTENANCE.md](MAINTENANCE.md) | 日常改代码的规矩（本文 §3 §12 §15 是它的浓缩） | 想确认某条规矩的原文 |
| [BUILD.md](BUILD.md) | 本机构建环境的三条环境变量与两个会骗人的绿灯 | 环境报错时 |
| [PARITY.md](PARITY.md) | 双端差异的历史核对结论（哪些已对齐、哪些故意保留） | 怀疑"两端不一样" |
| [SELECTION_PARITY_AUDIT.md](SELECTION_PARITY_AUDIT.md) | 选区高亮两端实现的逐条对照 | 改选区之前 |
| [UI_PARITY_REPORT.md](UI_PARITY_REPORT.md) | 界面细节的逐屏对照 | 改界面之前 |
| [MIGRATION.md](MIGRATION.md) | 从两个旧仓库合并到本仓库的历史 | 看到奇怪的历史遗留 |
| [CODE_REVIEW.md](CODE_REVIEW.md) | 历史代码评审留下的结论 | 想知道某处"为什么没改" |

### 接手路径

**第一天**：§1 产品 → §2 跑起来 → §3 分层规矩 → §4 仓库结构。
到这里你应该能跑通两端的测试，并且知道任意一个功能该去哪个文件找。

**第一周**：§5 数据与持久化 → §6 备份 → §7 阅读器架构 → §8 翻译架构。
这四节是"改错了会丢用户数据 / 会让阅读器不能用"的部分，动手之前一定要读。

**每次改动**：§15 提交前清单。
**用户报 bug**：§14 排查手册。

---

## 1. 产品是什么

读鼠是一个**双端的外语阅读器**：导入 EPUB / PDF / DOCX / TXT，在正文里选词选句，
就地出词典释义、翻译和上下文讲解，选中的词句可以收进生词本复习。
重点语种是日语（竖排、注音、假名），但不限于日语。

**分发方式是侧载，不上架。** 这一条决定了三件事：

1. **备份 / 恢复是一等功能**——用户换机、重装全靠它，不能"差不多能用"。
2. **签名不能换**（§12.2）——换了所有人必须卸载重装，本地数据全丢。
3. **没有崩溃上报、没有远程日志**——用户报 bug 时你只有一句话和一张截图，
   所以 §14 的排查方法很重要。

三块主要能力：

1. **书架**：导入、格式转换、文件夹与标签、阅读进度、封面。
2. **阅读器**：翻页 / 滚动、排版、主题与配色、选区高亮、朗读跟读高亮、
   点句翻译、译文浮层、书签与批注。
3. **学习（可选模块）**：查词历史、生词本、独立翻译工具、日语优先的每日复习与导出；
   可在设置中隐藏入口，隐藏不会删除任何学习数据。

外加**设置**：界面主题、阅读默认值、翻译服务配置、备份中心、操作指南、数据与隐私。

### 平台底线

| | iOS | Android |
| --- | --- | --- |
| 最低系统 | iOS 18.0 | minSdk 23（Android 6.0） |
| 目标 | — | targetSdk 36 / compileSdk 36 |
| 阅读引擎 | Readium swift-toolkit | Readium kotlin-toolkit 3.3.0 |
| 端上翻译 | 系统翻译 / AI | ML Kit Translate 17.0.3 |
| OCR | Vision | ML Kit Text Recognition 16.0.1（含日文包） |
| bundle id / applicationId | `com.example.Jerreader` | `com.jerreader.android` |

> ⚠️ iOS 的 bundle id 仍是 `com.example.Jerreader`。**不要"顺手改成正式的"**——
> 它决定了容器目录，改了等于换一个 App，用户所有本地数据消失。见 §12.3。

---

## 2. 三十分钟跑起来

```bash
cd /path/to/JerreaderUnified
export JAVA_HOME="<JDK 17 路径>/Contents/Home"
export ANDROID_HOME="<Android SDK 路径>"
export ANDROID_SDK_ROOT=$ANDROID_HOME
export DEVELOPER_DIR="<Xcode.app 路径>/Contents/Developer"
```

这几条**必须**设，原因：

- 如果 `xcode-select` 指向 CommandLineTools，不设 `DEVELOPER_DIR`，Kotlin/Native 链接会报
  `An error occurred during an xcrun execution`。
- Android SDK 可由 `ANDROID_HOME` 或不入库的 `local.properties` 提供。

> ⚠️ JDK 17 **不在**仓库里，也**不在**交付的工程压缩包里。
> 换机器或解压到别处时，把 `JAVA_HOME` 指到任意一份 JDK 17。
> **这是新机器上第一步就会卡住的地方。**

先跑公共算法测试，确认环境通了：

```bash
./gradlew :core:testAndroidHostTest --no-daemon
```

> **第一次跑 `--no-daemon` 常会假失败一次**，再跑一遍就好（Gradle 需要先落一次配置缓存）。

然后各跑一端：

```bash
./gradlew :ui:testAndroidHostTest :androidApp:testDebugUnitTest --no-daemon
```

```bash
xcodebuild -project iosApp/JerreaderUnified.xcodeproj -scheme JerreaderUnified \
  -destination 'platform=iOS Simulator,id=95225317-66F0-476C-843C-A2530F042FCD' \
  -configuration Debug test CODE_SIGNING_ALLOWED=NO ARCHS=x86_64 ONLY_ACTIVE_ARCH=NO
```

`ARCHS=x86_64 ONLY_ACTIVE_ARCH=NO` 不是可选项，见 §13「这台机器是 Intel Mac」。

装到模拟器上看一眼：

```bash
xcrun simctl install <udid> <路径>/Jerreader.app && xcrun simctl launch <udid> com.example.Jerreader
```

---

## 3. 代码分层：一句话规矩

> 改任何东西之前，先问：**这是"决定"还是"画法"？**

- **决定** → `core/`：纯 Kotlin，没有任何 UI 框架，两端共用，**必须有测试**。
  选区怎么合并、颜色怎么算、译文卡片避让到哪、用哪个翻译服务、文案写什么，都是决定。
- **画法** → `ui/`（Compose，Android 用）或 `iosApp/`（SwiftUI）。
  圆角多大、用哪个系统控件、动画曲线，都是画法。

历史上两端漂移的每一处，追根到底都是同一个"决定"被写了两遍。最典型的三个：

- 同一个主题色，一端叫「海洋」另一端叫「海蓝」——标题字符串在两边各写了一份。
  现在只在 `core/design/JerreaderCopy.kt` 里有一份。
- 选区高亮的默认背景色，在 `ReaderSelectionVisualStyle` 里是**第四份**主题色拷贝，
  已经和两个宿主都漂开了。1.3.4 改成读 `ReaderPageBackground`（§10.11）。
- iOS 的 `CFBundleGetInfoString` 里硬写着 `1.1.0`，从 1.1.0 起就没动过。
  1.3.4 改成 `$(MARKETING_VERSION)`（§10.13）。

### 硬约束：`core` 不许依赖 Compose

Compose Multiplatform 已经不再发布 `iosX64` 产物。带 Compose 的模块在
**Intel Mac 上连编都编不了**。把算法拆进 `core`，选区合并、调色、避让求解
才能在这台机器上跑 Kotlin/Native 测试，iOS 侧也才能直接从 SwiftUI 用上
`core`，不必先整体切成 Compose UI。

推论：任何用到 `androidx.compose.ui.unit.Dp`、`Color`、`@Composable` 的东西
都进不了 `core`。颜色在 `core` 里一律是 `Long`（`0xAARRGGBB`）或 `#RRGGBB` 字符串，
尺寸一律是 `Double`（逻辑像素 / dp，由宿主解释）。

---

## 4. 仓库结构

```
JerreaderUnified/
├── version.properties        ← 两端唯一版本号来源（§12）
├── keystore.properties       ← 安卓签名（§12）
├── settings.gradle.kts       ← :core :ui :androidApp
├── build.gradle.kts          ← 含 syncIosVersion 任务
├── gradle/libs.versions.toml ← 所有依赖版本
├── scripts/release.sh        ← 一条命令出 IPA + APK（§11.3）
├── docs/                     ← 本手册与专题文档
├── .tools/ → 仓库外的软链      ← JDK 17（§2）
│
├── core/       35 个 .kt。纯 Kotlin。targets: android, iosArm64,
│               iosSimulatorArm64, iosX64。导出成 JerreaderCore.framework
├── ui/         17 个 .kt。Compose Multiplatform。Android 的全部界面
├── androidApp/ 47 个 .kt。Activity、Room、Readium Kotlin、ML Kit、备份
└── iosApp/     62 个 .swift。SwiftUI + Readium swift-toolkit
```

工具链版本（`gradle/libs.versions.toml`）：
Kotlin 2.3.20 / AGP 9.0.1 / Compose MP 1.11.1 / Material3 1.9.0 /
Readium 3.3.0 / Room 2.8.4 / KSP 2.3.9 / coroutines 1.10.2。

### 4.1 `core` 里有什么

| 包 | 内容 |
| --- | --- |
| `design/` | `JerreaderAppPalette.kt` 全局主题每一档的表面色 + `JerreaderAppearanceMode` 明暗模式；`JerreaderCopy.kt` 两端共用文案与相对时间 |
| `domain/` | `BookFormat`（EPUB/PDF/DOCX/TXT）、`LanguageCode` |
| `library/` | 书库模型与仓储接口、`LibraryBackupPolicy.kt`（含 `LibraryBackupNaming`）、批注颜色、**`ReaderColorPreset.kt` 配色套系**、`ReaderColorMath.kt` HSV 换算、`ReaderPageBackground` 页面底色 |
| `lexical/` | 查词服务接口、词形还原 `WordMorphologyAnalyzer`、生词状态与最多五条语境、共用 JMdict TSV 索引 |
| `translation/` | 翻译服务接口、`TranslationProviderPolicy.kt` 服务商选择、跨页扩展、端上翻译计划、独立翻译 |
| `reader/geometry/` | `ReaderRect` / `ReaderSize` / `ReaderPoint` 等几何基元 |
| `reader/kernel/` | `ReaderSelectionController`、`ReaderWebViewBridge`：选区状态机与 JS 桥 |
| `reader/selection/` | 选区解码、调色 `ReaderSelectionPalette`、矩形合并、注入脚本 `ReaderSelectionScripts`、快照、朗读高亮几何 |
| `reader/overlay/` | 译文浮层的位置求解 `ReaderOverlayPlacement` 与排布策略 |
| `reader/paging/` | 竖排分页步进与列网格 |
| `reader/json/` | 桥接用的极简 JSON（`core` **没有** kotlinx.serialization，见 §13） |

### 4.2 两个宿主里有什么

**`androidApp/src/main/kotlin/com/jerreader/android/`**

| 目录 | 内容 |
| --- | --- |
| `MainActivity.kt` | 单 Activity 壳 + 依赖图的组装点 |
| `reader/` | `ReaderActivity`（阅读器宿主）、`ReaderTapTranslationController`、`PdfTapTranslationController`、`ReaderPreferencesMapper`、`reader/ui/` 高亮层与浮层宿主 |
| `data/` | Room：`JerreaderDatabase`（v7）、实体、DAO |
| `library/` | 导入、`ImmutablePublicationStore`、格式转换 |
| `translation/` | `ConfiguredTranslationService`、`AndroidTranslationSettingsStore`、`AndroidKeystoreCredentialStore`、ML Kit 适配 |
| `backup/` | `LibraryBackupService`、`BackupPolicyStore`、`BackupDirectoryStore` |
| `settings/` | `AndroidAppSettingsStore` |
| `learning/`、`lexical/`、`speech/` | 生词本、查词、朗读 |
| `ui/` | 实际界面在 `:ui` 模块，这里是 Android 特有的几个屏（`SettingsScreen` 等） |

**`iosApp/Jerreader/`**

| 目录 | 内容 |
| --- | --- |
| `App/` | `JerreaderApp`（入口）、`ContentView`、`JerreaderTheme`、`AppSettingsView`、`ReaderColorPresetPicker`、`LibraryBackupSettingsView`、`AppGuideView` |
| `Features/Reader/` | `EPUBReaderScreen`、`EPUBReaderHost`（UIViewController）、`EPUBReaderViewModel`、`EPUBSelectionBridge`、`PDFReaderHost`、`ReaderSettingsView`、`ReaderModels` |
| `Features/Library/` | `LibraryView`、书籍管理 |
| `Features/Learning/` | 生词本、查词历史、独立翻译 |
| `Data/Services/` | `EPUBImportService`、`LibraryBackupService`、`LibraryBackupPolicy`、`LibraryBackupRecords`、`TranslationSettingsStore`、词典与朗读服务 |
| `Resources/Info.plist` | 文档类型、导出 UTI、文件共享开关（§6.2） |

测试：`iosApp/JerreaderTests`（10 个文件，单元）、`iosApp/JerreaderUITests`（模拟器 UI）。

### 4.3 功能 → 文件对照表

| 功能 | 共用决定（core） | Android | iOS |
| --- | --- | --- | --- |
| 书架 / 导入 | `library/LibraryModels.kt`、`LibraryRepository.kt` | `library/LibraryImportService.kt`、`ui/LibraryComponents.kt` | `Features/Library/LibraryView.swift`、`Data/Services/EPUBImportService.swift` |
| 格式转换 | `domain/BookFormat.kt` | `library/DocumentToEpubConverter.kt` | `Data/Services/DocumentConversionService.swift` |
| 阅读器宿主 | `reader/kernel/*` | `reader/ReaderActivity.kt` | `Features/Reader/EPUBReaderHost.swift`、`PDFReaderHost.swift` |
| 选区高亮 | `reader/selection/*` | `reader/ui/ReaderSelectionHighlightLayer.kt` | `Features/Reader/EPUBSelectionBridge.swift` |
| 阅读设置 | `library/ReaderAppearance` | `ui/ReaderComponents.kt` | `Features/Reader/ReaderSettingsView.swift` |
| **配色套系** | `library/ReaderColorPreset.kt` | `ui/ReaderComponents.kt` 的 `ReaderColorPresetEditor` | `App/ReaderColorPresetPicker.swift` |
| 全局主题 / 明暗 | `design/JerreaderAppPalette.kt` | `ui/JerreaderTheme.kt` | `App/JerreaderTheme.swift` |
| 点句翻译 | `translation/*` | `reader/ReaderTapTranslationController.kt`、`PdfTapTranslationController.kt` | `Features/Reader/EPUBReaderViewModel.swift` |
| 译文浮层 | `reader/overlay/*` | `reader/ui/ReaderTranslationOverlayHost.kt` | `Features/Reader/EPUBReaderView.swift` |
| 翻译服务选择 | `translation/TranslationProviderPolicy.kt` | `translation/ConfiguredTranslationService.kt` | `Data/Services/TranslationSettingsStore.swift` |
| 查词 | `lexical/*` | `lexical/*` | `Core/Services/LexicalLookupService.swift` 等 |
| 生词本 / 学习 | `lexical/WordModels.kt` | `learning/`、`ui/LearningScreen.kt` | `Features/Learning/*` |
| 朗读 | `reader/selection/ReaderSpeechHighlightGeometry.kt` | `speech/AndroidSpeechService.kt` | `Data/Services/SystemSpeechService.swift` |
| 备份 / 恢复 | `library/LibraryBackupPolicy.kt` | `backup/LibraryBackupService.kt` | `Data/Services/LibraryBackupService.swift`、`App/LibraryBackupSettingsView.swift` |
| 操作指南 | `design/JerreaderCopy.kt` | `ui/AppGuideScreen.kt` | `App/AppGuideView.swift` |
| 设置 | — | `ui/SettingsScreen.kt` | `App/AppSettingsView.swift` |

---

## 5. 数据与持久化

> **这一节是"改错了会丢用户数据"的部分。** 侧载分发没有云端兜底，
> 一个键名改错，用户重装后书架就是空的。

### 5.1 iOS

**结构化数据：SwiftData**，schema `JerreaderSchemaV1`（1.0.0），
迁移计划 `JerreaderMigrationPlan`（目前 `stages` 为空）。六个 `@Model`：

| 模型 | 存什么 |
| --- | --- |
| `BookRecord` | 书的元数据、本地文件名、封面文件名、指纹、阅读进度与 locator、时长、分类标签、每本书的阅读主题 |
| `ReadingBookmarkRecord` | 书签 |
| `ReadingAnnotationRecord` | 批注（选中文字 + 颜色 + 备注） |
| `WordLookupRecord` | 查词历史 / 生词本 / 间隔复习状态与到期时间 |
| `TranslationFavoriteRecord` | 译文收藏 |
| `TranslationCacheRecord` | 译文缓存 |

> 加字段可以（SwiftData 会自动轻量迁移），**删字段或改类型必须写 migration stage**，
> 否则容器打不开——`JerreaderApp` 会退到 `DataStoreRecoveryView`，
> 它**刻意不自动重建数据库**，宁可让用户看到错误也不静默清空。

**书籍文件**：`Application Support/Jerreader/Books/`、`.../Covers/`
（`LibraryPaths.prepareDirectories`）。数据库里存的是文件名，不是路径。

**偏好：`UserDefaults.standard`**，键见 §5.3。

**密钥：Keychain**，service = `Jerreader.translation-backend`，
account = `proxy-access-token` 或 `direct-api-<provider>`。
**API Key 永远不进备份、不进 UserDefaults。**

### 5.2 Android

**结构化数据：Room**，数据库文件 `jerreader-library.db`，**version = 7**，
`exportSchema = true`，已写好 `MIGRATION_1_2` … `MIGRATION_6_7`。
六个实体：`BookEntity`、`WordLookupEntity`、`TranslationFavoriteEntity`、
`ReadingBookmarkEntity`、`ReadingAnnotationEntity`、`TranslationCacheEntity`。

> **没有 `fallbackToDestructiveMigration`，这是刻意的。** 改 schema 必须
> 手写 `Migration(6, 7)` 并加进 `addMigrations(...)`，漏了会在用户机器上直接崩。

**书籍文件**：`context.filesDir` 下的 `publications/`、`publication-staging/`、`covers/`
（`ImmutablePublicationStore`）。

**偏好：几个独立的 SharedPreferences 文件**——分开是为了让备份能整块取：

| 文件 | 谁在用 |
| --- | --- |
| `jerreader_app_settings` | `AndroidAppSettingsStore`（阅读默认值、配色套系、界面主题、学习模块显隐） |
| `jerreader_translation_settings` | `AndroidTranslationSettingsStore` |
| `jerreader_translation_credentials` | `AndroidKeystoreCredentialStore`（**密文**） |
| `jerreader_backup_policy` / `jerreader_backup_directory` | 备份策略与目标文件夹 |

**密钥：Android Keystore** 加密后再落 SharedPreferences，
alias `Jerreader.translation.credentials.v1`。**同样不进备份。**

### 5.3 键的完整清单

**iOS `UserDefaults`**（`*` = 在备份白名单里）

| 键 | 含义 |
| --- | --- |
| `appearance.theme-color` * | 界面色系（ocean/forest/wisteria/amber/berry） |
| `appearance.color-scheme` * | **1.3.4 新增**：明暗模式（system/light/dark） |
| `feature.learning-module-visible` * | **1.5.0 新增**：是否在主导航显示学习模块；默认显示 |
| `reader.colorPresets` * | 配色套系（编码串，与 Android 同格式同键名） |
| `reader.defaults.fontPointSize` / `.theme` / `.readingMode` / `.fontFamily` / `.lineHeight` / `.paragraphSpacing` / `.pageMargins` / `.pageMarginTop` / `.pageMarginBottom` / `.pageMarginHorizontal` / `.customBackgroundHex` / `.customSelectionColorHex` / `.appliesToExistingBooks` * | 全局阅读默认值 |
| `reader.showsProgress` * | 是否显示阅读进度 |
| `reader.defaultJapaneseTextOrientation` * | 日文版式默认值 |
| `library.pending-file-cleanup` | 待清理的 Inbox 交接副本（**1.3.4 新增**，不进备份） |
| `TranslationSettingsStore.backupDefaultKeys` * | 翻译偏好：服务商、语言、提示词、显示模式、震动、快速句译、自动重试、fallback、各家 endpoint 与 model |
| `LibraryBackupPolicyStore.backupDefaultKeys` * | 备份策略：开关、间隔、保留天数、份数、容量、范围 |

**Android SharedPreferences**

`jerreader_app_settings`：`app.theme`、`reader.fontScale`、`reader.theme`、
`reader.scroll`、`reader.font`、`reader.lineHeight`、`reader.paragraphSpacing`、
`reader.pageMargins`、`reader.orientation`、`reader.customBackground`、
`reader.customSelection`、`reader.pdfPaperMode`、`reader.showProgress`、
`reader.applyDefaultsToExisting`、**`reader.colorPresets`**、
**`feature.learningModuleVisible`**。

`jerreader_translation_settings`：`provider.mode`、`direct.provider`、
`backend.endpoint`、`backend.model`、`source.language`、`target.language`、
`quick.enabled`、`quick.unit`、`quick.disablesTapPageTurns`、`display.mode`、
`translation.haptics`、`automatic.retry`、`fallback.mode`、
**`prefer.ai.when.configured`（1.3.4 新增）**、`translation.prompt`、`grammar.prompt`。

`jerreader_backup_policy`：`automatic_enabled`、`interval_days`、`retention_days`、
`maximum_count`、`maximum_bytes`、`automatic_scopes`、`last_automatic_run`。
`jerreader_backup_directory`：`tree_uri`、`display_name`。

> **两端键名只有 `reader.colorPresets` 是刻意一致的**（编码格式也一致，
> 所以套系跨端可读）。其余键名不同是历史，别为了"统一"去改——改了就是丢数据。

### 5.4 加一个新的持久化键，要做四件事

1. 定义常量（不要散写字符串字面量）。
2. **加进备份白名单**：
   iOS `Data/Services/LibraryBackupRecords.swift` 的 `exportableDefaultKeys()`；
   Android `backup/LibraryBackupService.kt` 的 `settingsJson()` **和**恢复分支。
3. 想清楚**恢复到旧版本**会怎样——旧版本读不到这个键，必须能退回默认值。
4. 如果是"决定"，值的语义写进 `core`（枚举 + `fromId` 回退），别让两端各自解释字符串。

> 1.3.3 就漏过三个分边距键（`pageMarginTop/Bottom/Horizontal`），
> 用户恢复之后这三个值悄悄回默认，很难发现。

---

## 6. 备份与恢复

### 6.1 档案格式：两端各一份，**不通用**

两端都是 ZIP + `manifest.json`，但里面的布局不一样：

| | iOS | Android |
| --- | --- | --- |
| manifest | `manifest.json`，`version`，当前 **2** | `manifest.json`，`format = "jerreader.backup"`，`formatVersion` 当前 **1** |
| 记录 | `records.json`（一整块） | `library/books.json`、`reading/records.json`、`learning/records.json` |
| 设置 | `defaults.json`（UserDefaults 键值块） | `settings/preferences.json`（结构化字段） |
| 书籍文件 | 按 scope 打进压缩包 | 同左 |

所以**同一端备份 → 同一端恢复是好的，跨端恢复不支持**。
要做需要在 `core` 里定义一份共用的 backup payload 模型，是一块独立的活（§16）。

**两端共用的部分**（都在 `core/library/LibraryBackupPolicy.kt`）：
`LibraryBackupScope` 四个范围、`LibraryBackupPolicy` 策略、
`LibraryBackupPruner` 清理顺序（先按天、再按份数、最后按容量，
**永远保留最新一份**）、`LibraryBackupProfile` 随档旅行的备份规程。

> `LibraryBackupProfile` 会**无条件**写进 manifest，不管用户有没有勾"设置"范围。
> 目的是：换机恢复之后备份计划自动接上，而不是要用户重新配一遍。
> 文件夹只是**提示**（名字 + URI），不是授权——SAF 授权留在原机器上。

**恢复前一定会校验**：ZIP 结构、manifest 版本（高于本版直接拒绝）、
条目摘要、payload。放宽选择器只影响"能不能点中文件"，不影响校验。

### 6.2 档名与 UTI（1.3.4 改动，见 §10.4）

- 新档名：**`.jerbackup.zip`**，定义在 `core` 的 `LibraryBackupNaming.ARCHIVE_SUFFIX`，两端共用。
- 旧档名 `.jerreader-backup` **只读不写**，仍能恢复（`LEGACY_IOS_EXTENSION`）。
- iOS 导出 UTI `com.jerreader.backup` 仍在 `Info.plist` 里，
  但它现在**只服务旧文件**——新档就是普通 zip，任何 File Provider 都认得。
- iOS 选择器类型顺序：`[.zip, .jerreaderBackup, .archive, .data, .item]`。
- `UIFileSharingEnabled = true`、`LSSupportsOpeningDocumentsInPlace = false`：
  备份文件夹在「文件」App 里可见，但书是拷进来的，不是原地打开的（与 §10.1 相关）。

### 6.3 自动备份

策略在 `LibraryBackupPolicy`：`isDue(now)` 判断该不该跑。
可选间隔 1/3/7/14/30 天，保留 7/14/30/90/365/0 天，份数 2/3/5/10/20，
容量 250M/500M/1G/2G/5G/0（0 = 不限）。
`LibraryBackupProfile.normalized()` 会把别的构建写进来的值折回本构建能选的档位，
否则选择器里会出现一行谁也选不中的值。

---

## 7. 阅读器架构

### 7.1 选区管线

```
用户长按/拖动
  → 宿主 WebView 的原生回调（iOS EPUBSelectionBridge / Android InputListener）
  → core/reader/kernel/ReaderSelectionController 状态机
  → core/reader/selection/ReaderSelectionScripts 注入的 JS 量矩形
  → ReaderSelectionRectMerger 合并成可绘制的块
  → ReaderSelectionPalette 按当前页面底色算填充/描边颜色
  → 宿主绘制（iOS 原生层 / Android ReaderSelectionHighlightLayer）
```

三条必须记住的：

1. **调色只有一份**：`ReaderSelectionVisualStyle.palette(appearance)`。
   1.3.4 起它的默认背景也读 `ReaderPageBackground`，不再自己存一份主题色。
2. **`core` 给的颜色是 `Long`（`0xAARRGGBB`）**。Compose 要**无符号 Int**：
   `(this and 0xFFFFFFFFL).toInt()`。1.3.3 就是这里丢了 alpha，
   填充被画成全透明——"选区填充色不见了"。
3. **滚动偏移是传进去的，不是现读的**——见 §13。

### 7.2 竖排分页

ReadiumCSS **拒绝**给竖排（`vertical-rl`）分页，所以整页吸附是我们自己写的
滚动代理（iOS `EPUBReaderHost`）+ `core/reader/paging/` 的步进算法。

> ⚠️ **接管宿主框架的属性时，要连"什么时候还回去"一起写。**
> 1.3.4 在这里踩了两层（§10.2）：滚动代理装上去没摘过，而且
> `configureViewport()` 无条件把 `scrollView.isPagingEnabled = false`——
> 那正是 Readium 横排分页的开关。只在需要的分支里设值，
> 另一条分支就成了永久副作用。

### 7.3 译文浮层的避让求解

`core/reader/overlay/ReaderOverlayPlacement`：

- `solve(request)` → `ReaderOverlayLayout`（边、位置、最大高度）。
- `correct(measured, selection, safeBounds, gap)` → 位移。
  **它是绝对的**：对同一个输入重复调用会收敛，不会来回抖。
- 竖排优先横向避让（`prefersHorizontalAvoidance`），横排优先上下。
- **上下都塞不下时**，卡片取安全区高度并推到页边，宁可压住选区也要能读
  （1.3.4 改动，§10.7）。

宿主必须把**策略值**传进 `ReaderOverlayRequest`，不能用默认值——
`ReaderOverlayRequest` 的默认 `minimumCardHeight = 64.0`、
`preferredMaximumCardHeight = 196.0`，而 `ReaderTranslationLayoutPolicy` 的是
108 / 246。写测试时不注意这一点会得到看不懂的失败。

### 7.4 点句翻译的坐标系（**最容易改错的一处**）

关键事实：**同一个"偏移"在不同数据流里未必都要加。**

| 数据 | 来源 | 原点 | 要做什么 |
| --- | --- | --- | --- |
| 选区矩形 | `getClientRects()` | WebView 视口 | 要换算到原生视图坐标才能画 → **要加 WebView 在容器里的偏移** |
| 点按坐标 | Readium `readium-reflowable.js` 的 `clientX * devicePixelRatio` | **已经**是 WebView 视口 | **只欠一个 `/ density`，不要再减偏移** |

Readium 3.3.0 的字节码可以直接对：`EpubNavigatorFragment$WebViewListener.onTap`
把这个点**原样**包进 `TapEvent`；`adjustedToViewport()`（那个才会加上页容器
padding）只用在 `onDrag` 和 `onDecorationActivated` 上；`R2EpubPageFragment`
给自己的 `R2WebView` 设的是 `setPadding(0,0,0,0)`。
所以点按坐标和 `caretRangeFromPoint` 本来就同一个原点。

`ReaderTapTranslationController.toCssPoint` 因此就是

```kotlin
PointF(point.x / density, point.y / density)
```

和一直没出过问题的 `ReaderWordInteractionController.toCssPoint` 一致。
1.3.4 中途给它多减了一次偏移，结果每次点击都往上挪了约一行字（§10.12）。

**改这块的自检**：点一句应该选中那一句本身；点第一段正文的**正上方一点点空白**
应该收起菜单，不应该选中第一句；点最上面一行必须选得中。

---

## 8. 翻译架构

### 8.1 服务商怎么选（1.3.4 起）

决定在 `core/translation/TranslationProviderPolicy.plan(...)`，返回
`TranslationProviderPlan(primary, fallback)`：

1. 用户**显式**选过服务商 → 听用户的；
2. 否则 `preferAIWhenConfigured`（默认开）且 AI 配全了 →
   `DIRECT_API` 优先，其次 `BACKEND_PROXY`；
3. **端上模型（ML Kit / 系统翻译）降级成 fallback**，断网或报错时接住。

「配全了」= 有凭据 **且** endpoint **且** model 都非空。少一样就不算。

### 8.2 缓存分桶

`cacheNamespace` 必须按**实际生效的** mode 分桶，不能按存储的 mode。
否则升级之后会读到上一版端上模型留下的旧译文，用户会看到"改了设置没效果"。

### 8.3 凭据

见 §5.1 / §5.2。三条铁律：
**不进备份、不进日志、不进 UserDefaults/SharedPreferences 明文。**

### 8.4 语法解析是例外

`preferredAIProviderMode()` 在语法解析路径上**不看** `preferAIWhenConfigured`：
端上模型根本没有语法解析能力，没有可降级的路径，所以它要么用 AI 要么没有。
这不是 bug，注释里写了。

---

## 9. 全局主题与配色

### 9.1 三个互不相干的"主题"，别混

| 名字 | 管什么 | 在哪儿设 | 存哪儿 |
| --- | --- | --- | --- |
| **界面色系** | App 的强调色与表面色（5 档） | 设置 → 界面主题 | `appearance.theme-color` |
| **明暗模式** | App 走白天/黑夜/跟随系统 | 设置 → 界面主题（**1.3.4 iOS、1.3.5 Android**） | `appearance.color-scheme` |
| **纸张主题** | 阅读页的底色与文字色（light/sepia/cool gray/dark） | 阅读设置 / 默认阅读排版 | `reader.defaults.theme` 等 |

**阅读页不受明暗模式影响**——它按纸张主题定深浅。iOS 上阅读器是
`fullScreenCover`，自己设 `preferredColorScheme`，根上的设置管不到它。

### 9.2 表面色的四条不变量（有测试）

`core/design/JerreaderAppPalette.kt`，测试在 `JerreaderAppPaletteTest.kt`：

- 每个强调色产出**互不相同**的 canvas；
- 浅色模式所有表面亮度 **> 0.78**（还是纸，不是有色块）；
- 深色模式所有表面亮度 **< 0.25**；
- 表面与强调色的距离 **> 0.6**——**"染的是一层水，不是重新刷漆"**。
  这一条是防止以后有人"让主题更明显一点"，把页面刷成彩色，
  书封和正文就不像纸了。

### 9.3 配色套系的模型（改这块之前必读）

一个"套系"= **背景色 + 选区色这一对**。只存一半没有意义：内置主题
本来就各自带一套配好的选区色，半对数据正是两端会漂开的地方。

唯一实现：`core/library/ReaderColorPreset.kt` 的 `ReaderColorPresetStore`：

| 能力 | 方法 |
| --- | --- |
| 编解码 | `encode(presets)` / `decode(raw)` |
| 推导 id | `idFor(backgroundHex, selectionHex)` / `idFor(appearance)` |
| 增 / 删 | `added(existing, appearance, name)`（**1.3.4 新增重载**）/ `added(existing, id, name, bg, sel)` / `removed(existing, id)` |
| 当前排版的两色 | `backgroundHexFor(appearance)` / `selectionHexFor(appearance)` |
| 当前排版是不是这套 | `matches(preset, appearance)` |
| 没起名字时的名字 | `defaultName(existing)` → 「配色 3」 |
| 名字清洗 | `sanitizedName(value)` |
| 归一化色值 | `normalizedHex(value)` → `#RRGGBB` 或空串 |
| 套用到排版 | `applied(appearance, preset)`（只写两个颜色，**不动 theme**） |
| 上限 | `MAXIMUM_PRESETS`、`MAXIMUM_NAME_LENGTH` |

存储键两端都是 **`reader.colorPresets`**，编码格式（记录用换行、字段用制表符）
也是同一份，所以一端存的套系另一端读得懂。

- iOS：`@AppStorage("reader.colorPresets")`，UI 在 `App/ReaderColorPresetPicker.swift`。
- Android：`AndroidAppSettingsStore` 的 `KEY_COLOR_PRESETS`，UI 在
  `ui/ReaderComponents.kt` 的 `ReaderColorPresetEditor`。

两处入口都能存能用：**每本书的阅读设置** 和 **设置 → 默认阅读排版**。
手势两端一致：**轻点套用，长按删除**。

**两个踩过的坑：**

1. **别在每次按键时跑 `sanitizedName`。** 它会 `trim()`。输入过程中跑，
   「夜 读」的空格刚打出来就被吃掉，第二个词根本起不了头。
   输入时只做**必须当场生效**的限制（截长度），`trim` 留到**保存那一刻**。
2. **iOS 的命名不能用 `alert`，也不能用内联行。**
   `.alert` 里的 `Button("保存") { save() }` 读到的是**弹窗构建时**的 `@State`，
   名字到不了 action，保存会**静默无效**；内联行则会被键盘盖住它自己的保存按钮。
   现在命名是独立 sheet（`ReaderColorPresetNamingSheet`），自己持有文本、回调交回。

---

## 10. 1.3.4 这一版做了什么

按用户反馈的十条整理（iOS 五条、Android 五条），外加自检扫出来的三处，
以及一处**中途做错又回退**的记录（§10.12——回退的理由比改动本身更值得读）。
**每一条的"为什么"都写在了对应源文件的注释里**，这里只记结论与位置。

### 10.1 iOS：打开书会改动「文件」App 里原始 epub 的时间

书架的选书器本来就跑在**拷贝模式**（`CopyingDocumentPicker`），不碰原件；
出问题的是从「文件」App 走**「打开方式」**送进来的书——它是**原地 URL**，
向 file provider 要一个还没下载的条目会让它**落地**，iCloud Drive 落地时
盖一个新的修改时间。于是这本书跳到按时间排序的文件夹最上面。

`EPUBImportService` 现在在安全作用域里**先记下原件的创建/修改时间，读完再放回去**
（`SourceFileTimestamps`，只有确实变了才写）。同时清掉
`Documents/Inbox` 里 iOS 留下的那份交接副本——它会无限堆积，还会让用户在
「文件 → 读鼠」里看到每本书的第二份陈旧拷贝。**读一本书不该改动用户的文件。**

### 10.2 iOS：横排「逐页」下滑动会连续滚动，停不到整页

竖排的整页吸附是我们自己写的滚动代理（ReadiumCSS **拒绝**给竖排分页）。
问题是这个代理**装上去就没摘过**：同一个 WKWebView 切到横排后，它照样
无条件接管 `scrollViewWillEndDragging`，还按**竖排列**去算落点——
于是每一次拖动要么被弹回原处，要么停在一个根本不是页边界的偏移上，
而 Readium 自己的分页**压根没收到拖动结束**。

`EPUBReaderHost.removeVerticalPagingDelegate` 现在在每一次不需要竖排吸附的
布局里把 scroll view 交还给 Readium。

**但只做这一步不够，横排还是停不到整页。** 真正的开关是
`scrollView.isPagingEnabled`——Readium 的横排分页**就是它**：
`EPUBReflowableSpreadView` 在 `setupWebView()` 和 `applySettings()` 里都写
`scrollView.isPagingEnabled = !viewModel.scroll`。而我们的 `configureViewport()`
**每一次布局、对每一个 WebView 都无条件 `= false`**（当初是为了不让 UIKit 分页
去啃竖排的整列）。把代理摘掉只是把拖动还给了 Readium，Readium 手上却已经
没有吸附能力了。现在按模式还原：

```swift
scrollView.isPagingEnabled = !usesVerticalPageSnapping && !navigator.settings.scroll
```

竖排（我们自己吸附）与滚动模式都是 `false`，横排逐页是 `true`。
**教训**：接管宿主框架的某个属性时，要连"什么时候还回去"一起写；
只在需要的分支里设值，另一条分支就成了永久副作用。

### 10.3 iOS：配色套系只能存一个、输入名字时出问题

**根因不是保存逻辑，是"能不能按"的判定。** 旧的 `canSave` 要求
`idFor(背景, 选区)` 非空，而 `idFor` 只有两个颜色都是完整 hex 才返回 id。
设置页里"自定义背景颜色"和"自定义选区颜色"是**两个各自独立的开关**，
所以只有两个都恰好打开的那一小段时间里才存得下——看起来就像"只能存一个"。

现在**颜色对是从页面当下显示的样子推出来的**，内置主题也算一对完整颜色。
新增的四个方法见 §9.3 的表。

输入那一半：命名 sheet 去掉了固定 260pt 的 detent（键盘会把矮 sheet 整个盖住）、
把聚焦推迟到呈现动画之后（否则键盘静默不弹），并且**输入过程中不再回写绑定**
（回写会打断拼音候选）。保存按钮**永远可按**，空名字＝用建议名。

### 10.4 iOS：备份还是不能手动选择文件

1.3.3 放宽 `allowedContentTypes` 没解决问题：**自定义扩展名必须先被解析成
导出的 UTI，选择器才会让文件可选**，而 iCloud 的 File Provider 经常解析不出来。

这一版**改的是文件名**：备份档从 `.jerreader-backup` 改成 **`.jerbackup.zip`**
——和 Android 一直在写的名字一致。名字只有一份定义：

```kotlin
// core/library/LibraryBackupPolicy.kt
object LibraryBackupNaming {
    const val ARCHIVE_SUFFIX = ".jerbackup.zip"
    const val LEGACY_IOS_EXTENSION = "jerreader-backup"   // 只读，不再写
    fun isArchiveName(name: String): Boolean
}
```

旧扩展名**仍然能恢复**，只是不再写。选择器的第一个类型现在是 `.zip`。
放宽只影响"能不能点中"：`LibraryBackupService` 在写入任何东西之前
仍会校验 ZIP、manifest 版本、条目摘要和 payload。详见 §6.2。

### 10.5 iOS：界面主题里单独调白天 / 黑夜 / 跟随系统

判定在 `core/design/JerreaderAppPalette.kt` 的 `JerreaderAppearanceMode`
（`SYSTEM`/`LIGHT`/`DARK`，只有一个方法 `isDark(systemIsDark)`），有测试。

iOS 侧只做两件事：`JerreaderThemePreferences.appearanceModeKey`
（`appearance.color-scheme`，已进备份白名单），以及在 `JerreaderApp` 根上
**一次** `.preferredColorScheme(...)`。

**必须是 `preferredColorScheme`**：`JerreaderTheme` 的每个颜色都是读 trait 的
动态 `UIColor`，SwiftUI 给列表、键盘、sheet 画的系统色也是。只染我们自己的颜色，
会得到"深色页面配浅色键盘"。**阅读页不受影响**——它是 `fullScreenCover`，
自己按纸张主题定深浅。

### 10.6 Android：阅读界面没占满屏幕，只有中间一块

两件事叠成了用户截图里那条"上下白边夹着中间一块正文"：

1. `ReaderActivity` 给 `readerContainer` 上下**各留了 74dp / 156dp**，
   为的是"浮层永远不会压到正文"。在手机上那是**整整一页的阅读面积**，
   换来的是两条平时根本不显示的 bar。现在只 padding 系统栏本身，
   页面从状态栏一直铺到导航栏——和 iOS（正文 `.ignoresSafeArea()`，
   只有 bar 内缩）同一个形状。
2. 露出来的窗口底色是 Activity 主题的**白色**。Readium 的 fragment 只覆盖
   系统栏之间那一块，PDF 还会再在里面留黑边，所以一本 sepia 或夜间的书
   就成了"白纸上浮着一块有色面板"。现在 `applyReaderSurfaceColor()`
   把窗口刷成**当前页面自己的颜色**，换主题时同步。

顺带修掉一个把主题背景当成自定义色读回来的 bug
（`ReaderPreferencesMapper.toReaderAppearance`）：重开一本书会变成
「自定义」被选中、主题行空着。

### 10.7 Android：译文浮层有时不展开，只有很小一条

`ReaderOverlayPlacement.solveVertically` 在上下都塞不下时，把
`maximumCardHeight` 压到 `MINIMUM_CARD_HEIGHT`，`ReaderActivity` 的
`heightIn(max = layout.maximumCardHeight.dp)` 再把卡片自己的文字裁掉。

现在这种情况下卡片**取安全区高度并推到页边**，宁可压住选区也要能读。
测试在 `core/.../overlay/ReaderOverlayPlacementTest.kt`。

### 10.8 Android：系统翻译包几乎不可用 → 配了 AI 就自动优先

`providerMode` 默认 `ON_DEVICE` 且从来没人改过，所以配好的 API Key
**只有语法解析在用**，划词翻译还是 ML Kit。新的决策见 §8.1
（`core/translation/TranslationProviderPolicy.kt`，8 个测试）。

`cacheNamespace` 现在按**实际生效的** mode 分桶（§8.2）。
开关在设置页（只在还停在 ON_DEVICE 时显示），会走备份。
**这会消耗用户自己的 API 额度，是用户拍板要的。**

### 10.9 Android：自定义背景色对齐 iOS，并且也能存套系

Android 原来是两个 `#RRGGBB` 文本框，iOS 是系统取色轮。现在
`ui/ReaderComponents.kt` 的 `ReaderColorField` 给出**色相/饱和度/明度三条
带渐变的滑杆** + 预览圆点 + hex 输入，色值换算在
`core/library/ReaderColorMath.kt`（含 16×16×16 往返测试）。

`AndroidAppSettingsStore.saveColorPreset` 也改成走 §10.3 的新 `added` 重载——
从内置主题存的套系以前在这里被**静默丢掉**。

### 10.10 Android：点段落上方一点点的空白会选到下面的句子

28px 的"离字够近"判定把**页面上边距**也算进了第一段。
`core/reader/selection/ReaderSelectionScripts.kt` 增加了 `pointIsInsideBlock`
（块盒 + `TAP_BLOCK_MARGIN_CSS_PIXELS` 的余量）：点必须真的落在块盒里
（带一点余量），"离得近"不再等于"就是它"。

> 中途还给这条加过第二个原因（`toCssPoint` 少减了一次偏移），
> **那条是误判，已回退**，见 §10.12。

### 10.11 顺带：选区默认背景色是第四份主题色拷贝

`ReaderSelectionVisualStyle.defaultBackground` 自己存了一套主题色，
已经和两个宿主都漂开了。现在读 `ReaderPageBackground.themeArgb(theme)`。

### 10.12 回退记录：`toCssPoint` 不该减 WebView 偏移

§10.10 一度给 `ReaderTapTranslationController.toCssPoint` 加了"减去 WebView 在
`publicationView` 里的偏移"，理由是高亮层要减这个偏移。
**高亮层要减，点击不能减**——两者的输入根本不在同一个空间，理由与
Readium 3.3.0 的字节码证据见 §7.4。

多减那一次，等于把每一次点击都往上挪了一个页容器 padding（约一行字）：
点一句得到上一句，点最上面一行则算到正文之外被当成空点丢掉——
用户看到的是「选区点按不上，或者会点到别的地方」。现已回退，
`toCssPoint` 只做 `point / density`。§10.10 的 `pointIsInsideBlock` 保留，那条是对的。

**教训**：改坐标换算之前，先把每一路数据的原点逐条写下来，再决定谁需要换算。
"另一处也减了"不是理由。

### 10.13 顺带：`CFBundleGetInfoString` 从 1.1.0 起就是死值

改成 `Jerreader $(MARKETING_VERSION) by WANG ZIRUI`。
版本号的第二份拷贝正是 1.3.0 之前两端分家的原因。

### 10.14 顺带：备份白名单补进 `appearance.color-scheme`

新键必须同时进白名单，见 §5.4。

### 10.15 接手审查：Android 恢复会覆盖现有书籍，并留下错误的关系 ID

Android 旧恢复流程边读 ZIP 边写 Room，遇到 `library/books.json` 时却把书籍留到最后；
书签、批注和学习记录因此先按备份设备的 `bookId` 入库。目标设备若已经有同一本书但
UUID 不同，这些记录会变成孤儿。更严重的是，出版物文件在数据库判重之前使用
`overwrite = true` 复制：一份手工修改或冲突的备份能覆盖书架中已经存在的不可变文件。
备份元数据中的文件名也没有叶子名校验和解压大小上限。

现在 `LibraryBackupService` 使用两阶段恢复：先把所有条目放进隔离暂存目录，完整验证
manifest、scope、重复条目、叶子文件名、记录数、解压总量、单项大小、出版物长度和
SHA-256，以及归档版本和书籍 UUID 的唯一性；全部通过后才复制文件并在一个 Room 事务中合并记录。已有书按指纹匹配，绝不
复制或覆盖它的文件；新书使用无冲突文件名，数据库失败会删除本次新建文件。书签和批注
通过 `AndroidReaderRecordKeys` 用目标设备的书籍 UUID 重建稳定键，学习记录也同步重映射。
测试覆盖路径穿越、文件名冲突、现有文件内容不变和书签 UUID 重映射。

### 10.16 接手审查：Android 短按查词偶发首击无响应

短按脚本已经在 WebView 里建立选区时，Readium 的原生 `currentSelection()` 仍可能晚一拍
才收到对应 Locator；连续运行多个阅读器仪器测试时可以稳定撞出空结果。现在脚本显式派发
标准 `selectionchange` 事件，控制器也会在有限的一秒窗口内等待桥接同步。它不会改写
EPUB，超时仍按“没有可查词选区”安全降级。`ReaderWordLookupTest` 已在全量设备套件中
覆盖英文和日文短按查词。

### 10.17 iOS：翻译输入只保留首字或丢失输入法候选

翻译工具原来使用 SwiftUI 的纵向 `TextField`。中文、日文输入法的候选尚处于
`markedTextRange` 时，字符计数、按钮状态或翻译结果引起的界面更新可能把绑定值重新写入
编辑器，取消正在组合的候选并丢掉部分文字。切换到生词本或历史还会销毁翻译页，原来的
局部 `@State` 草稿也随之消失。

1.4.1 改为 `StableTranslationTextInput`：UIKit 在候选确认前独占 marked text，只有组合结束
后才允许模型文字回写；点击翻译会先收起编辑器、保存最终候选，再生成请求。编辑时使用内存
状态，结束编辑或离开页面才写 `SceneStorage`，因此不会因每个按键触发场景状态重建，同时
切换学习页分区后草稿仍能恢复。单元测试覆盖中日输入法回写规则，UI 测试会连续输入中日混合
句并往返生词本验证全文不丢失。

### 10.18 1.5：日语学习闭环

生词本收藏会进入「复习」分区。共用的 `VocabularyReviewScheduler` 决定队列与间隔：
到期词先于新词、日语词优先，每天最多 50 个到期词与 10 个新词；评价分为忘记、模糊、
认识、熟练。日语有原句时先显示挖空题，揭晓后展示词面、读音、基本形、词性、活用、释义、
用法与例句。算法只接收数值与状态，Android/Room 和 iOS/SwiftData 各自负责保存。

Android v7 为 `WordLookupEntity` 增加例句与复习计数、阶段、间隔、遗忘次数、上次/下次复习
时间；`MIGRATION_6_7` 为旧记录填安全默认值并建到期索引。iOS 在现有 SwiftData 模型上做
轻量加字段。两端备份都携带这些字段，旧备份缺字段时按「未复习」恢复。

### 10.19 1.5：学习模块可独立隐藏

设置页最上方的「显示学习模块」默认开启。关闭只移除主导航中的学习入口；若用户当时正在
学习页，会安全返回书架。生词、历史、复习进度和阅读内查词/翻译能力不删除，重新开启即可
继续。偏好进入两端设置备份，旧备份没有该键时保留当前选择。

### 10.20 Android：短按查词不再等待旧的原生选区

网页 DOM 已选中单词与 Readium 的原生 `Selection` flow 并非原子更新。旧路径在两者之间
读取时可能得到 `null`，也可能拿到上一笔选区。现在点击脚本在同一次命中计算中返回精确词面
与相邻上下文，原生侧直接据此查词；DOM 选区仍只作为临时高亮，不改写 EPUB 正文。
`ReaderWordLookupTest` 同时覆盖 CSS 坐标和 Readium DPR 像素坐标。

### 不要再去"顺手改"的三处

- **两端"点空白收起菜单"的判定已经一致**，别再单独调某一端的阈值
  （§10.10 加的是块盒包含判定，不是阈值）。
- **短按查词的 DPR 换算必须留在产生点击坐标的同一份 WebView 文档里**；不要再用 Android
  display density 或高亮层偏移换算（§10.12、§10.20）。
- **`ReaderSelectionScripts` 的绘制看着像死代码/可简化——它不是。** 见 §13。

---

## 11. 构建、测试、出包

### 11.1 测试矩阵（改完必须全过）

```bash
./gradlew :core:testAndroidHostTest :ui:testAndroidHostTest :androidApp:testDebugUnitTest --no-daemon
```

```bash
xcodebuild -project iosApp/JerreaderUnified.xcodeproj -scheme JerreaderUnified \
  -destination 'platform=iOS Simulator,id=95225317-66F0-476C-843C-A2530F042FCD' \
  -configuration Debug test CODE_SIGNING_ALLOWED=NO ARCHS=x86_64 ONLY_ACTIVE_ARCH=NO
```

**1.5.0 基线：**

| 目标 | 数量 |
| --- | --- |
| `:core:testAndroidHostTest` | **212** |
| `:core:iosX64Test` | **212**（同一份 commonTest，跑在 Kotlin/Native 上） |
| `:ui:testAndroidHostTest` | **8** |
| `:androidApp:testDebugUnitTest` | **13** |
| `:androidApp:connectedDebugAndroidTest` | **22** |
| `JerreaderTests`（iOS 单元） | **280**（跳过 3） |
| `JerreaderUITests`（iOS 模拟器 UI） | **18**（跳过 9） |

想额外验一遍 Kotlin/Native 行为，跑 `./gradlew :core:iosX64Test --no-daemon`。
**不要跑 `iosSimulatorArm64Test`**：在这台机器上它会静默跳过还报成功（§13）。

**UI 测试很慢（单个用例 20–60 秒），但它是设置类改动的唯一防线。**
1.3.4 改 §10.3 时，旧用例断言的"两个开关都开才能存"当场不成立，是它先红的；
更早还抓到过 alert 命名的静默失效。那类 bug 单元测试抓不到。

那 9 个跳过的 UI 测试是**条件跳过**，不是失败：它们需要一份注音夹具书
`UITestRubyBook.epub`（跳过原因写在 `throw XCTSkip(...)` 里）。
夹具没进仓库，所以**注音相关的选区路径目前没有自动化覆盖**——
改选区代码时这块要手工验。

### 11.2 只编不测

```bash
./gradlew :androidApp:compileDebugKotlin --no-daemon   # 会连带编 :ui 与 :core
```

> `:ui:compileDebugKotlinAndroid` **这个任务不存在**，别照着猜名字。

### 11.3 出包

```bash
./scripts/release.sh          # --android-only / --ios-only 可只出一端
```

脚本会：

1. 从 `version.properties` 读版本，`syncIosVersion` 写进 `iosApp/Version.xcconfig`；
2. 跑测试；
3. 出 release APK（**debug key 签名，故意的**，见 §12.2）与**未签名** IPA；
4. **把产物复制到 `~/Desktop`**，命名 `Jerreader-<版本>-<build>.{apk,ipa}`。

最后一步不是多余的：仓库路径带中文字符，某些传输通道会把它弄坏，
所以交付物一律落到纯 ASCII 路径。

IPA 是**侧载载荷**（`Payload/Jerreader.app` 的 zip），不是上架包——
上架签名需要用户自己的证书和描述文件。

---

## 12. 版本号、签名、身份标识

### 12.1 版本号只有一个来源

`version.properties`。Android 在 `androidApp/build.gradle.kts` 里读它；
iOS 由 `./gradlew syncIosVersion` 生成 `iosApp/Version.xcconfig`，
Xcode 工程在 **project 级别**引用。

> **任何 target 都不许设 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`。**
> target 级的值会静默覆盖 xcconfig——1.3.0 之前两端版本号分家就是这么来的。
> 同理，Info.plist 里也不许出现写死的版本号（1.3.4 才修掉最后一处，§10.13）。

### 12.2 安卓签名：**不要换 key**

`keystore.properties` 指向 `~/.android/debug.keystore`。
用 debug key 签 release 是**刻意**的：用户手上每一个侧载包都带这个签名，
Android 拒绝跨签名升级。换成 release keystore 会让所有人**必须卸载重装**，
本地数据全丢。要换必须先想清楚数据迁移。

### 12.3 身份标识（改了就丢用户数据）

- **包名 / bundle id**：`com.jerreader.android` / `com.example.Jerreader`。
- **数据库名**：`jerreader-library.db`（Android）；SwiftData 默认容器（iOS）。
- **SharedPreferences 文件名 / UserDefaults 键名**：§5.3 全表。
- **导出 UTI** `com.jerreader.backup`（现在只服务旧备份档，§6.2）。
- **Keychain service / Keystore alias**：§5.1 / §5.2。

改任何一个之前，先想清楚"用户升级之后这份数据怎么迁"。

---

## 13. 已知的坑

### 这台机器是 Intel Mac

`iosApp/Scripts/build-shared-framework.sh` 会**从 `ARCHS` 挑一个** Kotlin target。
用 `generic/platform=iOS Simulator` 会同时要 arm64 和 x86_64 两个切片，
链接直接失败：`ld: symbol(s) not found for architecture x86_64`。

**所有 iOS 模拟器构建都必须带 `ARCHS=x86_64 ONLY_ACTIVE_ARCH=NO`。**

同理，模拟器目标要 `iosX64`，不是 `iosSimulatorArm64`。
`iosSimulatorArm64Test` 在这台机器上会**静默跳过还报成功**——一个会骗人的绿灯。

### `--no-daemon` 第一次假失败

Gradle 需要先落一次配置缓存。再跑一遍。这是第二个会骗人的灯。

### `core` 没有 kotlinx.serialization

所有跨端数据都是手写编码（配色套系是制表符分隔的记录串，
桥接 JSON 在 `reader/json/ReaderJson.kt`）。
加依赖之前先确认它有 `iosX64` 产物。

### Kotlin → Swift 导出的名字规则

| Kotlin | Swift |
| --- | --- |
| `object X` | `X.shared` |
| `companion object` | `X.companion` |
| 枚举项 | 小写 |
| `const val MAXIMUM_NAME_LENGTH` | **原样** `Store.shared.MAXIMUM_NAME_LENGTH` |

拿不准就查生成的 `JerreaderCore.h`，别猜。

### 测试文件里不要整个 `import JerreaderCore`

它导出的 `TranslationService` / `LanguageCode` / `TranslationResult`
会和 app 侧同名 Swift 协议撞成 ambiguous。
要用哪个就 `import class JerreaderCore.XXX` 单点引入。

### 选区脚本：滚动偏移是**传进去**的，不是现读的

`core/reader/selection/ReaderSelectionScripts.kt` 里，滚动偏移由内核作为参数传入。
瓦片在选区调用时按 client 坐标测量，在之后另一次调用里按文档坐标绘制；
`addRange` 会把选区滚进视野，**绘制时再读一次 `scrollX/scrollY` 就是拿另一个原点
去换算之前测的坐标**，高亮会画到文字旁边。

两端宿主里的内联绘制**看起来不一样**是对的：它们在同一段脚本里测量并绘制，
所以可以拿浮层自己的 `getBoundingClientRect()` 当原点——这也是唯一能扛住
`vertical-rl` 的做法。拆成两次桥调用（也就是这里）就用不了这招。

**这段代码看着像死代码、像可以简化——它不是。** 改之前先跑通阅读器。

### `ReaderOverlayRequest` 的默认值不是策略值

默认 `minimumCardHeight = 64.0` / `preferredMaximumCardHeight = 196.0`；
`ReaderTranslationLayoutPolicy` 是 108 / 246。写测试要显式传策略值。

### 原生模拟器 MCP 工具用不了

它要求 `xcode-select` 指向完整 Xcode，而本机 Xcode 在 `~/Downloads` 下，
改 `xcode-select` 需要密码。替代做法：带上 `DEVELOPER_DIR` 直接用
`xcrun simctl install / launch / io <udid> screenshot`。

常用模拟器：iPhone 16（`95225317-66F0-476C-843C-A2530F042FCD`）、
iPhone 16 Pro（`5CE09295-DD94-4C85-B5DB-60FD55AC57C6`），
bundle id `com.example.Jerreader`。

`simctl` 还能直接灌 UserDefaults 来验主题这类开关，例如
`xcrun simctl launch <udid> com.example.Jerreader -appearance.color-scheme dark`，
配合 `xcrun simctl ui <udid> appearance dark|light` 可以两个方向都验一遍。

强行用 System Events 之类的通用点击自动化**会把模拟器搞到不可恢复**，别试。

---

## 14. 排查手册：用户报 bug 时怎么查

用户能给你的通常只有一句中文和一张截图。按这个顺序走：

**第一步：把现象翻译成"哪一层"。**

| 用户的话 | 先怀疑哪一层 |
| --- | --- |
| "颜色不对 / 看不见" | 先确认是 `core` 的算法还是宿主的转换。1.3.3 那次就是 Compose 的 `Long → Int` 丢了 alpha，`core` 的算法完全正确。**别一上来改 `core`。** |
| "点不准 / 点到别的地方" | 坐标系（§7.4）。把每一路数据的原点写下来比读代码快。**注意 §10.12 的反例：不是所有偏移都要减。** |
| "界面缺一块 / 有白边" | 先看 padding，再看**窗口底色**——露出来的往往是 Activity 主题色，不是布局问题（§10.6）。 |
| "存不上 / 只能存一个" | 先看**"能不能按"的判定**，再看保存逻辑。1.3.4 两次都是前者。 |
| "选不中文件" | UTI 解析，不是权限（§6.2）。 |
| "设置没效果" | 缓存分桶（§8.2）、或者根本没人读这个值（§10.8）。 |
| "滑动 / 翻页不对" | 先查我们有没有接管宿主框架的某个属性却没还回去（§7.2、§10.2）。 |

**第二步：找到那一层之后，先写一个失败的测试。**
`core` 里的能写单元测试就写；只有驱动 UI 才能暴露的（可用性、文案、键盘遮挡）
写 iOS UI 测试——虽然慢，但那是唯一能抓到这类问题的地方。

**第三步：两端都查一遍。**
用户只报了一端，不代表另一端没有。§10 里有三条是这样查出来的。

**第四步：改完跑全套（§11.1），并且真的把 App 跑起来截图看一眼。**
测试全绿但界面没渲染出来的情况发生过。

> **不要只看应用自己缓存的状态就下结论。** 用户说"数字不对 / 内容不对"时，
> 独立核对一次原始数据源（数据库、文件、原始响应），不要只读 App 内的缓存视图。
> 同样，改一处之前先确认那处**当前**是什么样——§10.12 就是照着"另一处也这么做"
> 推出来的错误改动。

---

## 15. 提交前清单

1. 改的是"决定"还是"画法"？决定进 `core` 且**带测试**。
2. 两端都改了吗？只改一端就是下一次漂移的起点。
3. 文案有没有第二份拷贝？统一进 `core/design/JerreaderCopy.kt`。
4. 颜色/数值有没有第二份拷贝？统一进 `core/design/` 或 `core/library/`。
5. 新增的持久化键，四件事做全了吗（§5.4）？
6. 改了 Room schema？写了 `Migration(n, n+1)` 并加进 `addMigrations` 了吗？
7. 接管了宿主框架的某个属性？写了"什么时候还回去"了吗（§7.2）？
8. 全套测试跑了吗（§11.1）？**包括 iOS UI 测试。**
9. 真的把 App 跑起来看过吗？
10. 出包前 `version.properties` 加过了吗？
11. `git commit` 了吗（§16）。

---

## 16. 待办 / 需要拍板

- **1.3.4—1.5.0 的改动还没提交。** 仓库当前只有一个基线提交
  `530516c baseline: 1.3.3 (54) before 1.3.4 bug fixes`，后续改动仍在工作区。
  接手第一件事：`git add -A && git commit`。
- **两端的备份文件格式不通用**（§6.1）。同一端备份恢复是好的，跨端不支持。
  要做需要在 `core` 里定义一份共用的 backup payload 模型——是一块独立的活。
- **明暗模式已在两端对齐。** Android 从 1.3.5 起也持久化/备份
  `JerreaderAppearanceMode`，并在设置页提供跟随系统、白天、黑夜三档。
- **注音选区没有自动化覆盖**（§11.1）——缺 `UITestRubyBook.epub` 夹具。
  把夹具加进仓库就能解锁 9 个 UI 测试。
- **iOS bundle id 还是 `com.example.Jerreader`**。改是对的，但改就丢数据，
  需要一次带迁移的版本（§12.3）。
- **git 历史很浅、没有远端**。建议每个版本至少一个提交，并考虑加一个私有远端做异地备份。

---

## 17. 版本历史（工程视角）

| 版本 | 关键工程事件 |
| --- | --- |
| 1.3.0 (51) | 两端合进统一 KMP 仓库；版本号收敛到 `version.properties` |
| 1.3.1 (52) | 修好 `project.pbxproj` 的两处 `TEST_HOST` 与 `PRODUCT_MODULE_NAME`——**在这之前 iOS 测试 target 从来没跑起来过** |
| 1.3.3 (54) | 主题表面色收进 `JerreaderAppPalette`；配色套系落地；`git init` 基线 |
| **1.3.4 (55)** | 用户十条 bug 全修（§10）；备份档名改 `.jerbackup.zip`；翻译服务商自动优先 AI；iOS 明暗模式；横排逐页吸附还给 Readium；选区默认色收进 `ReaderPageBackground` |
| **1.3.5 (56)** | Android 点击坐标改用当前 Readium WebView 的 DPR，修复点中上方/其他句；iOS 逐页模式以用户选择为准并修正代理吸附顺序；Android 补齐明暗模式；发布文档移除本机私有路径 |
| **1.4.0 (57)** | 两端新增待学习/学习中/已掌握/已忽略状态与最近五条原文语境；Room v6 与 SwiftData 轻量迁移保留旧收藏；捆绑 JMdict 常用词离线兜底并公开数据来源；学习页支持状态筛选与修改。 |
| **1.4.1 (58)** | iOS 翻译输入改用保护 marked text 的 UIKit 编辑器；候选确认后再发起翻译；场景草稿可跨学习页分区恢复。 |
| **1.5.0 (59)** | 两端新增日语优先的每日间隔复习闭环与四档评价；学习模块可在设置中独立显示/隐藏；Room v7 与 SwiftData/备份保留复习进度；Android 短按查词直接使用命中脚本词面，消除异步旧选区。 |
| **1.5.1 (60)** | 设置页对齐双端图标、双行文字和学习模块开关；词典来源收进数据与隐私；学习工具默认进入词语查词，句子切回词语时清空输入和旧结果。 |
