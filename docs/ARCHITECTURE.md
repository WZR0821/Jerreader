# Jerreader（Jerreader）架构说明

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
- PDF 点按容差以 16pt 屏幕坐标为真值，每次按 PDFView 当前缩放和页旋转换算到 page space；OCR 自定义长按为 0.30 秒。OCR 长按识别器始终附着在 PDFView，由 delegate 按实际触点页面判断是否存在可用文字层，因此 iPad 双页中相邻扫描页也可命中。文字 PDF 的翻译高亮保留 `PDFSelection`，OCR 页保留页内归一化识别框，屏幕 `CGRect` 仅是当前帧的派生值。缩放、滚动、旋转或 layout 变化时，使用 `selectionsByLine()` 和 PDFView 官方 `convert` 重算高亮与翻译卡片锚点，不做 `rect * scaleFactor` 推算。
- PDF 原生选择与点句翻译共用单调时间手势门：系统长按选区出现后，松手产生的尾随 tap 不会进入点句逻辑并清空选区。翻译菜单优先读取 PDFKit `currentSelection`，Readium 选区回调只作为后备；选择文本、页内范围和几何锚点因此来自同一个 PDFSelection。
- 选区的实时 frame 更新与翻译请求是两条独立通道：frame 变化驱动覆盖层布局但会过滤亚像素抖动；智能文本等待 170ms、精确文本等待 280ms 稳定，再按章节资源、规范化文本、语言对与服务配置生成语义身份去重。同一句在短按、智能选区和精确选区之间切换时，只更新覆盖层位置，不重复调用翻译服务。Readium 回传的 Navigator 坐标先经 UIKit 转为窗口坐标，覆盖层再减去自身 SwiftUI 全局原点，避免正文延伸到安全区而覆盖层不延伸时的纵向偏移。翻译使用单一 `ReaderTranslationState`，请求 UUID 同时保护连续选区、覆盖层关闭和迟到结果。
- Apple 翻译请求使用分阶段 watchdog：首先检测 SwiftUI 是否真正创建 `TranslationSession` action，未进入时只做一次 configuration invalidate 重启；重启后仍未进入则快速失败。网络翻译仍封顶为 30 秒，而已进入系统 session 的 Apple 请求有 180 秒语言包准备窗口，避免首次下载被旧的 30 秒限制误杀；超时后会给出保持前台或先在 Apple“翻译”App 下载语言的恢复步骤。请求 UUID 防止迟到结果回写。成功/失败状态都允许用户强制绕过缓存重新翻译；冷却键以书籍、章节资源、规范化文本和语言对组成，不含供应商，因此备用服务不能绕过同文本 5 秒冷却。
- `TranslationOutputPolicy` 是所有服务响应和缓存读取的统一可见性边界：只含空白、控制符、格式符、零宽字符或组合标记的结果不能进入 success。发现旧的不可见缓存行时会原子删除并按 cache miss 重试；服务端空白结果映射为 `resultNotFound`，参与一次自动重试与已配置备用服务切换，避免刷新后仍反复显示空卡。
- AI 句子结构分析沿用独立 `ContextExplanationService` 领域边界，UI 不依赖供应商结构。输入策略围绕当前句段截取有上限的上下文并生成语义缓存键；默认提示词固定为“句意→句子主干→1–3 个关键语法”的简短层次，旧默认模板自动迁移，用户自定义模板不覆盖。代理请求保留兼容任务名并增加 `analysisMode: grammar`。文法分析使用 60 秒 HTTP 超时和 70 秒界面 watchdog；OpenAI Responses 解析允许最终消息前出现不含 `content` 的 reasoning item。直接 API、后端代理和 Mock 都实现同一协议，输出经过可见性校验。
- 跨页句段扩展在请求网络前完成。先尝试选区已有上下文；不足时通过 `PublicationReaderController.crossPageContext` 按需补取。EPUB 只读当前可见 Readium 文档的基文节点、排除 `rt/rp` 并在 1,200 字符内围绕选区截取；PDF 点句使用前一页/当前页/后一页文字层。解析器只返回包含当前选区且边界可确认的完整句段，没有可靠上下文时返回 nil。扩展请求沿用正常翻译缓存与请求 UUID，不进行整本或整章翻译。
- WebKit 文本命中、语义提取和几何计算共用一套基文节点规则，统一排除 `rt/rp`。Readium `setupUserScripts` 只在每个内容 WebView 的 document-start 注入一条最小选择策略：注音保持可见，但设为 `user-select: none` 和 `pointer-events: none`，所以物理长按由汉字基文命中。不注册 `touchstart`/`selectionchange`，不移除、扩展或重建浏览器 Range，避免系统手柄与脚本竞争导致高亮消失。当某些 WebKit 版本仍回传纯注音端点时，只读语义快照会根据直接 `rt` 所在的 ruby 分组恢复对应基文，但不反向修改原生选区。长按观察器继续与 WebKit 原生选择识别器协作，但不再与水平 `UIPanGestureRecognizer` 同时成功：静止长按会取消待定翻页，明确横扫则由 pan 先识别并使观察器失败；松手后的尾随 tap 仍由单调时间门拦截。若 Readium 在短等待内没有回调原生选区，则仅在按点读取 ruby 基文作为降级，不写入 DOM Range。Swift 将每个基文 `ClientRect` 换算为 Readium 容器坐标，竖排时只补偿 Readium 用负 `UIScrollView.contentOffset.y` 表达的 UIKit 安全区位移，再交给不接收任何触摸的 `EPUBSelectionHighlightView` 绘制。因此可见标记不依赖 WebKit 原生涂色或会随 Readium 分栏位移的 DOM 覆盖层。单组/多组 ruby、逆向拖选、横排/竖排与字号换行共用同一逻辑；上下文和覆盖层避让框均不含注音。全部高亮仍是临时覆盖，不隐藏、不替换、不改写 EPUB 正文。
- 翻译卡片是临时覆盖层；可位于选区附近或顶部，成功状态只显示译文。横排优先比较完整选区上下空白；点段模式在卡片和选区之间保留 26pt 安全间距，而不是在卡片内部留白。原版竖排不受“选区附近/顶部”选项限制，优先在原文字列左边或右边放置普通圆角浮窗，它不是固定左右侧栏；手机优选 220pt 宽，空间不足时可收窄至 144pt，与点按的原文字列保持 12pt 间距，只有两侧都不可读时才回退到上下。当 EPUB 竖排元数据缺失时，高窄实时几何也可触发这一策略。为摆放浮窗而记录的 `focusFrame` 仅是几何锚点，不参与句/段边界、日文引号解析、Readium Range 或高亮。段落模式和较长文本只提高卡片的最大可用高度（iPhone 为 260–300pt，iPad 最高 360pt）；实际卡片始终按译文实测高度收紧，只有内容触及上限后才启用卡内滚动，避免短段落产生大片空白。底部外层留白为 12pt，文本底部保留 20pt，避免最后一行压在圆角边缘。用户可拖动卡片顶部大热区手柄手动调整位置；拖动时停止隐式动画、背景模糊和阴影，松手时只提交一次最终位置。选区、请求或显示模式变化后释放手动位置，重新自动跟随。关闭时清除选区并使过期请求失效，不重建 Navigator、不改写 EPUB。
- 独立翻译工作台持有自己的原文语言、目标语言、词语/句子模式和服务选择，不写回阅读器全局设置。原文可用 `ReaderLanguageDetector` 自动识别中/英/日，也可手动固定；请求策略统一拒绝空文本、同语言方向和超长内容。Apple 路径使用页面自己的 `TranslationSession`，直接 AI 与代理继续只依赖 `TranslationService` 协议；每次请求带 UUID，切换方向或页面消失后旧结果不能覆盖新状态。词语模式向 AI 使用只返回译词的短提示词，句子模式沿用用户配置的翻译提示词。所有自动化测试使用本地 Mock/Stub，不访问外网。
- 成功译文可以收藏。`TranslationFavoriteRecord` 保存原文、译文、语言对、服务标识、书籍上下文和 Locator；与 EPUB 文件完全分离，并在“学习”页与词语收藏统一检索。
- 划线、批注和笔记由 `ReadingAnnotationRecord` 与 `ReadingAnnotationStore` 持久化。EPUB 保存 Locator/选区锚点并用独立 Readium Decoration group 呈现；PDF 保存页码、规范化页内矩形和文本，布局变化时重新映射。删除书籍会与书签一起先提交数据库删除，再清理本地文件；任何标记都不写入出版物正文。
- EPUB、由 DOCX/TXT 转换得到的重排文档以及 PDF 共用 `ReaderSelectionVisualStyle` 与翻译覆盖层状态。重排文档的点击、长按、注音过滤和语义恢复均走同一基文节点管线；PDFKit 文字层和 Vision OCR 只负责产生领域选区与归一化几何。浅色、护眼、冷灰和深色主题分别使用适配的填充、边框和朗读色；自定义背景可保存独立选区色，低对比组合会自动向黑或白混合。原生选择确认后统一换成不接收触摸的临时高亮。
- AI 后端代理只接受 HTTPS。非敏感的 endpoint/model 保存到 `UserDefaults`，可选代理访问凭据保存到 iOS Keychain（`AfterFirstUnlockThisDeviceOnly`）；不记录凭据或原文。协议为 HTTPS `POST` JSON `text/sourceLanguage/targetLanguage/model`，成功响应接受 `translatedText` 或 `translation`。客户端附带由规范化文本、上下文、语言对和代理版本生成的 `X-Jerreader-Idempotency-Key`，代理可据此合并同一翻译的并发或重试请求。
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
- 学习界面只依赖 `LexicalLookupService` 返回的领域模型，不直接读取第三方响应。默认实现调用中文维基词典 MediaWiki API，带本机缓存、超时、日语/英语词形回退和 iOS 系统词典后备；Mock 只供离线测试。
- 日语词形管线先用保守规则生成候选词典形，再用真实词典释义验证候选，避免把猜测当作结果。领域模型同时保存假名、词性、活用说明和有限例句。若用户已配置直接 AI API 或代理，可按需对词条和书中原句做上下文语法解析；结果经过可见性校验后写入词条缓存，同一词条再次打开不自动重复计费。
- 视觉层使用自有的语义颜色和原生 SwiftUI 控件：书库采用封面网格、最近阅读与轻量统计；阅读器参考微信读书的沉浸式交互原则，正文优先、控件按需出现，目录/搜索/书签集中在底部导航 Sheet。学习卡片、AI 解释和翻译覆盖层采用冷灰蓝层次。页面进入、状态变化、标签切换、按钮按压和加载反馈共用短时动效令牌；拖动翻译卡片时关闭隐式动效，系统开启“减少动态效果”时停用位移和缩放类动画。外观参考只用于层级与交互原则，不复制第三方品牌资源。
- 阅读中可验收的生词入口是长按智能选中完整词语、完成翻译后从卡片更多菜单加入；先通过 `LexicalLookupService` 补全基本形、读音、词性、活用和例句，词典失败时使用已校验的当前译文降级，并始终记录书籍与有限语境。AI 深度解析由用户主动请求，避免无意产生费用。手动词典查询仍从学习页进入。“正文短按命中完整单词并直接写入学习记录”尚未稳定接入 Readium，是当前明确的降级项与未完成验收目标。
- AI 翻译同时支持个人设备上的直接 API 与通用后端代理。公开分发或多人共享凭据时必须经用户控制的后端代理；直接 API 只保存用户主动输入、可撤销且应设置额度的个人 Key。全部网络实现使用可注入 HTTP Client，自动化测试使用 Stub/Mock，不依赖外网或真实密钥。

