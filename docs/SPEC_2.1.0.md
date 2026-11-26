# Jerreader 2.1.0「摆上台面」实施规格

依据 `docs/ROADMAP_2.1_TO_3.0_2026-11-22.md` 第 4 节，另加用户 11-22 的要求：日文注音的选区连成一个整体。双端版本 2.1.0，build / versionCode 75（版本号和 README、MAINTENANCE、发版记录由主会话统一改，实施时不要动）。

两端共有的规则以 `shared/parity/*.json` 为准。改规则先改用例，Android `ParityFixturesTest` 和 iOS `host-test` 的 `parity-fixtures` 都要过。本版新增或改动的用例：

| 文件 | 内容 |
|---|---|
| `selection_tiles.json` | 注音整体选区的几何规则 |
| `ai_actions.json` | 卡片 AI 按钮、追问预设、直译和追问的指令、发给模型的材料块、追问的多轮消息和压平文本、来源角标 |
| `chapter_estimate.json` | 本章剩余时间 |
| `lookup_hint.json` | 第 N 次查 · 上次 MM-dd，以及「加入生词本」何时升为主按钮 |
| `companion_dot.json` | 伴读红点：6 小时内重新打开不亮 |
| `reading_companion.json` | `lensPrompt` 的每条期望末尾加了一行防注入句（`ai_actions.json` 的 `guard`） |

## 1. 注音整体选区

**现象**：选中带假名注音的汉字时，汉字框和假名框各画一块，中间有缝，各带一圈描边。

**规则**（`selection_tiles.json`）：
1. 量出每个选中的正文片段框。每个注音（`rt`）框并进它所注的那个正文框，也就是沿行方向重叠最多的那个，一样多时取跨行方向离得最近的。并入的条件：沿行方向有重叠，且跨行方向的间距不超过正文框自身的厚度。不满足就单独成块，和旧行为一样。
2. 一个注音只选中了它所注正文的一部分时，先把注音框沿行方向裁到选中的正文范围。
3. 分行时用正文框判断（跨行方向重叠不少于较短者的 0.55）；涂色用并入注音后的框。同一行相距 1.5 px 以内的段合成一段；相邻两行重叠时在中点分开。
4. 结果是一行一块：从假名顶端到汉字底边（竖排是从汉字左边到假名右边）。

**位置**：
- iOS：`EPUBReaderScripts.swift` 的 `jerreaderReaderSelectionRects` 直接返回合并好的块，点句高亮、朗读高亮和长按选区（UIKit 的 `EPUBSelectionHighlightView`）都吃这份结果。纯 JS 的合并算法在 `ReaderSelectionTileScript.swift`，host-test 用 JavaScriptCore 跑 `selection_tiles.json`。
- Android：`ReaderTapTranslationController.kt` 的点句覆盖层和长按选区桥共用同一段 JS。共享模块的 `ReaderSelectionRectMerger` 是这套算法的 Kotlin 版，由 `ParityFixturesTest` 跑同一份用例。
- 已保存的划线由 Readium 自己画，本版不改。

**长按选中手指下的词**（11-23 加入）：长按要选中包含手指下那个字的词。按在注音上算作它所注的汉字。

**按在注音词的空白处**（11-25 加入）：注音词里汉字之间的空隙，以及读音比汉字长时汉字旁边空出的地方（读音伸到这片空白上方的部分也算），按在这里算作这个注音词里离手指最近的汉字。只看同一行上的字（跨行方向离字不超过一个字宽），沿行方向离得不超过一个字；一样近（差 0.5 px 以内）时取前一个。两端的阅读器都给读音设了 `pointer-events: none`，按在读音上就是按在它下面的东西上。真书测试查出原来两端都没管这种按法：长按注音词的正中，正好落在两个汉字之间。

