# Jerreader 2.0.1（73）维护记录 · 2026-11-20

状态：**双端 2.0.1（73）已构建、验收并打包（2026-11-20）。** iOS：模拟器 XCTest、UI 测试、host-test、analyze 通过，未签名 IPA 已打出；Android：Release 构建（单元测试、Lint）、完整设备测试与 Release 冒烟通过，正式 APK 已打出。两个包都已复制到桌面。没有真机验证，没有用真实账号或 API Key；哪些修复只做了代码走读、没有针对性的自动化验证，见第 5 节。

2.0.1 是 2.0.0 的修复版。起因是用户用了 2.0.0 之后提的五条意见（第 2 节），另外按“其他问题也要复测、复现”的要求，把两端的阅读页、书架、设置、备份、学习页、AI 设置和 PDF 路径逐块审了一遍，确认属实的问题一并修掉（第 3 节）。没有新增功能模块；界面上新增的只有「上下边距」和扩充后的「界面主题」页。

## 1. 版本与标识

- 双端版本 **2.0.1**，build / versionCode **73**（2.0.0 是 72）。下一次双端发版从 74 起。
- 只改 iOS App 目标的 Debug / Release（`CURRENT_PROJECT_VERSION`、`MARKETING_VERSION`），测试目标仍为 1 / 1.0。Android 在 `androidApp/build.gradle.kts`。
- bundle ID（`com.raydon.Jerreader`）、applicationId（`com.jerreader.android`）和签名证书都没变。Android 沿用原来的签名，可以覆盖安装 2.0.0。

## 2. 用户提的五条

### 2.1 选区的填充色不显示

现象：轻点句子（或朗读）时画在原文上的选区，在白色纸张上有填充色，在护眼、冷灰、深色纸张和任何自定义背景色上只剩一圈描边，里面是空的。2.0.0 之前修过一次，只对白色纸张有效。

原因：选区是画在书的网页里的一组小方块，原来用普通的行内样式上色。ReadiumCSS 在护眼和夜间主题下、以及只要设置了页面背景色（冷灰和所有自定义色都算），会对页面里的每个元素声明 `background-color: transparent !important`，夜间主题还会声明 `border-color: currentColor !important`。普通行内样式压不过 `!important` 的样式表规则，于是填充被清成透明，描边在夜间还被换成文字的颜色。

修复（两端相同）：选区方块的填充和描边改成行内 `!important`（只有它能压过样式表里的 `!important`），并加 `background-image: none`，防止书自带的规则在上面铺图案。朗读时“正在读”的那一段同样处理。

- iOS：`EPUBReaderScripts.swift` 的 `markerPaintCSS(fill:stroke:)`。
- Android：`ReaderTapTranslationController.kt` 的 `selectionMarkerPaintCss(fill, stroke)`。
- 测试：iOS `testQuickSentenceHighlightKeepsItsFillUnderReadiumThemeRules`（在 WKWebView 里放上 ReadiumCSS 的这三条规则，分别在夜间、护眼、带页面背景色三种情况下取选区方块的计算样式，要求填充不透明度大于 0.25、朗读段比选区更深、描边不是文字颜色）；Android `SelectionMarkerPaintTest` 和设备测试 `ReaderTapTranslationTest` 里的对应用例。iOS 界面测试 `testSelectionFillOnEveryPaperAndVerticalMargins` 在真实阅读页里依次换四种纸张、点一句话并截图。

### 2.2 上下边距可调

原来只有「页边距」（左右）。现在：

