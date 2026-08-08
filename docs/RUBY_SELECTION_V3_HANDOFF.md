# 注音选区 v3 改动交接说明（2.1 build 23–24）

> 写给下一位维护者。本文只讲 2026-07-23 这次"日文注音选区高亮"改动：改了什么、为什么、边界在哪、哪些测试在守护它、什么绝对不能动。
> 通读顺序：先看本文，再看 `docs/Jerreader开发维护说明_精简版.md` 和 `docs/DEVELOPMENT_MAINTENANCE_GUIDE.md` §4.4（两者已同步为 v3 内容）。

## 1. 修复的问题

用户在日文 EPUB（如《Just Because!》，竖排、大量交错式注音 `<ruby>美<rt>み</rt>緒<rt>お</rt></ruby>`）中点句/长按翻译时：

1. 选区要么落在假名上、要么完全选不上（长按在注音上时 WebKit 折叠选区）；
2. 即使文字选对了，高亮在带注音的汉字处"缺一块"，看起来像没选中。

## 2. 两个根因与对应方案

### 根因 A：`rt` 里存在真实文本节点

旧方案只用 `user-select:none; pointer-events:none` 挡注音，但 WebKit 的长按选词不理会：按在注音上要么真选中假名，要么直接折叠选区，然后靠 Swift 侧异步"映射回汉字"补救，时好时坏。

**方案（选区策略 v3）**：文档加载完成（DOMContentLoaded）后，把每个 `rt` 的文本移入 `data-jerreader-rt` 属性并清空子节点，用 CSS 伪元素渲染：

```css
rt[data-jerreader-rt]::before { content: attr(data-jerreader-rt); }
```

注音渲染像素级不变（横排、竖排均验证过），但 DOM 文本流中不再存在注音文本节点——WebKit 的原生选区、长按、复制**物理上**只能落在汉字上；按在注音上会自动吸附到最近的真实文本（即汉字）。`rp` 同理移入 `data-jerreader-rp`（不渲染，ruby 引擎下本来就不显示）。

### 根因 B：CSS Custom Highlight 不给 ruby 文字绘制

点句高亮旧实现用 `CSS.highlights` + `::highlight()`。WebKit 对 `<ruby>` 内的文字**静默跳过绘制**——文字明明在 Range 里、翻译也正确，但高亮在注音汉字处出现缺口（用户看到的"选不中"截图就是这个）。

**方案**：弃用 CSS Custom Highlight，改为页面内自绘矩形覆盖层：

- 矩形集合 = 基文文本矩形 ∪ 每个基文节点配对的 `rt` 注音矩形（`jerreaderReaderSelectionRects`），整个 ruby 单元一起亮；
- 半透明放在**容器**上（`opacity:0.26`，子块纯色），基文与注音矩形重叠处不会叠色变深；
- 用绝对文档坐标（`rect + window.scrollX/Y`），Readium 分栏翻页不漂移；
- 长按/原生选区的 UIKit 高亮层（`EPUBSelectionHighlightView`）同样并入注音矩形，绘制前先剔除被包含矩形再按行合并。

翻译发送的文本**永远只有汉字基文**，注音只进视觉几何。

## 3. 生效边界（重要）

- 脚本只注入 EPUB 阅读器（`EPUBReaderViewController`）。PDF 是独立链路（PDFKit/Vision OCR），完全不受影响。
- 注音摘除只对日文内容生效，三个信号满足其一：
  1. 书籍库元数据语言为 `ja`/`jpn`（Swift 侧 `prefersJapaneseRubyDetachment`）；
  2. 章节文档 `<html lang>` 或 `xml:lang` 以 `ja` 开头（脚本内检测）；
  3. 任一 `rt` 注音含假名（脚本内 `kanaPattern` 检测）。
- 非日文 EPUB（含中文拼音/注音符号 ruby）：DOM 原样不动，只应用基础 CSS。有专门测试守护（见 §5）。

## 4. 代码地图（本次全部改动点）

| 位置 | 内容 |
| --- | --- |
| `Jerreader/Features/Reader/EPUBReaderHost.swift` → `selectionPolicyScript(detachRubyAnnotations:)` | v3 核心：CSS + 注音摘除 + 语言边界 + MutationObserver 兜底。幂等，可重复执行。注入点在同文件 `setupUserScripts`（document-start）与 `installSelectionPolicy`（恢复路径） |
| 同文件 → `quickSentenceDOMHelpers` | 新增 `jerreaderReaderViewportRectFilter` / `jerreaderReaderPairedAnnotation`（交错 ruby 中基文节点→配对 rt）/ `jerreaderReaderSelectionRects`（基文∪注音矩形） |
| 同文件 → `quickSentenceHighlightScript` | 点句高亮：自绘覆盖层（`#jerreader-reader-quick-sentence-highlight`，请求 token 存在 overlay 的 dataset 上） |
| 同文件 → `clearQuickSentenceHighlightScript` | 按 token 清除自己的覆盖层，不清新请求的 |
| 同文件 → `nativeSelectionSnapshotScript` / `selectionPointSnapshotScript` | 快照几何改用 `jerreaderReaderSelectionRects` |
| `Jerreader/Features/Reader/EPUBSelectionBridge.swift` → `EPUBSelectionHighlightView.merged` | 新增包含去重（注音整框会包含汉字条带），再按行合并 |
| `JerreaderTests/ReaderServicesTests.swift`、`LibraryTests.swift` | 断言全部升级到 v3 语义（详见 §5） |
| `docs/Jerreader开发维护说明_精简版.md`、`docs/DEVELOPMENT_MAINTENANCE_GUIDE.md` §4.4 | 已同步 v3 说明与红线 |

