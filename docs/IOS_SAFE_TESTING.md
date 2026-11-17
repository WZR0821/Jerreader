# iOS 低负载验证

2026-11-13 更新：按用户要求，**系统负载和温控降频只记录，不再排队、不再中止任何任务**；新增后台运行入口 `scripts/background_build.py`，不用模拟器的构建和 IPA 打包不再随登录会话一起被结束。依据与边界见“2026-11-13 负载与温控不再拦截”。单任务串行、主机锁、外部构建检测、内存与磁盘保护、失败不重试都不变。

此前（2026-10-05）按用户要求取消固定 50 的负载中止规则，改为 150 的排队门槛 + 单任务串行；该门槛已于 2026-11-13 取消。下文带日期的小节是当时的记录，与本段冲突时以本段为准。

这台 Intel Mac 曾在“编译后立即启动 iOS 18.2 模拟器”时出现 134.94 的系统负载，并有 WindowServer watchdog 历史。降低并发、分开编译和运行能让机器保持可用；用户态监测拦不住显示驱动卡死，也不能用一次成功推算长期成功率。

## 日常入口

所有命令从仓库根目录执行。需要完整 Xcode；如果 `xcode-select -p` 返回 Command Line Tools，给当前终端设置 `DEVELOPER_DIR`，指向已安装 Xcode 的 `Contents/Developer`，不修改全局设置。

```sh
# 只检查项目、Xcode、资源、冲突进程和模拟器状态，不构建、不启动设备
scripts/safe_xcodebuild.sh doctor

# 五组生产 Swift 逻辑回归：不启动模拟器、不访问外网
scripts/safe_xcodebuild.sh host-test

# iOS SDK 编译检查：每个验证阶段只执行一次
scripts/safe_xcodebuild.sh build

# Android 与 iOS 共用排队锁：单 worker，测试、lint、Debug APK
scripts/safe_android_build.sh

# 在登录会话之外做 Release 构建并打未签名 IPA（见“后台运行”）
scripts/background_build.py submit
```

`host-test` 串行编译并运行翻译 watchdog、学习调度/原句练习/阅读统计、离线词典安装与损坏保护、中日词典以及动态 AI 模型目录五个测试入口。它覆盖实际生产源文件，但不是 iOS XCTest，不替代 SwiftData、Readium、书籍内容和修改时间不变、UI 或真实设备验收。

### 排队方式

- 提交命令后先等待同一用户的主机锁；锁覆盖整个验证任务，包含阶段间的等待和清理。多个等待进程逐个获得锁，不承诺严格的提交先后顺序。
- 每个实际命令启动前重新检查：其他构建运行、模拟器被占用或内存暂不足时继续等待，不启动编译。遇到等待条件后，须连续 30 秒资源合格才放行。负载和温控降频不是等待条件。
- 等待默认每处最多 1 小时，约每 5 秒检查、每 45 秒报告原因，可按 Ctrl-C 取消。`JERREADER_QUEUE_TIMEOUT_SECONDS` 支持 1–43200 秒；超时记为 `queue-timeout`，不是构建失败或通过，也不会自动重新提交。
- 运行中的单 job 不因负载或温控降频被终止；仍监测内存、磁盘和外部竞争构建。两项读数照常写入 `health.jsonl`。
- `doctor` / dry run 只报告“可开始”或“将等待及原因”，不加入长时间队列、不编译、不启动设备。
- 这些入口只管理本仓库发起的构建和共用入口的任务，不是 macOS 全局任务调度器。其他项目仍可能绕开锁；检测到竞争时停止本次构建，不杀其他项目。

### 后台运行（登录会话崩溃不影响）

`WindowServer` watchdog 触发时，macOS 结束的是整个图形登录会话：终端、IDE、代理和它们启动的构建全部被杀。launchd 的用户级后台域（`user/<uid>`，会话类型 Background）不属于这个会话。2026-11-13 09:08 那次崩溃后核对，崩溃前启动、崩溃后还在的用户进程全都属于后台域。`scripts/background_build.py` 把不需要模拟器的任务提交到这个域里运行：

