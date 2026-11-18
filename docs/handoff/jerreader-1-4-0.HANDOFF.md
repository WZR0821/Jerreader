# Jerreader 1.4.0 交付记录

> 本文为 build58 历史交付；最新 build59 修复与交付请读 [当前交接](jerreader-hotfix.HANDOFF.md) 和 [验收维护记录](../HOTFIX_1.4.0_2026-10-16.md)。

更新于 2026-10-12。当前任务的构建、测试、视觉验收和安装包归档已完成。没有正在运行的本任务构建、测试、模拟器、后台目标或自动化；不需要恢复旧 session。工作区保留全部既有未提交改动，本轮未提交 Git 或清理用户文件。

## 本轮目标与完成范围

- iOS / Android 均为 1.4.0（58），作者 Jerry Wong。
- 统一常用图标源、主导航、书架、学习与设置二级页的布局；完善练习入口、草稿保留、返回路径、删除取消与编辑表单。
- 实测修复 iOS 空白原生菜单、学习切换后键盘遮挡底栏、双端小封面文字裁切。
- 保留 Android 离线基础词典与文件处理提速，并验证 Readium 原书内容、修改时间不变。
- 双端主要页面尽量接近；系统字体与安全区、封面更换入口、阅读引擎选项、平台模型/文件授权等差异列在报告中，未声称像素或所有功能完全一致。

## 最终交付物

- IPA：dist/iOS/Jerreader-1.4.0-build58-20261012-151558-unsigned.ipa
  - 13,812,777 字节；iOS 18.0+；arm64 unsigned Release。
  - SHA-256：3506dc67b82b31e38751f71ff79b9990397e7e3eb336ad6d9e55208a8a0f24ee
- APK：dist/Android/Jerreader-Android-1.4.0-build58-20261012-151143-release.apk
  - 144,043,314 字节；Android 6.0+；既有签名 Release，非 debuggable。
  - SHA-256：ff1cad878cc90798229f0006e45b971b519285301e71762a6e15f4d9b898b8f5
- 报告与维护方案：docs/UI_PARITY_1.4.0.md
- 图集：dist/QA/1.4.0/index.html（36 对 / 78 张原图）
- 校验与来源：dist/QA/1.4.0/validation-manifest.json、SHA256SUMS.txt、provenance.json

## 实际测试证据

以下目录均在 .build/SafeValidationLogs 下：

| 阶段 | 结果 | 目录 |
| --- | --- | --- |
| Android 最终 Release | 144 单测通过；Debug / Release Lint 各 0 错误、25 条依赖版本提示 | 20261012-145836-lh0zweqd |
| Android 功能与初轮 UI | 8 功能 + 4 UI 通过，包括离线词典与 Readium bytes + mtime | 20261012-141402-fruxhnpj |
| Android 最终 UI 与 Release | 4 UI 通过，Release 同 build 覆盖安装、离线启动通过 | 20261012-150529-p_9usup4 |
| iOS 全量 XCTest | 252 通过、3 外部 EPUB/ruby 夹具项跳过、0 失败 | 20261012-145419-606znonc |
| iOS 主导航 | 该用例通过；此阶段其它用例按后续最终记录为准 | 20261012-143442-_n96cte4 |
| iOS 设置与草稿 | 通过 | 20261012-144300-bv845i7n |
| iOS 正常学习及编辑 | 通过 | 20261012-144917-hs75csn2 |
| iOS 空态 / 深色大字 | 两项补验通过，并确认截图连续稳定 | 20261012-151632-k7osevue |
| iOS unsigned Release | 构建及严格归档检查通过 | 20261012-151134-73xqj0m8 |

另外，保护脚本 63 项回归、图标生成一致性、git diff --check 均通过。iOS 五组 UI 场景使用各自最后一次通过记录，不是一次调用全部通过；不要把较早的失败阶段写成通过。

最终视觉检查发现一张深色练习截图截在切换动画中，已在 UI 测试中增加所选分区/内容断言和连续稳定帧检查，再补验空态与深色两组。最终图集已使用新图；仅测试代码变化，两个发布包不受影响。

32 MiB 文件复制、指纹与检查微基准：旧 [2176,1493,1469] ms，新 [383,374,362] ms；中位数 1493 → 374 ms，约减少 75%。不是整本 EPUB 打开速度，原始数据已归档到图集目录。

## 后续维护边界

- 真机系统翻译模型下载、实际离线句段推理、个人证书重签安装尚未验收；不能由 Mock 或 unsigned IPA 包结构校验替代。
- Android 已比较签名与 build 57 一致；实际覆盖安装是本轮 build 58 Debug → Release，不是完整旧数据库迁移验收。
- iOS 的三个外部真实 EPUB/ruby 测试需要另备合法夹具。本轮内置夹具的只读书籍回归通过。
- 优先保持图标 JSON 唯一源、同页同状态双端截图、草稿/取消/返回回归。导入或阅读生命周期变化必须再跑 bytes + mtime。
- 系统栏、字体、部分帮助说明与阅读引擎可配置项仍有平台差异；下一轮若继续精细对齐，逐项扩充对应截图，不改备份数据策略来匹配外观。

## 构建规则与当前授权记录

本轮用户明确允许忽略温控、负载及外部占用，不停止、不消息打扰其他项目。本轮入口按命令设置 JERREADER_SESSION_ALLOW_THERMAL_THROTTLING、JERREADER_SESSION_ALLOW_HIGH_LOAD、JERREADER_SESSION_ALLOW_EXTERNAL_ACTIVITY 为 1；它们默认关闭，没有持久化到启动环境。仍保留本任务共享锁、单 job / worker、严格串行、内存/磁盘/显示故障保护及仅清理自有设备。

以后任务先读 AGENTS.md、Jerreader_PRD_v1.0.md 和 docs/ARCHITECTURE.md，再按当次用户授权执行。iOS 通过 scripts/safe_xcodebuild.sh，Android 通过 scripts/safe_android_build.sh；不直接调用底层编译器，也不要重复运行本次已结束的阶段。

## 本地归档辅助脚本

- scripts/package_unsigned_ipa.py：检查成功的 generic Release 日志，保留执行权限，校验 arm64 / unsigned / ZIP / 逐文件哈希。
- .build/visual-qa-20261012/collect.py：最终输入是安卓 150529-p_9usup4 和 iOS 的 ios-second-all、ios-settings-final、ios-learning-final、ios-stability-final；自动排除被最终场景替代的中间截图。
- .build/visual-qa-20261012/archive_android.py：已完成 APK 签名、版本、CRC、归档校验。
- .build/visual-qa-20261012/finalize_manifest.py：已完成交付清单生成。
