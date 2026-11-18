# Jerreader 1.7.1（70）维护记录 · 2026-11-18

状态：**Android 1.7.1（70）已构建、验收并打包（2026-11-18）。** Release 构建（单元测试、Lint）、完整设备测试与 Release 冒烟通过，正式 APK 已打出。iOS 没有改动，没有重新构建。没有真机验证。写这份记录时还没提交 git，也没有发布到 GitHub、没有在议题 #2 下回复（用户 11-18 的决定：先不发布、不回复）。

## 1. 版本与标识

- **只有 Android 升到 1.7.1，versionCode 70**（1.7.0 是 69）。小版本修复，按版本规则递增末位。
- **iOS 没有改动，仍是 1.7.0（69）**，没有重新构建，也没有新的 IPA。用户 11-18 的决定：iOS 没有这两个问题就不改 iOS，只改 Android。这一版因此是两端版本号不一致的例外；下一次两端一起发版时，build / versionCode 要从 71 起。
- applicationId（`com.jerreader.android`）和签名证书都没变，沿用原来的签名，可以覆盖安装 1.7.0。

## 2. 起因：GitHub 议题 #2

[议题 #2](https://github.com/WZR0821/Jerreader/issues/2)「阅读ui/手势有问题」（2026-10-11 提交，没写平台和版本）反馈两点：

1. 分页阅读时点按翻译，浮窗出现在屏幕最上或最下方，拖动条不在屏幕上，而浮窗只能靠拖动条调整位置。
2. 滚动阅读时左右滑动会快速切换章节，很容易误触、丢掉章节进度；希望至少加一个屏蔽手势的功能。

在 1.7.0 源码里逐一核对的结论：

| | iOS 1.7.0 | Android 1.7.0 |
|---|---|---|
| 问题一：浮窗贴在最上 / 最下、拖动条不可见 | 不存在。`ReaderTranslationOverlayPlacement` 给卡片留 108pt 最小高度，位置和拖动都夹在安全区内 | **存在**，见 3.2 |
| 问题二：滚动模式横滑切章 | 不存在。Navigator 一直以 `disablePageTurnsWhileScrolling: true` 创建，点按翻页也带 `ignoreWhileScrolling` | **存在**，见 3.1 |

## 3. Android 的改动

### 3.1 滚动阅读时横滑不再切换章节

原因在 Readium Kotlin 3.4.0 的 `R2WebView.onTouchEvent`：滚动模式下，手指横向移动超过触摸阈值、抬起时纵向位移不到 200px，就调用 `scrollLeft` / `scrollRight`。滚动的章节只有一页宽，这一“翻页”直接落到相邻章节。上下滑动时手指稍微斜一点就满足条件。

改法：

- 创建 EPUB Navigator 时打开 Readium 自带的开关 `EpubNavigatorFragment.Configuration.disablePageTurnsWhileScrolling`，与 iOS 相同。没有自己拦截触摸事件。
- **没有做成可关的设置项**：默认屏蔽，和 iOS 一致。议题里要的是“至少增加一个手势屏蔽功能”，这里是直接屏蔽。
- **竖排不屏蔽。** Readium 把竖排强制为滚动模式，正文横向滚动，滑到边缘换章是章节间唯一的手势；它只在边缘触发，不是议题说的误触。开关在创建 Navigator 时按 Readium 自己解析出的 `verticalText` 决定（`EpubNavigatorFactory.createPreferencesEditor(...).verticalText.effectiveValue`）；改排版方向会重建 Navigator，届时重新判断。
- 开关打开后，Readium 的 `goForward()` / `goBackward()` 在滚动模式下什么都不做。所以：
  - 底栏的“上一页 / 下一页”在滚动模式下改为切到上一章 / 下一章。这与改之前的实际效果相同（Readium 当时也是跳到相邻章节），也与 iOS 相同。
  - 轻点翻译开着、且没开“禁用点按翻页”时，屏幕左右 16% 边缘的轻点在滚动模式下不再翻页，按普通轻点处理（翻译所点的句子）。iOS 的点按翻页在滚动时同样不响应。
- 分页模式的滑动翻页、点按翻页都没变。

已知边界：一本书的 CSS 自己声明了竖排、但元数据没让 Readium 判成竖排，并且排版方向选“跟随出版物”、又开了滚动阅读时，横滑不能换章，要用底栏的按钮或目录。

### 3.2 译文浮窗保底高度，并始终留在屏幕内

原来的两处问题（`ReaderActivity.ReaderFloatingLayer`）：

- 卡片高度取“选区上方或下方剩余空间”，没有下限。选区占满大半页（点段翻译时常见）时，卡片被压成贴在上下栏边上的细条，标题栏和拖动条被裁掉。
- 实测尺寸后的避让逻辑在找不到不挡选区的位置时，优先“不挡选区”，宁可把卡片推出安全区；拖动只限制幅度（屏宽 45%、屏高 35%），不限制是否还在屏幕内。

改法，以 iOS 为准：

- 卡片高度下限 108dp（安全区更矮时取安全区高度），上下都放不下时盖在选区上。
- 三种摆放（选区上下、竖排的左右侧、顶部横幅）统一成一步：按实测尺寸算出起始位置，加上拖动和避让的偏移，最后夹回安全区（上下栏之间、左右各留 12dp）。安全区优先于“不挡选区”。
- 避让的每个候选位置先夹回安全区；都挡选区时，只有确实能少挡才移动，否则不动。
- 拖动从卡片当前的实际位置起算，止于安全区，拖动条不会离开屏幕；用户拖动过卡片后不再自动避让，卡片留在用户放的位置。选区、请求或显示方式变化后照旧恢复自动摆放。

### 3.3 测试

- `ReaderTapTranslationTest` 增加 `verifyCrampedOverlayAndDrag`：选区上下各只留 40dp 时，卡片高度不小于 100dp、在安全区和上下栏之间且位置稳定；向上、向右下拖出很远后分别停在安全区边缘；再往回拖 40dp，卡片立即移动 40dp 并停在选区上方不被避让推走。原有 24 组不遮挡选区的几何用例不变。
- 新增 `ReaderScrollGestureTest`（已加进 `scripts/android_device_validation.py` 的回归列表），用第一章加长到好几屏的合成书：
  - 分页模式下，注入的横滑翻到下一页、反向横滑翻回来。这一段同时证明注入的手势确实作用在正文上。
  - 切到滚动模式后，先向上滑一次，确认正文确实滚动了（触摸到达了滚动中的章节）；然后横滑、斜着横滑、带横向偏移的上下轻扫、反向横滑，每次之后都还在原章节，并且没有被弹回章节开头。
  - 滚动模式下“下一页 / 上一页”各切一章。
  - 排版方向改竖排后开关关闭，改回横排后恢复（这一条只查了开关的状态，没有在竖排书上实际滑动）。
  - 书籍文件内容和修改时间不变。
- 合成书夹具 `createSyntheticEpub` 增加可选参数 `fillerParagraphs`（默认 0，其他测试不受影响），给第一章追加填充段落。
- 完整设备集由 49 项变为 50 项（回归 38、界面 12）。

## 4. 没有改的

- iOS 的任何文件。
- PDF 阅读（`PdfTapTranslationController` 等）。
- 分页模式的手势、翻译和查词流程、书籍文件（仍然只读）。

## 5. 验证状态

日志都在 `.build/SafeValidationLogs/`。只验了 Android；iOS 没有改动，没有跑任何 iOS 构建或测试。

- `20261118-230848-kukurmrr`（`JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh`）：**通过**，`BUILD SUCCESSFUL in 11m 49s`。
  - `:androidApp:testDebugUnitTest` 95 项，0 失败，这次按 1.7.1 重新跑的。`:shared:testAndroidHostTest` 88 项、0 失败，被 Gradle 判为 UP-TO-DATE，沿用 11-17 21:14 那一轮的结果（共享层这次没有改动）。
  - `lintDebug` / `lintRelease`：各 0 错误、7 警告、1 提示，与 1.7.0 相同。
  - Release APK 是这一次构建出来的，后面没有再变。
- `20261118-232125-ius2qi4k`（设备测试，范围 `full`，带 Release 冒烟，第一次）：**失败**。回归 38 项里 37 项通过，包括带新用例的 `ReaderTapTranslationTest`；新写的 `ReaderScrollGestureTest` 在第一步失败（“分页模式下横滑没有翻页”）。界面测试和 Release 冒烟没有跑。
  - 失败的是测试本身，不是 App：从日志看，滑动是在 WebView 刚开始渲染后约 0.1 秒注入的（模拟器当时负载约 8），页面还没排好版；另外注入的事件用的是实际送达的时间，模拟器卡顿时系统的速度跟踪会把间隔过长的滑动当成手指已停，算不出速度就不翻页。这两个原因没有分别验证是哪一个起的作用，一起改掉了。
  - 改法：手势注入前先等正文按当前模式排好版（用页面自己报告的尺寸判断是分页还是滚动）；事件时间改用固定 10ms 的间隔，不受送达快慢影响；第一章加长，滚动模式下加一条“向上滑确实能滚动”的对照，否则“横滑后没换章”可能只是手势没送到。App 代码没有因此改动。
- `20261118-233829-0oienljq`（改完测试后重跑构建，重新打测试包）：**通过**，`BUILD SUCCESSFUL in 1m 34s`。只重新编译了测试代码；单元测试、R8 和 Release APK 都是 UP-TO-DATE，Release APK 与第一次构建的是同一个文件。Lint 重新跑了，结果相同。
- `20261118-234022-9k67m0xr`（设备测试，范围 `full`，带 Release 冒烟，第二次）：**通过**，约 16 分钟，负载最高 12.0，没有降频，没有 ANR。模拟器 `JerreaderQA_API35_20261007`（API 35，x86_64，1206×2622、480 dpi）。
  - 回归 38 项、界面 12 项全过，含 `ReaderScrollGestureTest`、带新用例的 `ReaderTapTranslationTest` 和 `thousandLearningRecordsRemainSearchableAndScrollable`。
  - 新浮窗用例的实测位置（像素，安全区上下为 168–2208、左右为 36–1170）：选区占满页面时卡片在 168–492，高 324px（108dp）；向上拖到头停在 168；向右下拖到头停在右下角（右 1170、下 2208）；再往回拖 40dp 后在 90–1050、1764–2088。截图 `android-translation-cramped.png`、`android-translation-cramped-dragged.png` 看过：标题栏、拖动条和三个按钮都完整显示。
  - Release 冒烟：x86_64 Release 包安装启动成功，EPUB、PDF 都能打开并回到书架，三个标签都能打开；装上去的包与构建产物的 SHA-256 一致。
- Android 正式包：`scripts/package_release_apk.py` 通过，`dist/Android/Jerreader-Android-1.7.1-build70-20261118-235658-release.apk`，42,186,557 字节（1.7.0 是 42,186,555），SHA-256 `05a60e613cd23981bd26e3483b992bd482add46a0962caaa920a5acd6ba55ce9`。badging：versionCode 70、versionName 1.7.1、compileSdk 37、minSdk 24、targetSdk 36、只有 arm64-v8a。签名身份与 1.7.0 的包相同，没有重复的类定义，不含测试夹具；与模拟器验过的 x86_64 包除原生库外内容一致。
  - 同 1.7.0：这台 Mac 的 `/usr/bin/java` 找不到 Java 运行时，打包时给这一条命令临时设了 `JAVA_HOME` / `PATH`，指向仓库的 `.tools/jdk17`。

## 6. 没验证的

- 没有真机验证。议题里的两个现象也没有在真机上复现过。
- 修复前的 1.7.0 Android 包没有在模拟器上复现这两个问题：结论来自读源码和 Readium Kotlin 3.4.0 的字节码。新测试没有在旧代码上跑过，所以“旧代码下这些测试会失败”没有实测。
- 竖排书在滚动时的横滑换章只查了开关状态，没有实际滑动验证。3.1 里写的已知边界（书自己声明竖排但 Readium 没判成竖排）没有处理，也没有测试。
- 真实书籍没有参与：测试用的都是合成 EPUB。
- iOS 没有重新验证；“iOS 没有这两个问题”来自读 iOS 源码，没有在模拟器或真机上专门验过这两个场景。
- 写这份记录时还没提交 git，没有推送，没有发 GitHub Release，议题 #2 没有回复。