- iOS：WebKit 在词的边界上偏向在光标处结束的词，长按一个词第一个字的前半边会选中前一个词。`EPUBReaderScripts.swift` 的 `longPressWordGuardScript` 装在选区策略脚本里，看长按带来的第一次选区：碰到手指下的字却没包含它时，用 `Selection.modify` 按 WebKit 的词规则改选包含这个字的词；按住和抬手时 WebKit 再选回去，也照样改回。用手柄拖出或调整的选区不动。手指下没有字时，`glyphAt` 交给 `rubyGlyphNear` 按上面空白处的规则找字。WebKit 量的字框贴着字（在 Mac 的 Safari 26.6.1 上核对过），空白处不在任何一个字里，命中的是注音词所在的段落。
- Android：Chromium 偏向从光标处开始的词，长按词尾那个字的后半边会选中后一个词。Android 上用脚本改选区会让 WebView 收起手柄和「翻译」「查词」菜单，所以改的是落点：`ReaderLongPressAim` 是每个 EPUB 页面 WebView 上的触摸监听，手指落下时用 `caretRangeFromPoint` 问页面手指在哪个字上；在字的后半边（沿行方向，竖排看纵向）就补发一个取消，再在同一个字的 1/4 处重新落下，原来的时间戳不变，后面的事件跟着平移。手指下没有字、落在注音词的空白处时，挪到按上面规则找到的那个汉字的 1/4 处，不在这个字的行上时跨行方向也挪到行中间。只挪静止的单指，且页面的回答要比长按超时早至少 60 ms 到达；手指移动超过触摸阈值、多指、链接和表单、从右到左的行都不挪。Readium 的点击监听装在页面容器上，不在 WebView 上，两者不冲突。
- 各引擎对空白处的命中不一样：可能是注音词本身、它所在的段落（光标在读音里或读音边上），也可能是注音词旁边的文字（光标在边上）。两端都从这几处收集候选的注音词。

**注音词被拆开**（11-26 加入，只 iOS）：每个汉字各带读音的注音词，iOS 长按一个字的后半边时，WebKit 自己选的词停在注音的边上，只有这一个字；按前半边时纠偏用 `Selection.modify` 改选，得到整个词。为了同一个字按哪里都得到同一个词，纠偏多管一种情况：WebKit 的选区已经包含手指下的字，但有一端停在注音的边上，外面紧挨着的还是文字（字母、数字或标记，即 `\p{L}\p{N}\p{M}`）。这时用 `Selection.modify` 选包含这个字的词，只在这个词把原来的选区整个包住而且更长时才换，否则把原来的选区放回去。端点可以在文字节点里，也可以在元素的子节点之间。扩到的是 WebKit 词规则给的词，不是整个注音元素。

- Android 不改：长按这种词，WebView 只选中手指下那一个汉字，前后半边都一样；用脚本改选区会收起手柄和菜单（RELEASE §6）。

## 1b. Android 英文点句断句（11-22 加入）

**现象**：样本代理用古腾堡的公版英文核对时，发现 Android 点句有两处和 iOS 不一致。
1. 在 Mr. 这类称谓处断开。原因是 `isAbbreviationDot` 只认单个大写字母。
2. 引号里有好几句话时，整段引语被选成一句。原因是引号里的句号一律不算句末。

**规则**：改的是 shared 的 `ReaderSentenceSegmenter`，只动英文，日文和中文照旧。
- 什么时候用英文规则：不是日文，并且书的语言是英语或这段文字里没有汉字。
- 断不断句：
  - 称谓缩写（Mr.、Mrs.、Dr.、St. 等 22 个）后面不断句。
  - 单个大写字母（J. K.）和带点的缩写（U.S.）后面，只在下一个词明显是句首时断句，比如 He、The、Then。
  - 句末标点后面跟小写字母时不断句，比如 p.m. and、"Stop!" he said、Wait... what。
  - 句号后面没有空格时不断句，比如 3.14、U.S.A.。
  - 编号缩写（No.、p.、pp.、Art.、Sec. 等）后面跟数字时不断句，比如 No. 5。这条对齐 iOS 已有的测试 `testQuickSentenceSegmentationKeepsNumberAbbreviationWithValue`。
  - 缩写表和句首词表与 iOS `refinedEnglishRanges` 逐项相同。
- 引号：
  - 引号不再把整段引语绑成一句，里面每句话各自是一个点句目标。
  - “Yes,” Holmes said. 这种引语后面跟的说明语，算进它前面那句。
  - 点在句末的右引号上时，选中这个引号收尾的那一句。

**测试**：
- `shared/src/commonTest/.../translation/ReaderEnglishSentenceTest.kt`：26 组用例，每组点四个位置，即目标词和期望句子的首字、中间、末字。其中两段是古腾堡的长引文。
- iOS 在 `ReaderServicesTests` 里加了同样的 24 组英语用例（`testEnglishSentenceTapTargetsMatchAndroid`），用来核对两端结果一致。另外 2 组只测 Android 怎么判断走英文规则，iOS 没有这一步。
- iOS 已有的 13 条英文断句用例，用同一套 Android 规则的原型核对过，结果全部一致。

