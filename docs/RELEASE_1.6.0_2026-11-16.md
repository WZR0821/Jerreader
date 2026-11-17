# Jerreader 1.6.0（68）维护记录 · 2026-11-16

状态：**双端 1.6.0（68）已构建、验收并打包（2026-11-16）。** Android：Release 构建（单元测试、Lint）、两轮模拟器设备测试（含新的 AI 大模型页和一屏断言）与 Release 冒烟全过，正式 APK 已打出；iOS：模拟器 XCTest 与 UI 测试 11-15 全过，Release IPA 11-16 打出。两个包都已复制到桌面。没有真机验证，没有用真实 API Key，没有提交 git。

## 1. 版本与标识

- 双端版本 **1.6.0**，build / versionCode **68**（1.5.0 是 67；统一版 `JerreaderUnified` 发到 66，68 比本机任何一条线发过的包都大）。
- 只改 iOS App 目标的 Debug / Release（`CURRENT_PROJECT_VERSION`、`MARKETING_VERSION`），测试目标仍为 1 / 1.0。Android 在 `androidApp/build.gradle.kts`。
- bundle ID、applicationId、签名证书都没变：Android 沿用原来的签名，可以覆盖安装 1.5.0。

## 2. AI 大模型设置（双端）

参考饭耳重做，iOS 11-14～15 先做，Android 这次照 iOS 移植；双端保存的 JSON 和枚举原始值一致。

- 原来的"AI API"统一改称 **AI 大模型**：设置首页、「翻译与 AI」、学习页翻译服务菜单、操作指南。
- 「设置 → AI 大模型」：顶部是正在用的供应商和模型（"换模型"在已添加的供应商之间切换），下面是已添加的供应商列表。每个供应商可以存多个模型，每个模型单独设置接口格式（OpenAI 兼容 / OpenAI Responses / Claude 兼容；Gemini 原生只留给已经在用它的模型）和思考强度（自动 / 关闭 / 低 / 中 / 高）。
- **添加供应商**不再分国内、海外：Claude、ChatGPT、DeepSeek 排在最前，然后是通义千问、豆包、Kimi、智谱 GLM、MiniMax、Gemini；列表里没有的用"自定义 API"（OpenAI 或 Claude 兼容接口，名称自填）。已添加的预设显示为"已添加"。
- 供应商详情页可以填 API Key、改 API 地址、从服务商拉取模型列表、测试连接。测试连接只测当前服务，不触发备用服务。
- 旧版设置会迁移：原来按服务商保存的 Key、模型和翻译缓存按旧名字找回。
- API Key 只存在本机（iOS 钥匙串、Android Keystore），不进备份、日志或安装包。
- Android 删除了旧的 `ui/AIModelPicker.kt`，iOS 删除了已无人引用的 `Jerreader/App/AIModelPicker.swift` 及其 4 处 pbxproj 引用（改动前的副本留在本机备份目录 `JerreaderBackups/2026-11-15-ai-port-session/`）。

## 3. 设置首页与一屏布局（双端）

- **设置首页**改为 iOS 设置的样式：大标题、品牌卡，分"阅读"（界面主题、默认阅读排版）、"翻译"（翻译与 AI、AI 大模型）、"数据与帮助"（备份与恢复、数据与隐私、操作指南）三组，带彩色图标块和当前取值；AI 大模型没有 Key 而翻译又依赖它时，行尾显示橙色提示。底部是版本和署名。
- **设置首页和「学习 → 翻译」页刚好一屏**：内容按屏幕高度分配，不上下滑动，也不在标签栏上方留空。实现是 iOS `OneScreenLayout.swift` 的 `OneScreenPage` / `FillStack`，Android 对应 `ui/OneScreenLayout.kt` 的 `OneScreenPage` / `FillColumn`：屏幕高时多出的高度分给标了 `stretch` 的行；屏幕矮时先让标了 `shrinks` 的项退一步（例如服务说明缩成一行），再由 `squeezes` 的项（翻译卡的译文区）让出剩下的差额。字号很大、屏幕很矮或键盘弹出时放不下，就保持原高度、照常滚动。
- **翻译页**（Android 这次照 iOS `TranslateToolView.swift` 重排）：
  - 自上而下：模式（词语 / 句子）与服务栏 → 翻译卡 → 错误提示 → 服务说明（一行，省略号）。
  - 翻译卡里：语言栏；输入区铺满整块（词语单行；句子 3～10 行）；译文区。译文、句子结构分析和词典补充在译文区里单独滚动，"复制""收藏"钉在译文区底部。"加入生词本""AI 结构分析"移到译文下方。
  - 服务说明按当前服务显示，例如"AI 大模型 · Claude · <模型>；可在「设置 → AI 大模型」换模型或添加供应商。"
- 其余界面打磨：分组卡片、分隔线、行高与间距统一到 iOS 系统样式（Android 用 `AISettingsComponents.kt` 的同一套组件）。

## 4. 验证状态

Android（2026-11-16，日志都在 `.build/SafeValidationLogs/`）：

- `20261116-115457-pke6tsul`（`JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh`）：**通过**，`BUILD SUCCESSFUL in 11m 42s`。这是安卓移植代码的第一次编译，一次通过。
  - `:shared:testAndroidHostTest` 81 项、`:androidApp:testDebugUnitTest` 89 项（含新增的 `AIProviderSettingsTest`、`AIModelCatalogServiceTest`），都是 0 失败。
  - `lintDebug` / `lintRelease`：各 0 错误、6 警告、1 提示。1.5.0 留下的 4 项照旧；新增 2 项都在 `ui/AISettingsComponents.kt`：页边距用了 `Configuration.screenWidthDp`（ConfigurationScreenWidthHeight），以及一个 `modifier` 参数位置（ModifierParameter）；提示是 `OneScreenLayout.kt` 的 `FillDataElement` 没写布局检查器属性。都不影响行为，改动留到下一版，以免包和源码对不上。
  - Kotlin 编译没有新增警告（`LearningScreen.kt` 的 "Expression is unused" 是原有的那行 `Unit`）。
