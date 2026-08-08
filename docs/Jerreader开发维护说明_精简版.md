# Jerreader（Jerreader）开发维护说明

> 适用版本：2.6（build 29）  
> 平台：iPhone、iPad、iOS 18 及以上、Swift 6  
> 用途：让后续维护人员快速理解项目，不作为逐行代码说明。

## 1. 项目是什么

Jerreader是一款以中、英、日三语为主的电子书阅读和翻译 App。主要功能包括：

- EPUB、PDF、DOCX、TXT 导入；
- 分页阅读、目录、搜索、书签和阅读位置恢复；
- 划线、批注、笔记及标记跳转；
- 日文竖排原版与横排阅读切换；
- 点句翻译、长按选择、拖动选区；
- Apple 翻译及 GPT、Claude、Kimi 等 API，AI 句子结构分析和跨页句段扩展；
- 阅读内把完整词语加入生词本，以及可关闭的翻译完成触感反馈；
- PDF 文字点击翻译和 OCR；
- 词语翻译中的词典详情、生词本、历史、收藏与导出；
- iPad 与横屏自适应。

## 2. 开发时必须遵守的原则

1. EPUB 和 PDF 的解析、排版继续使用 Readium，不自行重写阅读引擎。
2. 翻译、高亮和解释只做临时覆盖，不修改电子书正文。
3. 用户导入的原文件只读；App 使用复制到沙盒中的副本，不能回写原文件。
4. 页面只依赖 `TranslationService`、`LexicalLookupService` 等协议，不直接处理第三方 API JSON。
5. API Key 只保存在 Keychain，不能写进代码、日志、测试文件或 IPA。
6. 自动化测试必须使用 Mock/Stub，不能依赖外网。

## 3. 工程结构

```text
Jerreader/
├── App/               App 入口、主导航、设置和操作指南
├── Core/Models/       数据模型、SwiftData 模型和通用枚举
├── Core/Services/     翻译、查词及暂时关闭的语音服务协议
├── Data/Services/     Readium、文件导入、网络、缓存、Keychain、导出
├── Features/Library/  书架、导入、分类和书籍管理
├── Features/Reader/   EPUB/PDF 阅读、选区、OCR、翻译和阅读设置
├── Features/Learning/ 翻译、词典详情、生词本、历史、收藏和导出
└── Resources/         图标、颜色、Info.plist 等资源

JerreaderTests/       单元与集成测试
JerreaderUITests/     阅读选区等真实手势测试
docs/                  产品、架构和维护文档
dist/iOS/              iOS IPA、源码包与交付文档
dist/Android/          Android APK 与校验文件
```

正常依赖方向：

```text
界面 → ViewModel/状态 → Core 服务协议 → Data 实现 → Readium/Apple 框架/网络
```

## 4. 几条核心流程

### 导入和打开书籍

```text
文件选择器或“用Jerreader打开”
→ 只读取得文件
→ 复制临时快照
→ 检查重复文件和格式
→ 保存到 App 沙盒
→ 建立 BookRecord
→ Readium 打开并恢复阅读位置
```

每次打开书籍都有独立 attempt ID、自动重试和 25 秒超时保护。旧任务的结果不能覆盖新任务。遇到“卡在加载中”，优先检查 `EPUBReaderViewModel` 和 `EPUBPublicationService`，不要只延长等待时间。

### 竖排 ↔ 横排切换

日文竖排书切横排**不能只靠 Readium 偏好**。Readium 用 `cjk-vertical` / `cjk-horizontal` 两套 Readium CSS 表达 `verticalText`，但只有竖排那套声明了 `writing-mode`，横排那套一条都没有——切横排只是「不再强制竖排」，从不强制横排。自带竖排 CSS 的日文书（`<html class="vrtl">` + `.vrtl{-webkit-writing-mode:vertical-rl}`）因此怎么切都还是竖排。

当前方案（写作方向策略 v1，2026-07-28 起）：`EPUBReaderViewController.writingModePolicyScript(forcesHorizontal:)` 在 document-start 注入，补上 Readium 缺的那条声明——样式表里对 `:root,body,*` 声明 `writing-mode:horizontal-tb !important`（含 `-webkit-`/`-epub-`/`-ms-` 别名），并在 `documentElement`、`body` 上写**行内** `!important`（行内声明高于任何作者样式表规则，出版物自己的 `!important` 也压得过），再对内层容器做有界扫描兜底。原版模式下脚本完全空转。

翻页方向联动：横排 `readingProgression = .ltr`（从左向右），原版竖排 `.rtl`（从右向左）；Readium 的 `goLeft/goRight` 按此映射，边缘点按和滑动自动反向。

