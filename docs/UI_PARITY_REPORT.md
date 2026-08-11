# iOS 原版 vs 统一版：UI 与功能差异核对

最后一次原版对拍：2026-08-06，原版为 1.2.0 (build 46)。

> **1.5.1 增量核对（2026-08-10）**：设置页的图标/双行文字、学习模块开关与词典来源层级已重新对齐；学习工具默认进入词语查词，句子切回词语时清空内容。
>
> **1.5.0 增量核对（2026-08-10）**：学习页在两端统一为
> 「翻译／复习／生词本／历史」四分段；日语挖空卡、答案详情和四档评分分别在
> iPhone 16 Pro 模拟器与 Android 1080×2400 模拟器逐屏检查，无截断或控件重叠。
> 设置页新增同名「显示学习模块」开关，关闭后两端都只隐藏主导航入口并保留数据。
> 本文其余逐像素数字记录的是 1.2 原版迁移核对历史，不应解释为 1.5 与旧版仍逐像素相同。

结论：**UI 完全一致（含设置页、阅读器、译文卡片，逐像素 0 差异）；
统一版现在是主版本，并且已经比原版多修了两个 bug。**

---

## 一、核对方式

1. **源码全树 diff** —— 最可靠，直接看代码本身；
2. **逐字节 `cmp`** —— 对每一个含 `: View` 的 SwiftUI 文件；
3. **模拟器同数据像素对拍** —— 两个 App 共存，容器数据整份复制成一致；
4. **算法等价复算** —— 对走了 Kotlin 的那几段，用同一输入独立算两遍比对；
5. **点击自动化对拍** —— System Events 点 Simulator 窗口 + `simctl` 截图，走到设置页 /
   阅读器内部 / 译文卡片，见第七节；
6. **XCUITest** —— fixture 对不上，跑不出结论，见第六节。

## 二、UI：一致

59 个 Swift 文件里只有 3 个不同，全部是阅读器内部算法。

```
Only in UNIFIED/Core: Shared              ← 新增的类型摆渡层，不含 UI
ORIG/Features/Reader/EPUBReaderHost.swift      differ
ORIG/Features/Reader/EPUBSelectionBridge.swift differ
ORIG/Features/Reader/ReaderModels.swift        differ
```

**所有含 `: View` 的文件逐字节一致，无一例外。** 画界面的代码一个字节都没动。

像素对拍（iPhone 16 Pro / iOS 18.2，同一份 `Documents` + `Library`，忽略状态栏时钟所在的前 150 行）：

| 页面 | 不同像素 |
|---|---|
| 学习 | **0**（0.000%） |
| 设置 | **0**（0.000%） |
| 阅读器（竖排正文） | **0**（0.000%）—— 修 bug 前；见第四节 |
| 长按选区 + 译文卡片 | **0**（0.000%） |
| 书架 | 5 459（0.173%），仅系统状态栏时钟 + Home Indicator 动画相位 |

设置页、阅读器、译文卡片这三屏需要点击才能到达，是这一轮新补上的（自动化办法见第六节）。
至此**没有未对拍的界面**。

## 三、本次补平的三处

上一轮给原版做的修复，统一版是更早复制的，所以缺了。已全部补上：

| 项 | 说明 |
|---|---|
| `hex(from:)` 钳制 | Display-P3 扩展 sRGB 分量越界导致自定义颜色失效，统一版原本还是旧版本 |
| 译文卡片表头三槽位 | 拖拽横线压住"..."的修复 |
| 竖排逐页翻页 | `usesVerticalPageSnapping` / `stepVerticalPage` 整套 |

另外发现并修掉一处**统一版独有**的偏差：core 的 `toArgb` 用 `toLong()` **截断**而非四舍五入，
每个通道都被系统性压低最多 1（`0.25 × 255 = 63.75` → 63，而 iOS 的 `rounded()` 给 64）。
修正后两边 RGB **完全一致**：

| 主题 | 原版 Swift | 统一版（经 Kotlin） |
|---|---|---|
| LIGHT | rgba(64, 145, 209, 0.399) | rgba(64, 145, 209, 0.4) |
| SEPIA | rgba(201, 120, 33, 0.449) | rgba(201, 120, 33, 0.45) |
| COOL_GRAY | rgba(31, 133, 158, 0.399) | rgba(31, 133, 158, 0.4) |
| DARK | rgba(82, 178, 250, 0.42) | rgba(82, 178, 250, 0.419) |