- 原「页边距」改名「左右边距」，含义不变，仍是每本书各自的设置。
- 新增「上下边距」：正文离屏幕上下两端（标准布局下是上下栏）的留白。取值 0 到 4，步长 0.1，是 14pt（Android 为 14dp）的倍数；默认 1，也就是 2.0.0 的留白，所以升级后没动过这项的人版面不变。阅读设置面板里显示“窄 / 标准 / 宽”，「设置 → 默认阅读排版」里显示数值。
- 它和「界面布局」一样是全局设置，对所有书立即生效，不分单本；PDF 按页面本身显示，不受影响。Android 上横排滚动阅读时正文本来就在边缘下面连续滚动，也不加这段留白。
- 随备份保存和恢复（iOS 键 `reader.verticalMargins`，Android 备份里的 `verticalMargins`）。
- 实现：iOS 在 `EPUBReaderHost.swift` 里把给 Readium 的内容留白从固定的 14pt 改成按设置计算，改值后调用 `refreshContentInsets()` 让当前页立即重排；沉浸布局里上下两条“轻点显示上下栏”的感应条跟着留白走，不会压到正文。Android 把 Readium 自带的上下留白资源（`readium_navigator_epub_vertical_padding`）设为 0，改由 `ReaderActivity` 给页面容器加内边距（`updateReaderContentPadding`）。
- 测试：iOS `testVerticalMarginsDefaultToTheShippedGapAndClampStoredValues`，Android `VerticalMarginsTest`；备份往返分别在 `LibraryBackupServiceTests` 和 `BackupInheritanceTest` 里。

### 2.3 读了一会书之后，点开「设置」还停在上次的子页面

原来三个标签各自保留导航栈：在「设置」里进过某个子页面，离开去看书，过很久再点「设置」，看到的还是那个子页面，要自己点返回。

现在离开「设置」标签时就把它收回首页，再点进来总是从设置首页开始。

- iOS：`ContentView.swift` 给设置标签的导航栈加了一个随“离开设置”递增的 `id`。备份或恢复正在进行时不重建（它们要在发起的那一页上报告结果，`LibraryBackupActivity`）。
- Android：`MainActivity.kt` 在切换标签时把 `settingsRoute` 设回首页，并丢掉设置标签保存的页面状态。
- 「书架」和「学习」两个标签不变，仍然保留各自的状态（例如翻译工作台里没写完的内容）。
- 测试：iOS 界面测试 `testSettingsSecondaryPages` 和 Android `LearningUiParityTest.primaryTabsAndLocalModels` 都加了“进子页面 → 切到别的标签 → 切回来应在首页”的断言。

### 2.4 「界面主题」页功能太单一

原来这一页只有一行五个色块。现在分五块，两端相同：

1. **预览**：一张缩小的「继续阅读」主卡和两种按钮，用当前所选色系画出来，点色块时立即跟着变。
2. **外观模式**：跟随系统 / 浅色 / 深色。只影响书架、学习和设置等页面；阅读页的明暗仍然跟所选的阅读纸张走。
3. **主题色系**：从五种增加到八种，新增松石、赤陶、石墨，四个一行。原有五种的存储值不变。
4. **背景光晕**：每页顶部那层很淡的主题色，可以关掉，换成纯净的纸色。
5. **阅读纸张**：显示当前默认纸张，点进去是「默认阅读排版」。做成链接而不是直接在这里改，是为了让“同步应用到现有书籍”仍然只有那一页一个入口。

外观模式和背景光晕随备份保存和恢复。操作指南里「界面主题」一段同步改写。代码：iOS `AppSettingsView.swift`（`AppThemeColorSettingsView`、`ThemePreviewCard`）、`JerreaderTheme.swift`；Android `SettingsScreen.kt`（`SettingsRoute.THEME`）、`shared/.../ui/JerreaderTheme.kt`、`AndroidAppSettingsStore.kt`。

### 2.5 书架右上角的“三个点”图标重画

原图标的圆圈顶到了画布边缘，三个点偏大、间距偏宽，放在导航栏里显得又满又散。重画后圆圈半径从 10 收到 9，点的半径从 1.7 收到 1.25，间距从 7 收到 4.25，三个点聚在圆心附近。图标仍由 `design/jerreader-icons.json` 生成两端的绘制代码（`scripts/generate_ui_icons.py`，`--check` 通过），两端一致。

## 3. 复测中发现并修掉的其他问题

### 3.1 两端都有

