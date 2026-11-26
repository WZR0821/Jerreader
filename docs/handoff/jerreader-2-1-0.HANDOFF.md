# Jerreader 2.1.0（75）：卡片 AI、流式、注音整块选区

更新于 2026-11-26 10:47：两端验证和打包都完成，随本次提交入库。之后按用户授权推送、发 GitHub Release v2.1.0、把 LC 页换成 2.1.0；发布结果看 Release 页，不再回写本文件。

## 目标
- 用户 11-22 的原话："按照之前给的Jerreader的开发方向，现在开始做2.1.0"，另外"日文注音的选区现在能连成一个整体……作为一个整体选区"。
  - 开发方向是 `docs/ROADMAP_2.1_TO_3.0_2026-11-22.md` §4，规格是 `docs/SPEC_2.1.0.md`。
  - 版本 2.1.0 (75)，两端行为一致，iPad 不做。
- 11-23 用户追加："发现的BUG都给我修了，给我一个完美的成品"；"全量验证最重要的是查看带注音的日文选区是否成为一个整体"。
- 11-26 用户："我要求你全部、一次性改完，如果遇到用量限制，在限制后自行继续，弄完后直接发布最新版"。

## 现在在哪
- 功能、修复和文档都做完了。改动、验证和没验证的部分见 `docs/RELEASE_2.1.0_2026-11-23.md`。
- 两端真书注音截图已经裁切放大、核对后发给用户。
- 唯一没修好的是 iOS 竖排偶尔整页画高 36.7 pt，写在 RELEASE §6。

## 下一步
1. 下一次双端发版从 build 76 起，方向见路线图 §5。
2. 两端都还没上真机。先在真机上看 iOS 竖排有没有整页画高，再决定要不要接着查。
3. 接着查画高的话，先读 RELEASE §6。
   - 已知的是：每列顶上被截在网页视图顶边下约 69 pt 处，正文整体往上挪了 36.7 pt，看着像某个裁剪框按新的排版、正文按旧的位置画。这只是看截图的推测，没核实过。
   - 真书测试 `JerreaderRubyRegionUITests` 每项先截刚打开的页面，并记下几何和图层树，可以拿来按像素比。

## 关键文件
- `docs/SPEC_2.1.0.md` §1：注音整块选区、长按选中手指下的词、按在注音词的空白处。
- `docs/RELEASE_2.1.0_2026-11-23.md`：改动和验证记录。§6 是没验证和没修好的。
- `Jerreader/Features/Reader/EPUBReaderScripts.swift`：`longPressWordGuardScript`，`rubyGlyphNear` 是空白处的规则。
- `androidApp/src/main/kotlin/com/jerreader/android/reader/ReaderLongPressAim.kt`：安卓长按落点，含空白处的规则。
- `Jerreader/Features/Reader/EPUBReaderHost.swift`：`observeVerticalContentOffset`，竖排偏移修正，和 2.0.2 相同。
- `androidApp/src/androidTest/kotlin/com/jerreader/android/reader/ReaderRealRubyBookTest.kt`、`JerreaderUITests/JerreaderSelectionUITests.swift`（`JerreaderRubyRegionUITests`，8 项）：两端真书注音测试。
- 本机路径不写进仓库，都记在项目记忆里，包括存档、排队脚本、对拍工具、真书副本、截图和日志。

## 已经定下来的决定
- 注音和它的汉字画成一整块。长按不扩到整个注音词，这是用户的决定。
- 按在注音词的空白处算作同一行上最近的汉字，一样近时取前一个。空白指汉字之间，或较长的读音伸出汉字的那部分，按在伸出的读音上也算。按在正对某个汉字的读音上，结果和以前一样。11-25 定，两端同一规则。
  - iOS 在长按纠偏脚本里找字；安卓挪长按的落点，因为在安卓上用脚本改选区会收起选区手柄和菜单。
  - 两端都给读音设了 `pointer-events: none`。
- iOS 长按时 WebKit 的选区包含手指下的字、但一端停在注音边上（外面还是文字）时，扩到 `Selection.modify` 给的词，只在它包住原选区而且更长时换。11-26 真书截图查出：每个汉字各带读音的词，按某个字的后半边原来只选这一个字。Android 不改，长按这种词 WebView 只选一个汉字（是没有词典还是读音隔开，没核实；脚本改选区会丢手柄和菜单），写在 RELEASE §6。
- 安卓短按分词用 `android.icu` 的 BreakIterator，WebView 的 `Intl.Segmenter` 只作后备，因为 Chromium 安卓版的 ICU 数据删掉了中日文词典。
- iOS 竖排画高：阅读器里不放修补。开发中加过的整页重画和全部诊断都删了，偏移修正和 2.0.2 相同。
- 挂在 contentSize KVO 上的工作要先比新旧值：WKWebView 每次图层树提交都会设 contentSize，值没变 KVO 也通知。
- 用户的真书只在本机用：不进仓库和提交，不外发；回复里不引用书的文字和书名；测试结果包移出 iCloud 同步的 Documents 文件夹。

## 试过但不行的
- 治竖排画高的 7 种办法都无效：给 body 换透明渐变背景让整页重画；body 换一次 position；body 加 `translateZ(0)`；body 隐藏后放回；页面高 1 像素两帧；根元素隐藏两帧；网页视图矮 1 pt 0.4 秒。画高的那次，打开过程的排版记录、DOM、滚动数字和图层树都和画对的一样，事先看不出来。
- 拿桌面 Chrome 的结果代表设备上的 WebView：Chrome 158 量字框时把空白算进字里，设备上的 Chrome 124 不这样。所以后备规则不依赖字框的宽窄。
- iOS 真书测试每项做好几次长按：真书上每次 XCUI 查询约 3 秒，单项上限 3 分钟，所以每项只做一次。
- 截图前只等 `waitForIdleSync` 加 500 ms：模拟器用软件渲染，截到的是上一帧，要等 1.5 秒。

## 未决问题
- 竖排画高的原因；2.0.2 是否也有；真机上是否出现。
- 接受的局限写在 RELEASE §6：て形接 しまう 之类时查词改走在线；全假名的「いった」「いって」要用户选词条；维基词典的动词没有类别。
- 两端都没上真机，也没连真实服务。

## 验证情况
- Android：Release 构建 `20261126-000119-58onjs83`（应用单测 219 项）和设备测试 `20261126-001423-wv54yl0t`（回归 45 项、界面 13 组、真书、Release 冒烟）都通过。
- iOS：host-test `20261124-054838-syr_0jz1`；XCTest `20261126-093331-n5e_hq9r`（359 过、3 跳过）；界面测试 `20261126-094215-8oos8rh8`（19 项全过）、`20261126-101118-zebewse1`（15 过、11 跳过）、真书 `20261126-102639-1yttq1xu`（8 项全过，8 次长按都选中整个注音词，竖排没有画高）；IPA `20261126-104143-tkqnv9fb`（`Jerreader-2.1.0-build75-20261126-104516-unsigned.ipa`，SHA-256 `5b7ef06e…29da95`）。
- 两端截图都放大逐张看过，也发给了用户。
- 全在模拟器上跑；没上真机，没连真实的 AI 服务。
