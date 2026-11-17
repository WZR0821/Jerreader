# Android 构建与交付

## 环境

- macOS 或 Linux
- JDK 17（可使用本机已准备的 `.tools/jdk17/Contents/Home`）
- Python 3（打包脚本读取 Gradle 产物版本元数据）
- Android SDK Platform 37（compileSdk 37，targetSdk 仍为 36）
- Android SDK Build-Tools 36.0.0
- 可访问 Google Maven、Maven Central 和 JitPack 的首次依赖下载环境

Android 运行时不依赖 Google Play Services。JitPack 只在构建期用于 Readium PDFium 的两项原生依赖。

## 当前功能对齐版可安装 APK

执行：

```sh
scripts/package_android_parity.sh
```

当前源码版本为 1.7.0 / versionCode 69。本机 macOS 打包脚本经 `scripts/safe_android_build.sh` 进入与 iOS 共用的排队锁，采用 UTF-8 环境、单 worker、串行执行共享层测试、Android 单元测试、lint 和 APK 构建。已有构建或资源暂不稳定时等待（2026-11-13 起系统负载和温控降频只记录，不再因负载超过 150 排队）；不启动模拟器，也不自动删除构建目录中的文件。排队细节见 [安全验证](../IOS_SAFE_TESTING.md)。成功后根据 APK 元数据命名，例如：

- `dist/Android/Jerreader-Android-1.7.0-build69-debug.apk`
- `dist/Android/Jerreader-Android-1.7.0-build69-debug.apk.sha256`

该包使用 Android SDK 自动生成的 debug 证书签名，可直接侧载，但不是商店发布包。

正式交付的 Release APK 从仓库外、被 Git 忽略的 `keystore.properties` 读取签名配置（没有这个文件时 Release 不签名）。目前它指向的仍是 Android SDK 的 debug 证书，1.7.0 的 Release APK 也是这样签的，用户手机上的旧版可以直接覆盖升级。在保留上述 Debug 测试产物的同时，可通过同一串行入口额外构建 Release：

```sh
JERREADER_ANDROID_PACKAGE_VARIANT=release scripts/safe_android_build.sh
```

该选项只接受 `debug` / `release`，仅用于 Android 构建；Release 的 lint 和打包仍在同一单 worker 调用内运行。密钥配置保持在被忽略的本地文件中，不写入报告、仓库或安装包。是否实际可安装以 APK 签名校验结果为准。设置 `JERREADER_ANDROID_RELEASE_SMOKE=1` 后执行安全设备验收，会在原有 Debug 仪器化测试完成后，在同一临时设备中额外安装 Release 并检查离线启动与书架界面；不会触碰真实设备数据。

最终交付在设备阶段通过后，再运行 `scripts/package_release_apk.py --build-log <本次成功构建目录> --device-log <本次成功设备目录> --previous-apk <上一签名兼容的APK>`。脚本拒绝重复 DEX 类型、测试夹具、缺失本轮功能标记或签名身份改变的包，生成带时间戳的 Release APK、SHA-256 与 validation.json。不能仅凭 Gradle 成功或安装成功交付。1.7.0（69）的回归与正式包状态见 [1.7.0 维护记录](../RELEASE_1.7.0_2026-11-17.md)，1.4.1（60）的回归、图集与正式包见 [1.4.1 维护记录](../HOTFIX_1.4.1_2026-10-17.md)。完整设备集现为 49 项（37 项回归＋12 项界面），正式包必须与设备实际安装和启动的候选摘要一致。


## 历史 M4 点按查词阶段可安装 APK

执行：

```sh
scripts/package_android_m4_tap.sh
```

脚本会运行共享层 Android Host 测试、Android 单元测试、lint 和 APK 构建，然后生成：

- `dist/Android/Jerreader-Android-M4-TapLookup-installable.apk`
- `dist/Android/Jerreader-Android-M4-TapLookup-installable.apk.sha256`

设备/模拟器级的 Readium 点按回归需按下文单独运行，因为打包脚本不能假设构建机已连接 Android 设备。

## 历史 M2 APK

执行：

```sh
scripts/package_android_m2.sh
```

脚本会先运行共享层测试、Android 单元测试和 APK 构建，然后生成：

- `dist/Android/Jerreader-Android-M2-installable.apk`
- `dist/Android/Jerreader-Android-M2-installable.apk.sha256`

M2 包使用 Android SDK 自动生成的 debug 证书签名，可直接侧载安装，但不是商店发布包。目前的 Release APK 仍沿用这张 debug 证书（见上文）。以后要上架或换成 Jerreader 专用签名库时签名会变，已装的版本得先卸载再装，卸载会清掉 App 里的数据，换之前要先备份。签名库、密码和证书指纹不进 Git。历史 M0 脚本仅用于重现工程骨架产物。

## 设备级 Readium 回归

设备级验收单独安排，并且不得与 iOS 验证并行。先取得当前任务模拟器授权并指定已安装的单一 AVD，然后执行：

```sh
JERREADER_ALLOW_SIMULATOR=1 \
JERREADER_SIMULATOR_DESTINATION='platform=Android,serial=emulator-5580,avd=<已安装的AVD名称>' \
scripts/safe_android_build.sh test
```

当前设备套件覆盖导入与只读打开、本机词典/句段服务、学习列表、备份合并和 UI。它同时断言源 EPUB 与 App 内副本的 SHA-256、修改时间不变；服务适配器使用 Mock/Stub，不访问外网。仅复验 UI 可设置 `JERREADER_ANDROID_VALIDATION_SCOPE=ui`。历史 M1/M2/M4 夹具保留源码，但未进入当前选择的测试不能借历史记录声明当次通过。实际覆盖以设备日志为准。

## iOS 交付边界

两端源码均维护，iOS 继续使用 SwiftUI 与 Readium Swift，当前不嵌入共享 framework。iOS 构建（手动或交给 AI 编程助手都一样）只能从仓库根目录运行 `scripts/safe_xcodebuild.sh build` 或 `analyze`；模拟器测试遵守 `AGENTS.md` 的明确授权规则。iOS、Gradle 和设备测试不得并行。源码版本、历史测试记录与实际新构建产物分别记录，不能把旧 APK/IPA 当成本次验收输出。

## 本地离线验证

本机 macOS 使用统一队列执行单次验证：

```sh
scripts/safe_android_build.sh
```

入口固定单 worker、禁用并行和常驻 daemon，Kotlin 使用进程内编译，Java 最大堆 2 GiB；与 iOS/主机回归共用同一把锁；原来负载 150 的排队门槛已在 2026-11-13 取消，负载只记录。`gradle.properties` 也将直接 Gradle 调用的默认并发降为 1，但绕过安全入口的命令不会加入队列。

默认每个等待点最多 1 小时，可设置 `JERREADER_QUEUE_TIMEOUT_SECONDS`（1–43200 秒）。未知状态的外部 daemon 不会被当作空闲，也不会被自动关闭。Java 使用本机 JDK 17；测试使用 Mock，不依赖真实服务。

其他平台的构建命令与 CI 需独立配置资源监测；这里的 macOS 监测入口不能当作 Linux 健康检查。实际构建失败后先检查日志，不循环重试。历史维护验证记录见 [维护记录](../MAINTENANCE_2026-09-07.md)。
