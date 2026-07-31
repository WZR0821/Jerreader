# Jerreader Android 功能对照基线

基线为 iOS 1.0.0 build 39 当前已开启功能。“对齐”表示用户结果、数据和错误语义一致，不要求两端系统控件逐像素一致。

| 能力 | iOS 基线 | Android 目标 | 共享边界 | M0 | M1/M2 |
| --- | --- | --- | --- | --- | --- |
| EPUB/PDF/DOCX/TXT 导入 | FileImporter/外部打开 | Storage Access Framework | 格式、错误与指纹规则 | EPUB/PDF 技术验证 | M1 仅 EPUB：验证、元数据、封面、SHA-256 去重已完成 |
| 导入文件不变 | SHA-256 + mtime 回归 | SHA-256 + mtime 回归 | 验收夹具 | JVM + AOSP 设备回归通过 | 源文件＋App 内副本的导入/阅读/目录/偏好/退出回归通过 |
| EPUB 阅读 | Readium Swift | Readium Kotlin | Locator JSON/偏好合同 | 合成 EPUB Navigator 打开通过，真书待验 | Navigator、分层目录、字号/三主题、完整 Locator 恢复通过 |
| PDF 阅读 | Readium/PDFKit | Readium/PDFium，引擎待决策 | 领域选区与锚点 | 打开验证 | 不扩大既有 M0 技术验证，未作产品入口 |
| 竖排/ruby/点句/长按 | WebKit + UIKit 桥 | Android WebView + Readium 桥 | 断句、身份与请求状态 | 真书风险验证待做 | 未进入 M1/M2 |
| 翻译 | Apple/直接 API/代理 | 大陆可用平台实现/直接 API/代理 | `TranslationService` 及网络/缓存 | 协议 + Mock | 未进入 M1/M2，无网络请求 |
| PDF OCR | Vision | 可打包的中/日/拉丁 OCR | OCR 观测和文本重组模型 | 后续里程碑 | 未进入 M1/M2 |
| 书架/学习/设置 | SwiftUI | Compose Multiplatform | 共享 UI 与状态 | 一个验证页 | 书架网格、打开、删除确认完成；学习/完整设置未进入本里程碑 |
| 持久化 | SwiftData | 初期 Room | 仓储协议/备份格式 | 不迁移 iOS 数据 | Room 书籍、Locator 与 EPUB 偏好已完成，按要求无数据迁移 |
| 备份 | 文件夹授权 + 版本归档 | SAF 目录 + WorkManager | manifest/校验/迁移规则 | 后续里程碑 | 未进入 M1/M2 |
| 语音 | 功能开关关闭 | 保持关闭 | 功能开关 | 不开启 | 继续关闭 |

每个后续里程碑必须在本表增加验收用例和实际验证状态，不得以“已有底层代码”代替功能验收。