- **备份或恢复进行中离开页面**：iOS 上离开设置标签不再把正在备份的页面重建掉；Android 上备份和两种恢复改在应用级的协程里跑（`BackupCenterScreen.kt` 的 `outlivingThisPage`），离开备份中心不会把它们中途取消。
- **登录 ChatGPT 套餐后，已经填好密钥的 DeepSeek 从供应商列表里消失**（2.0.0 带进来的）：默认的 DeepSeek 只填了密钥、没改模型和地址（最常见的用法），登录套餐时它被当成“没动过的默认项”删掉，想换回去要重新添加、重新粘贴密钥。原因是登录套餐为了让套餐立即生效，谎称“正在用的供应商没有密钥”。现在套餐照样接管，但只有真的没有密钥的默认项才让位（`AIProviderSettings.add(_:activeHasKey:takesOver:)` / `adding(…, takesOver)`）。
- **登录套餐后提示“现在用套餐额度翻译”，实际翻译服务可能还是系统翻译**：套餐登录只把它设成“正在用的 AI 模型”，不改「翻译服务」。提示和欢迎页现在按当时的翻译服务分两种说法；翻译服务不是「AI 大模型」时，写明要到「AI 大模型」页点「用它翻译」。行为没变，只是不再说错。
- **同一条语法讲解点两次「保存到语法知识库」，知识库里出现两条一样的**：每点一次都是一份带新编号的草稿。现在标题、原句、讲解都相同的，更新原来那条而不是再加一条。
- **PDF 读到最后一页，进度到不了 100%**：Readium 把 PDF 的每一页记在页首的位置，10 页的 PDF 最后一页是 90%，于是 200 页以下的 PDF 永远不算“已读完”，只有一页的 PDF 永远是“未读”。现在停在最后一页就记为 100%（iOS `ReaderPDFProgress`，Android `PdfLocatorFormat.readingProgress`）。EPUB 不受影响。

### 3.2 iOS

- **目录里“当前章节”不对。** 一个文件里有多个目录项的书（`chapter.xhtml#part2` 这种），以及所有带目录的 PDF（整本是一个资源，目录项都是 `#page=N`），当前章节总是被判成该文件的最后一项：上下栏的章节名不对，目录里标亮的行不对，PDF 每个章节旁边都是 0%，「上一章 / 下一章」会跳过或不动。现在目录项按它在文件里的位置定位：PDF 按页码，EPUB 按锚点在正文里的位置（`ReaderOutlinePosition`、`ReaderOutlineBuilder.anchorFractions`）；从目录或章节按钮跳转后，在读者再次翻页之前，当前章节就是跳到的那一项。
- **从备份恢复后，已登录的 ChatGPT 套餐显示“未设置”**（2.0.0 带进来的）：恢复后重新载入设置时，只按 API Key 重建“已就绪的供应商”，套餐没有 Key，于是被漏掉，要重启应用才恢复。`TranslationSettingsStore.reloadNonSensitivePreferences()` 已补上。
- **批量整理只加标签，书却被移出了文件夹**：批量整理面板里“更改文件夹”默认是开的、目标是“无”，只想加标签的人一保存，选中的书就全被移出原来的文件夹。默认改为关。
- **当前筛选的分类被删掉或改名后，书架变空**：选中的分类不存在了，筛选条件却还留着。现在自动回到“全部”。
- **导航面板「批注」里点“编辑”没反应**：编辑窗和导航面板挂在同一层，系统一次只能弹一个，要手动关掉导航面板它才冒出来。现在编辑窗直接叠在导航面板上面。
- **书内搜索还没出结果就关掉面板，再打开显示“无结果”**：其实是搜索被打断了。现在再打开时自动把这次搜索重跑一遍。
- **拖动进度条松手后，滑块先弹回原位再跳到新位置**：新的一页要过一小会才到。现在滑块留在松手的地方，等新位置报上来再跟随。
- **PDF 里保存过的划线，重新打开书或横竖屏一转就不显示**：数据没丢，批注列表里还在，再改任意一条批注又全部回来。原因是 Readium 把 PDF 视图交给应用的时候文档还没装上，那一次画不出来，之后再没人补画。现在文档装上时补画一次（`PDFReaderHost.swift` 的 `installAnnotationsIfDocumentChanged`）。
- **PDF 里别的应用留下的批注会把点句翻译“吃掉”**：在预览、Adobe 等应用里做过高亮、图章的 PDF，或者带链接的论文，点这些位置上的句子毫无反应。原来只要点到任何批注就直接返回。现在只认本应用自己画的划线。
- **PDF 上点自己的划线没反应**：轻点翻译关着时只会切换上下栏；划线落在左右边缘时会变成翻页。现在和 EPUB 一样，点到自己的划线总是打开它。
- **横屏（两页并排）时点扫描版 PDF，“正在识别本页文字…”一直不消失**：点的若不是系统认定的“当前页”，识别结果被整个丢掉，提示也不撤，每点一次重新识别一次。现在识别完总会撤掉提示并留下缓存，只要那一页还在屏幕上就照常出译文。
- **设备日历不是公历时，「阅读成长」里的阅读时长全是 0**：阅读时长按日期记，写的时候用的是设备日历（和历写成 0008 年，佛历写成 2569 年），报告按公历去找，永远对不上。现在一律按公历记；之前按和历、民国历、佛历记下的日期在读取时换回公历。