alpha 仍差 ~0.001，是 ARGB 打包成字节的量化（1/255），无法也不必消除。

## 四、找出并修掉的 bug

统一版从这一版起是主版本，两处修复都**只落在统一版**，原版仍带着这两个缺陷。

### 4.1 竖排阅读器顶部空一条 62pt 的死带，每列底部被切掉

**表现**：竖排书进阅读器，正文有时整体下沉 62pt —— 顶部一条空白，每一列的最后一两个字被下边缘切掉。
不是每次都出现，大约五次里有一次，重启 App 就好，所以一直被当成偶发看走眼。

**成因**在 Readium 里。`EPUBReflowableSpreadView.updateContentInset()` 对 scroll 模式
（竖排永远走 scroll 模式，见 readium/swift-toolkit#370）把 webView 铺满整屏，
安全区靠 `scrollView.contentInset` 让出，`.regular` 竖直尺寸类下默认是上下各 62pt。
WebKit 会把这个 inset 当作 obscured inset —— 页面**已经**排在让出后的可见区里了；
可 UIKit 紧接着又按惯例把 `contentOffset.y` 设成 `-contentInset.top`，
于是这 62pt **被付了两次**。竖排页面正好只有一屏高、横向溢出，滚不回去，就永久留在错位状态。

实测签名非常干净：正常时正文第一行暗像素在 **137.7pt**，出问题时 **199.7pt**，
差值正好是 62.0pt。

**走过的弯路**：第一版修复是实现 `navigatorContentInset(_:)` 返回 `.zero`。
错的 —— `scrollView.contentInset` 会改变 WebKit 的**布局视口**，抹掉它页面会重排，
每列塞进更多字，正文第一行跑到 114pt，文字直接钻到顶栏底下。已完整回滚。

**正确的修法**只动 offset、不动 inset，在
`EPUBReaderHost.swift` 的 `pinVerticalContentOffset(in:)`：内容不高于视口时（竖排必然如此）
把 `contentOffset.y` 归零。仅在布局回合里做一次会**输掉竞态**（Readium 从 `applySettings`、
`safeAreaInsetsDidChange`、`traitCollectionDidChange` 都会重新下 inset，UIKit 随之改的 offset
并不触发新的布局回合），所以配一组 KVO：

- `contentOffset` —— UIKit 那次位移本身；
- `contentSize` —— 位移可能落在 WebKit 还在量的时候，随后的重排只以 contentSize 变化的形式到达。

两个 key 都要。只挂 `contentOffset` 时五次里仍会漏一次。

**验证**：连续 8 次冷启动进同一本竖排书，正文第一行**全部 137.7pt**，
且 L1 与 L2–L8 逐像素 **0 差异**。修复前同样的循环里 K5 是 853 977 px（29.98%）的错位。

原版仍是坏的 —— 同一轮同一台模拟器里对拍：

```
M-orig-r2-reader.png: 200.0pt      ← 原版，带死带
M-kmp-r2-reader.png : 137.7pt      ← 统一版，已修
```

### 4.2 切换排版方向后，屏上的选区色块沿着旧的行轴

`EPUBSelectionHighlightView.updateWritingMode(vertical:)` 只改了标志位就
`setNeedsDisplay()`，没有重新合并已经画在屏上的矩形。而合并是**沿行轴**分组的：
横排一行一块，竖排一列一块。切换排版方向不会立刻清掉选区（Readium 要先重载 spine item），
这中间那几帧色块是按**上一个**轴分的组，横竖颠倒。

修法：`EPUBSelectionBridge.swift` 里多存一份合并前的 `sourceRects`，
`updateWritingMode` 用新方向重新合并一次。这样色块和已经读到新方向的朗读切分保持同一根轴。

**这个 bug 原版也有**，同样没修。

### 4.3 自定义选区颜色在某些自定义背景下彻底失效

用户报的是"自定义选区颜色有时候会失效，怀疑和自定义背景颜色冲突"。是真的，而且能算出来。

旧算法按 `DARK_BACKGROUND_LUMINANCE = 0.38` 这条线判断页面深浅，再据此决定把强调色
往白还是往黑推。问题出在**饱和的中间调页面**：珊瑚、天蓝这类颜色相对亮度落在 0.38 以下，
被判成"深色页"，于是把强调色往**白**推 —— 而这种页面本身就够亮，往白推永远够不到可见度下限。
八次固定 18% 的推移走完 80% 的路程，用户挑的橙色到最后是一片没有橙味的奶白，而且**还是看不见**。
全量扫 531 441 组背景/强调色组合，有 **4 267 组**最终对比度压根没到下限。

