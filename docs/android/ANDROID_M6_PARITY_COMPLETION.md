# Android M6：划线高亮、操作指南与跨页句段扩展

本阶段按用户要求把「除备份外」的剩余 iOS 功能原样复刻到 Android。

## 1. 划线批注的正文可见高亮

- 新增 `shared/library/ReaderAnnotationColor.kt`，五种标记色的标识、名称和色值直接取自 iOS `ReadingAnnotationColor`：琥珀、海蓝、薄荷、珊瑚、紫罗兰，透明度也沿用 iOS 的 0.44 / 0.34 / 0.38 / 0.32 / 0.30。此前 Android 只有四色且没有紫罗兰。
- `ReaderActivity` 用独立 Readium Decoration 组 `jerreader-reading-annotations` 绘制标记，和 iOS 的分组名一致；批注新增、修改、删除后立即重绘。
- 通过 `DecorableNavigator.Listener` 监听高亮点击，点正文上的标记直接打开该条批注的编辑器。
- 标记只是 Decoration 覆盖层，不写入 EPUB 正文，也不改文件字节或修改时间。

## 2. 操作指南

- 新增 `shared/ui/AppGuideScreen.kt`，章节顺序、标题与正文对照 iOS `AppGuideView` 复刻：快速开始、如何导入、如何阅读与翻译、功能介绍、日文版式、翻译 API 如何使用（三种方案，含代理请求/响应 JSON 示例）、格式说明。
- 按 Android 实际情况改写了平台相关表述（系统文件选择器、加密存储、本机翻译代替 Apple 翻译）。
- **删去了 iOS 的「备份与重新签名」一节**，因为 Android 没有备份中心；写进指南会构成对不存在功能的承诺。
- 入口在 设置 → 操作指南 → 「打开操作指南」。

## 3. 跨页句段扩展

- 新增 `shared/translation/ReaderCrossPageExpansion.kt`，Kotlin 复刻 iOS 的三个部件：
  - `ReaderSentenceSegmenter`：句界判定。保留 iOS 的两条关键规则 —— 英文不把 `J. K. Rowling`、`U.S.`、`3.14` 切开；日文 `「本当？」と彼は……` 这类引号内终止符后紧跟引用助词时不断句。
  - `ReaderCrossPageContextBuilder`：构建有上限的上下文窗口；相邻页文本只有属于同一章节（或同一资源）时才允许并入。
  - `ReaderCrossPageTranslationResolver`：在上下文中定位选区（取最靠近中间的一处），扩展到完整句子单元，超过 2,000 字符或与原文相同则放弃。
- EPUB 侧：`ReaderTapTranslationController.crossPageContext` 注入脚本读取当前 Readium 文档的基文节点（排除 `rt/rp`），按 1,200–2,000 字符窗口截取。
- PDF 侧：`PdfTapTranslationController.crossPageContext` 用 ML Kit 识别前一页、当前页、后一页，交给同一个共享构建器。
- 译文卡新增「扩展跨页句段」。补齐成功才发起**一次**翻译请求；没有可确认的完整句段时显示「没有找到可确认的完整句段，已保留当前译文。」，不臆造内容、不重复计费。
- 单元测试 `ReaderCrossPageExpansionTest` 覆盖英文跨页补齐、日文跨页补齐、日文引号助词、英文缩写与小数、无上下文时返回空、跨章节文本被拒绝。

## 4. 顺带修复

Lint 报出两处 `StateFlowValueCalledInComposition`（阅读器工具栏的「显示阅读进度」和阅读器主题读取 `StateFlow.value`），会导致在设置里改这两项后阅读器不刷新。已改为 `collectAsState()`。

## 验证

- `:shared:testAndroidHostTest`、`:androidApp:testDebugUnitTest`：全部通过。
- `:androidApp:lintDebug`：0 error（此前 2 error）。
- `:androidApp:connectedDebugAndroidTest`：JerreaderM0_API36 上 13/13 通过、0 跳过、0 失败。
- 实机人工验收（API 36 模拟器）：
  - 长按选词 → 菜单「翻译 / 查词 / 划线/批注」→ 选「海蓝」保存 → 正文出现蓝色高亮，且与点按翻译的临时选区高亮同时正确显示。
  - 点句翻译 → 译文卡出现「AI 解析 / 添加笔记 / 扩展跨页句段」→ 点扩展，因所点句子本身完整，正确显示「没有找到可确认的完整句段，已保留当前译文。」
  - 设置 → 操作指南 → 打开，章节与文案渲染正常。