## 2. AI 调用层（两端各一份）

- 统一入口支持：单轮和多轮消息、流式增量、取消、用量（服务方给了就记）。
- **流式**
  - 直连 API 的四种格式都改为流式：
    - Responses：`stream: true`，读 `response.output_text.delta`，`response.completed` 带用量，`response.failed` 和 `error` 当作失败。
    - Chat Completions：`stream: true`，读 `choices[0].delta.content`，到 `[DONE]` 结束，最后一块可能带 `usage`。`finish_reason` 是 `length` 或 `content_filter` 时算没写完，不当作完整答案（两端一致）。
    - Anthropic Messages：`stream: true`，读 `content_block_delta` / `text_delta`；`message_start` 和 `message_delta` 带用量，`error` 事件当作失败。
    - Gemini：`:streamGenerateContent?alt=sse`，跳过 `thought` 段。
    - 各家的思考增量（`thinking_delta`、`response.reasoning_*`、`thought`）一律不显示。
  - ChatGPT 套餐本来就是 SSE，改成边读边出字，见到 `response.completed` 才算成功。
  - AI 代理和系统翻译（Apple / ML Kit）保持非流式，完成时一次性给出全文。
  - HTTP 状态不是 2xx 时按原来的错误映射处理。
  - iOS 用 `URLSession.bytes(for:)`，`lines` 会跳过空行，所以逐个 `data:` 行分发。Android 逐行读，取消时断开连接。
  - Android 断开连接后，等着读的那一步会报连接错误，这仍按取消处理：不重试，也不切到备用服务。
- **超时**：
  - 第一段增量之后改为「多久没有新增量」：翻译 30 秒，AI 结果 70 秒（和原来一致）。
  - 另设总上限 3 分钟。
  - 两端直连和 ChatGPT 套餐的流式请求，单次读超时都是 80 秒（iOS 是 `URLRequest.timeoutInterval`），比上面两个静默上限长，所以由静默上限决定。iOS 原来翻译 30 秒、AI 60 秒，系统的读超时会抢在静默上限前面报「暂时不可用」，「超过 70 秒未返回」的提示出不来。
- **界面**：部分文字最多每 50 ms 刷新一次。停止、超时或失败时留下的是屏幕上已经显示的文字，还没刷出来的最后一小段不补（两端一致）。卡片的阶段标识（iOS `translationPhaseIdentity`、Android `phaseIdentity`）在流式期间不变，也就是不重新淡入、不重测高度动画、不触发成功触感，完成时才触发一次。
- **迟到保护**：每次写回部分或最终文字都核对请求 id。关掉卡片或换句后，旧请求迟到的增量不得落进新卡片，两端都要有单测。
- **缓存**：只缓存校验通过的完整结果，部分结果一律不进缓存。主服务失败、由备用服务给出的译文按备用服务缓存：iOS 记在备用服务的 provider 身份下，Android 存进备用服务的命名空间。所以下次点同一句仍会问主服务，不会把备用的答案当成主服务的，也不会挂主服务的角标。
- **防注入**：所有 AI 指令末尾加 `ai_actions.json` 的 `guard` 一句，包括阅读透镜、句子结构分析、直译和追问。翻译提示词沿用 iOS 现有的 `<source>` / `<context>` 写法。
- **Mock**：
  - Mock 服务要能流式（把文字分 3–4 段，每段间隔约 80 ms）。
  - 每种动作给固定文本，供 UI 测试使用。直译的 Mock 返回两行：「直译：……」和「差别：……」。

## 3. Android 翻译请求带上下文（对齐 iOS）