收敛检测读的是注入的 `link[rel=stylesheet]` 里有没有 `/cjk-vertical/`，**不能**改回比较计算后的 `writing-mode`——策略脚本会把计算值钉死，它永远等于期望值。详见 `docs/DEVELOPMENT_MAINTENANCE_GUIDE.md` §4.2.1。

### 绝对红线（3.1 补充）

- **不要在 `setupUserScripts` 的 controller 上调用 `removeAllUserScripts()`**。那是 Readium 的 controller，`readium-reflowable`（选区回调、高亮、手势）注册在上面，清掉它选区高亮会整体失效。只允许 add；已加载文档改行为用 `evaluateJavaScript`。有静态测试守护。
- **导入必须用 `asCopy: true` 的选择器**。`.fileImporter` 给的是原文件就地 URL，一访问就会让 iCloud 刷新原文件修改时间。
- **PDF 点句必须有长度上限**（`PDFTextLayerSentenceSelector.maximumTappedSentenceCharacterCount`）。隐藏 OCR 层和日文排版常常整页没有句号，分词器会把整个窗口当成一句，表现为「点一句翻译整页」。超限时回退到所在物理行，再超限就按标点切分。
- **PDF 高亮兜底不得整页涂色**：行选区都匹配不上预期文本时，按预期文本长度截断，绝不直接高亮 PDFKit 放大后的整个 selection。

### 目录、章节与进度（3.0 起）

`ReaderOutlineBuilder` 决定目录：出版物自带目录且多于一条就直接用；为空或只有一条（只列封面的 NCX 没有导航价值）就从 `readingOrder` 生成，文件名是无意义编号时用「第 N 节」；整本只有一个正文文档才显示「没有目录」。

`ReaderOutlineItem.resourceKey` 会归一化 href（去掉 `#片段`、`?查询`、前导 `/`，解码百分号），因为目录常指向 `chapter.xhtml#sec2` 而阅读位置报告裸资源名。打开目录会自动滚动并高亮当前章节。

章节起点由 `resolveOutlineProgressions()` 在上屏后异步取 `positionsByReadingOrder()` 得到，**不要**挪到打开流程的关键路径上。底部进度条拖动时只做本地章节预览，松手才 `seek`——拖动中导航会让每一帧都触发跳转。

### 设置界面的职责划分

- **阅读设置（阅读界面内）**：只作用于当前这本书，界面底部有明确说明；
- **设置 → 默认阅读排版**：只作用于**以后导入的新书**，顶部有说明，底部另有「应用到现有书籍」按钮；
- 「显示可拖动阅读进度」是全局开关，只放在全局设置里，不要再放回单本设置；
- 「文字方向」只对检测到竖排的书（或已切成横排的书）显示，判断依据是 `publicationLayout`，**不是**「是否可重排」——否则英文书上会出现无意义的版式选择器；
- 「轻点句子快速翻译」「点句翻译时禁用点按翻页」属于阅读交互，放在阅读设置的「阅读交互」区，不要塞回「翻译与 AI」详情页。

### EPUB 选区和翻译

阅读交互分为三层：

1. 短按句子：直接翻译；
2. 长按：智能补全单词或句子；
3. 拖动系统手柄：严格使用用户最后框选的范围。

日文 ruby 注音是高风险区域。生效边界：注音处理脚本只注入 EPUB 阅读器（PDF 链路完全独立），且注音摘除仅在日文内容生效——书籍元数据语言为日文、或章节 `lang`/`xml:lang` 为 `ja`、或注音本身含假名，三者满足其一；非日文 EPUB 的 DOM 保持原样。当前方案（选区策略 v3，2026-07 起）在文档加载时把每个 `rt` 的注音文本移入 `data-jerreader-rt` 属性，并用 CSS 伪元素 `rt[data-jerreader-rt]::before { content: attr(data-jerreader-rt) }` 渲染：注音视觉不变，但 DOM 里不再存在注音文本节点，因此 WebKit 的原生选区、长按选词、复制和高亮从物理上只能落在汉字基文上（按在注音上会自动吸附到最近的汉字）。可见高亮的几何 = 基文矩形 ∪ 对应注音矩形，整个 ruby 单元一起亮；点句高亮用页面内自绘矩形层绘制，禁止改回 CSS Custom Highlight（WebKit 对 ruby 内文字不绘制它，表现为"汉字没被选中"）。旧的"把注音语义映射回汉字"的快照与降级逻辑仍保留为安全网。不要在 `touchstart` 或 `selectionchange` 中用 JavaScript 重建 WebKit Range，否则容易再次出现"只选中假名"或"完全没有高亮"。

主要文件：

