# Android 代码梳理（M4 点按查词前两项）

## 结论

Android M1/M2 的书架、Room 和 Readium 主链路边界清楚，但 `ReaderActivity` 原本同时承担出版物打开、Navigator 生命周期、目录、外观和状态保存，不适合继续把触点命中、分词、词形和词典解析堆入同一个文件。本阶段已把新能力拆到共享领域层和 Android 平台适配层，`ReaderActivity` 只负责编排与呈现。

## 当前分层

- `shared/domain`：跨平台语言与格式枚举。
- `shared/library`：书架模型、仓储协议和共享 UI 状态。
- `shared/translation`：既有句段翻译协议与 Mock；本阶段不接入阅读器。
- `shared/lexical`：新增查词协议、领域模型、输入规则、日英基本形分析、内置基础词典和 Mock。
- `shared/ui`：Compose Multiplatform 书架、阅读工具和新增词语卡；不读取 MediaWiki JSON。
- `androidApp/library`：SAF 导入和不可变出版物存储。
- `androidApp/data`：Room 实现。
- `androidApp/reader`：Readium 生命周期、Android ICU 分词和点按/长按桥。
- `androidApp/lexical`：真实网络适配器、第三方响应解析及“内置核心优先、在线词典后备”的生产组合。

## 本阶段处理的问题

1. 新建 `ReaderWordInteractionController`，隔离 Readium 输入监听、临时 Selection、原生长按菜单和查询状态；没有把第三方词典结构带进 Activity 或 UI。
2. 点击词语优先用 WebView 的 `Intl.Segmenter` 从触点建立 Range，再由 Android ICU `BreakIterator` 校验日英边界；日语仅扩展明确的活用后缀，并处理部分 WebView 把汉字与送假名拆开的情况。
3. 日语基本形以保守候选表达，不把未经词典验证的规则结果标成确定事实；英语区分不规则变化和启发式后缀规则。
4. 真实词典通过可注入 HTTP Client 调用中文维基词典；自动测试使用 Stub。另有小型真实离线核心词典保证常用词和验收词在无网时也有中文释义，Mock 不进入生产链。
5. 修复 Reader 内 Compose 对话框缺少 `ViewTreeLifecycleOwner` 的既有崩溃风险，目录、外观和词卡共用显式生命周期宿主。
6. 点按只建立临时浏览器 Range 和底部卡片，不修改 EPUB DOM、字节或修改时间。

## 明确保留的风险和后续项

- 当前自动验收覆盖无 DRM 的横排可重排 EPUB；复杂 ruby、跨文本节点词语、原版竖排和大量真实 EPUB 的触点矩阵仍需后续真书 QA。
- 内置词典只是断网最低保障，不是完整离线词库；不命中时会访问中文维基词典。大陆网络可达性因运营商而异，后续若要求完全离线，需要评估可再分发的英中、日中词库体积和许可。
- 本阶段没有查询历史、缓存、生词本、发音或 AI 深度解释，也没有扩大 PDF 功能。
- 备份按用户要求未实施；现有 iOS 文件保持冻结。