改法两条：

1. **先加不透明度，再动色相。** 加 alpha 让同一个颜色更实在，不改变它是什么颜色；
   往黑白推才是把用户挑的颜色变成别的颜色。所以只有 alpha 加到头（浅色页 0.55 / 深色页 0.62）
   还不够时才碰色相。
2. **极点靠量，不靠那条 0.38 的线。** 把白和黑分别合成到这个背景上各算一次对比度，
   哪边收益大用哪边；然后二分找**刚好够用**的最小混合比例，而不是八次固定推移。

结果：4 267 → **0** 组失效，色相破坏的平均值 0.1756 → **0.1280**，四套默认主题的调色板
逐字节不变。

这条算法在 `core`（统一版）和原版 `shared` 两边都改了，所以 iOS 与 Android 同时生效。

### 4.4 译文卡片：拖拽横线和"…"在窄卡片上叠在一起

`HStack` 里的 `.fixedSize` 子视图放不下时**不会报错，会直接叠着画**，这就是横线跑到
"…"底下的原因。改成先算宽度再决定画什么：

- 卡片放不下"标题 + 一个够大的拖拽热区"时，**先收起收藏星**（操作菜单里有同一项"收藏译文"，
  动作不会丢），还不够就**收起标题**（卡片本来就明显是译文卡）；
- 按钮不能收 —— 它们是动作，看不出来。

扫 140–460pt × 6 种按钮组合，0 处溢出，见 `ReaderTranslationHeaderMetricsTests`。

### 4.5 竖排翻页把一列劈成两半

用户要的是"两边各留一列缓冲，让某一列前后两页都完整出现"。iOS 竖排走的是滚动布局
（`EPUBSettings` 对竖排强制 `scroll = true`，见 readium/swift-toolkit#370），
翻页是自己按视口宽度滚 —— 视口几乎不可能正好是整数列宽，边界上那一列于是半张在这页、
半张在下页，两页都读不了。改成**每次少滚一列**。

量列宽这件事上踩了一个自己挖的坑，是补了测试才发现的：

```
DIAG x-advance: {"lefts":[342,306,270,234,198,162,126,90],
                 "deltaHistogram":{"36":49}}
DIAG settled: 21
```

`getClientRects()` 给的 `rect.width` 是**字墨的盒子**（21pt ≈ 字号），而真正的列距是
**相邻列左边缘之差**（36pt = 字号 20 × 行高 1.8）。照 `rect.width` 只让出 21pt，
比该让的少 42%，那一列照样会被劈开。改成量相邻列的位移中位数。

另外发现 WebKit 在 `readyState === 'complete'` 时还没有行盒，量出来是 null；
所以测量在 scroll view 的 `contentSize` 变化时重试 —— 那正是 WebKit 报告"我排完版了"的信号。

规则本身放在 `core` 的 `ReaderVerticalPageStep`，两端共用；测量在
`ReaderVerticalColumnMeasurementTests` 里用真实 WebKit 布局验证（6 个用例），
其中一个是端到端的性质：真实渲染页上翻一页，边界两侧必须各留下完整一列。

**Android 不需要这个改动。** Readium Android 的竖排走的是真正的 CSS 多列分页
（`--RS__colWidth:100vh` + `column-fill:auto` + `writing-mode:vertical-rl`），
而且这个 app 从不对竖排强制滚动模式 —— 多列分页天然只在行盒之间断开，
一列不可能被劈成两半。这个 bug 是 iOS 那条强制滚动的绕行方案的产物。

## 五、Android 端同步

| 改动 | Android 处置 |
|---|---|
| 4.3 选区调色板 | `shared/.../ReaderSelectionPalette.kt` 同样改掉，3 个新用例 |
| 4.4 译文卡片头部 | 新增 `TranslationCardHeaderMetrics` + `BoxWithConstraints`，8 个新用例 |
| 4.5 竖排翻页 | **不适用**，见上 |
| 4.1 / 4.2 | iOS 专有（UIKit `contentInset` / Swift 选区桥），无对应物 |