- `TranslationRequest` 加 `context: String? = null`。
- EPUB 和 PDF 点句都照 iOS `ReaderTranslationContext.window` 取上下文：共 1200 字，前后各一半。照 iOS 原样移植到 shared。
- EPUB 的前后文只取所在段落的正文，不含注音假名（rt/rp），也不跨到别的段落，和 iOS 一样：
  - 快速点句：用 `ReaderTranslationContext.paragraph`（对应 iOS `contextWindow`）。段落不超过 1200 字就整段发送，超过才在句子前后各取一半；段落只有这一句时不带上下文。
  - 点句所在的段落也照 iOS `jerreaderReaderBlock` 找（Android 的点句和选区共用 `ReaderBlockScript`）：先找最近的 p、li 等，找不到再往上找排成块的祖先。原来找不到 p 就直接用手指下的元素，用 div 分段的书里点注音汉字（ruby、rb）或着重号（em）只取到这个元素的字，常常只有一个词，于是打开查词而不是翻译整句。往上一直找不到块时（正文直接放在 body 里），iOS 不翻译，Android 仍沿用原来的兜底。跨页补句拼整章文字时，段落之间加空格也按这个找法，同 iOS `crossPageDocumentContextScript`；原来 div 分段的相邻段落会粘在一起。
  - 手动选区的「翻译」：前后文取选区所在段落的正文，段落的找法同 iOS `jerreaderReaderBlock`；选区跨段时不带上下文。页面脚本读不到选区时，才退回 Readium 自带的前后文（它会带读音、会跨段）。
  - 卡片上的「查词」把请求的上下文带进语境查词，没有就用原句，同 iOS 的 `initialContext`。
- 直连 API 和 ChatGPT 套餐的提示词照搬 iOS `TranslationPrompt`。
- 缓存键加上下文，命名空间升一版，对应 iOS 的 `jerreader-direct-api-v2`。
- AI 代理的请求体带 `context`，幂等键也算上 context，和 iOS 一样。

## 4. 翻译卡片的 AI 按钮条

**按钮**（`ai_actions.json` 的 `actions`）：
- 日语原文：结构、读懂、指代、直译、查词、追问。
- 其他语言和自动识别：结构、读懂、直译、查词、追问。

**每个按钮对应的功能**：

| 按钮 | 调用 | 结果区标题 |
|---|---|---|
| 结构 | 现有句子结构分析，带卡片的上下文 | 句子结构 |
| 读懂 | 阅读透镜 comprehension | 读懂这段 |
| 指代 | 阅读透镜 reference | 省略与指代 |
| 直译 | 新指令 `literal` | 直译对照 |
| 查词 | 打开现有的语境查词页，不在卡片里展开 | — |
| 追问 | 卡片内的追问对话 | 追问 |

**位置和样式**：
- 在译文和底部操作行之间。翻译成功后才出现，加载中和失败时不显示。
- 胶囊按钮：高 30，点按区 44；圆角 10；字号 13 Medium。
- 未选中：纸色底加细线框。选中：强调色 12%（深色模式 22%）底、强调色字。
- 卡片窄（竖排侧边卡 144–220）时自动换行，不横向滚动。

**结果区**：
- 在按钮条下方：
  - 顶部是 eyebrow：12 Semibold、强调色，写结果区标题。
  - 正文流式出字。
  - 生成中显示「生成中…」和「停止」。
  - 出错时显示错误文字和「重试」。
- 底部一行：「存为笔记」、「展开看全文」，右侧是来源角标。「结构」另有原来的「保存到语法知识库」。
- 再点已选中的按钮收起结果区。点别的按钮就切换：在途请求取消，已完成的结果按「卡片 + 按钮」缓存在内存里，卡片关掉就丢。
  - 这是按钮自己的状态。结构、读懂、指代的完整结果另外存进解释缓存，和解释页共用：iOS 存在阅读器内存里，Android 存在数据库里。所以别的卡片点同一句的同一个按钮，会直接取回结果。
  - 直译和追问只留在卡片内存里。
- 「直译」的结果按「直译：」「差别：」两行分开显示；模型没按格式回答时原样显示。
- 「展开看全文」打开现有的解释页或 Sheet，显示完整文字。

**卡片高度**：
- 有结果区时，卡片上限放宽到可用高度的一半（不含安全区），译文和结果在卡片里一起滚动。
  - 一半比平时的上限还低时（如手机横放），用平时的上限：两端都取两者中较大的那个（iOS `preferredMaximumCardHeight`）。
- 摆放仍避开原文选区。上下都放不下半屏时，用空间大的一侧，卡片缩到能放下为止，不盖住选区。
  - 最多缩到各端现有的最小卡片高度（Android 108 dp），不再往下缩；选区大到连最小高度都放不下时，允许盖住选区（11-23 主会话定）。
- 结果出来时，按钮行和结果一起滚进卡片的可视区（11-23 加）。结果比可视区还高时停在按钮行这一端，不滚到底，按钮行不会滚出去。「追问」滚到最后一条回答。
  - iOS 按量到的按钮条高度选滚动锚点（`cardAIScrollAnchor`）。Android 对整条按钮条 `bringIntoView`，追问时对末尾的标记。
