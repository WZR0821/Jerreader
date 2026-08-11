# 读鼠 Jerreader · 代码维护手册

适用版本：**1.3.0 (51)** 起（当前 1.5.1 (60)）。
本仓库是**唯一**需要维护的仓库。

> 刚接手这个项目？先读 [HANDOVER.md](HANDOVER.md)——那是全局入口，
> 讲产品是什么、功能落在哪个文件、怎么从零跑起来。本文是**日常改代码的规矩**。

---

## 0. 一句话规矩

> 改任何东西之前，先问：**这是"决定"还是"画法"？**
> 决定进 `core/`（两端共用，有测试）；画法进 `ui/`（Android）或 `iosApp/`（iOS）。

历史上两端漂移的每一处，追根到底都是同一个"决定"被写了两遍——
选区怎么合并、卡片放哪、颜色怎么算。写两遍就一定会在第一个 bug 修复时分家。

---

## 1. 仓库结构

```
JerreaderUnified/
├── version.properties        ← 两端唯一的版本号来源（见 §4）
├── keystore.properties       ← 安卓签名，不进版本库（见 §6）
├── settings.gradle.kts       ← :core :ui :androidApp
├── build.gradle.kts          ← 含 syncIosVersion 任务
├── gradle/libs.versions.toml ← 所有依赖版本
│
├── core/     纯 Kotlin，**没有任何 UI 框架**。阅读器的全部"决定"。
│             targets: android, iosArm64, iosSimulatorArm64, iosX64
│             com.jerreader.unified.{domain,library,lexical,translation,reader}
│
├── ui/       Compose Multiplatform。Android 侧的全部界面 + 阅读器浮层。
│             targets: android, iosArm64, iosSimulatorArm64
│             com.jerreader.unified.ui        ← 页面与组件（主题、书架、学习、设置、译文卡片）
│             com.jerreader.unified.reader.ui ← 选区高亮层、译文卡片定位层
│
├── androidApp/  Android 宿主：Activity、Room、Readium Kotlin toolkit、
│                ML Kit、备份、文件导入。包名 com.jerreader.android
│
└── iosApp/   完整 iOS App（SwiftUI）+ Readium swift-toolkit。
    ├── Version.xcconfig      ← 由 version.properties 生成，别手改
    ├── Jerreader/            ← 应用源码
    ├── JerreaderTests/       ← 单元测试
    ├── JerreaderUITests/     ← UI 测试
    └── Scripts/
        └── build-shared-framework.sh  ← Xcode 构建阶段自动跑，产出 JerreaderCore.framework
```

另有 `scripts/release.sh`（仓库根）：一条命令出 IPA + APK，见 §3.5。
两端差异的核对结论在 [PARITY.md](PARITY.md)。

### 为什么 `core` 不许依赖 Compose

Compose Multiplatform 已经不再发布 `iosX64` 产物。带 Compose 的模块在 Intel Mac 上
**连编都编不了**，更别说跑测试。把算法单独拆出来，选区合并、调色、避让求解就能在任何
一台开发机上跑 Kotlin/Native 测试——也意味着 iOS 侧今天就能从 SwiftUI 直接用上 `core`，
不必先整体切到 Compose UI。

**推论**：任何用到 `androidx.compose.ui.unit.Dp`、`Color`、`@Composable` 的东西都进不了
`core`。想共享一段带 `Dp` 的算术（比如译文卡片表头的排布），它属于 `ui`。

### 为什么阅读内核留在原生

Readium 的 Kotlin 与 Swift toolkit 是两个成熟的 EPUB 引擎，各自紧跟本平台 WebView。
用公共代码重写只会是倒退。搬进公共代码的是引擎**之上**的部分——选什么、画什么、卡片放哪。

---

## 2. 什么代码放哪

| 你要改的东西 | 放哪 | 例子 |
|---|---|---|
| 选区矩形怎么合并、写作方向怎么分组 | `core/reader/selection/` | `ReaderSelectionRectMerger` |
| 选区/朗读高亮用什么颜色、描边多粗、圆角多大 | `core/reader/selection/` | `ReaderSelectionVisualStyle`、`ReaderSelectionHighlightMetrics` |
| 译文卡片避开正文放哪 | `core/reader/overlay/` | `ReaderOverlayPlacement` |
| 竖排翻页步长、列格线 | `core/reader/paging/` | `ReaderVerticalPageStep`、`ReaderVerticalColumnGrid` |
| 跨页句子怎么补齐 | `core/translation/` | `ReaderCrossPageExpansion` |
| 备份策略、档案里带什么 | `core/library/` | `LibraryBackupPolicy`、`LibraryBackupProfile` |
| 词形还原、词典查询协议、间隔复习规则 | `core/lexical/` | `WordMorphologyAnalyzer`、`VocabularyReviewScheduler` |
| **两端都要显示的同一句话、同一个数字口径** | `core/design/` | `JerreaderCopy`（文案 + 相对时间 + 时长格式） |
| Android 的页面、组件、主题 | `ui/` | `JerreaderTheme.kt`、`JerreaderLibraryApp.kt` |
| Android 的 Activity / Room / Readium 适配 | `androidApp/` | `ReaderActivity.kt` |
| iOS 的页面与组件 | `iosApp/Jerreader/` | `LibraryView.swift` |
| iOS 与 Kotlin 之间的类型摆渡 | `iosApp/Jerreader/Core/Shared/` | `JerreaderCoreBridge.swift`（**只转换，不做决定**） |