### 3.3 Android

- **阅读页里点“去设置翻译服务”，打开的是设置首页**：应该直接到「翻译与 AI」。应用已经在后台时（`onNewIntent`）同样处理。
- **自定义背景色下文字颜色不跟着变**：选了深色的自定义背景，文字还是深色。现在按背景的明暗配浅墨或深墨，和 iOS 用同一组颜色；原来选的纸张主题也随自定义色一起保存，关掉自定义色后能回到原来的纸张。
- **阅读页的系统栏不跟纸张走**：深色纸张上状态栏图标还是深色，三键导航栏是一条颜色不对的色带；阅读设置面板底部有一条黑边。现在状态栏和导航栏的图标明暗、底色都随当前纸张；阅读设置面板一直铺到屏幕底，三键导航时三个导航键落在面板自己的纸色上（手势导航时内容直接滚到屏幕边缘）。
- **译文卡片上“加批注”，批注落在了页首**：用的是这一页的位置，不是选中的那句话。现在取真正选中的位置。
- **点句翻译偶尔留下一块去不掉的选区**：页面选中了句子，但阅读引擎没有返回选区，卡片没弹出来，选区就留在页面上没有东西可以关。现在这种情况下把选区清掉。
- **PDF 连续滚动时，只有屏幕中间那一页能点句翻译**：屏幕上同时露出的上一页末尾、下一页开头，点了只会切换上下栏。现在点到哪一页就识别哪一页；从译文卡加的批注也记在所点的那一页。
- **文字型 PDF 可能整页识别不到字**：给识别用的页面图是画在透明底上的，Word、LaTeX 导出的 PDF 没有自己的底色，交给识别器时成了黑底黑字。现在先铺白底再画。
- **PDF 点句后拖动或缩放页面，选区框留在原来的屏幕位置**：现在松手后回到那句话上，和已保存的批注一样。
- **从语法知识库「回到原书原句」打开 PDF，应用被系统回收后再回来会崩溃**：这个入口没告诉阅读页书是 PDF。已补上。
- **生词复习每评一次分，卡片先闪一下“正在准备复习…”；在「管理复习」里暂停或重置一个词，列表清空再回来，滚动位置丢失**：现在重算期间沿用上一份结果，和 iOS 一样。
- **直连 AI 的请求被取消或超时，显示成“服务不可用”**；**PDF 识别进行中退出阅读页可能崩溃**：两处都已处理。
- **其他**：译文卡和查词卡互相切换时，手动拖动过的位置不再被清零；拖动进度条时不再提前把进度写成目标值；换纸张或选区颜色时关掉开着的译文卡（它的颜色已经不对了）；竖排的书在「文字方向」里能看到并选回“竖排”。
- **书架**：筛选菜单里当前选中的一项带对勾；按书名、作者排序改成自然顺序（“第 2 卷”排在“第 10 卷”前面，全角数字同半角）；批量加标签时中文逗号、顿号也能分隔；导入较大的书时显示“正在整理这本书…”，不再像没反应。

### 3.4 这次没改、留到以后的

都是 2.0.0 之前就有的两端差异，不影响主要流程，改动面较大，没有放进这个修复版：

