# Jerreader 1.5.0（67）交接：Android 正式包与 iOS 未签名 IPA 均已打出

2026-11-13 09:48 补记：iOS Release IPA 已打出。用 `scripts/background_build.py submit` 在登录会话之外构建（源码副本内 Release 全量编译，`BUILD SUCCEEDED`，8 分 36 秒；验证日志 `20261113-094001-h4jj_bvq` 在 `~/Library/Caches/Jerreader/BackgroundBuild/worktree/.build/SafeValidationLogs/`）。产物 `dist/iOS/Jerreader-1.5.0-build67-20261113-094835-unsigned.ipa`，SHA-256 `56d92d7e3c68fa282660eb687a499d5e5fc9a055f884853cc68d3c069f74da40`，已复制到桌面；未签名、未装到设备、未发布到 SideStore。App 源码自 `test` / `ui-test` 通过后没有改动（最新的源码文件是 11-12 16:10）。同日用户决定系统负载和温控降频只记录、不再排队或中止，下文“队列阈值 150”“降频放行”“>100 就停”等说法都已作废，以 `AGENTS.md` 和 `docs/IOS_SAFE_TESTING.md` 为准。另：当天 09:04–09:07 有一条要求插电的 `build-release` 排队因电池供电一直没开跑，09:08 登录会话崩溃时被结束。

2026-11-12：本轮改进的源码与文档已写完。Android Release 构建跑了五次，第五次（`20261112-144639-u4dnvxww`）通过：共享 81 项与平台 82 项单元测试全过，Lint 0 错误 4 警告，arm64 Release 包 42.0 MB（1.4.1 为 144 MB），签名与 1.4.1 一致。前四次是安全中止或 compileSdk 过低（已修正），见 `docs/RELEASE_1.5.0_2026-11-12.md` 第 7 节。第五次用了用户只给这一次的降频放行，下次不能沿用。之后模拟器设备测试（`20261112-152402-uzflko27`，25 项与 Release 冒烟）通过，正式包 `dist/Android/Jerreader-Android-1.5.0-build67-20261112-153559-release.apk` 已打好并复制到桌面。iOS：`doctor` 通过；`host-test`（`20261112-160525-l9l47xax`）在 `parity-fixtures` 编译时因 `ReadingCompanion.swift` 一处表达式超出类型检查时限而失败，已改写；修正后的 `host-test`（`20261112-164445-2sdbv1zw`）七组全过，对拍 75 个用例一致。Debug `build`（`20261112-172216-czmsucps`）通过、无警告。`test` 第一次（`20261112-222502-ve3mr0ih`）测试包编译通过，但模拟器开机时负载冲到 129，被会话守护按 >100 中止，没跑测试；第二次（`20261112-233126-s0v0o_a0`）去掉会话守护的负载上限后，模拟器开机通过，但负载升到 248 时安全脚本的 `ps` 健康检查超过 8 秒超时而中止，仍没跑测试；2026-11-13 用户要求把监测命令超时放宽到 30 秒后，第三次（`20261113-005335-prmh3z5m`）通过：XCTest 259 通过、3 跳过（都要外部真实书籍）、0 失败。`ui-test`（`20261113-012630-xq11d85i`）9 通过、11 跳过、0 失败，跳过的是要真实书籍或 iPad 的项。Release IPA 待打；上次用的模拟器是 `YomiReader QA iOS 18.2`（`373E00D2-4DA9-4018-9167-BBA72E705158`）。2026-11-11 本机在 Moji 的 Gradle 构建期间出现 WindowServer watchdog，用户随后要求"先改代码，构建前逐阶段征得同意"。下一位接手的人不要直接开跑，先按下文逐阶段问用户。

分支：`maintenance/2026-11`。基线提交 `e154f53`（bundle ID 改名；推到 GitHub 前按真实日期重写，现为 `2002caa`）之后的改动全部未提交。

## 改了什么

完整说明见 `docs/RELEASE_1.5.0_2026-11-12.md`，摘要：