### 一条硬规矩

**用户能看见的同一句话，只允许写在一处。**
`core/design/JerreaderCopy.kt` 是那一处。Compose 直接读它；Swift 通过
`JerreaderCore.framework` 读同一份常量。iOS 上的写法是：

```swift
// 文件顶部写一次。必须是计算属性，不能是 let —— 原因见 §8。
private var copy: JerreaderCopy { JerreaderCopy.shared }

Text(copy.libraryTitle)                          // 不要写 Text("书架")
```

Compose 侧：

```kotlin
import com.jerreader.unified.design.JerreaderCopy
Text(JerreaderCopy.libraryTitle)                 // 不要写 Text("书架")
```

历史教训：书架页的标题在 iOS 上叫「书架」，在 Android 上叫「书库」，
差了好几个版本没人发现——因为它们是两个字符串字面量。
同类的还有九处，全部列在 [PARITY.md](PARITY.md) §1.1。

**数字口径也算文案。** 「平均进度」iOS 四舍五入、Android 截断，
「累计阅读」iOS 会说「1小时30分」、Android 只会说「1 小时」——
这类格式化函数同样只写在 `JerreaderCopy` 里。

只在一端存在的文案（比如 Android 的格式筛选 chip）就留在那一端，
但要在 PARITY.md §2 里记一笔，说明它是**有意**的单端功能，不是漏对齐。

---

## 3. 构建与测试

### 3.1 环境（本机固定的两条）

```bash
export JAVA_HOME="<JDK 17 路径>/Contents/Home"
export DEVELOPER_DIR="<Xcode.app 路径>/Contents/Developer"
export ANDROID_HOME="<Android SDK 路径>"
```

- `JAVA_HOME` **必须带 `Contents/Home`**，直接指向 macOS 的 JDK 包根目录会报
  `invalid directory`。
- 如果 `xcode-select` 指向 CommandLineTools，每条 `xcodebuild` 都要显式设置
  `DEVELOPER_DIR`。
- Android SDK 可由 `ANDROID_HOME` 或不入库的 `local.properties` 提供。

### 3.2 公共算法测试（必跑，两端各跑一遍）

```bash
./gradlew :core:testAndroidHostTest :core:iosX64Test
```

**两个都要跑，不能只跑一个。** commonTest 里一个反引号用例名带了逗号，JVM 编得过，
Kotlin/Native 报 `Name contains illegal characters: ","`——共用代码只在一端验过就不算验过。
`iosX64Test` 的链接步骤需要 `DEVELOPER_DIR`，否则 `xcrun xcodebuild -version` 就挂。

### 3.3 Android

```bash
./gradlew :androidApp:testDebugUnitTest        # 单元
./gradlew :androidApp:assembleDebug            # 出 debug APK
./gradlew :androidApp:assembleRelease          # 出可安装的 release APK
```

设备验收（改阅读器后**必须**跑，这是 Android 侧唯一能真正验证 Readium 行为的手段）：

```bash
# 无头起模拟器
"${ANDROID_HOME}/emulator/emulator" -avd JerreaderM0_API36 \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
./gradlew :androidApp:connectedDebugAndroidTest
```

### 3.4 iOS

```bash
cd iosApp
xcodebuild -project JerreaderUnified.xcodeproj -scheme JerreaderUnified \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO test
```

`CODE_SIGNING_ALLOWED=NO` 不是可选的：仓库在 iCloud 同步的 Documents 下，文件供应器会
不断补 `com.apple.FinderInfo` 扩展属性，ad-hoc codesign 会报
"resource fork, Finder information, or similar detritus not allowed"。

Xcode 工程用 `PBXFileSystemSynchronizedRootGroup`（objectVersion 77），
**加删源文件不需要改 pbxproj**，放进目录就行。

### 3.5 出包（一条命令出两个产物）

```bash
./scripts/release.sh
```

只想出一个平台时：`./scripts/release.sh --android-only` 或 `--ios-only`。

它做四件事，顺序不能换：

1. `./gradlew syncIosVersion` —— 把 `version.properties` 写进 `iosApp/Version.xcconfig`；
   **必须在 xcodebuild 之前**，因为 xcconfig 是构建开始时读的，构建中途改它不生效；
