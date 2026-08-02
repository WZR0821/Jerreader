# Jerreader Android 功能对照基线

基线为 iOS 1.0.2（内部 build 43）已开启的功能。“对齐”表示用户结果、数据和错误语义一致，不要求两端系统控件逐像素一致。状态列写实际验收结果，不写“底层已有代码”。

| 能力 | iOS 实现 | Android 实现 | 当前状态 |
| --- | --- | --- | --- |
| EPUB/PDF/DOCX/TXT 导入 | FileImporter/外部打开 | Storage Access Framework | 已完成。四种格式共用一条导入链路，DOCX/TXT 本机转成 EPUB 副本 |
| 导入文件不变 | SHA-256 + mtime 回归 | SHA-256 + mtime 回归 | 已完成。源文件与 App 内副本在导入、阅读、点按、退出后 bytes 与 mtime 不变，设备测试断言 |
| EPUB 阅读 | Readium Swift | Readium Kotlin 3.3.0 | 已完成。分层目录、翻页/翻章、Locator 跨会话恢复、进度滑杆、阅读时长 |
| PDF 阅读 | Readium/PDFKit | Readium/PDFium | 已接入产品入口，可打开与翻页；未做真书大规模 QA |
| 阅读排版 | 字体/字号/行距/段距/边距/主题/背景/方向 | 同项，按书持久化 | 已完成，含冷灰主题、自定义背景与选区色、分页/滚动、原版/横排/竖排 |
| 目录/全文搜索/书签 | Readium Search + SwiftData | Readium Search + Room | 已完成，集中在同一导航面板 |
| 划线/批注/笔记 | Readium Decoration + SwiftData | Readium Decoration + Room | 已完成。五种标记色与 iOS 同源，正文可见高亮，点高亮直接编辑，标记不写回原书 |
| 点按翻译 | 短按句/段，长按智能选区 | 短按句/段，长按原生选区菜单 | 已完成并实机验收，含设备像素→CSS 像素换算回归 |
| 翻译服务 | Apple/直接 API/代理/缓存 | 本机 ML Kit/直接 API/代理/缓存 | 已完成。默认免 Key 本机翻译，GPT/Claude/Kimi/DeepSeek/Gemini/兼容服务与 HTTPS 代理均可配置 |
| AI 句子结构分析 | ContextExplanationService | 同协议 | 已接入译文卡「AI 解析」，需用户自备 AI 配置 |
| 跨页句段扩展 | EPUB 章节内补取 / PDF 前后页 | 同策略 | 已完成。本机补齐可确认句段后只发一次请求，无把握时明确拒绝 |
| 查词 | 中文维基词典 + 系统词典后备 | 内置核心词典 + 中文维基词典 | 已完成，长按选区菜单「查词」与学习页手动查词 |
| PDF OCR | Vision | ML Kit 文字识别（含日文） | 已实现点句/点段识别与论文双栏隔离；真书 QA 未做 |
| 书架 | 分组/系列/标签/搜索/筛选/排序/批量 | 同项 | 已完成 |
| 学习 | 生词本/收藏/历史/导出 | 同项 | 已完成，支持 CSV / Markdown / Anki TSV 导出 |
| 设置 | 主题/默认排版/翻译服务/提示词 | 同项 | 已完成，主题为 iOS 同五色：海蓝、森林、紫藤、琥珀、莓红 |
| 视觉体系 | JerreaderTheme 冷灰蓝 | shared/ui/JerreaderTheme.kt | 已对齐，accent 与 canvas/paper 计算式与 iOS 同源 |
| 操作指南 | AppGuideView | AppGuideScreen | 已完成。章节与文案对齐 iOS，仅去掉 Android 未实现的备份一节 |
| 备份 | 文件夹授权 + 版本归档 | — | 未实现，用户明确要求本轮不做 |
| 语音 | 功能开关关闭 | 保持关闭 | 与 iOS 一致，底层保留不开放 |

每个后续里程碑必须在本表更新实际验收状态。
