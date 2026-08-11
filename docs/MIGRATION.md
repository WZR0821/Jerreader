# 迁移路线

阅读器选区这一层已经做完：选区几何、合并、调色、绘制常数、朗读高亮、手势闸门、
DOM 探针、JSON 解码、卡片避让求解——全部只剩一份实现，跑在 `core` 里，两端共用。
原 `shared` 模块里的非 UI 公共 Kotlin 也已整体迁入。

---

## 已完成

| 能力 | 位置 | 用例数 |
|---|---|---|
| 几何原语 | `core/…/reader/geometry/` | 被以下各项使用 |
| 矩形合并（竖排 + ruby） | `core/…/reader/selection/ReaderSelectionRects.kt` | 7 |
| 选区配色（含 `spokenFill`） | `core/…/reader/selection/ReaderSelectionPalette.kt` | 6 |
| 朗读高亮切分 | `core/…/reader/selection/ReaderSpeechHighlightGeometry.kt` | 6 |
| 长按/点按手势仲裁 | `core/…/reader/selection/ReaderSelectionSnapshot.kt` | 5 |
| DOM 探针脚本（唯一一份） | `core/…/reader/selection/ReaderSelectionScripts.kt` | 间接 |
| JSON 解码与容错 | `core/…/reader/json/`、`…/selection/ReaderSelectionDecoder.kt` | 9 |
| 卡片避让求解 + 事后修正 | `core/…/reader/overlay/ReaderOverlayPlacement.kt` | 13 |
| 布局策略常数 | `core/…/reader/overlay/ReaderTranslationLayoutPolicy.kt` | 同上 |
| 一次点按的完整编排 | `core/…/reader/kernel/ReaderSelectionController.kt` | 11 |
| 书籍格式 | `core/…/domain/BookFormat.kt` | 2 |
| 备份策略 | `core/…/library/LibraryBackupPolicy.kt` | 10 |
| 跨页句子扩展与分段 | `core/…/translation/ReaderCrossPageExpansion.kt` | 8 |
| 独立翻译 | `core/…/translation/StandaloneTranslation.kt` | 10 |
| 翻译输入校验 | `core/…/translation/TranslationService.kt` | 3 |
| 词形还原 | `core/…/lexical/WordMorphologyAnalyzer.kt` | 4 |
| Compose 高亮层 / 卡片定位层 | `ui/…/reader/ui/` | framework 链接通过 |

合计 **94 个用例，在 Android host 与 Kotlin/Native iOS 上各跑一遍，全绿**。

## 下一步

1. **Android 宿主补齐**。`androidApp` 目前只是选区演示。原 Android app 的书库、学习、
   设置页还在原工程 `androidApp/` 与 `shared/…/ui/` 里，可以照 iOS 的做法整树复制过来，
   再把选区相关调用改指 `core`。原 `shared/…/ui/` 的 Compose 代码本来就是公共的，
   搬进 `ui` 模块即可。

2. **iOS 切 Compose overlay**。`ui/…/ReaderOverlayViewController.kt` 已经写好，
   但需要 Apple Silicon Mac 才能构建（见下）。切过去之后
   `EPUBSelectionHighlightView` 与 SwiftUI 的卡片定位代码就可以整段删掉。

3. **平台存储层**。`LibraryRepository` 接口已在 `core`，两端各自实现（Android Room /
   iOS SwiftData）。建议先统一接口，不要急着统一存储引擎。

## 值得记住的几个决定

- **`core` 不依赖 Compose**。这一条一旦破坏，Intel Mac 上就再也跑不了 Kotlin/Native 测试，
  iOS 也没法只用逻辑不用 Compose UI。
- **`ReaderAppearance.forSelection()` 存在的理由**：Kotlin 默认参数不会随 Objective-C
  导出保留，Swift 调用方要么写全 11 个字段、要么自己瞎填。工厂方法把"只关心 4 个字段"
  这件事说清楚，从而两端共用同一个 `ReaderAppearance` 类型。
- **事后碰撞修正必须是绝对偏移**。原 Android 实现是累加的，测量一抖动卡片就会慢慢走开。
  `ReaderOverlayPlacement.correct()` 每次都从求解位置重新算，`ReaderTranslationOverlayHost`
  喂进去之前会先把已应用的修正减掉。有用例守着这条。
- **合并在 Kotlin，绘制在 JS**。`selectRangeScript` 返回**未合并**的原始 rects，Kotlin 合并后
  再用 `paintTilesScript` 把成品贴片交回页面。页面不该重新推导它无法被测试的行结构。
- **阅读器配置的书写方向压过页面自报**。部分 WebView 会把 `-epub-writing-mode: vertical-rl`
  通过 `getComputedStyle` 报成 `horizontal-tb`，信它会让整个合并轴转 90°。
  iOS 侧由 `EPUBReaderHost.resolvedVerticalText` 提供答案，与 Readium preferences 同源。
- **模拟器测试任务会静默跳过**。KGP 在架构不匹配时把 `iosSimulatorArm64Test` 的 `enabled`
  置为 false，构建照样显示 BUILD SUCCESSFUL 却一个用例没跑。所以本工程加了 `iosX64` 目标，
  并且验收时要看用例数，不能只看构建结果。
- **Kotlin/Native 的反引号测试名不能含逗号**，否则 `compileTestKotlinIos*` 会报
  `Name contains illegal characters: ","`——JVM 侧不会报。