2. `:core:testAndroidHostTest` + `:androidApp:testDebugUnitTest`，然后
   `:androidApp:assembleRelease` 出 APK；
3. `xcodebuild -sdk iphoneos -configuration Release` 出 `Jerreader.app`，
   放进 `Payload/` 压成未签名 IPA；
4. 两个产物都 `cp` 到 `~/Desktop`，文件名带版本与 build 号。

**产物一定要复制到 `~/Desktop`。** 只放在工程 `dist/` 里找不到，而且工程路径以前含中文目录时
文件查看器读不了。

---

## 4. 版本号

**唯一真源是仓库根的 `version.properties`。**

```properties
versionName=1.3.0
versionCode=51
```

- Android：`androidApp/build.gradle.kts` 直接读它 → `versionName` / `versionCode`。
- iOS：`./gradlew syncIosVersion` 生成 `iosApp/Version.xcconfig`，
  Xcode 工程的**项目级** Debug/Release 以它为 `baseConfigurationReference`
  → `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`。
  各 target 里**不再写**这两个键——写了就会盖掉 xcconfig，这正是以前两端漂移的机制。

发版流程：改 `version.properties` 两行 → 跑 `./scripts/release.sh`。不要在别处改版本号。

历史包袱：1.3.0 之前 iOS 走到 1.2.4 (50)、Android 停在 1.1.2 (47)、统一版外壳是 0.1.2 (3)。
1.3.0 (51) 把三条线并成一条，build 号从 iOS 的 50 接着往下走。

---

## 5. 身份标识（改动前务必看）

| | 值 | 改了会怎样 |
|---|---|---|
| Android `applicationId` | `com.jerreader.android` | 改了 = 装出第二个空 app，用户书库和进度留在旧的里 |
| Android 签名 | debug key（见 §6） | 换 release key = 签名不一致，必须先卸载，本地书库丢失 |
| iOS `PRODUCT_BUNDLE_IDENTIFIER` | `com.example.Jerreader` | 同上；1.3.0 起统一版沿用**原版**的 bundle id，所以它是原版的升级包 |
| iOS 产物名 | `Jerreader.app` / `Jerreader-<版本>-<build>.ipa` | target 仍叫 `JerreaderUnified`，靠 `PRODUCT_NAME = Jerreader` 定名 |
| iOS 显示名 | `Jerreader`，简体中文环境 `读鼠`（`InfoPlist.xcstrings`） | 仅改文案不会改变数据容器 |
| Android 显示名 | `Jerreader`，简体中文环境 `读鼠`（`values-zh-rCN`） | 仅改文案不会改变数据容器 |
| Room 数据库 schema 目录 | `androidApp/schemas/com.jerreader.android.data.JerreaderDatabase/` | 目录名跟着包名走，别动包名 |

两端显示名已经对齐：工程名、包名和应用内文案使用 `Jerreader`，仅简体中文系统桌面
显示名使用「读鼠」。见 [PARITY.md](PARITY.md) §3。

---

## 6. 签名

`keystore.properties`（仓库根，不进版本库）：

```properties
storeFile=<debug.keystore 的绝对路径>
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
```

文件不存在时 release 仍然不签名，Android 会拒绝安装。

**为什么用 debug key**：用户手机上已装的历史版本都是 debug key 签的
（cert SHA-256 `0d8262ce…`）。换成 release key 会签名不一致，必须先卸载旧版、本地书库数据丢失。
独立 release keystore 应保存在仓库外的安全位置（alias `jerreader`），**只有要上架/正式
分发时才切**，并且要明确告诉用户"先卸载"。

验签：

```bash
"${ANDROID_HOME}/build-tools/<版本>/apksigner" verify --print-certs <apk>
```

iOS 出的是**未签名 IPA**，用 AltStore / Sideloadly 之类自签安装。

---

## 7. 两端各自的边界（别想着"顺手统一"的部分）

| 能力 | iOS | Android | 能统一吗 |
|---|---|---|---|
| 本机翻译 | Apple 系统翻译（`Translation` framework） | ML Kit on-device | **不能**，是两套系统能力 |
| 密钥存储 | 钥匙串 | Android Keystore | **不能** |
| 备份文件夹授权 | security-scoped bookmark | SAF persisted grant | **不能**，都是按项授权、跟设备走 |
| 数据库 | SwiftData | Room | 不值得，各自都很稳 |
| EPUB 引擎 | Readium swift-toolkit | Readium Kotlin toolkit | **不该**，见 §1 |
| 竖排翻页 | 强制滚动模式 + 自己算列格线 | CSS 多列分页，天然不劈列 | 规则共用（`core`），实现各自 |

写在 `core` 里的规则两端共用；上表这些是**实现**层面的合理分歧，不是漂移。

