# Android M5：阅读器整合、点按翻译与 iOS 视觉对齐

本阶段把此前只做了服务层、没有接入界面的能力全部接进 `ReaderActivity`，并把 Android 的视觉令牌换成 iOS `JerreaderTheme` 的同一套配色。

## 进入本阶段时的实际状态

工作树里已有翻译、学习、设置、PDF、DOCX/TXT 的实现文件，但 `ReaderActivity` 仍停留在 M4：`:androidApp:compileDebugKotlin` 失败（`ReaderToolbar` 缺 11 个参数、`EXTRA_SOURCE_FORMAT` 未定义、`ReaderThemeOption.COOL_GRAY` 分支缺失）。也就是说这些能力当时既不能编译也不能验收。

## 本阶段完成的接线

- `ReaderActivity` 重写：新工具栏（上一页/下一页、上一章/下一章、书签星标、进度滑杆）、`ReaderNavigationPanel`（目录 + Readium 全文搜索 + 书签 + 划线批注）、`ReaderAppearancePanel`（字号、行距、段间距、页边距、字体、四主题、自定义背景/选区色、分页/滚动、文字方向、PDF 论文双栏）。
- 阅读设置改为 `StoredReaderPreferences` 编解码，Readium 偏好与 Jerreader 专有字段（自定义色、论文模式）一起按书持久化；`ReaderThemeOption.COOL_GRAY` 由 `ReaderPreferencesMapper` 映射为背景色而不是丢失。
- 点按翻译（`ReaderTapTranslationController`）、原生选区菜单「翻译 / 查词 / 划线批注」、译文卡（复制、收藏、AI 解析、添加笔记、打开翻译设置）全部接入。
- PDF 走 `PdfNavigatorFragment` + `PdfTapTranslationController`（ML Kit 识别页面文字后点句/点段翻译），入口由 `EXTRA_SOURCE_FORMAT` 决定。
- 书签、划线批注写入 Room（`ReaderRecordRepository`），阅读进度与累计阅读时长写回书架。
- 边到边窗口内边距：工具栏自行让出状态栏，Navigator 停在手势条上方。

## 修复的两个真实缺陷

1. **点按翻译在真机上完全无效**：Readium 的 `TapEvent.point` 是 Navigator 视图像素，而注入脚本用的 `caretRangeFromPoint` 是 CSS 像素。模拟器实测 `dpr = 2.75`、`innerWidth = 393`，未换算时坐标落在视口外，命不中任何文本节点，因此点按后什么都不发生。已在 `ReaderTapTranslationController` 和 `ReaderWordInteractionController` 的输入监听里按 `displayMetrics.density` 换算。
   既有设备测试只用 `getBoundingClientRect()` 得到的 CSS 坐标直接调用内部方法，绕过了这一步，所以一直是绿的。本阶段新增 `translateAtDevicePointForTesting`，用设备像素走同一条真实链路断言译文，防止回归。
2. **默认翻译服务需要 API Key**：iOS 默认是免 Key 的 Apple 翻译，Android 却默认 `DIRECT_API`（DeepSeek），全新安装点按翻译只会提示去配置。已把 `TranslationPreferences.providerMode` 默认值和 `AndroidTranslationSettingsStore` 的读取默认值统一改为 `ON_DEVICE`（ML Kit 本机模型），与 iOS 的「默认不需要 API Key」一致。

## 视觉对齐

新增 `shared/ui/JerreaderTheme.kt`，把 iOS `JerreaderTheme.swift` 的 accent RGB、canvas/paper/raisedPaper/mutedSurface 计算式、line 与 accentFill 透明度、22pt 卡片圆角照搬过来，并据此生成完整 Material3 `ColorScheme`。此前只覆盖了 `primary`/`background`/`surface`，其余槽位仍是 Material 默认紫色，书架筛选条、底部导航和封面占位都是紫的。主题选项也改为 iOS 的同五种：海蓝、森林、紫藤、琥珀、莓红。

## 验证

- `:shared:testAndroidHostTest` + `:androidApp:testDebugUnitTest`：全部通过。
- `:androidApp:connectedDebugAndroidTest`：JerreaderM0_API36（API 36 x86_64）13 项通过、0 跳过、0 失败，含导入不变性、目录/偏好/Locator 恢复、英日点按查词、点句/点段翻译，以及新增的设备像素点按用例。
- 真机链路人工验证：合成 EPUB 经 SAF 导入 → 打开阅读器 → 点按正文 → 临时高亮所点句子 → 底部「点按翻译」卡显示 `Then she opened a book.` → `然后她开了一本书。`，服务标记「Android 本机翻译」，未配置任何 API Key。

## 已知未完成项

- 备份中心（SAF 目录授权 + 版本归档 + 保留策略）尚未实现。
- 划线批注已持久化并可在导航面板跳转，但尚未用 Readium Decoration 在正文上绘制可见高亮。
- 设置中的「操作指南」页尚未移植。
- 跨页句段扩展（EPUB 章节内补取上下文 / PDF 前后页）尚未移植。
- 竖排、ruby 注音的真书选区矩阵仍需真实日文 EPUB 的设备 QA。
- Gradle 在 `--no-daemon` 下首次 `assembleDebug` 偶发 Kotlin BTAPI 编译失败、重跑即过；使用 Gradle 守护进程构建可规避。