- `Features/Reader/EPUBReaderHost.swift`
- `Features/Reader/EPUBReaderViewModel.swift`
- `Features/Reader/ReaderSelectionResolver.swift`
- `Features/Reader/TranslationOverlayView.swift`

### 翻译请求

```text
选中文字
→ 等待选区稳定
→ 生成请求身份
→ 查询缓存
→ 调用当前翻译服务
→ 失败时重试一次或切换备用服务
→ 展示译文并按需收藏
```

单次请求上限为 2,000 字符。连续点句时必须使用请求 UUID 和 Task 取消，避免旧译文覆盖新译文或重复消耗 API。

所有服务结果和缓存都必须经过可见性检查；空白或只含零宽字符的结果不能进入成功界面，旧空白缓存应删除后重新请求。AI 解释使用独立服务协议、受限上下文和 watchdog。跨页扩展只使用 Readium 前后文或 PDF 相邻页中可确认的完整句段。

### PDF 点击翻译

有文字层的 PDF 使用 PDFKit 坐标命中文字；扫描版使用 Vision OCR。点按容差为屏幕 16pt。原生长按选区要通过手势门防止松手后的点按清空，并优先读取 PDFKit 当前选区。选中范围必须同时显示临时高亮，关闭翻译后移除。重点检查页面缩放、旋转、双页和坐标转换。

## 5. 修改功能时去哪里

| 需求 | 优先查看 |
| --- | --- |
| 书架、导入、重复文件 | `Features/Library`、`EPUBImportService.swift` |
| EPUB 打开或加载卡住 | `EPUBReaderViewModel.swift`、`EPUBPublicationService.swift` |
| 分页、阅读方向、设置 | `EPUBReaderHost.swift`、`ReaderSettings.swift` |
| 日文注音与选区 | `EPUBReaderHost.swift`、`ReaderSelectionResolver.swift` |
| 翻译弹窗与拖动 | `TranslationOverlayView.swift` |
| AI API、缓存和重试 | `Data/Services` 中的翻译服务 |
| PDF 点击和 OCR | `PDFReaderHost.swift` |
| 查词、生词、历史、导出 | `Features/Learning` |
| 数据模型 | `Core/Models` |

## 6. 构建和测试

用 Xcode 打开：

```text
Jerreader.xcodeproj
```

命令行测试：

```sh
xcodebuild test \
  -project Jerreader.xcodeproj \
  -scheme Jerreader \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

每次修改后至少验证：

- 工程能够编译；
- EPUB 和 PDF 都能打开、退出并再次恢复位置；
- 点句、长按、拖动选区不会和翻页冲突；
- 日文横排、竖排都能选择汉字及其注音；
- 翻译失败、取消、重试和缓存行为正常；
- 用户原文件的大小、修改时间和哈希没有变化。
- iPad 竖屏、横屏都能覆盖全窗口，旋转后阅读位置和控件仍正常。

阅读器、选区、并发或导入逻辑有改动时，还应运行对应 UI 测试和 Xcode Analyze。

## 7. 发布注意事项

- 发布前同时更新版本号和 build number。
- 工程里不能包含 API Key、证书、描述文件、个人 Team ID 或真实测试电子书。
- 用户目前需要未签名 IPA；IPA 内应只有标准的 `Payload/Jerreader.app`。
- 打包后检查 IPA 可以解压，且没有测试资源、用户文件和密钥。
- 真机重点验证 Apple Translation 语言包、文件导入和 API 连接。

## 8. 当前已知限制

- Apple Translation 的真实语言包需要在支持的 iOS 18+ 真机上验收。
- 阅读正文“短按单词并直接加入生词本”仍受 Readium 原生选择能力限制；当前稳定入口是长按完整词语、完成翻译后从译文卡片加入，或使用“学习 → 翻译 → 词语”。
- 不支持 DRM 绕过、旧 `.doc`、MOBI/AZW、整本自动翻译、登录、支付或云同步。
- iPhone、iPad 和横屏均已接入；发布前仍需在目标真机检查旋转与多任务窗口尺寸。

## 9. 接手后的第一步

1. 阅读 `AGENTS.md`、`Jerreader_PRD_v1.0.md` 和 `docs/ARCHITECTURE.md`。
2. 在干净环境编译并运行一次全部测试。
3. 用一份普通 EPUB、一份带 ruby 的日文 EPUB、一份文字 PDF 和一份扫描 PDF 做冒烟测试。
4. 检查 Git 状态和敏感信息，再建立可回退的基线提交。
5. 后续每次只修改一个明确问题，并保留可复现步骤和回归测试。

维护时最重要的判断标准是：**原文不被修改、翻译请求不重复、旧异步任务不覆盖新状态、用户原文件不被写入。**