- Android 的批量整理没有全选，且“更改文件夹”的默认语义和 iOS 相反。
- 书架上长按一本书弹出的菜单，两端的项数不同。
- 空书架点“从备份恢复”，恢复完返回时落在设置首页而不是书架。
- Android 换封面时如果应用被系统回收，回来后这次换封面失效。
- 「学习」页在两端保留状态的范围不完全一致。
- 译文卡片开着时点页面空白处，iOS 上选区被清掉而卡片留着（可能是有意的设计，没动）。
- Android 的「学习」各页在旋转屏幕、系统切换深浅色时会丢掉正在进行的内容（复习到一半的一组、翻译工作台的结果、没保存的语法笔记）。要逐个改成可保存的状态，改动面大。
- Android 本机翻译语言包只在 Wi-Fi 下下载；用流量时点“下载”会等满 3 分钟才提示超时，期间本机翻译和卸载被同一把锁卡住。
- 离线词典下载途中离开页面，下载被取消（两端）；Android 上如果这时应用被系统杀掉，会留下没清理的临时文件。
- iOS 翻译工作台翻译到一半切到别的标签，翻译被取消且没有提示。
- Android 三键导航下，书架的导入、管理书籍和语法笔记编辑等底部面板，导航键所在的一条是白色，和面板的纸色略有差别（Material 底部面板自己的窗口，2.0.0 就是这样）。

## 4. 验证状态

日志都在 `.build/SafeValidationLogs/`（iOS 的 build、host-test、analyze 和打 IPA 走后台入口，在源码副本里构建，日志在副本的同名目录）。开发和大部分验证在 11-19，收尾和打包在 11-20 凌晨。

iOS（XCTest 用模拟器 YomiReader QA，UI 测试用 iPhone 16 Pro Max，都是 iOS 18.2）：

- `20261119-190854-w3jg3ifj`（build，后台入口）：**通过**。五条意见和第一轮复测修复改完后的第一次编译。
- `20261119-192748-gfjc5f3r`（build）：失败，只有一处：`PDFReaderHost.swift` 新加的属性类型 `PDFDocument` 有歧义（PDFKit 和 Readium 各有一个同名类型），写明 `PDFKit.PDFDocument` 后重新提交。
- `20261119-192839-80qkveut`（build）：**通过**，没有源码警告，只有原有的 scheme 构建顺序警告。
- `20261119-200424-br44dx_0`（test）：**通过**，XCTest 300 通过、3 跳过、0 失败（2.0.0（72）是 290 通过；跳过的 3 项照旧要外部真实书籍）。这一版新增的都在其中：选区填充（`testQuickSentenceHighlightKeepsItsFillUnderReadiumThemeRules`）、上下边距（`testVerticalMarginsDefaultToTheShippedGapAndClampStoredValues`）、登录套餐保留已填密钥的默认供应商（`testSigningInToThePlanKeepsADefaultProviderThatHasAKey`）、PDF 末页进度（`testPDFProgressReadsTheLastPageAsFinished`）、目录项定位（`testOutlinePositionReadsPDFPagesAndAnchors`）、阅读时长公历键（`testReadingActivityDayKeysAreGregorianWhateverTheDeviceCalendar`），以及 PDF 划线补画（`testPDFHighlightsGivenBeforeTheDocumentLoadsAreDrawnOnceItDoes`：在视图加载前交给阅读页一条划线，文档装上后第 0 页上恰好有一条本应用的批注，再交一次仍是一条）。
- `20261119-201614-jliwbyn4`（ui-test，整套）：17 通过、11 跳过、1 失败。跳过的是要真实注音 EPUB 的 10 项和 iPad 专用的 1 项，同 2.0.0。`testSettingsSecondaryPages` 通过，其中有这一版加的断言：主题页八种色系、外观模式、背景光晕、阅读纸张入口，默认阅读排版里的上下边距，以及“进子页面 → 切到别的标签 → 切回来在设置首页”。截图逐张看过：主题页上半、选石墨后、下半，设置首页（底部显示 Jerreader 2.0.1），默认阅读排版的左右 / 上下边距，书架右上角的新图标，阅读设置面板四屏。失败的 1 项是这一版新写的 `testSelectionFillOnEveryPaperAndVerticalMargins`，原因在测试自己：这台模拟器的应用临时目录里放着一本测试用的真实 EPUB，示例书入口优先打开它，停在封面页（一张图），测试找不到要点的正文。给示例书入口加了一个只在 Debug 构建里存在的参数 `--jerreader-sample-text-ui-test`（有它就总是导入自写的 TXT 示例文本），测试改为按位置点正文、每次截图后用「关闭翻译」收起卡片，然后单跑：
- `20261119-205154-p8ojul2i`（ui-test，只跑上面这一项）：四种纸张的四张截图都留下了，随后超过单项测试默认的 2 分钟时限被终止，没跑到上下边距。把这一项的时限设为 360 秒后再单跑：
- `20261120-002120-tedkkh91`（ui-test，只跑上面这一项）：**通过**，162 秒。六张截图逐张看过：护眼、冷灰、深色、浅色四种纸张上，被点的那句话都有填充色（分别是橙、青、蓝、浅蓝），译文卡片正常；上下边距拖到「宽」时正文上下各空出一大段，拖到「窄」时正文贴到上下栏。
- 上面的 XCTest 之后，iOS 应用源码只改了一处：`LibraryView.swift` 里示例书入口的那个 Debug 参数（`#if DEBUG` 之内，不进 Release 包）。界面测试文件的改动只有上面说的这一项测试。
- `20261120-002203-bq0v79dr`（host-test，后台入口）：**通过**，16 项全过，含 `ai-providers`、`chatgpt-plan` 和 `parity-fixtures`。
- `20261120-003222-mwmewg28`（analyze，后台入口）：**通过**，没有源码警告，只有原有的 scheme 构建顺序警告。
- `20261120-003352-nbmpy8ha`（`scripts/background_build.py submit ipa`，Release，generic iOS device，不签名，在 launchd 后台域跑）：**通过**（`** BUILD SUCCEEDED **`），约 6 分钟。没有源码警告；25 条 `.pcm: No such file or directory` 是 dsymutil 找模块缓存时的警告，另有 1 条原有的 scheme 构建顺序警告，同 2.0.0。
- iOS 正式包：`dist/iOS/Jerreader-2.0.1-build73-20261120-004001-unsigned.ipa`，15,080,769 字节（2.0.0（72）是 15,004,902），SHA-256 `65096fc61028cf9b7208ca6c2331db582e0436a440a5359f7186ec9ff4651f0f`。bundle ID `com.raydon.Jerreader`、CFBundleShortVersionString 2.0.1、CFBundleVersion 73、最低 iOS 18.0、只有 arm64、不含测试夹具；Release 可执行文件里查不到 `--jerreader-sample-text-ui-test` 这个测试参数。