```sh
scripts/background_build.py submit          # 默认 ipa：Release 构建 + 未签名 IPA
scripts/background_build.py submit build    # 也可以是 build | analyze | host-test | doctor
scripts/background_build.py wait            # 等到结束，通过才返回 0
scripts/background_build.py status          # 状态、阶段和最近几行输出
scripts/background_build.py collect         # 把通过的 IPA 复制进本仓库的 dist/iOS
scripts/background_build.py cancel          # 停止正在运行的后台任务
```

- 后台任务读不了“文稿”“桌面”“下载”文件夹（macOS 隐私保护，后台进程没有界面可以申请权限）。`submit` 先在当前会话里把源码同步到 `~/Library/Caches/Jerreader/BackgroundBuild/worktree`，后台任务编译这份副本。副本有自己的 `.build/SafeDerivedData` 和验证日志：第一次是全量编译，之后增量。`.build`、`dist`、`.git`、`.tools`、Gradle 目录、`local.properties` 和签名材料（`keystore.properties`、证书、描述文件）不进副本。
- 副本里运行的仍是 `scripts/safe_xcodebuild.sh`：同一把主机锁、单 job、每阶段一次、不重试。后台任务和前台任务不会同时编译。
- 编译的是提交那一刻的源码快照，之后在仓库里的修改不影响已提交的任务。同一时间只有一个后台任务。
- 任务定义只为这一次运行加载，不写入 `~/Library/LaunchAgents`，重启后不存在。会话崩溃后重新登录，用 `status` 或 `wait` 看结果，通过后 `collect`。任务消失又没有留下结果时状态为 `lost`，不会自动重跑。正常注销后任务是否保留没有验证过。
- 需要指定完整 Xcode 时，把 `DEVELOPER_DIR` 设在 `submit` 这条命令上，它只传给这次任务。
- 不支持模拟器、UI 测试和 Android：模拟器验收仍在登录会话里按下一节执行；Android 依赖不进副本的 JDK、SDK 配置和签名文件。

## 模拟器验收

需要用户在当前任务明确同意。先用完整 Xcode 的 `xcrun simctl list devices available` 找到一个已安装的 iOS 18+ iPhone。当前设备必须是 Shutdown；脚本不会擅自关闭用户已经打开的设备。构建及单元测试不得打开 Simulator GUI。

```sh
# 下方 UDID 是占位符，需要替换为实际设备 ID
JERREADER_ALLOW_SIMULATOR=1 \
JERREADER_SIMULATOR_DESTINATION='platform=iOS Simulator,id=<UDID>' \
scripts/safe_xcodebuild.sh test

# UI 单独进行，按改动选择一个测试类/方法，最多 8 个逗号分隔选择
JERREADER_ALLOW_SIMULATOR=1 \
JERREADER_SIMULATOR_DESTINATION='platform=iOS Simulator,id=<UDID>' \
JERREADER_TEST_FILTER='JerreaderUITests/TestClass/testMethod' \
scripts/safe_xcodebuild.sh ui-test
```

`test` 默认运行 JerreaderTests 全部单元/集成测试；`ui-test` 只运行 JerreaderUITests。选择用例只减少运行范围，不把未执行用例算作通过。单元测试筛选格式为 `JerreaderTests/TestClass[/testMethod]`。

`JerreaderSelectionUITests/testIPadShellUsesWholePortraitAndLandscapeWindow` 只在 iPad 上运行，在 iPhone 上会跳过；要单独以 iPad 模拟器为 destination，用上面的筛选跑这一项。2026-11-16 之前 UI 测试目标只设了 iPhone（`TARGETED_DEVICE_FAMILY = 1`），运行器在 iPad 上走 iPhone 兼容模式，读到的设备类型是 iPhone，这一项在 iPad 上也会跳过；现已改成与 App 相同的 `"1,2"`。

