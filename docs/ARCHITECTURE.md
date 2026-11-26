# Jerreader 架构说明

当前源码为 iOS 1.7.0（69）、Android 1.7.1（70），实际验收以 [1.7.0 维护记录](RELEASE_1.7.0_2026-11-17.md) 为准，Android 1.7.1 的两处修复见 [1.7.1 维护记录](RELEASE_1.7.1_2026-11-18.md)，1.5.0、1.6.0 见 [1.5.0](RELEASE_1.5.0_2026-11-12.md)、[1.6.0](RELEASE_1.6.0_2026-11-16.md) 的维护记录；1.4.0（59）的回归修复与当时的维护计划见 [1.4.0 记录](HOTFIX_1.4.0_2026-10-16.md)；较早验收见 [2026-10-07 记录](VALIDATION_2026-10-07.md)，开发回顾见 [开发总结与维护方案](DEVELOPMENT_REVIEW_2026-10-05.md)。下列历史通过记录不代表当前源码的重新验收。

## 当前阶段

本仓库当前完成 Milestone 0、Milestone 1 和 Milestone 2 的可用主流程，以及用户明确指定的阅读设置、AI 翻译、PDF 点击翻译与 OCR、目录/搜索/书签、划线/批注/笔记、AI 句子结构分析、跨页句段扩展、iPad/横屏适配、阅读统计与书籍整理、译文收藏、阅读内加入生词本、P1 学习记录与导出、操作指南、多格式导入和侧载维护备份功能：EPUB/PDF/DOCX/TXT 导入与书架、Readium 阅读器、位置恢复、Apple/直接 API/后端代理翻译、可切换的句子/段落轻点翻译、中英日独立词语/句子翻译工作台、词语模式内的词典详情、日文句子译文本机假名注音、主题适配选区、翻译缓存与失败恢复、历史、生词本/收藏、SwiftData 持久化、可选范围的手动/自动备份与恢复，以及离线测试。

## 分层

- `App`：应用入口、共享 SwiftData 容器、“书架＋学习＋设置”三标签主导航，以及自适应浅色/深色的统一冷色视觉令牌、卡片组件和遵循系统“减少动态效果”的动效令牌。
- `Core/Models`：与具体服务和界面无关的领域模型。
- `Core/Services`：翻译、查词协议，以及当前由功能开关关闭的语音协议。界面只能依赖这些协议。
- `Data/Services`：平台实现、真实联网实现、Mock 实现和本地数据操作。包含 Readium EPUB/PDF 导入与打开、DOCX/TXT 本机重排转换、沙盒文件管理、封面提取、Apple Translation 适配、GPT/Claude/Kimi/DeepSeek/Gemini 官方 API 与开放兼容 API 适配、通用 HTTPS AI 后端代理、翻译缓存、自动重试/备用服务、书签、划线/批注/笔记、学习导出、译文收藏、备份归档/验证/恢复/保留策略、中文维基词典 MediaWiki 适配，以及查词记录的去重与生命周期规则。
- `Features/Library`：SwiftData 驱动的封面书架、阅读统计、进度、最近查看、分类/标签/搜索、书名/作者/最近排序、按格式过滤的文件选择器、外部打开路由、多传输方式说明、重复文件检测、导入状态与删除确认；封面直接打开全屏阅读器。
- `Features/Learning`：中英日翻译、词语模式词典详情、日文假名注音、词语卡片、生词本、译文收藏、查词历史、搜索、复制、收藏管理，以及 CSV/Markdown/Anki TSV 导出。
- `Features/Reader`：Readium Navigator 的 UIKit 容器与 SwiftUI 外壳、目录、全文搜索、书签、划线/批注/笔记、AI 句子结构分析、跨页句段扩展、阅读内生词入口、进度、阅读时长、阅读设置、智能文本选区和翻译覆盖层。
- `JerreaderTests`：不依赖网络和真实密钥的单元测试。

## 已确定的技术决策