Android（构建都是 `JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh`）：

- `20261119-185341-ykn2ttd_`（构建）：**通过**。五条意见和第一轮复测修复改完后的第一次构建。
- `20261119-192753-93l3wqt1`（构建，含第二轮复测修复）：失败在 `lintDebug`，2 个错误，都是 `StateFlowValueCalledInComposition`：`AIPlanScreens.kt` 两处在组合函数里直接读了 `store.preferences.value`。改成 `collectAsState()` 后重新提交。
- `20261119-193312-lkm7nz9q`（构建）：**通过**。
- `20261119-194733-z9oxuiex`（设备测试，full 范围 + Release 冒烟，模拟器 API 35）：**通过**，仪器测试 50 项全过（回归 38 项 + 学习界面 12 项），Release 冒烟通过。118 张截图逐张看过：主题页（浅色上半、下半、深色）、护眼 / 冷灰 / 深色 / 浅色四种纸张上的选区填充、新的“更多”图标、设置首页的版本号、默认阅读排版里的两项边距、一页 PDF 进度 100% 都正常。只发现一处问题：三键导航时，阅读设置面板最底下的三个导航键压在滚动的内容上。修法是在面板底部垫一条和面板同色、与导航键等高的底条（手势导航时这条高度为 0），见 `ReaderSettingsSheet.kt`。
- `20261119-200755-ybt_8316`（构建，含上面的底条）：**通过**，10 分 32 秒。shared 主机测试 98 项、androidApp 单元测试 159 项全部通过（2.0.0（72）是 88 + 145）；lintDebug、lintRelease 没有错误；debug、androidTest 和 release 包都打出来了。几次构建合起来，Kotlin 的编译警告有 4 条，都落在这一版没有动过的代码行上（`shared/.../ui/ReaderChrome.kt:124` 多余的类型转换、`JerreaderApplication.kt:33` 用了已弃用的常量、`ConfiguredTranslationService.kt:497` 的 `when`、`androidApp/build.gradle.kts:89` 的弃用写法），不影响行为，这次没有清。
- `20261119-202643-30iab7dw`（设备测试，full 范围 + Release 冒烟，模拟器 API 35，对应上面这次构建）：**通过**，仪器测试 50 项全过（回归 38 项 + 学习界面 12 项）。Release 冒烟：覆盖安装后 EPUB 和 PDF 都真的打开并返回书架，ML Kit 组件都能创建，书架、学习、设置三个标签都能打开，设置首页显示 Jerreader 2.0.1。阅读设置面板的六张截图（四屏、沉浸布局、界面控制）里，导航键都落在面板自己的底条上，不再压内容。这次构建相对上一次只多了底条，其余页面的截图沿用上一轮的核对结果。
- 打包：`scripts/package_release_apk.py` 用上面这次构建和设备测试，`--previous-apk` 用 2.0.0（72）的包。Android 正式包 `dist/Android/Jerreader-Android-2.0.1-build73-20261120-002156-release.apk`，42,367,102 字节（2.0.0（72）是 42,367,100），SHA-256 `9812650503c84c039bf9a8d5aec13908ea92151b53bbe4d74da902d97e840672`。applicationId `com.jerreader.android`、versionName 2.0.1、versionCode 73、minSdk 24，只有 arm64-v8a；和模拟器上验过的 x86_64 包除原生库外逐项相同，签名证书和 2.0.0 相同，可以覆盖安装。