- iOS 要同时改 `preferredMaximumCardHeight` 和 `maximumContentHeight`。Android 改 `ReaderFloatingLayer` 的 `preferredCardHeight`。

**菜单**：
- 「更多」里的「AI 阅读透镜…」保留，用来选另外两种透镜。
- 按钮条显示时，「AI 句子结构分析」不再出现在菜单里。按钮条被隐藏时恢复。

## 5. 追问

- 点「追问」后：
  - 对话为空时显示 2–3 个预设（`presets`）。
  - 下面是一行输入框，占位「问问这句话…」，旁边是发送按钮。
  - 发出后显示问题，答案流式出现在下面。可以接着问，同一张卡片里多轮。
- 发给模型的内容：
  - 支持多轮的服务（四种直连格式和 ChatGPT 套餐）发 `followUp[].messages`。
  - 只能单轮的服务（AI 代理）发 `flattened`。
  - 只保留最近 4 轮；保留下来的最早一轮的用户消息开头带材料块。
- 键盘弹出时卡片停在键盘上方，打字期间允许暂时盖住原文。收起键盘后恢复避让。
- 每个答案都能「存为笔记」，存的格式是「问：…」换行「答：…」。

## 6. 存为笔记和来源角标

**存为笔记**：
- 追加到这句的批注：
  - 已有同一选区的划线或批注时，在原笔记后空一行，追加「【结果区标题】」换行再接正文。
  - 没有时，用默认颜色新建一条划线，把这段作为笔记。新建的记的是卡片上的原文，不带注音读音。
- 选区没有可用锚点时（跨页、PDF 无锚点），按钮置灰，旁边小字「这段选区不能存笔记」。
- 存好后提示「已存为笔记」。
- 两端现在的保存都是覆盖写，这里必须改成追加：iOS `ReadingAnnotationStore.save`，Android 按 `annotationKey` 先查再写。
- 卡片菜单的「添加笔记」也先找这段已有的笔记，找到就打开它来改（iOS `annotationDraftForCurrentSelection`）。不能另起一条同键的新笔记，否则会把存为笔记追加的内容覆盖掉。
- 选区菜单的「划线」「批注」同样先找已有的笔记。新建的记不带读音的原文，和卡片一致；Android 用 `ReaderRubySelectionText` 读页面上的选区文字，读不到才用 Readium 的。
- Android 两个入口共用 `annotationDraft`，按不带读音的原文和 Readium 的选区文字都找一遍，因为 2.1.0 以前选区菜单记的是后者（带读音）。

**来源角标**：
- 格式「模型名 · 含上下文」或「模型名」，见 `badge`。用在每个 AI 结果上，也用在 AI 模型给出的译文上。
- 模型名的来源：
  - 直连 API 用模型 id。
  - ChatGPT 套餐用 `modelTitle`。
  - AI 代理用代理配置里的模型，没有就写「AI 代理」。
  - 系统翻译写「Apple 翻译」或「本机翻译」，不加「含上下文」。
- 点角标弹出「发送给模型的内容」：模型、原文、上下文（没有就不显示这一节）。

## 7. 未配置 AI 时的引导

- 判断「没配置 AI」：iOS 是 `isProviderConfigured` 判定没有 AI 提供方，Android 是 `preferredAIProviderMode() == null`。这时 AI 按钮照样显示；「查词」不需要 AI，照常工作。
- 点 AI 按钮弹出半屏 Sheet，标题「先接入一个 AI 模型」，三行：
  1. 「用 ChatGPT 账号登录」：直接走现有的套餐登录。
     - 登录成功后，用户不用再改别的设置，AI 按钮马上能用。两端「已配置 AI」的判断都要算上已登录的 ChatGPT 套餐（11-23 加）。
     - 这只影响 AI 功能的判断，翻译服务仍按用户的选择走。
  2. 「填 API Key」：进「AI 大模型」设置页，关掉这页后回到原来的阅读器和位置。
     - iOS 在 Sheet 里用 NavigationStack 包一层 `AIModelsView`。
     - Android 不能结束阅读器：现有跳转设置页用的 `FLAG_ACTIVITY_CLEAR_TOP` 会关掉 `ReaderActivity`。优先在阅读器里用全屏 Dialog 托管 AI 大模型页；托管不了，就新开一个 `MainActivity`，从这页返回时回到阅读器（11-23 改）。
     - 原有的「翻译设置」跳转不在本版范围，不改。
  3. 「不再显示这些按钮」：隐藏整条按钮条，并提示「可在阅读设置里重新打开」。