---

## 8. 已知的坑

### Gradle / Android

- `./gradlew --no-daemon assembleDebug` 首次会偶发 Kotlin BTAPI「Compilation error」
  但没有任何 `e:` 行，重跑即过。**用守护进程（不加 `--no-daemon`）可规避**。
- 偶尔出现 `compile_and_runtime_r_class_jar/.../R.jar` 缺失，
  删掉 `androidApp/build/intermediates/compile_and_runtime_r_class_jar` 后重跑。
- `dexBuilderDebug` 报 `Type ... is defined multiple times: .../AndroidAppTab 2.class`：
  仓库在 iCloud 同步的 Documents 下，中断的构建会在 `build/intermediates` 里留下
  Finder 式的「文件 2」副本，D8 把它当成第二份同名类。清掉即可，源码里没有重复：
  `find . -name "* 2.class" -path "*/build/*" -delete`
- 单元测试里做 JSON 往返必须把 `org.json:json` 放进 `testImplementation`——
  `unitTests.isReturnDefaultValues = true` 让 android.jar 的 `org.json` 桩返回默认值，
  不换实现根本测不了解析。

### Kotlin/Native + iOS

- `:ui` 的 iOS 侧在 Intel Mac 上**只能编 arm64 设备切片，跑不起来**（Compose 无 iosX64 产物）。
  iOS App 现在链接的是 `:core` 的 `JerreaderCore.framework`，不是 `:ui`。
- `iosSimulatorArm64Test` 在 Intel Mac 上会**静默跳过还报成功**。要验证 iOS 侧的公共代码，
  跑 `:core:iosX64Test`。
- Kotlin 的默认参数**不会**导出到 Objective-C。Swift 调用方要么写全所有参数，
  要么在 Kotlin 侧提供一个命名工厂（见 `ReaderAppearance.forSelection`）。
- **属性名别撞 C/C++ 关键字**。`JerreaderCopy.delete` 在 Swift 侧直接「has no member」——
  Kotlin/Native 生成 ObjC 头文件时把它改名了。已改叫 `deleteAction`。
  同类要小心的还有 `new`、`class`、`template`、`operator`。
- Kotlin 的 `object` 到了 Swift 是个普通 class，**不是 `Sendable`**。
  写 `private let copy = JerreaderCopy.shared` 会被 Swift 6 并发检查拒掉；
  改成计算属性 `private var copy: JerreaderCopy { JerreaderCopy.shared }`。
- Kotlin 的 `Int` 导出成 `Int32`，`Long` 导出成 `Int64`。Swift 调用方要显式转：
  `copy.bookCount(count: Int32(books.count))`。

### 模拟器

- 本机的模拟器点击自动化（System Events + `simctl`）**会整个失效且救不回来**：
  Simulator 进程活着、设备 Booted、`simctl io screenshot` 照常出图，
  但 System Events 报告它有 0 个窗口。重开 / `pkill -9` / `simctl shutdown` /
  杀 `CoreSimulatorService` 四种都没用。
  **那时改走单元测试**——用真实 WKWebView 布局做断言比一对截图能说明的更多。
- 跨模拟器会话的截图**不可严格对拍**：重启前后有 ~8.7% 的亚像素噪声。
  只有同一次会话内采的图能逐像素比。

### 原生模拟器 MCP 工具

要求主机装完整 Xcode 并 `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`。
这条要密码，只能由使用者自己跑。本机 Xcode 在 `~/Downloads/`，所以这套工具用不了。

---

## 9. 改动清单（每次提交前过一遍）

- [ ] 新增的"决定"进了 `core`，并且 `core/src/commonTest` 里有用例
- [ ] `./gradlew :core:testAndroidHostTest :core:iosX64Test` 全绿
- [ ] 用户可见的新文案、新数字格式进了 `core/design/JerreaderCopy.kt`，两端都读它
- [ ] 只在一端做的界面改动，问一句「另一端要不要跟」；答案是"不跟"就写进 PARITY.md §2
- [ ] Android：`:androidApp:testDebugUnitTest` 全绿；动了阅读器则 `connectedDebugAndroidTest` 也跑
- [ ] iOS：`xcodebuild test` 全绿
- [ ] 版本号只改 `version.properties`
- [ ] 两个产物都在 `~/Desktop`

---

## 10. 旧仓库怎么办

旧工程从 1.3.0 起**冻结**，只作为历史归档，不要再往那里提交。它的 Android app
与 `shared` 模块已整树迁入本仓库
（`shared/ui` → `ui/`，`shared` 的算法层与 `core` 合并去重，`androidApp` 原样迁入）。
两处 `shared` 比 `core` 新的实现——`ReaderCrossPageExpansion` 的引号感知断句、
`LibraryBackupProfile`——已经前向移植到 `core`。