## 当前验证边界

- 1.0.3 正式版（内部 build 44）在 iOS 18.2 iPhone 16 Pro 模拟器执行 243 项单元/集成测试：240 项通过、3 项真实外部 EPUB 夹具测试因未注入文件按设计跳过、0 失败；独立翻译页面 UI 测试 1 项通过。新增回归覆盖中英日自动识别、同语言方向拦截、词语/句子长度与 AI 提示词，以及短段落译文框按实测高度收紧；既有仅打开书籍保持内容和修改时间不变、长译文滚动、竖排原文左右浮窗、日文引号、File Provider 备份和 PDF 双栏论文模式回归继续通过。Xcode Analyze 与全新 DerivedData 下的未签名 arm64 Release 构建通过。真实 iCloud Drive 文件夹授权及重复选择、Apple 语言包与用户 AI 账户仍保留为真机验收项。
- 用户提供的真实日文注音 EPUB 以只读副本注入临时 Debug 包后，横排、出版物原版竖排和强制缺失 Readium 原生回调三项物理长按 UI 测试均通过；原版竖排→横排→原版竖排往返会读取实际 DOM `writing-mode` 与 Readium 方向确认收敛。书内自定义、书籍管理和语音入口隐藏路径也通过。Release 构建不得复用这个注入目录。
- iPad Pro 11 英寸（M4）iOS 18.2 模拟器的竖屏→横屏 UI 测试通过：窗口原点保持 `(0,0)`、画布宽高随方向切换、书库导航和导入入口持续可用；保存的最终帧经人工复核无兼容黑边、裁切或旋转中间态。
- 主标签全屏、真实书三种选区、版式往返、书内自定义/书籍管理、语音入口隐藏、iPad 竖横屏等既有 UI 测试通过；新增备份中心 UI 回归也已通过，覆盖自动开关、策略、范围、手动备份、恢复和本地备份入口。
- Xcode 16.2 的 Debug Scheme Analyze，以及干净 DerivedData 下使用 iPhoneOS 18.2 SDK 的未签名 arm64 Release 构建均已通过。
- 当前 Intel Mac 的 iOS 18.2 模拟器中，`LanguageAvailability` 能列出支持语言，但对英语→简体中文、日语→简体中文和英语→西班牙语的状态都返回 `unsupported`。因此真实 Apple 语言包下载和译文输出保留为 iOS 18+ 真机验收项；自动化测试严格使用 Mock，不用伪译文替代真机验收。

## 下一里程碑

1.3.5（内部 build 56）为当前正式分发版：阅读器段落译文框按内容收紧，只在长译文触及上限后滚动；“学习 → 翻译”支持中英日原文/译文方向、词语/句子模式，以及 Apple、AI API 或 AI 代理的页面内选择。关于页仅显示 Marketing Version，不显示内部构建号，作者为 WANG ZIRUI。备份仍是用户选择的文件提供商存储，不是应用账户或跨设备同步服务；语音功能仍暂时关闭。