Android 那边症状不同但同源：Compose 的 `Row` 会把带 `weight` 的孩子压到 0，
于是拖拽横线**悄悄变得拖不动**，而不带 weight 的标题照旧占位、把按钮挤出卡片边缘。
同一套算术两边都能治。收起星标的同时给操作菜单补了"收藏译文"，动作不会丢。

```
Android: 61 tests, 0 failures      (:androidApp:testDebugUnitTest + :shared:testAndroidHostTest)
iOS 统一版: 258 tests, 0 failures
```

## 六、版本号

| | 1.2.2 那一轮 | 1.2.3 那一轮 | 现在 |
|---|---|---|---|
| iOS 统一版 | 1.2.2 (48) | 1.2.3 (49) | **1.2.4 (50)** |
| iOS 原版 | 1.2.2 (48) | **1.2.3 (49)** | 1.2.3 (49) |
| Android 原版 | 1.1.1 (46) | **1.1.2 (47)** | 1.1.2 (47) |
| Android 统一版外壳 | 0.1.1 (2) | **0.1.2 (3)** | 0.1.2 (3) |

1.2.4 只动统一版（见第十二节）。Android 那边先解决自己的 UI 问题，之后再和统一版
合并，所以这一轮 Android 的版本号不动。

## 七、Kotlin 移植部分的审计结果：没有新 bug

调色板、手势闸门、矩形合并、浮层定位逐条比对，要么忠实，要么是有文档说明的有意分歧。
`ReaderSelectionRectMerger` 与原版 `merged(_:vertical:)` / `groupIntoLines(_:vertical:)`
在同一批输入上结果一致（96 个用例）。

`ReaderSpeechHighlightGeometry` 有两处静默分歧，都不是缺陷：

- 退化色块的分母取法不同 —— 该分支不可达；
- 已知书写方向时不再走"高比宽大 1.55 倍"的宽高比兜底 —— 严格比原版更准。

## 八、测试 target：已补齐

`JerreaderUnified.xcodeproj` 现在有三个 target：

```
Targets: JerreaderUnified, JerreaderTests, JerreaderUITests
```

用 Xcode 16 的 `PBXFileSystemSynchronizedRootGroup` 挂目录，不需要逐文件登记。
原版那 8 个测试文件（~8 920 行）**原样拷过来只改了 `@testable import` 一行**，
其余一个字没动就全过了 —— 这本身就是统一版行为等价的一个证据。

```
Executed 258 tests, with 3 tests skipped and 0 failures
```

3 个跳过的都是要外部 EPUB fixture 的选考用例（`JERREADER_LAYOUT_EPUB_PATH` 等），
设计如此，不是缺陷。

新增的两组测试：

- `ReaderTranslationHeaderMetricsTests`（7 个）—— 把译文卡片头部的排布算术钉死。
  SwiftUI 的 `HStack` 里放 `.fixedSize` 子视图时**不会报告放不下，而是直接叠着画**，
  所以这套算术必须自己就是对的。扫了 140–460pt × 0/1/2/3/4/5 个按钮，0 处溢出。
- `ReaderVerticalColumnMeasurementTests`（6 个）—— 在真实 WebKit 布局里量列宽，
  见下面第 4.3 节。

关于 `JerreaderUITests` 的既有情况（上一轮的结论，仍然成立）：
里面 8 个覆盖选区与译文定位的用例全部 `XCTSkip`，要求注入 `UITestRubyBook.epub`。
把模拟器里那本真实日文竖排书（含 `text/part0005*`，与 `--jerreader-selection-test-href=part0005`
吻合）注入 bundle 后用例能跑，但断言钉死在另一本书的内容和坐标上：

```
XCTAssertEqual failed: ("Optional("が")") is not equal to ("Optional("覗")")
```

是 fixture 对不上，不是缺陷。另有 3 个用例（`testIPadShell...`、`testPrimaryTabs...`、
`testBackupCenter...`）**在两个仓库里都一直是红的，原因这次查清了**：它们等的是
`navigationBars["书库"]`，而书架页的标题早就改成了「书架」，于是三个用例都卡在第一句
断言上超时 15 秒。改掉标题字面量后三个全绿。`testIPadShell...` 另外还断言窗口宽
≥ 800pt，那只描述 iPad，在 iPhone destination 上现在 `XCTSkipUnless` 跳过而不是失败
—— 一个长期红着的套件会把真正的失败盖住。

## 九、点击自动化：这一轮不可用

上一版报告写"这台机器上没有可用的点击自动化"，那是错的，已推翻。可用的组合是
**System Events 对 Simulator 窗口做坐标点击 + `xcrun simctl` 装/起/截图**：