- `20261116-120953-7f1suill`（设备测试，范围 `full`，带 Release 冒烟）：**通过**，约 12 分钟。模拟器 `JerreaderQA_API35_20261007`（API 35，x86_64，1206×2622、480 dpi），负载最高 7.7，没有 ANR。
  - 回归 20 项、界面 5 项全过。`primaryTabsAndLocalModels` 新加了一屏断言：「学习 → 翻译」和设置首页往上滑一次，锚点位置不变；设置首页最底下的署名不滑动就能看见。
  - Release 冒烟：x86_64 Release 包安装启动成功，EPUB、PDF 都能打开并回到书架，三个标签都能打开。
  - 截图核对过：翻译页和设置首页都刚好一屏，标签栏上方没有空白；设置首页底部显示 "Jerreader 1.6.0"；「翻译与 AI」页正常。
- 发现设备测试脚本 `scripts/android_device_validation.py` 的测试清单是写死的，新写的 `translation.AIProviderSettingsStoreTest`（7 项：新安装沿用原来的缓存键、旧设置连同 Key 和缓存迁移、备份带供应商不带 Key、旧备份恢复正在用的供应商、每种接口格式的请求、格式和思考强度参与缓存键、增删供应商时 Key 跟着走）和界面测试 `aiModelsProvidersAndModels` 都不在里面，第一轮没跑。已把两者加进清单（改前备份 `android_device_validation.py.before-ai-tests`）。
- `20261116-122211-klrkoaxt`（设备测试，范围 `full`，不带 Release 冒烟，APK 没变）：**通过**，约 10 分钟，负载最高 7.4，没有 ANR。回归 27 项、界面 6 项全过。
  - AI 大模型页截图核对过：首页（正在用的模型、换模型、供应商列表、未填 Key 的橙色提示）、添加供应商（预设 / 自定义，Claude、ChatGPT、DeepSeek 在最前）、新增 Claude 并测试连接、换模型、供应商详情、模型详情（接口格式、思考强度、测试连接、设为正在用、删除）。请求全部是 Mock，Key 只在内存里。
- Android 正式包：`scripts/package_release_apk.py` 通过，`dist/Android/Jerreader-Android-1.6.0-build68-20261116-123313-release.apk`，42,137,219 字节（1.5.0 是 42,013,847），SHA-256 `ba7810b84abd20e3a67dae2471c819b0f91f1d81734c9ba52f467d71ec28e8dd`。badging：versionCode 68、versionName 1.6.0、compileSdk 37、minSdk 24、targetSdk 36、只有 arm64-v8a。与模拟器验过的 x86_64 包除原生库外完全相同；签名与 1.5.0 一致，可覆盖安装；10,673 个类无重复，不含调试夹具。已复制到桌面。
  - 注意：这台 Mac 的 `/usr/bin/java` 找不到 Java 运行时，`apksigner` 会直接报错；打包时给这一条命令临时设了 `JAVA_HOME` / `PATH` 指向仓库的 `.tools/jdk17`。

iOS：

- 2026-11-14 `20261114-231137-fsvd6euv`（host-test）：**通过**，含 `ai-providers` 和 `parity-fixtures` 两组。
- 2026-11-15 `20261115-134454-lkvkaxh0`（test，模拟器）：**通过**，XCTest 264 通过、3 跳过、0 失败（跳过的 3 项照旧要外部真实书籍）。
- 2026-11-15 `20261115-140348-wruedtf4`（ui-test）：**通过**，15 通过、11 跳过、0 失败（跳过的照旧是 iPad 专用 1 项和要真实注音 EPUB 的 10 项）。新增的一屏和 AI 大模型页 UI 测试在里面；`20261115-142113-tj3s_x_m` 又单独跑了一次 AI 大模型页测试，1 通过。截图核对过。
- 以上三次在升版本和删 `AIModelPicker.swift` 之前。删除前 grep 过，除自身外没有任何引用。
- 2026-11-16 `20261116-123240-a5_5etws`（`scripts/background_build.py submit ipa`，Release，generic iOS device，不签名，在 launchd 后台域跑）：**通过**（`** BUILD SUCCEEDED **`），约 7 分钟，负载最高约 4。没有源码警告；日志里 23 条 `.pcm: No such file or directory` 是 dsymutil 找模块缓存时的警告，另有 1 条原有的 scheme 构建顺序警告。Xcode 清掉了旧的 `AIModelPicker.o`，说明删除生效。
- iOS 正式包：`dist/iOS/Jerreader-1.6.0-build68-20261116-123923-unsigned.ipa`，14,332,199 字节（1.5.0 是 13,973,715），SHA-256 `5096e37f048c8edf039c928cd9fca8edb2c6bea9fd5571f7ff84887f83270e53`。bundle ID `com.raydon.Jerreader`、CFBundleShortVersionString 1.6.0、CFBundleVersion 68、最低 iOS 18.0、只有 arm64、不含测试夹具。已复制到桌面。

## 5. 没验证的

- 双端都没有真机验证；用户手机 430×932 比模拟器高，一屏效果在真机上还要看一眼。
- 真实 API Key 和真实服务商接口没有参与任何验证（测试都用 Mock）。
- 新加的几家供应商（通义千问、豆包、智谱、MiniMax）没有连过真实接口。
- 没提交 git。