- 最低系统版本为 iOS 18，界面采用 SwiftUI。
- EPUB 和 PDF 解析/导航采用 Readium Swift Toolkit 上游提交 `3d8bcdc`（3.8.0 后续的缓存失效修复）；不自行实现排版内核。DOCX 仅解析 Open XML 文本与基本元数据，TXT 进行常用编码检测，两者生成本机 EPUB 副本后统一交给 Readium 排版。
- 句段翻译可选 Apple Translation Framework、用户个人 AI API Key 或自建 HTTPS AI 代理，所有实现都通过 `TranslationService` 隔离，UI 不读取任何供应商的响应结构。
- `TranslationSession` 只在 SwiftUI `translationTask` 生命周期中取得，再包装为 `TranslationService`；视图模型不读取 Apple 响应结构。
- 首次使用语言方向时由系统确认并下载语言包；成功结果按文本规范化值、语言对、提供方和版本生成 SHA-256 缓存键。测试只使用 Mock，不下载语言包或访问外网。
- 2.6 暂时关闭全部发音、听书和跟读界面。既有 `SpeechService`/`AVSpeechSynthesizer` 实现只作为可逆内部代码保留，并由 `SpeechFeatureAvailability.isEnabled == false` 统一隔离；重新开放前必须单独验收，不能影响共用选区或翻译覆盖层。
- 工程、日志和 IPA 不包含预置密钥。用户主动填写的个人 API Key 按服务商分别保存到 Keychain；面向多人公开分发或共享凭据时仍使用后端代理。测试必须可完全离线运行。
- 备份使用带版本清单的 JSON + 实体文件归档，不复制 SwiftData store。范围分为书籍、阅读、学习和非敏感设置；API Key、代理凭据、翻译缓存和文件夹授权书签永不进入归档。v2 对实体文件保存 SHA-256/大小并在恢复前验证，关系记录按目标设备的书籍 UUID 重映射。用户可通过系统文件夹选择器授权 iCloud Drive、“我的 iPhone”或其他文件提供商目录；书签仅保存在当前安装的 UserDefaults，每次列出、创建、恢复、分享、删除或清理该目录中的备份时持有安全作用域，权限失效会显式报错而不回退到 App 本地目录。重装后需再次选择同一文件夹。当前目录中的备份在列表内提供直接“恢复”按钮；每次手动恢复都会重新建立 `UIDocumentPicker` 会话，并在 picker 完全退出后才显示确认框。选择边界接受自定义 UTI、普通归档/数据以及 File Provider 动态 `public.item`，避免 iCloud 元数据刷新后已有备份变灰；实际恢复仍由归档层严格校验 manifest、路径、大小和摘要。自动备份采用 App 生命周期到期补做，并受保存天数、份数和总容量共同约束；它不承诺 App 未运行时准点唤醒。
- 查词和翻译只通过 Sheet 或临时覆盖层呈现，不写入 EPUB 正文。
- 阅读位置以完整 Readium Locator JSON 保存；位置变化后节流落盘，退出阅读器或应用进入后台时立即刷新。
- 书签以独立 `ReadingBookmarkRecord` 保存完整 Locator，不改写 EPUB；目录、搜索和书签共用导航 Sheet。EPUB 搜索委托 Readium Search API，PDF 搜索 PDFKit 文字层。
- 字体、字号、行间距、段间距、页边距、背景和日文排版方向按书保存。为使这些阅读设置确实生效，当前由 Readium 排版引擎应用用户样式（`publisherStyles = false`），不自行修改 EPUB DOM。
- 逐页模式显式使用 Readium 的适配页面、禁止滚动和自动列数/自动 spread 配置。iPhone 通常显示单页，iPad 与横屏可由 Readium 根据可用宽度显示单页或双页；App 不自行计算分页或重写 EPUB 排版引擎。
- Readium `WKWebView` 在逐屏分页中禁止回弹、缩放、纵向 bounce 和 inset 自动调整；固定版面额外禁止平移。重排版 EPUB 保留 Readium 的横向 pan，因为它同时负责逐页手势和原生文本选区。
- 中文、英文和日文是当前唯一的翻译语言集。日文书提供“原版 / 横排”双模式：原版将 `verticalText` 和 `readingProgression` 交还出版物元数据；横排同时设定 `verticalText = false` 和 `readingProgression = .ltr`，因此章节间与章节内都统一从左向右。
- Reader 只把选中文本、Locator JSON 和选区 frame 上报给视图模型。EPUB 翻译入口分三层：短按正文按用户设置用临时 DOM Range 命中所在句子或当前语义块中的一个段落；长按第一次原生选区时利用前后 Readium 文本片段智能补全，单词用 `NLTokenizer`、多词与点句共用句子解析器；同一选区会话中一旦用户拖动手柄改变原始文本，解析器便进入精确模式，后续严格使用系统选区原文，直到点按其他位置、翻页、关闭卡片或位置改变时重置。轻点翻译默认在 Readium 边缘翻页观察者之前消费单击，使一次触摸只会进入翻译命中；该策略不影响横向滑动。用户可在设置中关闭保护，或关闭轻点翻译以恢复左右点按翻页。英文解析先将 EPUB 源码换行投影为等长空格，再在 `NLTokenizer` 上校正人名首字母和可结句的缩写；日文解析只针对引号内问号/叹号后紧跟 `と`、`って` 等引用表达的系统误切合并相邻范围，同时保留真正独立的新句。两者都保持 WebKit UTF-16 高亮偏移不变。PDF 优先用 PDFKit 文字层按点击位置映射字符，并按句子或由空行、缩进和版面几何限定的单个段落提取；无可用文字层时渲染当前页缩略图并调用本机 Vision OCR。按书开启的论文双栏模式只改变自动 PDF 识别：PDFKit 文字层依照 `selectionsByLine()` 的真实 UTF-16 范围和页内几何组合点击栏，并以不连续 `PDFSelection` 高亮；OCR 则把候选行限制在点击的半页栏。横跨 72% 页宽以上的标题、摘要和图注退回普通流程，PDFKit 原生长按/手柄选区保持不变。OCR 行会根据行距、段首缩进、分栏和横/竖排几何关系重组，遇到标点遗漏时也不跨段串句；日文小号假名只有在尺寸、方向和汉字邻接关系同时匹配时才会作为注音过滤，竖排判断不依赖 PDF 语言元数据。命中文本与本页 OCR 结果只缓存在当前阅读器内存。长按文字层仍保留原生选择菜单。所有命中只创建临时覆盖高亮，不改写出版物正文或 PDF 文件。
- 1.5.0 起，EPUB 在关闭轻点翻译时把非边缘单击交给同一条 DOM 命中链路，粒度改为词语：命中点必须在字形 28pt 内，`ReaderWordSegmenter` 用 `NLTokenizer` 取整词，所在句子作为原句；日文由 `japaneseWordBounds` 把分词结果重新拼成词（2.1.0 起，Android 短按脚本里有逐行对应的同名函数，两端结果一致）：前缀带后面的词，汉字带送假名，词干按最长匹配带词尾，助词不并，点词里哪个字都得到整个词，规则见 [2.1.0 维护记录](RELEASE_2.1.0_2026-11-23.md) §3。拼词之前，`repairedJapaneseTokens`（Android 是 `repairJapaneseSegments`）先把 ICU 放错的边界挪到系统分词的位置，Android 的段取自系统 ICU（`JapaneseWordBreaks` 用 `android.icu.text.BreakIterator`，带中日文词典；Chromium 给 Android 的 ICU 数据裁掉了这份词典，WebView 的 `Intl.Segmenter('ja')` 只按汉字、平假名、片假名分段，系统给不出分词时才用它），且只把词段交给这两步，所以两端拿到同样的段；载荷以 `.quickWord` 触发器交给视图模型，直接打开语境查词并写入学习记录。未命中字形时仍切换阅读界面。Android 的短按脚本在同一次 WebView 计算中返回词面与前后文，DPR 换算留在该文档内，不读取可能滞后的 Readium 原生选区；查词成功即记录一次。
- PDF 点按容差以 16pt 屏幕坐标为真值，每次按 PDFView 当前缩放和页旋转换算到 page space；OCR 自定义长按为 0.30 秒。OCR 长按识别器始终附着在 PDFView，由 delegate 按实际触点页面判断是否存在可用文字层，因此 iPad 双页中相邻扫描页也可命中。文字 PDF 的翻译高亮保留 `PDFSelection`，OCR 页保留页内归一化识别框，屏幕 `CGRect` 仅是当前帧的派生值。缩放、滚动、旋转或 layout 变化时，使用 `selectionsByLine()` 和 PDFView 官方 `convert` 重算高亮与翻译卡片锚点，不做 `rect * scaleFactor` 推算。
- PDF 原生选择与点句翻译共用单调时间手势门：系统长按选区出现后，松手产生的尾随 tap 不会进入点句逻辑并清空选区。翻译菜单优先读取 PDFKit `currentSelection`，Readium 选区回调只作为后备；选择文本、页内范围和几何锚点因此来自同一个 PDFSelection。
- 选区的实时 frame 更新与翻译请求是两条独立通道：frame 变化驱动覆盖层布局但会过滤亚像素抖动；智能文本等待 170ms、精确文本等待 280ms 稳定，再按章节资源、规范化文本、语言对与服务配置生成语义身份去重。同一句在短按、智能选区和精确选区之间切换时，只更新覆盖层位置，不重复调用翻译服务。Readium 回传的 Navigator 坐标先经 UIKit 转为窗口坐标，覆盖层再减去自身 SwiftUI 全局原点，避免正文延伸到安全区而覆盖层不延伸时的纵向偏移。翻译使用单一 `ReaderTranslationState`，请求 UUID 同时保护连续选区、覆盖层关闭和迟到结果。
- Apple 翻译请求使用分阶段 watchdog：首先检测 SwiftUI 是否真正创建 `TranslationSession` action，未进入时只做一次 configuration invalidate 重启；重启后仍未进入则快速失败。网络翻译仍封顶为 30 秒，而已进入系统 session 的 Apple 请求有 180 秒语言包准备窗口，避免首次下载被旧的 30 秒限制误杀；超时后会给出保持前台或先在 Apple“翻译”App 下载语言的恢复步骤。请求 UUID 防止迟到结果回写。成功/失败状态都允许用户强制绕过缓存重新翻译；冷却键以书籍、章节资源、规范化文本和语言对组成，不含供应商，因此备用服务不能绕过同文本 5 秒冷却。
- `TranslationOutputPolicy` 是所有服务响应和缓存读取的统一可见性边界：只含空白、控制符、格式符、零宽字符或组合标记的结果不能进入 success。发现旧的不可见缓存行时会原子删除并按 cache miss 重试；服务端空白结果映射为 `resultNotFound`，参与一次自动重试与已配置备用服务切换，避免刷新后仍反复显示空卡。
- AI 句子结构分析沿用独立 `ContextExplanationService` 领域边界，UI 不依赖供应商结构。输入策略围绕当前句段截取有上限的上下文并生成语义缓存键；默认提示词固定为“句意→句子主干→1–3 个关键语法”的简短层次，旧默认模板自动迁移，用户自定义模板不覆盖。代理请求保留兼容任务名并增加 `analysisMode: grammar`。文法分析使用 60 秒 HTTP 超时和 70 秒界面 watchdog；OpenAI Responses 解析允许最终消息前出现不含 `content` 的 reasoning item。直接 API、后端代理和 Mock 都实现同一协议，输出经过可见性校验。
- 跨页句段扩展在请求网络前完成。先尝试选区已有上下文；不足时通过 `PublicationReaderController.crossPageContext` 按需补取。EPUB 只读当前可见 Readium 文档的基文节点、排除 `rt/rp` 并在 1,200 字符内围绕选区截取；PDF 点句使用前一页/当前页/后一页文字层。解析器只返回包含当前选区且边界可确认的完整句段，没有可靠上下文时返回 nil。扩展请求沿用正常翻译缓存与请求 UUID，不进行整本或整章翻译。
- WebKit 文本命中、语义提取和几何计算共用一套基文节点规则，统一排除 `rt/rp`。Readium `setupUserScripts` 只在每个内容 WebView 的 document-start 注入一条最小选择策略：注音保持可见，但设为 `user-select: none` 和 `pointer-events: none`，所以物理长按由汉字基文命中。不注册 `touchstart`/`selectionchange`，不移除、扩展或重建浏览器 Range，避免系统手柄与脚本竞争导致高亮消失。当某些 WebKit 版本仍回传纯注音端点时，只读语义快照会根据直接 `rt` 所在的 ruby 分组恢复对应基文，但不反向修改原生选区。长按观察器继续与 WebKit 原生选择识别器协作，但不再与水平 `UIPanGestureRecognizer` 同时成功：静止长按会取消待定翻页，明确横扫则由 pan 先识别并使观察器失败；松手后的尾随 tap 仍由单调时间门拦截。若 Readium 在短等待内没有回调原生选区，则仅在按点读取 ruby 基文作为降级，不写入 DOM Range。Swift 将每个基文 `ClientRect` 换算为 Readium 容器坐标，竖排时只补偿 Readium 用负 `UIScrollView.contentOffset.y` 表达的 UIKit 安全区位移，再交给不接收任何触摸的 `EPUBSelectionHighlightView` 绘制。因此可见标记不依赖 WebKit 原生涂色或会随 Readium 分栏位移的 DOM 覆盖层。单组/多组 ruby、逆向拖选、横排/竖排与字号换行共用同一逻辑；上下文和覆盖层避让框均不含注音。全部高亮仍是临时覆盖，不隐藏、不替换、不改写 EPUB 正文。
- 翻译卡片是临时覆盖层；可位于选区附近或顶部，成功状态只显示译文。横排优先比较完整选区上下空白；点段模式在卡片和选区之间保留 26pt 安全间距，而不是在卡片内部留白。原版竖排不受“选区附近/顶部”选项限制，优先在原文字列左边或右边放置普通圆角浮窗，它不是固定左右侧栏；手机优选 220pt 宽，空间不足时可收窄至 144pt，与点按的原文字列保持 12pt 间距，只有两侧都不可读时才回退到上下。当 EPUB 竖排元数据缺失时，高窄实时几何也可触发这一策略。为摆放浮窗而记录的 `focusFrame` 仅是几何锚点，不参与句/段边界、日文引号解析、Readium Range 或高亮。段落模式和较长文本只提高卡片的最大可用高度（iPhone 为 260–300pt，iPad 最高 360pt）；实际卡片始终按译文实测高度收紧，只有内容触及上限后才启用卡内滚动，避免短段落产生大片空白。底部外层留白为 12pt，文本底部保留 20pt，避免最后一行压在圆角边缘。用户可拖动卡片顶部大热区手柄手动调整位置；拖动时停止隐式动画、背景模糊和阴影，松手时只提交一次最终位置。选区、请求或显示模式变化后释放手动位置，重新自动跟随。关闭时清除选区并使过期请求失效，不重建 Navigator、不改写 EPUB。两端的卡片都有 108pt / dp 的最小高度，位置和拖动都夹在上下栏之间的安全区内：选区占满页面、上下都放不下时，卡片盖在选区上，不会被压扁或挤出屏幕（Android 自 1.7.1 起；此前会缩成贴边的细条，避让逻辑还会把它推到屏幕外）。Android 的避让在用户拖动过卡片后停用。
- 滚动阅读时两端都关闭 Readium 的横滑翻页（`disablePageTurnsWhileScrolling`；Android 自 1.7.1 起）：滚动的章节只有一页宽，横滑一次就会跳到相邻章节。Android 只对横排生效，开关在创建 Navigator 时按 Readium 自己解析出的 `verticalText` 决定；竖排总是滚动模式，正文横向滚动，滑到边缘换章是章节间唯一的手势，保持不变。开关打开后 Readium 的 `goForward()` / `goBackward()` 在滚动模式下不起作用，所以 Android 底栏的“上一页 / 下一页”在滚动模式改为切到相邻章节（iOS 的同名按钮也是这个效果），轻点翻译的左右边缘点按翻页在滚动模式下停用。
- 独立翻译工作台持有自己的原文语言、目标语言、词语/句子模式和服务选择，不写回阅读器全局设置。原文可用 `ReaderLanguageDetector` 自动识别中/英/日，也可手动固定；请求策略统一拒绝空文本、同语言方向和超长内容。Apple 路径使用页面自己的 `TranslationSession`，直接 AI 与代理继续只依赖 `TranslationService` 协议；每次请求带 UUID，切换方向或页面消失后旧结果不能覆盖新状态。词语模式向 AI 使用只返回译词的短提示词，句子模式沿用用户配置的翻译提示词。所有自动化测试使用本地 Mock/Stub，不访问外网。
- 成功译文可以收藏。`TranslationFavoriteRecord` 保存原文、译文、语言对、服务标识、书籍上下文和 Locator；与 EPUB 文件完全分离，并在“学习”页与词语收藏统一检索。
- 划线、批注和笔记由 `ReadingAnnotationRecord` 与 `ReadingAnnotationStore` 持久化。EPUB 保存 Locator/选区锚点并用独立 Readium Decoration group 呈现；PDF 保存页码、规范化页内矩形和文本，布局变化时重新映射。删除书籍会与书签一起先提交数据库删除，再清理本地文件；任何标记都不写入出版物正文。
- EPUB、由 DOCX/TXT 转换得到的重排文档以及 PDF 共用 `ReaderSelectionVisualStyle` 与翻译覆盖层状态。重排文档的点击、长按、注音过滤和语义恢复均走同一基文节点管线；PDFKit 文字层和 Vision OCR 只负责产生领域选区与归一化几何。浅色、护眼、冷灰和深色主题分别使用适配的填充、边框和朗读色；自定义背景可保存独立选区色，低对比组合会自动向黑或白混合。原生选择确认后统一换成不接收触摸的临时高亮。
- AI 后端代理只接受 HTTPS。非敏感的 endpoint/model 保存到 `UserDefaults`，可选代理访问凭据保存到 iOS Keychain（`AfterFirstUnlockThisDeviceOnly`）；不记录凭据或原文。协议为 HTTPS `POST` JSON：翻译请求 `text/context/sourceLanguage/targetLanguage/model/prompt`，成功响应读 `translatedText` 或 `translation`；解析请求带 `task: "explain"`、`analysisMode` 与 `responseLanguage`，依次读 `explanation`、`answer`、`translatedText`、`translation`。两类请求都带 `X-Jerreader-Translation-Protocol: 1`；翻译请求另附由规范化文本、上下文、语言对和代理版本生成的 `X-Jerreader-Idempotency-Key`，代理可据此合并同一翻译的并发或重试请求。Android 的 `AIProxyProtocol` 发同样的请求，幂等键也按同一算法生成（翻译请求暂不带 `context`）；字段说明见 [维护说明「AI 代理协议」](MAINTENANCE.md#ai-代理协议)。
- 直接 API 预设覆盖 OpenAI Responses、Claude Messages、Kimi/DeepSeek/OpenAI Chat Completions 与 Gemini `generateContent`；“其他兼容服务”使用 OpenAI Chat Completions 结构。配置界面用 GPT、Claude、Kimi 等可见卡片选择服务商，官方 endpoint 与推荐模型自动填写，普通路径只需粘贴 Key。供应商标记优先从本地资产目录读取，缺失或未获得商标授权时使用可读文字标记回退，不下载远程图片、不暗示供应商背书。非敏感配置写入 `UserDefaults`，Key 按服务商独立写入 Keychain（`AfterFirstUnlockThisDeviceOnly`）。服务版本哈希只包含服务商、endpoint 与模型，不含 Key。
- 网络提供方的短暂故障可自动重试一次；仍失败时可切换到用户明确配置且可用的备用服务。请求 UUID 与 `didUseFallback` 防止迟到结果覆盖和服务之间循环切换；命中缓存的同一句不会再次请求上游。
- 代理配置、语言对和供应方版本参与翻译缓存键；切换目标语言、供应方或代理模型不会误用旧结果。网络、鉴权、配置和结果错误映射为领域错误供 UI 呈现。
- 自定义 Info.plist 声明现代 `UILaunchScreen`，避免新 iPhone 进入旧尺寸兼容窗口；阅读器使用全屏 Cover，正文视图延伸到真实屏幕边缘。
- App target 的设备族为 iPhone+iPad，并声明 iPhone 横屏及 iPad 四方向。主导航使用 iOS 18 的自适应 Tab/Sidebar，书架、学习、设置和阅读控制为大屏设置可读最大宽度；Readium EPUB/PDF 均使用自动 spread，旋转时不重建出版物或丢失 Locator。
- 书架数据库只保存相对文件名，不保存本机绝对路径；阅读文件与封面位于 Application Support 下的 App 私有目录。
- 导入时先计算 SHA-256 指纹去重，再由 Readium 校验与解析；受保护出版物直接拒绝，不尝试解密。
- 导入 Sheet 先选择格式并据此设置系统文件选择器的 `allowedContentTypes`；选择 PDF 时系统列表只展示 PDF，而不是导入后再做界面过滤。
- EPUB、PDF、DOCX 和 TXT 可从系统“文件”选择，也可由其他 App 的“用Jerreader打开”、AirDrop 或 Finder/Apple Devices 文件共享进入。所有入口统一调用导入服务；新文件导入后立即进入阅读器，重复文件按 SHA-256 指纹打开书架中的现有副本。
- App 不声明原地打开 File Provider 文档；系统文件选择器先提供导入副本，避免把原始文件 URL 交给Jerreader。对于仍由外部打开入口传入的安全作用域 URL，会经 `NSFileCoordinator` 只读协调并复制成临时快照；Readium 的异步校验和解析只读取该快照，结束后立即清理，不持有外部文件权限，也不修改原文件。书架中的 App 自有出版物副本在打开前移除 POSIX 写权限，并在整个 Publication/Navigator 会话中持续保留进入 Readium 前记录的修改日期；关闭 Publication 后再次恢复，防止仅打开、阅读、选区或退出触发文件时间更新。书架点击本身不写 `lastOpenedAt`，仅在真实阅读位置落盘时把进度和最近阅读时间写入 SwiftData。重复文件错误直接携带第一次计算出的 SHA-256，打开书架副本时不会再次读取外部文件。
- 阅读打开链路在 MainActor 上连续创建 Readium retriever、opener、publication 和 navigator，不跨 detached task 转移 Readium 引用对象。SwiftUI 首次展示发生瞬时取消时会独立自动重试一次；关闭页面和 watchdog 取消不会触发重开，第二次取消会显示明确错误而非笼统系统提示。
- 每次 Reader 打开由独立 attempt ID 和 25 秒 watchdog 管理。超时会使当前 attempt 失效并进入可重试错误页；随后才返回的旧 Publication 会被关闭，不得覆盖新的重试。退出阅读器也会取消 watchdog 并使尚未完成的结果失效，避免永久停在加载页。
- Finder/Apple Devices 文件共享只是将文件交给 App `Documents` 目录的本地通道；它不是云同步，也不会启动本地网络服务。
- `WordLookupRecord` 同时承载缓存、历史和收藏状态；缓存键由语言与标准化基本形组成，重复查询更新同一记录的次数和时间。
- 查词缓存键进行 Unicode 标准化和大小写归一，但保留有语义的重音符号，避免将 `resume` 与 `résumé` 合并。
- 单条词语记录保存最近一次查询语境；新查询没有上下文或书籍来源时会清空旧值，不把旧句子误标成当前语境。
- 收藏词条可以脱离历史独立保留：清空历史时只删除未收藏记录；取消收藏时仅在记录也不属于历史时删除。
- 翻译页的词语模式只保存当前词条 UUID，并通过 SwiftData 查询解析展示对象；其他页面删除记录后不会继续操作已经脱离 `ModelContext` 的对象。
- 删除书籍时先提交 SwiftData 书架变更，再删除 EPUB 和封面；数据库保存失败会回滚且不动文件，文件清理会做一次幂等重试。
- `BookRecord` 保存用户可编辑的书名、作者、语言、系列、文件夹和标签。自定义封面先缩放并原子写入 Application Support，SwiftData 保存成功后才清理旧封面；批量整理在一个事务中提交，失败时整体回滚。文件夹只是本机书架分组，不移动或改写导入的出版物。
- 学习界面只依赖 `LexicalLookupService` 返回的领域模型，不直接读取第三方响应。默认实现优先查询内置中日词典与已安装的英语扩展包，未命中再使用中文维基词典 MediaWiki API 等既有服务，带本机缓存、超时、日语/英语词形回退和 iOS 系统词典后备；Mock 只供离线测试。
- 日语词形管线先用保守规则生成候选词典形，再用真实词典释义验证候选，避免把猜测当作结果。领域模型同时保存假名、词性、活用说明和有限例句。若用户已配置直接 AI API 或代理，可按需对词条和书中原句做上下文语法解析；结果经过可见性校验后写入词条缓存，同一词条再次打开不自动重复计费。
- 视觉层使用自有的语义颜色和原生 SwiftUI 控件：书库采用封面网格、最近阅读与轻量统计；阅读器参考微信读书的沉浸式交互原则，正文优先、控件按需出现，目录/搜索/书签集中在底部导航 Sheet。学习卡片、AI 解释和翻译覆盖层采用冷灰蓝层次。页面进入、状态变化、标签切换、按钮按压和加载反馈共用短时动效令牌；拖动翻译卡片时关闭隐式动效，系统开启“减少动态效果”时停用位移和缩放类动画。外观参考只用于层级与交互原则，不复制第三方品牌资源。
- 阅读中可验收的生词入口是长按智能选中完整词语、完成翻译后从卡片更多菜单加入；先通过 `LexicalLookupService` 补全基本形、读音、词性、活用和例句，词典失败时使用已校验的当前译文降级，并始终记录书籍与有限语境。AI 深度解析由用户主动请求，避免无意产生费用。手动词典查询仍从学习页进入。“正文短按命中完整单词并直接写入学习记录”自 1.5.0 起两端均已接入（见上文 EPUB 翻译入口），命中准确度、ruby 与竖排的设备正式验收仍未完成，长按入口作为降级保留。
- 阅读伴读（1.5.0，移植自统一版）：小说 / 非虚构模式按书保存；AI 阅读透镜、困惑地图、90 秒章节闭环和续读回顾的规则在共享 Kotlin `ReadingCompanion.kt`、`ReadingLens.kt` 与 iOS 同名 Swift 文件中各一份，困惑地图只读本机查词记录与复习结果。iOS 的伴读状态在 `ReaderCompanionModel`，与 `ReaderSearchModel` 一样是 `@Observable` 的阅读器子模型。
- 双端对拍：`shared/parity/*.json` 的同一批用例由 Android `:shared:testAndroidHostTest` 的 `ParityFixturesTest` 和 iOS `host-test` 的 `parity-fixtures` 套件分别执行。改动复习、原句练习、备份清理或伴读规则时先改用例。
- AI 翻译同时支持个人设备上的直接 API 与通用后端代理。公开分发或多人共享凭据时必须经用户控制的后端代理；直接 API 只保存用户主动输入、可撤销且应设置额度的个人 Key。全部网络实现使用可注入 HTTP Client，自动化测试使用 Stub/Mock，不依赖外网或真实密钥。

## 当前验证边界

- 1.0.3 正式版（内部 build 44）在 iOS 18.2 iPhone 16 Pro 模拟器执行 243 项单元/集成测试：240 项通过、3 项真实外部 EPUB 夹具测试因未注入文件按设计跳过、0 失败；独立翻译页面 UI 测试 1 项通过。新增回归覆盖中英日自动识别、同语言方向拦截、词语/句子长度与 AI 提示词，以及短段落译文框按实测高度收紧；既有仅打开书籍保持内容和修改时间不变、长译文滚动、竖排原文左右浮窗、日文引号、File Provider 备份和 PDF 双栏论文模式回归继续通过。Xcode Analyze 与全新 DerivedData 下的未签名 arm64 Release 构建通过。真实 iCloud Drive 文件夹授权及重复选择、Apple 语言包与用户 AI 账户仍保留为真机验收项。
- 用户提供的真实日文注音 EPUB 以只读副本注入临时 Debug 包后，横排、出版物原版竖排和强制缺失 Readium 原生回调三项物理长按 UI 测试均通过；原版竖排→横排→原版竖排往返会读取实际 DOM `writing-mode` 与 Readium 方向确认收敛。书内自定义、书籍管理和语音入口隐藏路径也通过。Release 构建不得复用这个注入目录。
- iPad Pro 11 英寸（M4）iOS 18.2 模拟器的竖屏→横屏 UI 测试通过：窗口原点保持 `(0,0)`、画布宽高随方向切换、书库导航和导入入口持续可用；保存的最终帧经人工复核无兼容黑边、裁切或旋转中间态。
- 主标签全屏、真实书三种选区、版式往返、书内自定义/书籍管理、语音入口隐藏、iPad 竖横屏等既有 UI 测试通过；新增备份中心 UI 回归也已通过，覆盖自动开关、策略、范围、手动备份、恢复和本地备份入口。
- Xcode 16.2 的 Debug Scheme Analyze，以及干净 DerivedData 下使用 iPhoneOS 18.2 SDK 的未签名 arm64 Release 构建均已通过。
- 当前 Intel Mac 的 iOS 18.2 模拟器中，`LanguageAvailability` 能列出支持语言，但对英语→简体中文、日语→简体中文和英语→西班牙语的状态都返回 `unsupported`。因此真实 Apple 语言包下载和译文输出保留为 iOS 18+ 真机验收项；自动化测试严格使用 Mock，不用伪译文替代真机验收。

## 下一里程碑

1.3.5（内部 build 56）的历史分发行为：阅读器段落译文框按内容收紧，只在长译文触及上限后滚动；“学习 → 翻译”支持中英日原文/译文方向、词语/句子模式，以及 Apple、AI API 或 AI 代理的页面内选择。关于页仅显示 Marketing Version，不显示内部构建号，作者为 Jerry Wong。备份仍是用户选择的文件提供商存储，不是应用账户或跨设备同步服务；语音功能仍暂时关闭。

## 2026-09-07 维护补充

- 独立翻译页编辑原文、切换语言/模式/服务时，使旧输出及附属任务失效。Android 的 `TranslationToolSession` 集中管理翻译、词典和解析任务的取消；`runCatchingActive` 在挂起调用返回或抛错后检查取消状态，避免忽略取消的服务回写旧结果。页面语言和服务只在进入时读取全局初值。
- Android 翻译输入保留用户原文；超长时显示限制并禁用提交，切换模式不截断文字。译文先于可选的词典补全显示。
- iOS 收藏状态只在持久化成功后显示；失败展示错误。翻译页按钮行按可用宽度/动态字体调整，Android 分段控件和搜索框允许随文字增高。
- 维护修复、构建结果及后续开发建议另见 `docs/MAINTENANCE_2026-09-07.md`；历史验证记录不代表本次检查通过。

- 第二轮维护将独立翻译 watchdog 抽到不依赖 UI 的 Foundation 类型，使用请求 ID 隔离启动重试、超时与迟到完成；可在 macOS 主机运行 `scripts/test_translation_watchdog.swift` 对生产类做离线回归，无需模拟器。Android 任务作用域使用独立计时器，使不响应取消的服务也不能维持界面永久加载；词典补全与主翻译独立计时。
- 面向用户的翻译长度限制与计数使用完整字符簇，Android 通过平台正则字符分段、iOS 原生界面通过 Swift Character。Readium 的 UTF-16 文字偏移和几何映射继续使用原有坐标单位，不能与显示字符计数混用。


## 2026-09-07 学习功能扩展

- 学习页保留翻译、生词本和历史分区，新增统一“练习与工具”入口。原句练习与语法收藏只使用已有内容；保存动作不触发 AI。
- `WordReviewState` 固定间隔调度与 `SentencePractice` 题面生成独立于 UI。iOS 复习状态和练习卡为原记录上的可选 Data 字段；Android 为对应 Room 行的可选 JSON 字段。删除父记录同步删除附属学习状态。评分时快照书籍 ID，之后同词在其他书查询不会重新归属过去评分。
- `GrammarNoteRecord` / `GrammarNoteEntity` 独立保存用户确认的解析。跳回原书通过可选初始 Locator 进入现有 Readium 入口；不会先覆盖书籍保存的阅读位置。缺失原书时保留知识库内容。
- 每日阅读统计附着于书籍记录。现有前台阅读计时落盘时按当地午夜拆分有效秒数，书内查询入口记录发起次数；数据只写 SwiftData / Room。统计可关闭，无每日旧历史不做反推。报表使用每 10 分钟查询次数与自评“认识”比例，不宣称语言等级。
- iOS 通过 `JerreaderSchemas.swift` 冻结发行版 V1 属性，V2 采用轻量迁移。Android 使用顺序 5→6→7→8→9 迁移。新增备份字段可缺省解码，学习/阅读范围分别携带对应数据；沿用原备份合并策略保留本机已有记录，不以旧备份覆盖当前进度。
- 离线词典包固定来源、版本和 SHA-256，由用户主动下载。下载器有可注入的 Mock 协议；流式 CSV 解析与 SQLite 建库分开，完整校验后同目录原子替换。取消/失败只清理临时包，既有词典保留。导入包位于独立应用支持目录，不接触书籍目录，且排除可重新下载的数据备份。
- 查词仍返回 `WordExplanation`。英语词典包命中时优先使用本机释义；无匹配时沿用原字典/备用服务链。独立翻译页的英语→中文词语模式也优先使用离线词条；句子翻译继续遵循用户的翻译服务设置。


## 中日优先与学习工具 UI 校验

- 9 月 8 日内置中日词典扩充：`ja-zh-core.tsv` 与 `ja-zh-wiktionary.tsv` 为两端共同输入，后者由固定哈希的中文维基词典快照生成。共 27,983 个不同日语词头。`JapaneseChineseLexicon` 建立词头、假名、活用与精确中文释义索引；同音多词可在独立查词界面选择，不猜测读音或执行句子翻译。词语模式命中本地词条后不启动系统翻译语言包或 AI 请求。来源、修改说明、CC BY-SA 4.0 全文和原词条链接随应用提供。2.1.0 起，短按选中的整词在词典里没有原样时，`JapaneseChineseLexicon.japanese` 再按 する 动词、两层变形、名字和复数后缀、敬语前缀、复合动词还原，只认唯一的词头；两端用同一份扩展规则表（1411 条，条数和哈希由两端测试核对），含 ていく、てくる、てくれる；只有五段动词才有的词尾不还原成一段动词（いらない 不是 いる），假名写的 いった、いって 列出 行く、言う 两个候选；维基词典收的假名词头遮住核心词典里读这个音的词时，两者都列为候选，核心词典的在前（いく：行く／いく）。`ja-zh-core.tsv` 加了 152 个维基词典包里没有的常用词，共 280 条。
- 两端翻译页提供“日语 → 中文”“中文 → 日语”快捷入口，保留用户的原默认设置和其他语言。日语汉字词可通过显式方向避免被自动识别为中文。
- 五个学习工具统一独立导航标题、16 点页面边距、16 点圆角卡片、720 点内容宽度上限；各页面拥有单独的懒加载滚动容器。安卓原句自测的页面返回和系统返回均先回到原句列表。
- 语法编辑器两端提供标题、语言、标签、原句和解释。阅读器的保存动作先显示可编辑内容；无原书位置及搜索无结果均有明确状态。中日内容不改变 Readium 正文或书籍文件。
- UI 样例仅用于 Debug：iOS 使用内存 SwiftData，Android 使用不可导出的 Debug Activity 与内存 Room，均不向用户书架插入测试数据。正式测试代码不依赖外网。

- 9 月 8 日统一学习页面的实际卡片内边距 16、子项间距 12、边线 0.75、按钮点击区域至少 44、按钮圆角 8；正文 17、提示 15、附注 12，并统一语法操作顺序与编辑卡片、成长报告文案及图表几何。不能以源码令牌对齐代替两端截图验收，结果见 `docs/DICTIONARY_UI_2026-09-08.md`。
- 9 月 8 日历史调整：用户同意将构建启动负载阈值从每逻辑核心 2 调整为 2.5（8 核主机为 20）。9 月 12 日用户进一步明确要求改为固定 50，启动前与运行中共用此上限；模拟器阶段间资源稳定等待仍使用每逻辑核心 1。构建默认 1 job、上限 2，其他保护继续由 `AGENTS.md` 和安全构建脚本约束。

- 9 月 9 日验证工具维护：安全入口新增 `doctor` 与 `host-test`，默认单 job、Swift 驱动单并发；主机逻辑回归与 iOS 构建共用主机锁，运行中监测负载/内存/温控/竞争任务。经授权的模拟器测试拆分为编译、资源稳定等待、单设备启动、再次稳定等待和不重新编译的测试执行。失败不重试，只清理本次进程组与设备。详见 `docs/IOS_SAFE_TESTING.md`；此策略本身不代表 iOS 测试已通过或驱动风险已消除。

## 学习页面与封面性能（2026-09-09）

- 两端 `WordReviewSnapshot` 每个数据版本只解码一次、稳定排序一次；页面时钟和显示答案复用解析状态。`ReadingGrowthSnapshot` 在后台聚合七日活动与评分。SwiftData 对象不跨 actor，仅传值快照；任务取消和输入身份检查共同隔离过期结果。Android 使用 `Dispatchers.Default` 和受页面生命周期约束的协程。
- iOS `BookCoverThumbnailStore` actor 与 Android `BookCoverCache` 在后台串行解码缩略图，最长边限制 768 像素、缓存保留量 24 MiB。缓存键包含文件身份、修改时间和大小；切换封面时仅显示当前文件对应的结果。只读独立封面，不接触出版物正文；安卓收到内存压力回调后清缓存。
- 安卓生词本与历史使用稳定键的 `LazyColumn`，搜索结果与 Room Flow 复用；两端搜索控件固定，最小高度 48、控制栏左右留白 20，使用同一实色背景，列表按正文 17/次级 15/附注 12 的层级呈现。
- 本轮编译、逻辑和 UI 证据详见 `docs/SMOOTHNESS_UI_2026-09-09.md`。主机性能对照不能替代真机帧率；没有当次 iOS 截图不得声明像素一致。

## 语境查词内容迭代（2026-09-10）

- 书内词典 Sheet 保留查询原句并允许修改词语；内置词典的同音和多义候选供用户选择。日语活用候选经词典及词性验证，离线结果携带规则说明。
- 词典来源与 AI 语境建议分层，AI 主动触发并使用独立语境查词缓存身份。学习记录换原句、词形、义项或书籍时清除旧解析；迟到 AI 保存按查询身份和记录版本校验。
- 不新增数据库字段，不触及出版物文件或 Readium 生命周期。用户安排稍后设备/UI 验收，详见 `docs/CONTEXTUAL_LOOKUP_2026-09-10.md`。

## 动态模型目录（2026-09-12）

翻译设置新增独立 `AIModelCatalogService` 服务协议与平台 GET 传输实现。UI 只读取模型 ID、显示名、来源和刷新时间，不直接解析供应商 JSON。目录按当前供应商/地址/凭据隔离，用户刷新并确认选择后才更新现有模型设置；不自动迁移用户配置。分页和错误处理由服务层负责，测试注入 Mock HTTP。详细范围与验收边界见 `REVIEW_2026-09-12.md`。

## 查词维护（2026-10-05）

- iOS 独立翻译页的词典补全使用独立 watchdog（15 秒）、任务取消与请求 ID；Android 提取 `StandaloneWordLookupSession` 复用既有任务控制。两端词典失败都能单独重试，保留主译文，不重新请求翻译。
- iOS 英语词库 actor 串行持有一个只读 SQLite 连接及预编译查询，按顺序批量匹配候选；成功安装或删除后释放，失败安装不替换旧连接。Android 词典补全直接复用本次本地译词的释义，匹配词语、语言并确认释义非空后才能复用。
- 两端在取消后阻止进入下一查词服务；Android 历史写入失败保留可见释义，重试可直接重写历史。只改辅助词典与学习界面，不改变书籍文件、Readium 生命周期或数据 Schema。

## 构建排队（2026-10-05）

用户将原负载超过 50 中止的规则替换为 150 排队门槛。主机回归、iOS 构建和 Android 打包经同一监督器与主机锁串行执行，固定单 job / 单 worker；高负载或已有任务时等待，运行中负载单独升高不再中止。内存、温控、磁盘、外部竞争构建和模拟器授权仍受保护。历史阈值说明由此节及最新 AGENTS 取代，细节见 `IOS_SAFE_TESTING.md`。

2026-11-13：150 排队门槛和温控中止已取消，负载与温控只记录；新增 `scripts/background_build.py`，在登录会话之外通过同一监督器构建并打包 IPA。依据见 `IOS_SAFE_TESTING.md`。

## 2026-10-06 Android 离线与读取优化

- `OnDeviceTranslationService` 协调基础词典与已安装本机模型；`LocalTranslationBackend` 隔离 SDK，`LocalTranslationModels` 向 UI 暴露语言包状态和显式操作。ML Kit Task 取消等待后仍保持底层操作互斥直至完成。
- Android 内置固定来源的英语基础 TSV，完整英语 SQLite 包可选。本地词典命中不访问模型或在线 API；模型缺失属于需准备的状态，不触发在线备用。
- 不可变文件流式拷贝同时摘要；Readium 关闭后单次完整校验再原子提交。阅读器 IO 校验离开主线程，保留 SHA-256 + mtime 回归。
- 本轮范围、平台差异和验收记录见 `ANDROID_OPTIMIZATION_2026-10-06.md`。

## 2026-10-12 界面与导航维护（1.4.0 / 58）

- iOS 与 Android 的常用界面图标共用 `design/jerreader-icons.json`，由 `scripts/generate_ui_icons.py` 分别生成 UIKit/SwiftUI 和 Compose 路径；生成文件不手动维护。
- 学习页默认显示练习概览，复用现有词语、原句和练习服务。翻译草稿由导航上层持有，切页取消请求但保留输入，不自动重新请求服务。Android 使用嵌套 SaveableStateHolder；iOS 由 LearningHubView 持有 TranslationToolDraft。
- Android 设置分组、导航栏、书籍封面与管理表单对齐 iOS；显示与布局变更不修改出版物副本。系统文件授权、文件选择器和本地翻译提供方仍按各平台实际能力实现。
- 双端版本号统一为 1.4.0，内部版本 58。验收和维护步骤以 [1.4.0 界面验收](UI_PARITY_1.4.0.md) 为准。

## 2026-10-16 回归修复（1.4.0 / 59）

- 学习采用“翻译 / 生词本 / 练习与工具 / 历史”四分区，练习包含复习、原句、语法与成长。离线查询统一使用翻译词语模式；词典与翻译模型的准备集中在设置，不再另设“离线学习包”或方向快捷按钮。
- iOS Readium 保持文档、定位、排版与选区所有权。竖排逐页使用 WebKit 原生 column-axis 与 Readium gutter，body 是唯一横向分页容器，根元素不再嵌套分栏；UIScrollView 观察维持异步加载后的分页状态，RTL 按资源起始边缘对齐，保留不足一屏的合法末页。前后翻页按逻辑页号处理，避免反向跳过倒数页。所有样式只作用于内存文档。
- 横竖模式切换先等待 Readium 正式导航完成；只在完整文档和 Readium CSS 持续失配时恢复，不能强制重载 about:blank 或正在加载的 WebView。调试诊断只发布已加载的实际 CSS，并在滚动后读取真实坐标。
- 出版物只读保护捕获 POSIX 纳秒时间戳；未变化时不写入文件元数据，实际被框架触碰后才用 utimensat 精确恢复 mtime，并保留 atime。Foundation Date 往返会损失纳秒，不能用于此处的原值保护。
- 备份的默认用户流程为外部文件夹，首次立即备份先授权目录；从目录恢复后沿用实际所选目录，优先于归档中的旧路径提示。iOS 同路径重新选取也刷新书签；Android 先验证再替换持久授权。高级范围与保留策略折叠，旧本机归档保留导出和恢复能力。
- Android 列表以 manifest 中的生成时间排序；恢复完整读取 ZIP 并完成 CRC/元数据校验后才进入 Room 事务，合并时保留现有书籍文件和较新的进度，失败清理新建的暂存文件。API Key、目录授权和翻译缓存仍不进入归档。
- 双端常用图标及指南图标共用原始矢量路径；Android 保留透明颜色以按实际承载面混色，统一表单细线、字体层级、学习控件和封面编辑草稿。更换封面仅在“保存”时提交，取消或撤销不更新书籍。
- Android 词条详情只读已有学习记录，复用收藏与语法保存服务，不因打开详情重新查词或联网。列表点按进入、长按管理，返回保留筛选；复制与收藏操作各占底栏一半。双端用同状态的正常、空数据和大字深色截图核对。
- 双端阅读控制采用贴边栏和共享矢量图标；Android 正文保留区固定，隐藏控制不重分页，翻章按钮按多章节条件显示。真实点译坐标与文件不变验证覆盖相应偏移。
- Release APK 在交付前额外校验签名连续性、ZIP CRC、版本、新功能标记和全部 DEX 类型唯一性；构建成功或安装启动成功不能替代这个检查。生成目录出现的重复 class/dex 副本仅可逆隔离，不改系统同步配置。
- 本轮编译、设备测试、图片与交付限制以 [回归修复记录](HOTFIX_1.4.0_2026-10-16.md) 为准，不能用 58 的验收结果替代。

## 1.4.1 阅读界面设置与注音

界面缩放在根环境统一设上限，阅读正文的 Readium 字号独立。阅读器透明度只作用于控制栏背景，正文始终保留相同上下空间，显示/隐藏控制栏不触发重新分页。沉浸阅读仅隐藏阅读页的系统状态栏，摄像头和刘海安全区仍由系统管理。

上下栏透明度、沉浸阅读、日语注音属于可备份的非敏感设置；旧备份缺省字段保留当前值。注音是翻译辅助卡片的显示能力，复用已有翻译状态，不改变服务协议、缓存身份或 EPUB。Android 保守匹配本机词典的已确认读音及活用，未知与歧义内容不猜读音；iOS 沿用本机分词器。

离线资源页面仅呈现简短说明、状态、下载/卸载及必要进度/错误，许可使用短外链，随包许可资源保留。Apple 语言包由系统管理，Android 语言包由本机服务管理，不把平台能力差异伪装成可用操作。当前版本实际构建、测试和交付状态见 [1.4.1 维护记录](HOTFIX_1.4.1_2026-10-17.md)。

## 2026-11-17 界面布局、选句与导入筛选（未发版）

- 阅读界面分「标准」「沉浸」两种布局，入口在 阅读设置 → 阅读界面 → 界面布局。
  - 存储键是 `reader.layoutMode`，值为 `standard` / `immersive`，两端相同。它随备份导出和恢复。
  - 标准：上下栏常驻，正文排在两栏之间。
  - 沉浸：正文全屏，上下栏浮在正文上，默认收起。轻点正文上下的空白条或没有文字的地方会呼出上下栏，4 秒后自动收起。空白条是安全区加页边。正文上的轻点仍用来查词和翻译。
  - 只有切换布局会让 Readium 重新分页。沉浸下显示、隐藏上下栏都不会。
  - 沉浸下系统状态栏始终隐藏。原来的“沉浸阅读”开关改称“隐藏状态栏”，只在标准布局下显示，存储键仍是 `reader.immersiveReading`。上一节说的“正文始终保留相同上下空间”，现在只适用于标准布局。
- 修了“选一句却翻成整段”：
  - iOS：系统选区回退后，再拖手柄一律按精确选区处理，不再被智能补全扩大。
  - iOS：智能补全的句子最长 220 字（`ReaderSmartSelection.maximumCompletedSentenceLength`）。超过时说明附近找不到句界，保留原选区。
  - 日文对白「」后面即使没有句号也算句末，跨段落的对白不再连成一句。两端规则相同：安卓在共享的 `ReaderCrossPageExpansion.kt`，iOS 在 `ReaderSentenceSegmentation.swift`。
  - iOS 直连 AI 的提示用 `<source>` / `<context>` 分开原文和上下文，缓存身份升为 `jerreader-direct-api-v2`，旧的整段结果不再命中。安卓的翻译请求不带上下文，不涉及这一条。
- 导入弹层可先选格式（全部、EPUB、PDF、Word、TXT）。选定某种格式后只把它交给系统选择器（iOS 传内容类型，安卓传 MIME 类型），其他格式不能选；选“全部”时仍可选所有支持的格式。导入弹层全高显示。
- iOS「默认阅读排版 → 间距」里的「分别设置」（上 / 下 / 左右边距）删了。这三个值（`reader.defaults.pageMarginTop` 等）只存不读，阅读器一直只用统一的「页边距」，安卓也从没有这一项。旧安装里残留的值不再读写，不影响使用。

## 2.0.0 AI 套餐与「纸墨」界面（2026-11-19）

### 套餐（AI 大模型 → 添加供应商 → 套餐）

只放供应商条款允许第三方阅读应用使用的两种：

- **ChatGPT 套餐（实验）**：OpenAI 为开源应用提供的 Sign in with ChatGPT（[说明](https://developers.openai.com/siwc/token-sharing-open-source)）。用户在系统浏览器里登录 ChatGPT，App 拿到只属于本机的令牌，翻译走用户 ChatGPT 套餐的额度，不需要 API Key。手机端的回调方式 OpenAI 还没写进文档，所以标“实验”。
- **Claude 套餐额度**：Claude Max / Ultra 每月附带的 API 额度挂在 Console 组织上，用法和普通 API Key 相同，所以不新增供应商，只引导用户去 Console 建 Key、再用现有的 Claude 供应商。
- Coding Plan、Token Plan 一类编程套餐的条款只许在编程工具里用，阅读翻译不在其列，页脚说明不提供。

登录流程（两端相同）：

- 首次登录用 `client_id=dynamic_agent_client`，带 `agent_name_hint=Jerreader` 和本机随机的 `ext_agent_host_id=urn:uuid:…`；授权服务器在回调里发正式的 `client_id`，之后刷新和再次登录都用它。
- 授权地址 `https://auth.openai.com/api/accounts/authorize`，令牌 `/api/accounts/oauth/token`，吊销地址从 OpenID 发现文档读。scope 为 `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`，resource 为 `https://api.openai.com/v1`。PKCE S256，另带 state 和 nonce，回调时逐项核对。
- 回调地址是 `http://127.0.0.1:<随机端口>/auth/callback`：App 在本机回环地址上临时开一个只接受这一条路径的监听，收到授权码后把浏览器转回 App，监听随即关闭。
  - iOS：`NWListener` + `ASWebAuthenticationSession`，转到 `jerreader://chatgpt-auth/done`（会话的回调 scheme 是 `jerreader`，页面随之关闭）。
  - 安卓：`ServerSocket`（`ChatGPTPlanLoopback.kt`）+ 默认浏览器的 Custom Tab（按包名指定浏览器，免得别的 App 截走 OpenAI 的链接；不依赖 androidx.browser）。监听回 302 到 `intent://chatgpt-auth/done#Intent;scheme=jerreader;package=<包名>;end`，页面里另有一个同样的链接，浏览器没自动跳时可以点。这个地址由 `ChatGPTAuthRedirectActivity`（无界面）接住，把发起登录的页面带回前台并关掉 Custom Tab。浏览器在前台时，`ChatGPTPlanSignInService` 前台服务让进程不被冻结：安卓 14 起是 `shortService`，系统最多给约 3 分钟；更早的系统是普通前台服务。没收到回调就回到 App（离开后再回来 1.5 秒内没有回调）算取消，最长等 10 分钟。
- 令牌只存本机：iOS 钥匙串（service `Jerreader.translation-backend`、account `chatgpt-plan-credential`，`ThisDeviceOnly`）；安卓用原有的 `AndroidKeystoreCredentialStore`（系统密钥库加密），单独一个账户 `chatgpt-plan-credential`。本机 host id 和正式 client_id 存普通偏好设置。**都不进备份**；从备份恢复到另一台设备要重新登录。
- 刷新：过期前 120 秒内的令牌视为过期；刷新不带 scope；同时发起的多个请求只刷新一次（后到的请求拿到刷新好的令牌）；刷新令牌轮换后保存新的。刷新令牌失效（`invalid_grant` 等）就清掉账号，提示重新登录。
- 退出登录先删本机令牌，再尽力吊销刷新令牌；吊销失败不影响本机状态。
- 换令牌时核对授予的 scope（build 72 起）：令牌响应列出了 scope 却没有 `chatgpt.tokens.use.direct`，说明用户在授权页没有允许使用套餐，这次登录不保存、刷新令牌当场吊销，提示重新登录并允许；响应没列 scope 时按已授予处理。被拒或请求时收到 `chatpass_v2_scope_not_authorized` / `chatpass_v2_invalid_authorization_context` 后，**只有下一次**登录带 `prompt=consent` 重新显示授权页，登录成功就清掉这个标记，平时不强制授权页。

翻译请求：

- `POST https://api.openai.com/v1/responses`，`store: false`、`stream: true`，带 `instructions` 和 `input`。`input` 必须是消息数组 `[{"role":"user","content":"…"}]`，系统提示放 `instructions`；套餐接口不收字符串形式的 `input`（2.0.0（71）发的是字符串，测试连接和翻译都会被 400 拒绝，build 72 改正）。不带 `temperature`、`max_output_tokens`、`previous_response_id` 等套餐不支持的字段。2.1.0 起边读边解析，部分文字先显示在卡片上（见下文 2.1.0 一节）；仍然**只有收到 `response.completed` 才算成功**，半截结果不缓存。
- 错误按 OpenAI 文档的说法分类（两端同一套规则，iOS `ChatGPTPlanRefusal`、安卓 `ChatGPTPlanRefusal`，对拍用例相同）：
  - 401：换新令牌重试一次；还被拒就提示重新登录。
  - 额度用完（`subscription_sharing_usage_limit_exceeded`）直接停下，不自动重试，提示去 <https://chatgpt.com/settings/usage> 查看。
  - `subscription_sharing_usage_unavailable`、`subscription_sharing_user_unavailable`，以及 408 / 409 / 425 / 429 / 5xx：暂时不可用，按普通失败处理（可自动重试、可换备用服务）。71 把 `usage_unavailable` 当成额度用完，72 改正。
  - 其余 4xx（含 403）：OpenAI 拒绝了这次请求，**不再一律当成“登录失效”**，也不清掉登录。提示先说明原因（账号或工作区不能用套餐、不支持某个参数、不允许调用这个接口、没有授权使用套餐、模型不可用、地区或账号限制），再附 OpenAI 的原话和“HTTP 状态 · 错误码 · 参数 · 请求 id（`x-request-id`）”，测试连接页的这段文字可以选中复制。错误体可能是 `{"error":{…}}`、直接准入的 `{"detail":…}` 或纯文本；网页不显示，原话超过 300 字截断。
  - 事件流里的 `response.failed` / `error`：额度码照上面停下；没有错误码、暂时性错误码或 `server_error` 一类算暂时不可用；其他错误码按被拒处理，显示原话。
- 额度用完、需要重新登录、请求被拒这三种不自动重试，也不换备用服务（与 iOS 只在暂时性错误时换备用服务一致），阅读页不提供“重新翻译”按钮。
- 模型列表来自 `GET /v1/models`，只列 `visibility` 为 `list` 的模型，显示账号给的名字，存的是模型 id。
- 测试全部用 Mock：iOS `scripts/test_chatgpt_plan.swift`（host-test 的 `chatgpt-plan` 组，回环监听在本机真实运行，不出本机）和 XCTest `ChatGPTPlanTranslationTests`；安卓是 JVM 单元测试 `ChatGPTPlanProtocolTest`、`ChatGPTPlanSessionTest`、`ChatGPTPlanCompletionTest`、`ChatGPTPlanSettingsTest`（假的 HTTP 客户端；回环监听同样在 127.0.0.1 真实运行）。没有用真实 ChatGPT 账号验证过。

## 2.0.1 修复版（2026-11-20）

没有新模块；下面是这一版改动里以后维护要知道的几条约定。逐条问题和验证情况见 [2.0.1 维护记录](RELEASE_2.0.1_2026-11-20.md)。

- **选区方块的颜色必须用行内 `!important`。** 轻点翻译和朗读的选区是画在书页 DOM 里的一组小方块。ReadiumCSS 在护眼、夜间主题下，以及只要设了页面背景色，会给页面里每个元素声明 `background-color: transparent !important`（夜间还有 `border-color: currentColor !important`）。普通行内样式压不过它，所以填充和描边都写成行内 `!important`，并加 `background-image: none`。iOS 在 `EPUBReaderScripts.markerPaintCSS(fill:stroke:)`，Android 在 `selectionMarkerPaintCss(fill, stroke)`；以后往书页里画别的东西也照这个写法。
- **上下边距是全局设置，不进每本书的排版。** 取值 0–4（14pt / 14dp 的倍数，默认 1）。iOS 键 `reader.verticalMargins`（`ReaderInterfaceDefaults`），`EPUBReaderHost` 用它算给 Readium 的上下内容留白，改值后调 `refreshContentInsets()`；Android 键同名（`AndroidAppSettingsStore`），Readium 自带的上下留白资源 `readium_navigator_epub_vertical_padding` 置 0，由 `ReaderActivity.updateReaderContentPadding()` 给页面容器加内边距（横排滚动阅读和 PDF 不加）。它和 1.7.0 删掉的「分别设置」无关，不读那几个旧键。
- **「设置」标签离开即回首页。** iOS 给设置标签的 `NavigationStack` 一个随“离开设置”递增的 `id`（`ContentView.settingsVisit`）；`LibraryBackupActivity.isBusy` 为真时不递增，免得把正在报告结果的备份页重建掉。Android 在切换标签时把 `settingsRoute` 设回首页并丢掉保存的页面状态。书架和学习两个标签仍保留各自的状态。
- **外观模式和背景光晕。** iOS 键 `appearance.mode`（`system` / `light` / `dark`）和 `appearance.backdrop-glow`，外观模式直接设在窗口的 `overrideUserInterfaceStyle` 上（不用 `preferredColorScheme`，这样回到“跟随系统”立即生效，阅读页按纸张定的明暗也仍然优先）；Android 键 `app.appearanceMode`、`app.backdropGlow`，枚举 `JerreaderAppearanceMode` 在 `shared`。主题色枚举在两端都只在末尾追加（松石、赤陶、石墨），存的是名字。四项都随“非敏感设置”进备份。
- **套餐接管“正在用的 AI 模型”时不删有密钥的默认项。** `AIProviderSettings.add(_:activeHasKey:takesOver:)`（Android `adding(…, takesOver)`）：`takesOver` 只表示“这一项要成为当前项”，“没动过的默认项让位并从列表里移除”仍然只在它真的没有密钥时发生。套餐登录不改「翻译服务」，登录后的提示按当时的翻译服务分两种说法。
- **PDF 进度在最后一页记 100%。** Readium 给 PDF 的 `totalProgression` 是页首位置（`(页码 − 1) / 总页数`），最后一页到不了 1。iOS `ReaderPDFProgress.adjusted`、Android `PdfLocatorFormat.readingProgress` 在位置等于总页数时返回 1；只用于显示和 `lastReadProgress`，保存的 Locator 不改。
- **iOS PDF 划线要等文档装上再画。** Readium 调 `setupPDFView` 时 `pdfView.document` 还是空的，文档稍后才装上。`PDFReaderHost` 监听 `PDFViewDocumentChanged`，并在位置变化时用 `installAnnotationsIfDocumentChanged()` 兜底；点按命中只认本应用自己装上的划线（`ownAnnotationID`），PDF 里原有的批注、链接不拦截点句翻译。
- **iOS 目录项按文件内位置定位。** 一个资源里有多个目录项时（`chapter.xhtml#part2`，或整本一个资源的 PDF `#page=N`），当前章节按 `ReaderOutlinePosition` 比较：PDF 用页码，EPUB 用锚点在正文里的相对位置（`ReaderOutlineBuilder.anchorFractions`）。
- **iOS 阅读时长的日期键一律公历。** `ReadingActivity.dayCalendar` 是 ISO 8601 日历加当前时区；读取时把以前按和历、民国历、佛历写下的键换回公历（`gregorianKey`）。
- **Android 语法笔记去重在仓库层。** `grammarNoteToSave(note, existing)`：编号不存在而标题、原句、讲解全同的，沿用已有那条的编号；iOS 在 `GrammarNoteEditor` 保存时做同样的查找。

## 2.1.0 卡片 AI 与流式（2026-11-23）

逐条规格见 [SPEC_2.1.0](SPEC_2.1.0.md)，问题和验证见 [2.1.0 维护记录](RELEASE_2.1.0_2026-11-23.md)。下面是以后维护要知道的约定。

- **流式入口。**
  - iOS 在 `AIStreaming.swift`。`URLSession.bytes(for:)` 的 `lines` 会跳过空行，所以逐个 `data:` 行分发。
  - Android 在 `AIStreamParsing.kt` 和 `AIStreamTimeouts.kt`。
  - 四种直连格式和 ChatGPT 套餐都是流式；AI 代理和系统翻译仍一次给出全文。
  - `length`、`content_filter` 当作没写完，不算完整答案。
- **计时。**
  - 静默上限：翻译 30 秒，AI 70 秒。总上限 3 分钟。
  - 流式请求的单次读超时两端都是 80 秒。它必须比静默上限长，否则系统的读超时会先到，报「暂时不可用」，「超过 70 秒未返回」的提示出不来。
- **取消。** Android 取消时断开连接，正在读的那一步会报连接错误。catch 里先调 `currentCoroutineContext().ensureActive()`，所以取消不会被当成失败，也不会去重试或切备用服务。
- **缓存。**
  - 只缓存完整的结果。
  - 备用服务给的译文按备用服务缓存。iOS 记在备用 provider 的身份下；Android 靠 `TranslationResult.fallbackProvider`，存进备用服务的命名空间。
  - 结构、读懂、指代进解释缓存，和解释页共用；直译和追问只留在卡片内存。
- **迟到保护。** 每次写回部分或最终文字都核对请求 id。卡片关掉时清掉按钮状态：iOS 在 `ReaderCardAI`，Android 是 `ReaderAIActions.reset()`。
- **笔记是追加写。**
  - iOS 在 `ReadingAnnotationStore.save`。Android 按 `annotationKey` 先查再写，查用 `ReaderRecordRepository.findAnnotation`。
  - 新建笔记的入口都先找这段已有的笔记：iOS 是 `annotationDraftForCurrentSelection`，Android 是 `annotationDraft`。
  - 新建的笔记记不带读音的原文。Android 的选区文字用 `ReaderRubySelectionText` 读。
- **注音整体选区。**
  - 规则和用例在 `selection_tiles.json`。
  - iOS 的 `jerreaderReaderSelectionRects` 直接返回合并好的块，算法在 `ReaderSelectionTileScript.swift`。
  - Android 的点句覆盖层和长按选区桥共用同一段 JS；shared 的 `ReaderSelectionRectMerger` 是它的 Kotlin 版，`ParityFixturesTest` 跑同一份用例。
- **英文断句。**
  - Android 在 shared 的 `ReaderSentenceSegmenter`，iOS 在 `ReaderSentenceSegmentation.swift`。
  - 两端用同一批句子对拍：Android 是 `ReaderEnglishSentenceTest`，iOS 是 `testEnglishSentenceTapTargetsMatchAndroid`。改一端要同时改另一端。
- **查词次数的键。** 语言加原形，没有原形才用原样；卡片和查词页共用这个键。翻译卡片选中单个词时先查一次词典再记，词典查不到就不记。
- **长按选词。**
  - 长按选中手指下那个字所在的词。iOS 由选区策略脚本里的 `longPressWordGuardScript` 在 WebKit 选好之后改选区。Android 用脚本改选区会丢掉 WebView 的拖动柄和「翻译」「查词」菜单，所以由 `ReaderLongPressAim` 在手指按下时先问页面，按在字的后半边时把这次按压挪到同一个字的前 1/4 处。
  - 按在注音词的空白里（汉字之间，或较长的读音伸出汉字的那部分），算同一行上最近的汉字，一样近取前一个：iOS 用 `rubyGlyphNear` 找这个字，Android 照同一规则挪落点。按在正对某个汉字的读音上，结果和以前一样。`rt`、`rp` 设了 `pointer-events:none`，读音本身点不中。
  - 选区不会自动扩到整个注音词，这是用户定的。
- **iOS 竖排偏移修正挂在 KVO 上。** `observeVerticalContentOffset` 同时观察 contentOffset 和 contentSize。WKWebView 每次图层树提交都会给滚动视图设一次 contentSize（`_setContentSizePreservingContentOffsetDuringRubberband`），自动 KVO 在值没变时也会通知。挂在这里的工作要先比新旧值，也不能自己引起下一次提交，否则大约每 0.4 秒触发一次，竖排书开着就一直跑。2.1.0 开发中加过的整页重画因此先改成只在尺寸真变时触发，后来整个删掉了：竖排整页画高没有修好，见维护记录第 6 节。
