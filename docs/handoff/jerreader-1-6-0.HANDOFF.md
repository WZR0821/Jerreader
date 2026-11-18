# Jerreader 1.6.0（68）：安卓移植 AI 大模型界面、双端升版、打包

更新于 2026-11-16 12:50：**已完成**。构建、验收、打包和汇报都做完了，这份文件只留作记录。

## 目标
用户 11-15 原话："依次全部做完，打包完成之后再给我汇报"。
- 把 iOS 的 AI 大模型设置改版、iOS 式设置首页和一屏布局搬到安卓。
- 双端升到 1.6.0 / 68。
- 删掉 iOS `AIModelPicker.swift`。
- 构建验收后打 IPA 和 APK 放桌面。
- 更新文档和备忘。

不提交 git。

## 现在在哪
全部完成，细节和日志编号见 `docs/RELEASE_1.6.0_2026-11-16.md`。
- 交付：
  - `dist/iOS/Jerreader-1.6.0-build68-20261116-123923-unsigned.ipa`
  - `dist/Android/Jerreader-Android-1.6.0-build68-20261116-123313-release.apk`
  - 两个包桌面都有副本，都没装机，也没发布到 SideStore 源。
- README 顶部加了 1.6.0 一行。
- 设备测试脚本 `scripts/android_device_validation.py` 补进了两组新测试（见下）。

## 下一步（都要用户点头）
1. 真机装机看一屏效果。用户手机 430×932，比模拟器高。
2. 用真实 Key 试几家供应商。通义千问、豆包、智谱、MiniMax 没连过真实接口。
3. 是否提交 git，由用户决定。
4. 下一版可顺手处理 lint 新增的 2 个警告，都在 `ui/AISettingsComponents.kt`。
5. 读屏 `ProviderDetailView.swift` 那句矛盾提示仍没改，不在本次范围内。

## 关键文件
- `docs/RELEASE_1.6.0_2026-11-16.md`：改动与验证记录。
- `androidApp/src/main/kotlin/com/jerreader/android/ui/OneScreenLayout.kt`：安卓一屏布局 `OneScreenPage` / `FillColumn`。
- 本机备份目录 `JerreaderBackups/2026-11-15-ai-port-session/`：改动前的文件，包括 pbxproj、build.gradle.kts、README、设备测试脚本、`LearningScreen.kt`，以及两端的 `AIModelPicker`。

## 已经定下来的决定
- 供应商模型放在 androidApp 的 `translation` 包，因为 KMP commonMain 用不了 java.net。
- 翻译卡嵌套 `FillColumn` 的处理：自然高度取 maxIntrinsicHeight，least 默认取 minIntrinsicHeight。FillColumn 的固有高度覆写成 least 之和和自然高度之和，这样外层挤卡片不会挤穿。
- 第二轮模拟器验收不带 Release 冒烟：APK 没变，第一轮已冒烟。

## 坑（反直觉）
- 设备测试的仪器测试清单是写死的。新写的 androidTest 不加进 `REGRESSION_CLASSES` / `UI_CLASSES` 就不会跑，结果照样报通过。
- `apksigner` 要 Java 运行时，`/usr/bin/java` 找不到。打包那一条命令要临时把 `JAVA_HOME` / `PATH` 指向仓库 `.tools/jdk17/Contents/Home`。
- FillColumn 的子项不能是 LazyColumn、BoxWithConstraints、Scaffold 这类要子组合的布局。

## 验证情况
- 安卓（11-16）：
  - Release 构建通过，单元测试 81 + 89，lint 0 错误。
  - 模拟器两轮都通过：第一轮 20 + 5 项加 Release 冒烟，第二轮 27 + 6 项。
  - 一屏断言和 AI 页截图都核对过。
- iOS：
  - 模拟器单元和 UI 测试 11-15 全过。
  - 11-16 Release IPA 构建通过，没有源码警告。
- 双端都没有真机验证，没有用真实 API Key。