- 设备点 → 屏幕坐标：按 Simulator 窗口宽度 / 402 换算；
- 每次点击前先确认 Simulator 确实是最前台进程，否则合成点击会落进别的窗口；
- 截图走 `xcrun simctl io <udid> screenshot`，1206×2622（3×），与窗口缩放无关。

设置页、阅读器、译文卡片这三屏的 0 像素结论就是这么拿到的。

**注意跨会话不可比**：模拟器重启前后的截图有 ~8.7% 的亚像素噪声（没有整像素位移）。
只有**同一次模拟器会话内**采的图才能严格对拍。

**但这一轮它坏了**：Simulator.app 进程活着、设备 `Booted`、
`xcrun simctl io ... screenshot` 照常出图，但 System Events 报告它**有 0 个窗口**，
没有可访问的菜单栏（`UI elements enabled = true`，所以不是权限问题）。
重开、`pkill -9` 后重开、`simctl shutdown`/`boot` 后重开、
杀 `com.apple.CoreSimulator.CoreSimulatorService` 后整套重启，四种都没救回来。

原生的模拟器工具也用不了：它要求主机装完整 Xcode 并执行
`sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`，
这条要密码，只能由使用者自己跑。

所以 4.5 的验证换了条更硬的路：不去截图比对翻页结果，而是在
`ReaderVerticalColumnMeasurementTests` 里用**真实 WebKit 布局**量列宽、算翻页步长、
断言边界两侧各留完整一列。这比一对截图能说明的更多 —— 也正是它逼出了
`rect.width` 量错对象那个 bug。

## 十、备份可继承（1.2.3 / 1.1.2 新增）

要求三条：备份要能被继承、新装的 App 默认就给出导入入口、导入后默认沿用原来的
文件夹和原来的备份设置。

### 10.1 备份档案自己带着"备份怎么做"

新增 `LibraryBackupProfile`（iOS `Data/Services/LibraryBackupPolicy.swift`、
Android `shared/.../library/LibraryBackupPolicy.kt`），写进 manifest：自动开关、
间隔、保留天数、份数上限、容量上限、自动备份范围，外加文件夹的**名字和路径**。

两个决定值得记下来：

- **它不受"阅读与翻译偏好"这个范围管辖。** 只勾了阅读进度的备份，恢复后一样把
  备份计划带回来 —— 备份计划不是阅读偏好，用户没勾它不代表不想要它。
  `testArchiveCarriesTheBackupRegimenEvenWithoutTheSettingsScope` 钉死这一条。
- **文件夹只是提示，不是授权。** iOS 的 security-scoped bookmark 和 Android 的 SAF
  persisted grant 都是按项授权、跟着这台设备走的，写进档案也没用，而且不该写。
  所以档案里只有一个名字和一个路径。

manifest 多一个可选字段而已，格式版本不用动：Swift `Codable` 和 `JSONObject`
都会忽略不认识的键，旧档案恢复到新版、新档案恢复到旧版都不会坏。

### 10.2 导入之后沿用原来的文件夹

先**试着直接接管**：iOS 用 `adoptDirectory` 重新选一次并立刻把 bookmark 解回来验证
（选中只证明此刻能写，解回来才证明下次启动还在），失败就整体回滚，不会拿用户
现有的文件夹去赌；Android 用 `adoptIfPermitted`，只在这次安装**确实已经持有**那棵
树的 grant 时才接管。

接管不成时不假装成功，也不让用户自己去文件系统里找：备份中心浮出一行
「刚恢复的备份来自「书房备份」」，按钮把选择器**直接开在那个文件夹上**
（iOS `picker.directoryURL`、Android `OpenDocumentTree().launch(uri)`），
重新授权是一次点击。

`lastAutomaticBackupAt` 取档案自己的 `createdAt`，所以继承来的计划是**接着走**，
而不是恢复完立刻触发一次备份。

### 10.3 新装的 App 默认就有导入入口

空书架就是重装后的样子。iOS `LibraryView`、Android `JerreaderLibraryApp` 的空状态
在「导入书籍」下面加了「从备份恢复」，进去的备份中心会自己把文件选择器打开
（iOS 等 400ms 再弹 —— 这个页面自己就是一个 sheet，在转场里发起第二个 presentation
会被 UIKit 静默丢掉）。

### 10.4 验证