- 版本：双端 1.5.0 / 67（61–66 留给已发到 66 的统一版）。iOS bundle ID `com.raydon.Jerreader`。Android `minSdk` 24、`compileSdk` 37（依赖要求；本机 SDK 已装 `platforms;android-37.0`）、`targetSdk` 仍为 36。
- Android 体积：R8 只压缩不混淆、资源压缩、ABI 拆分（交付 arm64-v8a，x86_64 仅供模拟器）、工具链升级、Readium 3.4.0 与 `PdfLocatorFormat` 旧 PDF 位置换算。
- 双端对拍：`shared/parity/*.json`，Android `ParityFixturesTest`，iOS host-test 套件 `parity-fixtures`。
- 结构：iOS `@Observable` + `ReaderSearchModel` / `ReaderCompanionModel`，大文件拆分；Android `ReaderSessionViewModel` / `ReadingSessionRecorder`。
- 从统一版移植：AI 阅读透镜、伴读模式、困惑地图、续读回顾、90 秒章节闭环（小点提示，不自动弹出）、复习卡发音 / 跟读。
- 短按查整词：iOS 关闭"轻点快速翻译"后轻点词语直接查词并记入学习记录（`ReaderWordSegmenter`）；Android 命中脚本直接返回词面与前后文、DPR 换算修正、查词成功即记录。

## 待跑阶段（每阶段先问用户，串行、只跑一次、失败不自动重跑）

先看其他会话有没有在构建（负载和温控 2026-11-13 起不再拦截）；出现卡顿或黑屏立即停止并告诉用户。

1. ~~Android：`JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh`~~ 已通过（`20261112-144639-u4dnvxww`）。改了 Android 源码就要重跑，打包脚本会核对构建日志。
2. ~~Android 设备测试~~ 已通过（`20261112-152402-uzflko27`）。命令要带 `ANDROID_HOME=<本机 SDK>`，否则启动前就退出。
3. iOS：~~`doctor` → `host-test` → `build` → `test` → `ui-test`~~ 已通过（`ui-test` 是 `20261113-012630-xq11d85i`：9 通过、11 跳过；跳过的 10 项要注入真实注音 EPUB，安全脚本还不支持，1 项要 iPad）。以后再跑时注意：模拟器开机后负载会冲到 200 以上、约 4 分钟才回落，安全脚本冷却能等到；会话外加的">100 就停"守护会把它掐掉。模拟器需 `JERREADER_ALLOW_SIMULATOR=1` 与单一 `JERREADER_SIMULATOR_DESTINATION`）。
4. ~~iOS 正式包~~ 已完成（2026-11-13，见文首补记）。以后重打用 `scripts/background_build.py submit`，它在副本里执行 `JERREADER_CONFIGURATION=Release scripts/safe_xcodebuild.sh build` 和 `scripts/package_unsigned_ipa.py`，再 `collect`。
5. ~~Android 正式包~~ 已完成（`package_release_apk.py` 也要 `ANDROID_HOME`）。
6. 产物先复制到 `~/Desktop` 再交付；用户没要求不发布到 SideStore。

Android 编译期的风险已经排除。仍待验证的：iOS 编译（Swift 6 对新数组拼接表达式与 `fileprivate` 扩展的类型检查、`EPUBReaderHost` 里新增的私有枚举）；R8 保留规则已由 Release 冒烟（打开 EPUB 与 PDF）验证。

留到下个版本：Lint 剩下的 4 项警告（EmptySuperCall、OldTargetApi、core 1.19.1、`recordReaderLookup` 的 LogNotTimber），详见 RELEASE 第 7 节。现在改会让已构建的包和源码对不上。

## 交付后要告诉用户的事

- 新 iOS 包名是另一个 App：旧书库走"备份 → 恢复"，API Key 要在新 App 里重新填写。
- Android 不再支持 Android 6 和 32 位 ARM 手机。
- 未移植：复习页按阅读困难排序、词条原文 Locator（"回到原文"改为书内搜索）、困惑地图 CONTEXT 类条目；章节闭环改为小点；iOS 透镜结果页不能切换伴读模式。
- iOS 语音只在单词复习卡里打开，全局开关仍为关闭。
- 短按查词的命中精度、ruby、竖排、与翻页手势的冲突需要真机或模拟器验收。真实 API Key 未参与任何验证。

## 清理记录

`.build` 里的旧 DerivedData 与 IPA 暂存目录已按用户选择移到废纸篓（保留日志、测试记录、`SafeDerivedData`、`SafeHostTests`、`SafeValidationLogs`、`SourcePackages`、ecdict 词典源和 `dist/` 全部安装包）。如需找回，到废纸篓按原名恢复。
