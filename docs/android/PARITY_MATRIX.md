# Jerreader Android 功能对照基线

当前双端源码为 2.0.0（内部版本 71）。1.5.0 至 2.0.0 的改动与实际验收状态见 [2.0.0](../RELEASE_2.0.0_2026-11-19.md)、[1.7.1（只有 Android）](../RELEASE_1.7.1_2026-11-18.md)、[1.7.0](../RELEASE_1.7.0_2026-11-17.md)、[1.6.0](../RELEASE_1.6.0_2026-11-16.md)、[1.5.0](../RELEASE_1.5.0_2026-11-12.md) 的维护记录；1.4.1（60）的字号、精简资源、阅读栏透明度、沉浸阅读和日语注音见 [1.4.1 维护记录](../HOTFIX_1.4.1_2026-10-17.md)，1.4.0（59）见 [1.4.0 记录](../HOTFIX_1.4.0_2026-10-16.md)。以下功能表主要保留较早阶段的历史基线，1.5.0 之后没有逐项重新核对，表中的状态和库版本可能已过时；“对齐”指用户结果与语义一致，不能替代本轮截图或真机验收。

| 能力 | iOS 实现 | Android 实现 | 当前状态 |
| --- | --- | --- | --- |
| EPUB/PDF/DOCX/TXT 导入 | FileImporter/外部打开 | Storage Access Framework | 已完成。四种格式共用一条导入链路，DOCX/TXT 本机转成 EPUB 副本 |
| 导入入口 | 文件/iCloud/其他 App 打开/隔空投送 | SAF + VIEW（含扩展名匹配）+ 分享到读鼠 | 已完成。除 MIME 白名单外新增按扩展名匹配，覆盖只发 `application/octet-stream` 的文件管理器；`ACTION_SEND` 可从浏览器或网盘分享进来 |
| 导入文件不变 | SHA-256 + mtime 回归 | SHA-256 + mtime 回归 | 已完成。源文件与 App 内副本在导入、阅读、点按、退出后 bytes 与 mtime 不变，设备测试断言 |
| EPUB 阅读 | Readium Swift | Readium Kotlin 3.3.0 | 已完成。分层目录、翻页/翻章、Locator 跨会话恢复、进度滑杆、阅读时长 |
| PDF 阅读 | Readium/PDFKit | Readium/PDFium | 已接入产品入口，可打开与翻页；未做真书大规模 QA |
| 阅读排版 | 字体/字号/行距/段距/边距/主题/背景/方向 | 同项，按书持久化 | 已完成，含冷灰主题、自定义背景与选区色、分页/滚动、原版/横排/竖排 |
| 目录/全文搜索/书签 | Readium Search + SwiftData | Readium Search + Room | 已完成，集中在同一导航面板 |
| 划线/批注/笔记 | Readium Decoration + SwiftData | Readium Decoration + Room | 已完成。五种标记色与 iOS 同源，正文可见高亮，点高亮直接编辑，标记不写回原书 |
| 点按翻译 | 短按句/段，长按智能选区 | 短按句/段，长按原生选区菜单 | 已完成并实机验收，含设备像素→CSS 像素换算回归。选区高亮改用 `::selection` 按主题着色，不再用坐标绘制覆盖层，横排竖排均不会重叠或偏移；注音不进选区 |
| 翻译服务 | Apple/直接 API/代理/缓存 | 本机 ML Kit/直接 API/代理/缓存 | 已完成。默认免 Key 本机翻译，GPT/Claude/Kimi/DeepSeek/Gemini/兼容服务与 HTTPS 代理均可配置 |
| AI 套餐（2.0.0） | `ChatGPTPlan*`：NWListener 回环 + ASWebAuthenticationSession，凭据存钥匙串 | `translation/chatgptplan/`：ServerSocket 回环 + 默认浏览器 Custom Tab + 前台服务，凭据存 Keystore | 两端协议、文案和页面一致，JVM / host-test 用 Mock 覆盖登录、刷新、撤销、额度用完和翻译；没有用真实 ChatGPT 账号验证 |
| AI 句子结构分析 | ContextExplanationService | 同协议 | 已接入译文卡「AI 解析」，需用户自备 AI 配置 |
| 跨页句段扩展 | EPUB 章节内补取 / PDF 前后页 | 同策略 | 已完成。本机补齐可确认句段后只发一次请求，无把握时明确拒绝 |
| 查词 | 中文维基词典 + 系统词典后备 | 内置核心词典 + 中文维基词典 | 已完成，长按选区菜单「查词」与学习页手动查词 |
| PDF OCR | Vision | ML Kit 文字识别（含日文） | 已实现点句/点段识别与论文双栏隔离；真书 QA 未做 |
| 书架 | 分组/系列/标签/搜索/筛选/排序/批量 | 同项 | 已完成 |
| 学习 | 翻译工作台/生词本/历史/导出 | 同项 | 已完成。学习页与 iOS 同为「翻译 / 生词本 / 练习与工具 / 历史」，查词并入翻译的词语模式：词语模式限 80 字、使用固定词典提示词，成功后自动补词典形/读音/词性/释义并写入历史；本页可单独选服务，不改全局设置。支持 CSV / Markdown / Anki TSV 导出 |
| 设置 | 主题/默认排版/翻译服务/提示词 | 同项 | 已完成，主题为 iOS 同五色：海蓝、森林、紫藤、琥珀、莓红 |
| 视觉体系 | `JerreaderTheme` + `JerreaderDesignSystem.swift`（「纸墨」） | `shared/ui/JerreaderTheme.kt` + `JerreaderDesignSystem.kt` | 2.0.0 按 [DESIGN_2.0.md](../DESIGN_2.0.md) 重做：颜色、字体、间距、圆角、阴影和动效令牌两端数值相同，书架、阅读器、学习、设置逐页移植，模拟器截图两端逐页对照过（53 对，见 2.0.0 维护记录第 5 节） |
| 操作指南 | AppGuideView | AppGuideScreen | 已完成。章节与文案对齐 iOS，含备份与恢复一节 |
| 备份 | 文件夹授权 + 版本归档 | SAF 目录授权 + zip 归档 | 已实现。四类范围、手动/自动备份、按天数/份数/容量从最旧清理并永远保留最新一份、按名恢复或从任意文件恢复；恢复只做合并。目录授权、API Key、代理凭据与翻译缓存不入包。真机长期自动备份未做时间跨度验收 |
| 语音 | 功能开关关闭 | 保持关闭 | 与 iOS 一致，底层保留不开放 |

每个后续里程碑必须在本表更新实际验收状态。

Android 离线基础词典、语言包管理、UI 与读取优化的实施过程见 [2026-10-06 记录](../ANDROID_OPTIMIZATION_2026-10-06.md)，最终验证、截图对照及交付见 [2026-10-07 验收记录](../VALIDATION_2026-10-07.md)。