```
iOS 统一版：264 tests + 13 UI tests，0 failures（其中备份继承 6 个、空书架入口 1 个）
iOS 原版  ：JerreaderSelectionUITests 13 tests，0 failures
Android   ：:androidApp:testDebugUnitTest + :shared:testAndroidHostTest，0 failures
            （manifest 往返 6 个 + normalized() 5 个）
Android   ：BackupInheritanceTest 3 tests on JerreaderM0_API36，0 failures
```

Android 的 JSON 往返在单元测试里要真的能跑，得把 `org.json:json` 放进
`testImplementation` —— `unitTests.isReturnDefaultValues = true` 让 android.jar 的
`org.json` 桩返回默认值，不换实现根本测不了解析。

设备上那 3 个 instrumented 用例走的是真服务：自己压一个只有 manifest 的
`.jerbackup.zip`，`restore(Uri.fromFile(...))` 进去，断言 `BackupPolicyStore` 里
确实变成了档案里那套计划、`lastAutomaticBackupAt` 等于档案的 `createdAt`、
没有 grant 的文件夹以 `suggestedFolderUri` 的形式回来。

## 十一、复现

```bash
export DEVELOPER_DIR="<Xcode.app 路径>/Contents/Developer"
# 源码差异
OLD_APP="<旧工程>/Jerreader"
NEW_APP="<JerreaderUnified>/iosApp/Jerreader"
diff -rq "$OLD_APP" "$NEW_APP"
# 视图逐字节
for f in $(cd "$OLD_APP" && grep -rl ": View\b" --include='*.swift' .); do
  cmp -s "$OLD_APP/$f" "$NEW_APP/$f" || echo "DIFFERS: $f"
done
```

竖排死带那个 bug 的复现：模拟器上冷启动进一本竖排书，量正文第一行暗像素的 y。
**137.7pt 是对的，199.7pt 是坏的**。单次不一定复现，要连着起 5 次以上。
统一版 8/8 全对；原版当前仍稳定复现 200.0pt。

## 十二、1.2.4：这一轮的三个问题

### 12.1 备份文件选不上，尤其从主界面进去

两处，都是 SwiftUI 的 sheet 在转场里被 UIKit 静默丢掉。

书架空状态的「从备份恢复」进去以后，备份中心会**自己**把文件选择器打开——而备份中心
自己就是一个刚呈现的 sheet。在转场没结束时发起第二个 presentation，UIKit 直接丢掉，
`restoreImportRequest` 却留在非 nil 上，于是**后面再点也没反应**。上一轮的办法是先
睡 400ms 再弹，这是在赌转场比 400ms 快。同一个坑还有第二处：选中备份之后要弹的
确认对话框，是在选择器**正在消失**的那一帧发起的，同样会被丢——文件选上了，什么都
没发生。

改成用 UIKit 呈现（`BackupImportPickerHost`）。它不赌时间，而是**等到呈现者真的闲下来**：
沿着 `presentedViewController` 走到最上面那个，确认它在窗口上、不在被呈现、不在被关闭、
没有正在跑的 `transitionCoordinator`，才呈现；否则每 100ms 再看一次，最多 6 秒。
结果的回传也同样等——选择器关干净了才把 URL 交出去，确认对话框于是不会再被丢。

### 12.2 「选择备份」只在新装时出现

空书架不等于重装。用户自己清空了书架，那是他的选择，不该被一个「从备份恢复」按钮
一直提醒。新增 `LibraryFirstRunStore`：第一本书落到书架上的那一刻起，这台设备就不再
是"刚装好"的了，之后再空也不显示。设置 → 备份中心里的恢复入口一直都在。

按书本数量而不是布尔量记录（`noteLibrary(bookCount:)`），调用方没法在空书架上不小心
把状态置掉。

### 12.3 竖排最旁边一列还有一部分在下一页

上一轮（4.5）让翻页每次少滚一列，那只解决了一半：那一列在**下一页**是完整的，但
**这一页**照旧把它的一截显示出来——用户看到的就是那一截。而且上一轮的模型假设每列
等宽，真实页面不是：`vertical-rl` 里段间距是**块轴**上的间隙（就是横向的），标题的
行高又和正文不同，所以一屏之内列边界根本不在同一个节奏上。

改成量真东西。`ReaderVerticalColumnGrid`（`core`，与 Android 共用）接收 WebKit 实际
排出来的**每一个列盒**，回答两件事：