Android 设备验收同样通过共享入口 `scripts/safe_android_build.sh test`。默认执行已审核的 49 项离线设备与 UI 用例（37 项功能回归、12 项界面用例，含 9 项备份、千条列表详情/筛选回归与翻译卡片几何场景；2026-11-16 补入了此前写好却没列进运行清单的 5 个回归类和 6 项界面用例）；只复验界面时可设置 `JERREADER_ANDROID_VALIDATION_SCOPE=ui`，执行固定的 12 个界面用例，不接受任意命令或测试类。先完成 APK 与测试 APK 编译，再显式指定单一 AVD。入口在持锁、资源稳定后启动只读临时设备，使用 2 个虚拟核心、请求 2 GiB 内存、当前 SDK 支持的 `swiftshader` 软件渲染，无窗口、无快照，关闭本轮不需要的 guest Vulkan。SDK 可能提高镜像最低内存，设备记录同时保存请求值与 guest 实际 MemTotal，不能将请求值当作硬限制。

临时设备开机完成后先等待资源稳定、核验开机窗口及 events，再切换飞行模式、关闭 Wi-Fi 和安装 APK。测试前再次等待稳定并确认系统设置；诊断缺失、超时、开机 events 中有 ANR 或存在无响应窗口时不启动测试。这一流程兼容缺少 telephony 服务的 AOSP 镜像。命令依据 [AOSP ConnectivityService](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java)。仅在本次拥有的只读覆盖层中卸载预装的 Jerreader 与 androidTest 两个包，再装预编译 APK，避免原 AVD 预装更高版本或不同调试证书导致安装失败；其他包及原 AVD 数据保留。

37 项功能回归先在同一进程运行一次（入口按至少 17 项核对）；随后 12 项 UI 用例在 instrumentation 连接前设置系统字体（两项大字号用例为 1.5 倍，其余 1.0 倍），每项用新进程执行一次，严格串行，不自动重试。每份和汇总结果均核对完成状态、最低用例数及每个用例的成功状态，跳过、漏跑或失败不能算通过。每个 UI 用例完成后导出截图，成功后才合并至证据目录，后续中断不会覆盖既有证据。关闭设备前保存实际系统版本、屏幕配置、ANR、全部 logcat 缓冲区、窗口及内存信息；失败也保存已产生的截图。

每次测试按以下顺序执行：

1. 验证项目文件、完整 Xcode、内存、磁盘、其他构建和模拟器状态；获取主机独占锁。
2. 所有模拟器关闭时执行一次 `build-for-testing`，复用固定 `.build/SafeDerivedData`。
3. 等待负载连续 30 秒不超过每逻辑核心 2.5（本机 8 线程即 20；2026-11-12 用户要求由每核 1 上调）；最多等待 5 分钟，等不到也继续下一步（2026-11-13 起不再因此退出）。等待期间内存或磁盘不合格仍然中止。
4. 仅启动指定设备并等待 bootstatus；再次等待资源稳定。
5. `test-without-building` 执行一次，单设备、单 worker、禁用并行测试、关闭覆盖率和额外失败诊断采集。每项测试默认 120 秒、最大 180 秒，整个测试阶段最多 30 分钟。
6. 成功、失败或中断后都只关闭本次启动的设备，保存日志与结果，再释放锁。不清空、不删除设备、不重启显示服务。