版本：`MARKETING_VERSION = 2.1`，`CURRENT_PROJECT_VERSION = 24`。

## 5. 测试在守护什么

全部在 `JerreaderTests`（模拟器 WKWebView 上跑，真实 WebKit 行为）：

- `testSelectionPolicyLetsPhysicalTouchesReachRubyBaseWithoutRewritingRanges`：脚本静态不变量（含 `data-jerreader-rt`、版本 3、无 touchstart/selectionchange/addRange）。
- `testRubySelectionBridgeRecoversKanjiWithoutMutatingNativeSelection`：核心回归。断言：rt 摘除后注音仍渲染（横排+竖排）、跨整个 ruby 的选区只含汉字、**选区几何必须覆盖注音区域**（就是用户截图那个 bug 的回归锁）、快照不改写浏览器 Range。
- `testSelectionPolicyLeavesNonJapaneseRubyDOMUntouched`：边界锁。中文拼音 ruby + 非日文书 → rt 文本原样保留。
- `testExternalRubyPublicationFixtureWhenProvided`（环境变量 `JERREADER_RUBY_FIXTURE_BASE64` 门控）与 `LibraryTests.testExternalRubyEPUBRecoversBaseSelectionInHorizontalAndVerticalModes`（`JERREADER_RUBY_EPUB_RESOURCE` 门控）：真书夹具校验，平时跳过。
- `LibraryTests.testFirstSpine...`：首个 spine WebView 必须在加载前拿到 v3 脚本（`__jerreaderReaderSelectionBridgeVersion === 3`）。

## 6. 红线（绝对不要，改前必读）

1. **不要改回 CSS Custom Highlight**（`CSS.highlights` / `::highlight()`）画阅读器高亮——WebKit 对 ruby 文字不绘制它，会原样复现"汉字没被选中"。
2. **不要删除 `rt` 元素或隐藏它的盒子**——v3 只移走其中的文本节点，元素、布局、视觉必须保留（否则注音消失/排版塌陷）。
3. 不要在 `touchstart`/`selectionchange` 里重建或改写 WebKit Range（历史事故根源）。
4. 不要把注音摘除提前到文档解析中（半解析的 rt 会只捕获一半读音），保持 DOMContentLoaded 时机。
5. 不要给翻译文本混入注音——注音只允许进"视觉矩形"。
6. 摘除逻辑必须保持幂等（脚本会被 document-start 注入和恢复路径重复执行）。

## 7. 本机构建 / 测试 / 打包速查

如果完整 Xcode 不在系统默认路径（且 `xcode-select` 指向 CommandLineTools），不要改系统设置，使用当前终端的环境变量：

```sh
export DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer
```

跑单元测试（**必须带 `CODE_SIGNING_ALLOWED=NO`**：项目在 iCloud 同步的 Documents 目录下，文件供应器会不停补 FinderInfo 扩展属性，模拟器 ad-hoc 签名会报 "resource fork, Finder information, or similar detritus not allowed"；这不是代码问题）：

```sh
xcodebuild test -project Jerreader.xcodeproj -scheme Jerreader \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -derivedDataPath .build/DerivedData \
  -only-testing:JerreaderTests \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
```

打未签名 IPA：按 `docs/DEVELOPMENT_MAINTENANCE_GUIDE.md` §10（Release + `CODE_SIGNING_ALLOWED=NO` → `Payload/Jerreader.app` → `ditto -c -k --norsrc`），产物放 `dist/iOS/`，命名 `Jerreader-<版本>-build<NN>-unsigned.ipa`，发布前记得同步升 `CURRENT_PROJECT_VERSION`。

当前交付基线：`dist/iOS/Jerreader-2.1-build24-unsigned.ipa`
SHA-256：`68c643fd33d8c0c2ec32b355297900651dde90fa90daf5127d2bf2a7cd29d214`

## 8. 遗留观察项（非缺陷，供后续验证）

- 摘除后 `Selection.toString()`、复制、Readium locator 文本均不含注音（这是预期行为，也是翻译正确的前提）。
- 全文搜索走 Readium 对原始资源的检索，不受 DOM 摘除影响；如未来发现搜索定位到含注音文本的偏移异常，从 `EPUBPublicationService` 查起，与本改动无关。
- 若某本日文书元数据缺失、章节无 `lang`、注音又全是罗马字（极罕见），三个边界信号都不满足时会回退为"不摘除"。如遇到，优先在导入时修正书籍语言元数据，不要放宽脚本边界。