## 5. 没验证的

- 两端都没有真机验证：iOS 只在 iOS 18.2 模拟器上测过，Android 只在 API 35 模拟器（x86_64）上测过。交给手机的 arm64-v8a 包和测过的 x86_64 包除原生库外逐项相同，但没有装到真机上。
- 选区填充：没有拿到用户出问题的那本书。修复依据的是 ReadiumCSS 的规则，iOS 用单元测试在 WKWebView 里按这些规则取了计算样式，两端的截图用的是自写的示例文本和合成 EPUB，纸张只覆盖了浅色、护眼、冷灰、深色四种预设；自定义背景色只有 iOS 单元测试里“带页面背景色”那一种情况，没有截图。如果某本书自带的样式用别的办法盖住选区，这次的测试发现不了。
- Android 的 PDF 修复（连续滚动时相邻页点句、文字型 PDF 铺白底再识别、拖动或缩放后选区回位、识别中退出、从语法知识库回到 PDF 原句）都是按代码走读修的。设备上和 PDF 有关的验证只有 Release 冒烟打开一份一页的文字 PDF，没有针对这几条的设备测试。
- iOS 的 PDF 修复里，划线补画有单元测试（真实的 PDFView）；别的应用留下的批注不再挡住点句、点自己的划线、横屏双页时的识别提示这三条没有自动化验证，只做了代码走读。
- 第 3 节其余没有写明测试的条目（批量整理的默认值、筛选分类被删后回到“全部”、批注编辑窗叠放、搜索重跑、进度条滑块、Android 的系统栏颜色、批注落点、残留选区等）同样只做了代码走读，或者只在整套回归里没有出错，没有专门为它们写的测试。
- ChatGPT 套餐仍然没有用真实 ChatGPT 账号在 2.0.1 上登录过；“登录后已填密钥的 DeepSeek 消失”的修复只有单元测试。其他 AI 供应商也都没连真实服务。
- 外观模式和背景光晕：截图看过 iOS 浅色、Android 浅色和深色下的主题页；“跟随系统”时系统切换深浅色的那一下没有自动化验证。动效同 2.0.0，只核对了参数。
- iOS UI 测试里要真实注音 EPUB 的 10 项和 iPad 的 1 项照旧跳过；iPad 版已暂停，不验收。
- 第 3.4 节列的遗留问题这次都没改。
- 模拟器上的一处痕迹：`20261119-201614-jliwbyn4` 里失败的那次测试，把 iPhone 16 Pro Max 模拟器里那本自放的真实 EPUB 的纸张改成了“护眼”。只影响这台模拟器里那本书的显示，不影响任何测试的断言。