构建阶段最多 30 分钟，启动设备命令最多 120 秒，bootstatus 最多 180 秒。监测命令（`ps`、`sysctl`、`pmset` 等）每条最多等 30 秒，超时仍按“监测不可用”停止（2026-11-13 用户要求由 8 秒放宽：模拟器开机时负载约 250，`ps` 超过 8 秒，XCTest 一项未跑即中止）。以上是工具超时和采样保护，不能充当内核级资源上限。Apple 官方说明支持拆开构建与执行：[TN2339](https://developer.apple.com/library/archive/technotes/tn2339/_index.html)。

## 保护边界与诊断

| 项目 | 行为 |
| --- | --- |
| 编译并发 | 固定 `-jobs 1`；Swift 驱动 `-j1`，禁用 batch；Scheme 构建与测试并行关闭；Android `--no-parallel --max-workers=1`、Kotlin 进程内编译；nice 10 优先级 |
| 负载与温控 | 2026-11-13 起只记录：1 分钟 load average 和温控降频都不排队、不中止。只有模拟器阶段之间的稳定等待参考负载，最多等 5 分钟 |
| 运行中保护 | 每轮约 5 秒采样（系统查询耗时另计）；内存压力非正常、可用百分比低于 15%、磁盘不足 12 GiB 时中止 |
| 启动内存余量 | `memory_pressure -Q` 的 system-wide free percentage 至少 20%；该值是系统报告值，不等同于单一 App 可分配内存 |
| 查询失败 | 尚未启动时关闭队列并等待监测恢复，仍受排队超时约束；运行中停止并清理，不能因为缺少数据而放行 |
| 互斥 | 主机测试、iOS 与 Android 验证共用 flock；启动前发现竞争任务就排队，运行中出现新的竞争构建则中止本次任务；不自动重试 |
| Gradle | 仅日志最后明确记录为空闲的 daemon 可放行；活跃、日志缺失或未知状态继续等待，不用低 CPU 利用率猜测。外部 daemon 不会被自动关闭 |
| 中断 | 向本次命令独立进程组及可由父进程链确认归属的独立 Gradle 子进程组依次发 INT/TERM/KILL；不 killall，不操作 WindowServer；系统托管 XPC 服务仍可能在进程组之外 |
| 缓存 | 固定 DerivedData；不自动清缓存，不用更换目录重试 |
| 重试 | 编译和测试都无自动重试，故障必须先查日志 |

监测能拦截后来启动的竞争任务，但不能阻止外部工具启动它们；iOS、Android 构建和设备测试仍需使用者保持串行。nice 是优先级，不是 CPU/GPU 硬配额。CPU、内存和温控正常也不能证明 Intel 显示驱动安全：2026-11-13 的崩溃发生在机器空闲时。

可选环境变量保留 `JERREADER_CONFIGURATION=Debug|Release`、`JERREADER_DRY_RUN=1` 和筛选器；`JERREADER_BUILD_JOBS` 只接受 1。dry run 仅做预检和展示基础参数，不执行完整测试链路。Android 入口用于本机 macOS，复用同一套资源监测；它不要求安装 Xcode，但仍检测已启动的模拟器进程。

`build` 默认配置为 Debug。制作交付 IPA 时必须显式使用 `JERREADER_CONFIGURATION=Release scripts/safe_xcodebuild.sh build`，成功后再把该次日志目录交给 `package_unsigned_ipa.py --validation-log`。Debug generic 的成功不能代替 Release；打包脚本会核对实际命令配置。配置选择错误的阶段应保留原始结果，修正参数后另开明确的验证阶段，不改名冒充正式包。

### 单次进程温控例外（2026-10-07，已于 2026-11-13 作废）

2026-11-13 起温控降频不再拦截任何任务，`JERREADER_SESSION_ALLOW_THERMAL_THROTTLING` 设置与否没有区别，`summary.json` 也不再记录它。以下两段是当时的记录。

用户在本轮验收中明确授权“本次不用管温控”，并要求继续完成剩余任务。只有取得这类直接授权后，才可在对应安全入口命令中设置 `JERREADER_SESSION_ALLOW_THERMAL_THROTTLING=1`；默认是 `0`，其他值拒绝。不得将它写入项目配置、全局环境或 shell 启动文件。

该开关只让已读取到的温控降频不再成为本次进程的排队/中止原因；仍保留原始温控读数，启动日志明确显示例外，`summary.json` 记录 `allow_thermal_throttling`。监测缺失、内存、磁盘、150 排队门槛、模拟器阶段连续 30 秒负载稳定等待、共享锁、外部任务占用和测试失败检查仍有效。Android 子进程继承同一选项。下一次未显式设置时恢复默认温控保护。

排队预检已经发现负载超门槛、资源不足或外部构建时，先保留该等待原因，再查询设备清单。主机繁忙使监测暂不可用时，只在尚未启动任务的有界队列内等待；缺失数据不能启动任务。资源回落后必须完成进程、设备及资源检查。运行中的监测错误仍传播至监督器并清理当前任务。2026-10-07 完整离线维护回归 53 项通过。

每次实际运行保存在 `.build/SafeValidationLogs/<时间-随机后缀>/`：阶段 `.log`、资源 `health.jsonl`、最终 `summary.json`；实际 iOS 测试另有 `.xcresult` 与 `test-summary.json`。通过 Xcode 16 的 `xcresulttool` 核验至少一项通过、零失败，且通过数加跳过数必须等于总数；未结算、空测试集合或全部跳过不算通过。状态分为 `passed`、`phase-failed`、`queue-timeout`、`safety-stopped` 和 `interrupted`。安全中止不是断言失败，也不是测试通过。

保护逻辑离线回归入口：

```sh
python3 scripts/test_safe_ios_validation.py
```

## 2026-10-17 交付产物检查

本轮曾出现带 ` 2.class` / ` 2.dex` 的生成副本，以及构建成功但 DEX 类型重复的 Release APK。旧生成目录已可逆隔离；当前本机的 `androidApp/build`、`shared/build` 为忽略的符号链接，指向用户 Library/Caches 下的全新输出。该路径不写入 Gradle 或工程配置，普通检出仍可使用默认目录。完整重建阶段以命令局部 GRADLE_OPTS 禁用构建缓存和 Kotlin 增量，防止复用已污染产物；不修改共享保护默认值。

设备 UI 和 Release 断网启动通过后，使用 `scripts/package_release_apk.py` 核对签名与上一交付身份相同、包名与版本正确、非 debuggable、ZIP CRC 正常、DEX 类型零重复、无 Debug 夹具及本轮功能标记。发现重复类型即拒绝交付，即使安装和启动成功也不能放行。iOS 通过 generic unsigned Release 后使用 `scripts/package_unsigned_ipa.py` 检查 arm64、无签名/加密/测试夹具、可执行权限和每个打包文件的摘要。

若单个无 GUI 模拟器仍触发高负载或显示卡顿，立即停止本机模拟器验收。后续 iOS 集成/UI 测试应在真实 iPhone 或另一台稳定 Mac/CI 上执行，需要另行配置设备与签名；本次未接入远程服务或修改签名。

历史上 2026-09-12 曾使用固定 50 的负载中止阈值；2026-10-05 用户将其替换为上述 150 排队策略。模拟器阶段之间要求连续 30 秒负载不超过每逻辑核心 2.5（2026-11-12 用户要求由每核 1 上调：iCloud/Spotlight 常驻时本机空闲负载就有 10–15，每核 1 的 8 基本等不到），内存、温控、磁盘和并发限制仍生效。调整门槛不代表允许自动重试或启动模拟器。2026-11-13 起 150 排队门槛和温控中止取消，阶段间等待改为最多 5 分钟后继续，见下一节。

2026-11-12 查到的崩溃记录：会话被踢回登录界面只有 2026-11-11 17:16 一次（`WindowServer` watchdog，原因是 `Display ... not ready`，当时冷编译 Gradle 且另有 4 个 Claude 会话，17:29 重新登录时负载 128）；另有 2026-10-18 11:29 一次内核 panic（Intel CrashLog，附近无构建记录）和三次关机卡住（非崩溃）。同期负载 43–58 的测试与 iCloud 同步、194–205 的模拟器开机都未崩溃，所以负载本身不是可靠的崩溃预兆，显示异常和 `WindowServer` 占用仍须照常盯住。

## 2026-11-13 负载与温控不再拦截

用户反馈：负载门槛经常被触发，任务做不完，IPA 打不出来。核对两次有系统记录的会话崩溃：

- 2026-11-11 17:16：`WindowServer` watchdog。崩溃报告里整机 CPU 占用约 35%，只有一个 Gradle JVM 在用约 2.4 个核心。
- 2026-11-13 09:08：同样的 watchdog。当时没有任何构建，CPU 占用约 7%，电池供电，开盖唤醒后约 8 分钟。统一日志里 Intel 显示驱动（`AppleIntelICLLPGraphicsFramebuffer`）在一次屏幕电源状态切换中卡住：`VRR_ERR`、`SRD Exit` 之后不再出帧，约 40 秒后 watchdog 结束会话。

同样的驱动报错平时也会出现并自行恢复：2026-11-13 凌晨的模拟器测试约 100 分钟，负载多次超过 200，`VRR_ERR` 出现 179 次，没有崩溃。负载高和温控降频既不是崩溃的必要条件，也不是充分条件；按它们排队或中止只会让任务失败。是驱动缺陷还是屏幕链路的硬件问题没有查明。统一日志只保留约一天半，2026-11-11 那次的驱动日志已经查不到。

用户据此决定：

- 取消 150 的排队门槛。负载照常写入 `health.jsonl`，不参与任何判断。
- 温控降频不再是排队或中止原因，同样只记录。
- 模拟器阶段之间的稳定等待保留，最多 5 分钟，等不到就继续。`JERREADER_SESSION_ALLOW_HIGH_LOAD=1` 只用于跳过这段等待。
- 入口之外不再另加“负载超过某值就停”的守护脚本。
- 增加后台运行入口（见“后台运行”）：目标从“避免崩溃”改成“崩溃了任务也不丢”。

不变的部分：主机锁与单任务串行、外部构建和模拟器占用检测、内存（启动 20%、运行 15%、压力等级正常）、磁盘 12 GiB、各阶段时限、监测缺失即停、失败不自动重试、模拟器须当次明确同意。这些改动不降低崩溃概率；出现屏幕卡顿、黑屏或显示延迟时的处理也不变。

实测记录（2026-11-13 09:40，电池供电）：

- `python3 scripts/test_safe_ios_validation.py`：74 项离线回归通过，含后台入口的 5 项（任务定义、同步排除项、拒绝模拟器模式和重复提交、任务消失记为 `lost`、只收取通过的 IPA）。
- `scripts/background_build.py submit doctor`：后台域内预检通过，监测数据齐全。
- `scripts/background_build.py submit`：任务在后台域运行（`launchctl managername` 为 `Background`），副本内 Release 全量编译加打包共 8 分 36 秒，`BUILD SUCCEEDED`。采样 100 次，负载最高 8.25，可用内存最低 65%，没有出现温控降频。产物 `Jerreader-1.5.0-build67-20261113-094835-unsigned.ipa`，13,973,715 字节，SHA-256 `56d92d7e3c68fa282660eb687a499d5e5fc9a055f884853cc68d3c069f74da40`；`collect` 后摘要一致。包内 53 个文件，文件清单与仓库里上一次 Release 构建的 App 相同。
- 没有验证的：登录会话真的崩溃时这个任务能否继续。崩溃无法人为制造，目前只有 2026-11-13 09:08 幸存进程都属于后台域这一条旁证。IPA 没有签名，也没有装到设备上运行。高负载或温控降频下的实际构建这次没有遇到，新规则在这两种情况下的行为只有离线回归覆盖。

## 2026-09-09 实际验证记录

- 安全脚本 24 项离线回归通过，覆盖阈值、缺失监测值、资源稳定等待、参数限制、flock 互斥、构建失败不启动设备、不重试、模拟器清理幂等、结果不能为空，以及真实轻量 Python 父子进程中止和无关进程保留。测试中的模拟器/Xcode 调用使用 Mock，没有启动模拟器。
- `host-test` 四组生产 Swift 回归全部通过，采样峰值负载 3.89，最低 system-wide memory free percentage 为 62%。日志：`.build/SafeValidationLogs/20260909-001424-96g5j1c9/`。
- 唯一一次 generic iOS device / Debug / arm64 / 未签名完整构建成功（`BUILD SUCCEEDED`），使用默认 1 job、新 Swift 参数与固定 DerivedData；采样峰值负载 6.56，最低 system-wide memory free percentage 为 62%，未触发资源保护。日志：`.build/SafeValidationLogs/20260909-001706-_k7_3mmb/`。没有为验证性能而再次构建。
- Shell 语法、Scheme XML/并行配置与 `git diff --check` 通过。未进行 iOS XCTest/UI 或真机测试；未修改出版物导入、Readium 生命周期或用户书籍。

## 2026-10-05 排队策略验证

- `python3 scripts/test_safe_ios_validation.py`：37 项离线回归通过（3.857 秒）。包括真实轻量 Python 进程之间等待锁释放、取消/超时不夺取锁、150 门槛、连续稳定等待、运行中高负载不中止、保留温控/内存保护、Gradle 空闲状态判定及本任务独立子进程识别。
- 所有 Xcode、Gradle 和模拟器调用均为 Mock；用例只启动轻量 Python 进程，不以真实高负载构建做压力测试。
- 两个安全入口与四个 Android 打包入口的 Shell 语法、`git diff --check` 通过。新 Android 入口尚未实际执行构建，不宣称 APK 已通过。

### Android 预编译设备验收（2026-10-06）

`safe_android_build.sh` 同时生成 Debug 应用和 androidTest APK。显式获得 Android 模拟器授权后，`safe_android_build.sh test` 使用上述预编译 APK，不调用第二次 Gradle。设置 `JERREADER_ALLOW_SIMULATOR=1` 以及唯一目标 `JERREADER_SIMULATOR_DESTINATION=platform=Android,serial=emulator-5580,avd=<AVD名称>`，并配置 `ANDROID_HOME`。

内部 `android_device_validation.py` 只能由共享监督器调用。模拟器以只读、无窗口、单台方式运行，继承受监督进程组；测试禁用网络，结果必须达到审核范围的用例数且无失败、跳过或漏跑。结束时只清理本次拥有的设备。开始前仍等待更严格的模拟器资源稳定条件；实际设备执行与中止记录见 [本轮验收记录](VALIDATION_2026-10-07.md)。

### 2026-10-12 本轮授权例外

用户明确要求本次忽略温控与负载。命令可显式设置 `JERREADER_SESSION_ALLOW_THERMAL_THROTTLING=1` 和 `JERREADER_SESSION_ALLOW_HIGH_LOAD=1`；两个开关均默认关闭，不写入 shell 配置或项目环境。负载仍记录在日志，但不阻止本命令的入队或阶段冷却。共享串行锁、外部构建占用、内存、磁盘、显示故障保护及 30 秒资源稳定检查继续生效。这不改变后续任务默认的 150 入队门槛。

同日，其他项目两次启动设备中断打包后，用户答复“直接进行验收和打包，无需理会”。该次任务可额外设置 `JERREADER_SESSION_ALLOW_EXTERNAL_ACTIVITY=1`，允许外部构建及非本任务设备同时存在；不暂停或终止其他项目。此开关默认关闭，仅写在本次命令中并记录至摘要。本任务仍通过共享锁串行执行，单 job/worker，保留内存、磁盘、监测完整性与实际测试失败检查。选定的 iOS 设备若已被启动仍不得占用；Android 同样拒绝已占用的目标 serial。清理使用数字父进程链确认归属，避免读取外部进程完整参数造成监测超时，只关闭本任务进程及自己启动的指定设备。