- 阅读器设置的「翻译与 AI」里加开关「翻译卡片显示 AI 按钮」，默认开。键名 `reader.aiActionBar.hidden`，默认 false。平台备份里有阅读器偏好的就一起备份，这是非敏感设置。

## 8. 查词次数

- 规则见 `lookup_hint.json`：
  - 第 2 次起显示「第 N 次查 · 上次 MM-dd」。上次是今天写「今天」，昨天写「昨天」，跨年写完整日期。
  - 第 3 次起「加入生词本」是主按钮。
  - N 是算上这一次之后的次数。「上次」必须在本次记录之前取，两端现在的记录方法都会覆盖它。
- 显示位置：
  - 查词卡片的词头下方，用 caption 字号。
  - 翻译卡片：选区是单个词时，显示在译文上方。
- 翻译卡片选中单个词时，第 3 次起底部操作行出现实心的「加入生词本」。已在生词本里就不显示。
- iOS 翻译卡片现在不记查词次数。本版改成和 Android 一样：单个词的卡片每张记一次，重绘不重复记。「单个词」沿用各端现有判断。
- 这张卡片已经记过这个词时，从卡片里点「查词」打开的查词页不再记第二次（11-23 加）。
- 卡片和查词页按同一个键记：语言加原形，没有原形才用原样。所以卡片上的「開けた」和查词页的「開ける」算同一个词（Android `LearningRecordRepository.wordKey`）。
- 卡片上的「加入生词本」只加星，不另记一次（11-23 加）：
  - 卡片已经记过这个词：给那条记录加星。
  - 卡片的查词还没存好：等它存完再加星。
  - 卡片没记成（查词失败，或 Android 的卡片还在流式）：这次就算卡片的那一次，记一次并加星。
  - 词典查不到时，用卡片的完整译文当释义，说明写「从阅读正文加入；释义来自本次译文。」。Android 的卡片还在流式、没有完整译文时，提示「词典暂时不可用，未加入生词本。」；iOS 要等译文完成才能加。

## 9. 本章剩余时间（仅 EPUB）

- 规则见 `chapter_estimate.json`。
- 各参数的取法：
  - `readingSeconds`：这本书累计的阅读秒数，加上本次会话已读的时间。
  - `progression`：当前位置在全书中的进度（totalProgression）。
  - `chapterEnd`：下一个阅读顺序资源第一页的进度；最后一章取 1.0；拿不到时为空，结果也为空。
- 阅读器底栏：进度行下面加一行 caption，写「本章还剩约 6 分钟」或「本章还剩不到 1 分钟」，次要文字色。结果为空时不占位。位置变化后更新，阅读中每 30 秒刷新一次。
- 书架「继续阅读」卡片：在原信息行下面加同样的一行。每本书最后一次的 `chapterEnd` 存在 UserDefaults / SharedPreferences，键名 `reader.chapterEnd.<书的 id>`。用书记录里的累计秒数和进度，按同一规则算。

## 10. 伴读

- 红点：打开书时按 `companion_dot.json` 判断，6 小时内重新打开不亮。读完一章仍照旧亮。`lastOpenedAt` 必须是上一次打开的时间，iOS 和 Android 都要核实取值时它还没被本次打开覆盖。两端都在每次保存阅读位置时改写它，所以 6 小时是从上次读到的时候算起。
- 界面上所有「AI 困惑地图」改成「困惑地图」：iOS `ReaderCompanionViews.swift`、`LearningDesignViews.swift`，Android `ReaderCompanionPanel.kt`、`LearningPracticeScreen.kt`。
- 伴读面板的内容本版不改。

## 11. 验收

- 两端：
  - 单元测试和 parity 用例全过。
  - 流式解析四种格式外加套餐，都有离线单测。
  - 迟到增量、取消、只缓存完整结果，各有单测。
- 至少一条 UI 或设备测试（Mock 模式）走完：
  1. 点句。
  2. 点「读懂」看到流式结果。
  3. 点「追问」，选一个预设，看到答案。
  4. 核对卡片不超过半屏、不盖住选区。
- 竖排侧边卡的按钮条换行正常。
- 设置首页和翻译页仍是一屏。
- 两端截图对照一致：浅色和深色、横排和竖排各一组。