- **翻页落到真实列边界上。** 向前翻时，新页的右缘正好落在本页最左那一整列的末端 ——
  那一列在两页上都完整，且中间不会漏掉任何一列。
- **两边的残列被盖住。** `leadingGutterWidth` / `trailingGutterWidth` 报出被页面两缘
  切开的那两条，用页面自己的底色盖上，读起来就是页边距。底色取自文档
  `getComputedStyle(document.body).backgroundColor`，不是主题猜的——出版方样式表能自己
  改背景，盖错颜色就是页边一道漆。

三条边界情况写进了实现，不是事后补的：段间距落到页缘时盖 0（空白不用盖）；页面位置
是书签或 Readium 自己的进度滚动给的（不在任何格线上）时，只如实描述、不去强行对齐——
强行对齐会吃掉文档自己的起始留白，把第一列顶到屏幕边上；半点以内的残边算齐平，因为
行盒比字墨高出半行距，半点里没有任何笔画。

`isPagingEnabled` 同时关掉了。它按**视口整数倍**吸附，而一页列数乘列宽根本不是一个
视口——两者相抵的结果是：每个视口边界上那一列，在前一页被盖、在后一页也被盖，
**整列读不到**。改由 `EPUBVerticalPagingScrollDelegate` 站在 Readium 的 delegate 前面，
只接管 `scrollViewWillEndDragging`（Readium 唯一没实现的那个），把一次拖拽吸附到一页。

```
core: ReaderVerticalColumnGridTest 11 个用例
      —— 含等宽 / 带段间距 / 混合列宽三种版式下逐 0.25pt 扫过整个书脊，
         断言"没有任何一列被显示成半张"，以及走完整个书脊不丢列
```

### 12.4 选区没颜色

这次找到的是机制，不是又一个补丁。

`locationDidChange` 里无条件把点译高亮拆掉。而 Readium 对**任何**位置变化都会发这个
回调（`scrollViewDidScroll` → `setNeedsNotifyPagesDidChange`，0.3 秒防抖）——竖排书永远
在滚动模式，手指压在页面上、翻页后的惯性、甚至 app 自己那句修安全区的
`contentOffset.y = 0`，每一个都会发一次。于是：点一句话 → 高亮画上、卡片弹出 →
0.3 秒后一次无关的位置变化把高亮抹掉 → **卡片还在，句子上没有颜色**。竖排日语书滚得
最多，所以最常见——和用户给的截图完全对得上。

高亮是文档自己 DOM 里的绝对定位浮层，页面滚动时它跟着正文走。所以只要**还在同一个
资源里**就没有理由拆它；换了资源，那是另一个文档，本来也就没了。收起卡片走的是
`clearSelection()`，那才是正常的终点。

新增 `takeQuickSentenceHighlight()`：token 和它所在的资源必须一起忘掉，六个清理点各写
两行赋值迟早会漏一个。

`testQuickSentenceHighlightStaysOnItsSentenceAfterScrolling` 用真实 WebKit 布局钉住
这个改动依赖的前提：在一篇长 `vertical-rl` 文档里画上高亮，横向滚过三个视口，标记
相对它那句话的位移必须不变。

### 12.5 验证

```
iOS 统一版（iPhone 16 Pro / iOS 18.2）
  Executed 278 tests, 266 passed, 12 skipped, 0 failures
  —— 单元 265（较上一轮 +1：竖排滚动后高亮不掉色）+ UI 13
core（Kotlin，两端共用）
  :core:iosX64Test           117 tests, 0 failures
  :core:testAndroidHostTest  117 tests, 0 failures
  —— 其中 ReaderVerticalColumnGridTest 11 个，两个 target 上都全过
```

`:core:iosX64Test` 是必跑的，不能只跑 host 侧：commonTest 里一个反引号用例名带了逗号，
JVM 编得过，**Kotlin/Native 报 `Name contains illegal characters: ","`**——共用代码只在
一端验过就不算验过。（链接步骤还需要 `DEVELOPER_DIR`，否则 `xcrun xcodebuild -version` 就挂。）

跳过的 12 个是老样子：3 个单元用例要真机能力，9 个 UI 用例等 `UITestRubyBook.epub`
注入，和这一轮无关。

**只动了统一版。** Android 那边的 UI 问题先由它自己收，收完再和统一版合并——所以
`ReaderVerticalColumnGrid` 虽然写在 `core` 里两端共用，这一轮只有 iOS 在用它。
