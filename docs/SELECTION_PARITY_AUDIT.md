# 选区高亮与选区避让：双端差异审计

对比基准：

| 平台 | 包 | 时间 | 对应代码 |
|---|---|---|---|
| Android | `dist/Android/Jerreader-Android-1.0.3-build44-installable.apk` | 2026-08-04 17:36 | `androidApp/` + `shared/`（Kotlin） |
| iOS | `dist/iOS/Jerreader-1.1.0-build45-cold-ui-unsigned.ipa` | 2026-08-04 19:51 | `Jerreader/Features/Reader/`（Swift） |

两端阅读内核都已经是 Readium 3.x（Kotlin toolkit 3.3.0 / swift-toolkit）。真正的分歧不在内核，
而在内核**之上**：同一个句子，两端算出的矩形、画出的形状、卡片落的位置都不一样，因为这几段逻辑
在两个语言里各写了一遍。

---

## 一、选区高亮

| 维度 | iOS（Swift） | Android（Kotlin） | 后果 |
|---|---|---|---|
| 绘制载体 | `EPUBSelectionHighlightView`（WebView 之外的 UIKit 兄弟视图，Core Graphics） | 页面内 JS 贴片 + Compose `Canvas` 两处 | 两套绘制代码，改一处不影响另一处 |
| 矩形合并 | `EPUBSelectionHighlightView.merged()`：按 `midY` 判同行 + 间距 ≤2 合并 | `ReaderSelectionRectMerger`：按书写方向分行 → 行内 run 合并 → 重叠行取中点分离 | iOS 版**不认识竖排**，日文竖排书的合并轴是错的 |
| ruby 包围盒 | 有：丢弃被更大盒完全包含的矩形 | 无 | Android 在有 ruby 的行上填充/描边叠加两次 |
| 圆角 | `min(3, h × 0.16)` | 固定 `4f` | 同一句字号不同时观感不一致 |
| 外扩 | `insetBy(-0.5, -0.5)` | 无 | Android 同一行两块之间有发丝缝 |
| 描边宽度 | `0.75` | `1f` | Android 的高亮明显更"重" |
| 朗读高亮 | 有：`ReaderSpeechHighlightGeometry` 按比例切分当前朗读段 | **完全没有** | Android 朗读时选区不跟读 |
| 调色 | `ReaderSelectionVisualStyle`（Swift） | 同名 Kotlin 移植 | 亮度阈值一个用 `0.04045`（sRGB 规范值）一个用 `0.03928`（旧草案值），少数自定义色在两端跨过对比度阈值的时机不同，出来的颜色不一样 |
| `spokenFill` | 有 | 无 | 同上 |
| JS 探针 | Swift 里一份 | Kotlin 里另一份 | **两端对"选中的句子是什么"本身就可能给出不同答案**（ruby 页尤其明显） |

## 二、选区避让

| 维度 | iOS | Android |
|---|---|---|
| 算法形态 | 纯函数 `ReaderTranslationOverlayPlacement.layout()`，一次求解出 边/位置/宽度/最大高度 | 没有求解器：区域由一串 Compose 修饰符的 if/else 决定，外加 `onGloballyPositioned` 里的事后碰撞修正 |
| 可选边 | above / below / left / right / top 五种 | 上/下 + 竖排左右 + 顶部横幅 |
| 侧向避让触发 | `usesVerticalSideAvoidance()`：竖排书，或"细高选区"启发式；长译文也允许侧放 | 仅 `isVertical && sideWidth >= 160.dp` |
| 二级避让框 | 有 `horizontalAvoidanceFrame`（整段可见译文区，不只是被点的那一列） | 无 |
| 间距 gap | 12（侧向）/ 26（整段）/ 18（单句） | 单一 `GAP` 常量 |
| 顶部横幅冲突 | `prefersTop` 命中选区时**回落**到 above/below 求解 | `topBannerCollides` 布尔判断 |
| 卡片宽度 | 按译文长度与屏宽在 220–440 间求解，侧放再收窄 | 固定 320 / 420 / `sideWidth ≤ 300` |
| 事后修正 | 无（单次求解，加载→结果不换边） | `popupAvoidanceX/Y` **累加**位移，靠 0.5px 阈值收敛 |
| 拖拽夹取 | `clampedPosition()` 公共函数 | `coerceIn(±45%, ±35%)` 直接写在回调里 |

累加式修正是这里最值得注意的一处：`popupAvoidanceX/Y` 每次测量都在上一次的基础上再加位移，
而不是从求解位置重新算一个绝对偏移。只要测量抖动，卡片就会一点点"走"开。

---

## 三、统一后的归属

| 原实现 | 新位置 |
|---|---|
| 两份矩形合并 | `:core` `ReaderSelectionRectMerger`（Kotlin 的分行算法 + iOS 的包含去重） |
| 两份调色 | `:core` `ReaderSelectionVisualStyle`（含 `spokenFill`，亮度阈值统一为 sRGB 的 `0.04045`） |
| 绘制常数 | `:core` `ReaderSelectionHighlightMetrics`（采用 iOS 那组，它是对着真实文字调出来的） |
| iOS 独有的朗读高亮 | `:core` `ReaderSpeechHighlightGeometry`（并补上竖排轴） |
| iOS 独有的手势闸门 | `:core` `ReaderSelectionGestureGate` |
| 两份 JS 探针 | `:core` `ReaderSelectionScripts`（一份，两端注入同一段脚本） |
| 两份 JSON 解析 | `:core` `ReaderJson` + `ReaderSelectionDecoder` |
| iOS 求解器 + Android 事后修正 | `:core` `ReaderOverlayPlacement.solve()` / `.correct()`（修正改为**绝对**偏移） |
| 散落的布局常数 | `:core` `ReaderTranslationLayoutPolicy` |
| 两份绘制视图 | `:ui` `ReaderSelectionHighlightLayer` / `ReaderTranslationOverlayHost`（Compose Multiplatform） |
