# Jerreader 开发规则

- 开始改动前先阅读 `Jerreader_PRD_v1.0.md` 和 `docs/ARCHITECTURE.md`。
- 一次只实施用户明确指定的里程碑，不提前增加登录、云同步、支付、DRM 绕过或整本翻译。
- EPUB 渲染使用 Readium，不自行重写排版引擎。
- 翻译和解释只能使用 Sheet、覆盖层或临时高亮，不得修改 EPUB 正文。
- UI 依赖服务协议，不直接依赖第三方响应结构。
- 真实在线服务必须有 Mock；测试不得依赖外网。
- 禁止提交 API Key、证书、个人 Team ID 或本机私有路径。
- 完成改动后运行可用的构建与测试；未实际运行的项目不得声称通过。
- 短按查词若受 Readium 限制，必须记录降级方案与未完成的正式验收目标。
- 书架中已导入的书籍文件是不可变输入：仅打开、阅读、翻页、选区、翻译和退出都不得改写文件内容或文件修改时间；阅读进度与最近阅读时间只能写入应用自己的数据库（iOS 为 SwiftData，Android 为 Room），不得写回书籍文件。凡修改导入、Readium 打开或阅读器关闭生命周期，必须保留并运行“内容不变＋修改时间不变”的回归测试。
- 品牌内部名、工程名、包名和应用内文案统一使用 `Jerreader`；仅简体中文系统桌面显示名使用“读鼠”。

## 版本规则

- 双端版本保持同步。小版本修复递增末位（如 1.4.0 → 1.4.1）；大版本更新递增中间位并将末位归零（如 1.4.1 → 1.5.0）。每次可分发构建的内部 build / versionCode 同步递增。
- 内部 build / versionCode 必须大于本机任何一条线发过的包。`JerreaderUnified` 与本仓库共用 Android `applicationId`（`com.jerreader.android`），统一版已发到 versionCode 66；装过统一版的手机拒绝安装更小的 versionCode。所以 1.4.1 (60) 之后的下一个可分发构建直接用 67，之后逐次 +1。
- iOS bundle ID 自 2026-11-11 起为 `com.raydon.Jerreader`（此前为占位的 `com.example.Jerreader`）。换 ID 后手机上是另一个 App：旧书库用“备份 → 恢复”迁移；API Key 只存在旧 App 的钥匙串里，不进备份，需要重新填写。

## 构建保护规则（适用于所有 AI 代理）

- 这台 Mac 已多次在密集构建和模拟器运行期间触发 Intel 显示驱动的 `WindowServer` watchdog，并导致整个用户会话回到登录界面。这是 P0 级开发环境风险。
- 任何代理都不得直接调用 `xcodebuild`。iOS 构建和测试必须从仓库根目录通过 `scripts/safe_xcodebuild.sh` 执行。
- 默认使用 `scripts/safe_xcodebuild.sh doctor` 预检、`host-test` 运行纯 Swift 离线逻辑回归；iOS 构建使用 `build` 或 `analyze`，generic iOS device、禁用签名、固定 1 个构建 job，不启动 Simulator。主机回归不能替代 iOS XCTest/UI 或书籍不可变正式验收。
- 每个验证阶段只允许一次构建尝试。失败后先阅读并定位错误；不得对同一命令自动重试、循环构建或通过更换 DerivedData 连续重跑。
- 只有用户在当前任务中明确同意模拟器/UI 验收后，才允许运行模拟器。当前改动无法用 generic build 验收时，代理必须先说明原因并征得用户同意。执行前必须告知用户，并显式设置 `JERREADER_ALLOW_SIMULATOR=1` 和唯一的 `JERREADER_SIMULATOR_DESTINATION`。
- 模拟器单元测试使用 `scripts/safe_xcodebuild.sh test`，默认排除 UI 测试、禁用并行测试且最多一个 destination。UI 测试只能通过 `scripts/safe_xcodebuild.sh ui-test` 单独运行。不得为了构建或单元测试主动打开 Simulator GUI。
- 任何 iOS 构建开始前必须验证当前仓库和 `Jerreader.xcodeproj/project.pbxproj`；目标文件不存在、Xcode Developer 目录无效或监测数据缺失时停止。启动前已有构建、负载偏高或资源暂不稳定时进入等待队列，不抢占其他项目。
- 用户于 2026-10-05 取代原固定 50 中止规则：**150 为排队门槛**，1 分钟系统负载超过 150 时不启动下一任务；回落至 150 及以下且其他条件连续稳定 30 秒后串行执行。运行中的任务不因负载单独超过门槛而被终止；内存、温控、磁盘、显示异常和实际构建失败保护仍生效。模拟器阶段间更严格的稳定等待及明确授权要求不变。
- iOS、主机回归和 Android 打包共用主机排队锁。Android 构建从仓库根目录使用 `scripts/safe_android_build.sh`，固定单 worker、禁用并行与常驻 daemon；已有打包脚本也走此入口。排队默认每个等待点最多 1 小时，超时不启动该阶段，可用 `JERREADER_QUEUE_TIMEOUT_SECONDS` 在 1–43200 秒内调整；等待不是失败重试。
- 外部 Gradle daemon 只有在日志可确认最后状态为空闲时不阻挡；活跃或无法确认的进程继续等待。不得为抢占队列擅自停止其他项目。共享入口只能约束接入它的任务；运行中若外部又启动竞争构建，仍中止本次任务，不自动重试。
- 不得并行执行 iOS 构建、Android/Gradle 构建、模拟器测试、多个 DerivedData 构建或多代理构建。长时间构建中也不得启动第二个构建。
- 不得执行 `simctl erase all`、`simctl delete all`、针对所有设备的 shutdown，也不得终止 `WindowServer`。如再次出现屏幕卡顿、黑屏或显示延迟，立即停止当前验证，不得继续重试。
- 保护脚本通过 `scripts/safe_ios_validation.py` 统一持锁、监测和清理；不得绕开入口直接运行内部编译命令。模拟器测试必须先编译一次，等待资源稳定后只启动指定设备，再执行 `test-without-building`。安全中止不能作为抬高阈值或自动重试的理由。细节见 `docs/IOS_SAFE_TESTING.md`。
