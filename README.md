# 读鼠 Jerreader

> **本 APP 通过 GPT-5.6 sol 模型开发。**

Jerreader 是一款面向中、英、日文阅读与学习的 iOS / Android 电子书阅读器。当前版本为 **1.5.1 (60)**。

## 主要功能

- 支持 EPUB、PDF、DOCX 和 TXT；EPUB / PDF 由 Readium 打开，DOCX / TXT 在本机转换。
- 支持中英日互译、短按查词、长按选区、词典详情和 AI 句子分析。
- 内置日语 JMdict 离线词典，并支持中文维基词典与系统词典后备。
- 生词本、翻译收藏、历史记录、间隔复习与 CSV / Markdown / Anki TSV 导出。
- 支持阅读进度、书签、搜索、划线批注、竖排、注音、阅读主题与本地备份。
- 翻译、解释和高亮只显示在界面覆盖层中，不修改导入的原书。

## 下载

安装包请前往 [GitHub Releases](https://github.com/WZR0821/Jerreader/releases)。

- Android：下载 APK 后安装。
- iOS：提供未签名 IPA，需由用户自行签名后安装。

## 翻译与隐私

Apple 系统翻译不需要 API Key。也可以在 App 内配置 GPT、Claude、Kimi、DeepSeek、Gemini 或 OpenAI 兼容服务。API Key 保存在设备安全存储中，不包含在源码、安装包或备份里。

## 项目结构

| 目录 | 用途 |
|---|---|
| `core/` | 两端共用的阅读、选区、词典、学习与备份策略 |
| `ui/` | Android 使用的 Compose Multiplatform 界面组件 |
| `androidApp/` | Android 宿主、Room、Readium 与 ML Kit |
| `iosApp/` | SwiftUI iOS App 与 Readium Swift Toolkit |
| `version.properties` | Android / iOS 共用的版本号来源 |

## 开发

需要 JDK 17 和完整 Xcode。常用验证命令：

```bash
./gradlew :core:testAndroidHostTest :androidApp:testDebugUnitTest
scripts/safe_xcodebuild.sh build
```

统一出包使用 `./scripts/release.sh`，版本号从 `version.properties` 读取。

更多说明：[1.5.1 发布说明](docs/RELEASE_NOTES_1.5.1.md) · [维护手册](docs/MAINTENANCE.md) · [双端差异](docs/PARITY.md) · [词典来源与许可](docs/THIRD_PARTY_DICTIONARIES.md)
