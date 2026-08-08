# Jerreader Android M0

## 目标

M0 只验证“Kotlin Multiplatform + Compose Multiplatform + 双平台原生 Readium”的工程边界，不进行现有 iOS UI、SwiftData 或 Readium Swift 迁移。

M0 交付：

- 产出使用 debug 证书签名、可直接侧载安装的 APK；该包不用于应用商店发布。
- Android App 与 KMP/CMP `shared` 模块可独立构建。
- Android 可通过系统文件选择器导入 EPUB/PDF 的私有副本。
- Android 使用 Readium Kotlin 打开 EPUB，PDF 使用 Readium PDFium 适配器做能力验证。
- 导入副本在打开前设为只读，阅读前后校验 SHA-256 与修改时间。
- 在纯 AOSP 模拟器上用合成 EPUB 运行 Readium 打开、关闭回归，验证字节和修改时间不变。
- 共享模块定义平台无关的翻译服务协议、Mock 和一个低风险共享页面。
- 测试不使用外网、真实 API Key 或受版权保护的书籍。

## 中国大陆分发约束

- 核心阅读、导入、数据与备份不依赖 Google Play Services。
- OCR 后续优先评估随应用打包的中文、日文和拉丁文字模型，不把 GMS 动态下载作为唯一路径。
- 不把 ML Kit 动态翻译模型作为大陆版唯一默认服务。直接 AI API、HTTPS 代理与 Mock 继续通过共享 `TranslationService` 隔离。
- 所有密钥在 Android 只能保存在 Keystore 保护的本地存储中；M0 不接收、不保存真实密钥。
- 不新增账号、云同步、支付、DRM 绕过或整本翻译。

## 架构边界

`shared/commonMain` 包含领域模型、服务协议、用例状态和可共享 Compose UI。

`androidApp` 包含 Android 文件权限、只读副本、Readium Kotlin/PDFium、后续 Room/Keystore/WorkManager 适配器。

现有 `Jerreader` iOS target 保持不变。待 Apple Silicon + 完整 Xcode 环境可用时，再将 `shared` 导出的 `JerreaderShared` framework 嵌入 SwiftUI，并保留 Readium Swift 原生阅读器。

现有 iOS 工程获批前不修改也不替换；未来 iOS 交付格式仍为未签名 IPA。

## M0 不属于完成的功能

- 书架持久化、完整导入去重与封面提取。
- 阅读设置、Locator 保存、目录、搜索、书签和批注。
- 点句、长按选区、ruby 过滤、翻译卡片与 PDF OCR。
- 真实翻译、查词、备份、导出和商店发布。
