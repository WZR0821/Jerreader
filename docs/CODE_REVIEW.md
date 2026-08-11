# 代码梳理：逻辑问题与修复

对 `core` / `ui` / `androidApp` / `iosApp` 逐段过了一遍，并回头核对原工程 iOS 与 Android 的
对应实现。共 8 处，全部已修，全部有测试或构建验证。

原工程仍未改动（未提交改动数 88，与开工时一致）。**下列问题中有 3 处原工程也存在**，
标注为"继承"——那边的修复要单独做，本次没有碰。

---

## 1. 选区可能整行消失（继承自原版 iOS，严重）

`ReaderSelectionRectMerger.dropContained`

去重规则原本是：若存在另一个框**包含**自己，且它 *更宽* **或** *更高* **或** *下标更靠前*，
就丢弃自己。这个析取不是反对称的。两个相差不到 0.5px 的重叠框，加上包含判定的 0.5px 容差后
**互相包含**：大的那个输在"下标"，小的那个输在"更宽"，于是**两个都被丢掉**。

亚像素级重叠的碎片正是 ruby 注音行产生的典型几何，所以整行高亮会凭空消失。

原版 iOS `EPUBSelectionHighlightView.merged` 里是同一段逻辑，同样有此问题。
（原版 Android 没有这段去重，因此不受影响。）

**修复**：改成严格全序——先比面积，面积相等再比下标。面积最大（并列时下标最小）的框
永远不会被丢，保证至少一个幸存。

回归测试：`两个近乎相同的框只应留下一个幸存者`——修复前该用例失败。

## 2. 点空之后旧高亮残留（继承自原版 Android）

`ReaderSelectionController.selectAt`

每次点击开头会执行 `clearHighlightScript()` 清掉页面内的贴片，但**没有同时清掉已发布的
state**。如果这次点击没落在正文上而提前返回，页面内的高亮没了、原生高亮层还在画上一句，
两层一直不同步，直到下一次成功点击。

原版 Android 的 `quickTranslate` 是同样的结构：先 `clearHighlightScript()`，失败路径直接
返回，`selectionRects` 保持旧值，Compose Canvas 继续画。

**修复**：清页面和清 state 放在一起。
回归测试：`命中之后再点空不应残留旧高亮`——修复前该用例失败。

## 3. 贴片可能画在文字旁边（本次自己引入）

`ReaderSelectionScripts`

把"合并"挪进 Kotlin 之后，测量（`selectRangeScript`）和绘制（`paintTilesScript`）变成了
**两次**求值。矩形是 client 坐标，绘制时要加 `scrollX/scrollY` 转成 document 坐标——而
`addRange` 会把选区滚动到可视区。两次求值之间滚动位置若变了，就会用错误的原点换算，
高亮落到文字旁边。

这恰好是原版注释里警告过的那个坑（"Measuring and painting have to happen in one
evaluation"），被我拆开时重新引入了。

**修复**：`selectRangeScript` 一并返回它测量时的 `scrollX/scrollY`，`paintTilesScript`
改为接收这两个值，不再在绘制时重读。

## 4. 卡片位置：拖过去又被弹开（继承自原版 Android）

`ReaderTranslationOverlayHost`

事后碰撞修正在 `onGloballyPositioned` 里无条件运行，而被测量的 bounds 里已经包含了用户的
拖拽位移。用户一旦把卡片拖到选区上，修正就把它推开——而且每次重组都推一次，拖拽永远赢不了。

原版 Android 的 `popupCollisionModifier` 是一样的：`cardDragX/Y` 已经加进 offset，碰撞
修正照跑不误。（原版 iOS 不同：拖拽会设 `manualPosition`，自动定位随之让位。）

**修复**：`drag != Zero` 时跳过修正并把已有修正归零——位置交还给用户，与 iOS 行为一致。

## 5. 竖排书第一次选中时合并轴是错的（本次引入）

`EPUBReaderHost.init`

`updateWritingMode` 只加在了设置变更路径上，`init` 里漏了。于是竖排日文书**打开后的第一次
选中**仍按水平轴分行——正是这次要治的那个 bug。

**修复**：`init` 里与调色板一起把书写方向也播下去。

## 6. 发布贴片用了过期的坐标变换（本次引入）

`ReaderSelectionController.selectAt`

`geometry` 在点击换算时取了一次，之后一直用到发布。但 `addRange` 会滚动页面，而 iOS 侧的
安全区补偿正是用 scroll offset 表达的——点击时正确的变换，等贴片回来时可能已经过期，
原生高亮会偏离文字。

**修复**：发布前重新取一次 `contentGeometry()`。

## 7. 桥接回调可能永不触发（本次引入）

`ReadiumWebViewBridge.evaluateJavaScript`（Android）

`scope.launch { ... callback.onResult(...) }`。这个 scope 属于 Activity，可能在调用方还活着
的时候被取消；协程被取消则回调不会触发，而调用方是 `suspendCancellableCoroutine`——
整个选区流程就**永久挂起**。

**修复**：用 `invokeOnCompletion` 兜底，保证"恰好一次"。同时把这条契约写进
`ReaderWebViewBridge` 的文档，连同 `contentGeometry()` 必须在主线程调用。

## 8. Swift 编译器崩溃（本次引入，已绕开）

把 `EPUBSelectionGestureGate` 从 struct 改成 `final class` 之后（状态在 Kotlin 对象里，
struct 会宣称自己有做不到的值语义），Swift 6 在 `SWIFT_STRICT_CONCURRENCY = complete` 下
为"读取自身静态属性的类初始化器默认参数"生成 SIL 时崩溃，且不输出任何 `error:` 行，
只报 `SwiftEmitModule ... failed`。

**绕开**：拆成 `init(suppressionDuration:)` + `convenience init()`，不用默认参数。

---

## 验证

```
:core:testAndroidHostTest   96 tests, 0 failed
:core:iosX64Test            96 tests, 0 failed   （同一批用例，Kotlin/Native）
:androidApp:assembleDebug   BUILD SUCCESSFUL
:ui:linkDebugFrameworkIosSimulatorArm64  BUILD SUCCESSFUL
iosApp                      BUILD SUCCEEDED
```

UI 未回归：全部 SwiftUI 视图文件仍逐字节一致；同数据像素对拍差异仍只有系统时钟
（y=56..133）与 Home Indicator 动画相位（y=2583..2597）。

## 留给原工程的三条

第 1、2、4 条在原工程里同样存在。本次按要求没有动原工程，如果那边还要继续维护，
这三处值得单独修一遍——或者直接切到本工程的 `core`。