## 仍未完成

- 备份中心（按用户要求本轮不做）。
- PDF 与 OCR 只在合成用例上验过；竖排、ruby 注音的真书选区矩阵仍需真实日文 EPUB 的设备 QA。

---

# Android M7：UI 重构、阅读器版式修复与点按翻译对齐

针对用户反馈的六项问题重做。

## 1. 视觉体系重构

- `JerreaderTheme` 增加显式排版比例（headlineSmall 20sp 到 labelSmall 11sp）。此前用 Material 默认字号，在手机上普遍偏大，是设置页显得松散混乱的主因。
- 新增 `JerreaderComponents`：`JerreaderCard`（纸面 + 1dp 描边 + 18dp 圆角）、`JerreaderSection`、`JerreaderRow`、`JerreaderSegmented`（紧凑分段控件）、`JerreaderStepper`。此前每个选项都是填充/文字按钮对，看上去全是主操作，且没有边界。
- 设置页改用统一分区；删掉了重复出现两次的「操作指南」卡片；关于页改为标签值对。

## 2. EPUB 横排 / 竖排 / 分页

根因是 `ReaderAppearancePanel` 是不可滚动的 Column，**「文字方向」和 PDF 双栏开关落在屏幕外，根本点不到**。面板现在可滚动并分成「版式 / 翻页与方向 / 主题与配色」三区。实机验证：分页、滚动、原书、横排、竖排均生效，竖排时 Readium 正确切换为纵向书写与从右向左分栏，划线高亮跟随。

面板嵌进设置页时使用 `embedded = true`，交给外层页面滚动，避免嵌套滚动导致内容被截断。

## 3. 悬浮译文卡

译文卡与词卡不再是遮住半屏的底部对话框，改为阅读器内的悬浮层：根据选区矩形选择放在其上方或下方（取空间更大的一侧），宽度留边、最高占屏 46%，卡内滚动，其余区域仍可看到原文。过程中修掉一个布局缺陷 —— `padding` 写在 `heightIn` 之前会把可用高度吃掉，导致卡片被压扁到只剩标题。

## 4. 书架卡片

书名、作者、进度分行显示，三个并排文字按钮（管理/封面/删除）改为「⋯」溢出菜单，修掉「删除」被挤成两行的错乱。筛选条拆成「格式」和「排序 + 文件夹」两行，排序不再被挤出屏幕。底部导航去掉了「书/学/设」伪图标与下方重复文字。

## 5. 二级界面返回

书架、学习、设置和操作指南都是 Compose 状态而非 Activity，系统返回键此前直接退到桌面。加入 `BackHandler`：指南 → 设置，学习/设置 → 书架，书架 → 退出。

## 6. 阅读器导航栏移到底部

顶栏只保留「返回 / 书名 / 书签」，其余控制移到底部栏：进度滑杆 + 目录 / 上一章 / 上一页 / 下一页 / 下一章 / Aa，与 iOS 的正文优先、控制在下的布局一致。

## 7. 点按翻译按 iOS 1:1 补齐

- **请求 UUID 防迟到**：每次请求带 token，超期结果不再覆盖当前卡片。
- **语义去重**：身份 = 章节 href + 规范化文本 + 语言对 + 服务模式；同一句正在翻译或已成功时不再发起请求。
- **30 秒看门狗**：超时给出明确文案而不是无限转圈。
- **手动重试冷却**：同一段文字 5 秒内只允许一次手动重试；冷却键不含供应商，切换备用服务不能绕过。成功卡片也可「重新翻译」。
- **触感反馈**：完成时按设置项决定是否震动。
- **加入生词本**：选中单个词并完成翻译后，卡片提供「加入生词本」，先查真实词典再写入学习记录。

Android 长按走的是系统 ActionMode 菜单，没有 iOS 那样的连续选区回调，因此 iOS 的 170ms/280ms 选区稳定去抖在这里没有对应物，未实现。

## 验证

- `:shared:testAndroidHostTest`、`:androidApp:testDebugUnitTest` 全部通过；`:androidApp:lintDebug` 0 error；`:androidApp:connectedDebugAndroidTest` 13/13 通过。
- 实机逐项验证：书架卡片与筛选、设置页分区与滚动、阅读器底部导航栏、外观面板滚动与横排/竖排/分页、悬浮译文卡完整动作行、返回键从设置回书架再退出。
