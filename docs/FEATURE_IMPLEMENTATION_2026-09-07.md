# 五项学习功能实施记录

后续内置词典已扩充至 27,983 个日语词头；9 月 8–9 日的构建、UI 复验和当前验收边界见 [内置中日词典与 UI 复验](DICTIONARY_UI_2026-09-08.md)。以下保留 9 月 7 日阶段记录。

用户已批准依次开发：生词复习 → 原句练习卡 → 语法知识库 → 阅读成长报告 → 离线学习包。两端代码已接入。此前完成 iOS generic 构建及 Android APK、101 项单元测试和 lint；这些是上一轮结果。本轮按用户要求调整为中日优先并校验 UI，新增记录见 `docs/LEARNING_UI_PARITY_2026-09-07.md`。

## 功能范围

1. 生词复习：收藏词自动进入队列，每组最多 10 个；先看语境再揭示释义。认识按 1/3/7/14/30/60 天递增，模糊退一级并隔一天，不认识隔 10 分钟重学。立即保存评分，支持暂停、重置；最多保留每词 1000 次评分。
2. 原句练习卡：从译文收藏指定挖空词或短语，匹配所有完全相同的文字；题面可编辑，答案和译文默认隐藏。不请求 AI。
3. 语法知识库：主动保存已有解析，编辑标题、解释和标签，全文搜索，有可用位置时返回原书。书籍缺失时仍保留已保存内容，不自动合并相似语法。
4. 阅读成长报告：按本机日历日期分割前台阅读时段，跨午夜和夏令时采用实际秒数；单次最多 6 小时，每书最多保留 730 个日期。按周/书籍展示天数、时长、书内查询次数、每 10 分钟查询密度和复习正确率。查询包含发起的书内翻译、查词及重试；无时长或评分显示暂无数据。关闭开关后不再采集并隐藏报告，原有历史保留。旧版累计分钟不伪造每日历史。
5. 离线学习包：优先内置 128 个项目自编中日基础词条，两端打包同一 TSV，支持日语词形候选、假名精确查词和中文别名反查；未收录回退现有服务。基础词条覆盖有限，不是完整日语词典。英语→中文 ECDICT 保留为可选扩展，用户主动下载。真实包 65,933,428 字节，770,611 条有效输入记录（索引按规范化词条去重）。流式解析、SQLite 索引、完整文件哈希和条目数量校验；临时索引验证成功后原子替换旧包，支持取消、重试、删除。安装需预留 300 MB。词典可重新下载，不进入书架备份。

## 数据与兼容

- SwiftData：保留发行版 V1 的不可变 schema 属性快照，V2 使用轻量迁移。WordLookupRecord 新增可选 reviewData，TranslationFavoriteRecord 新增可选 practiceData，BookRecord 新增可选 activityData；新增 GrammarNoteRecord。
- Android Room：版本 5→6（复习）→7（练习卡）→8（语法）→9（每日阅读统计），迁移均为加字段或加表。
- 新学习字段进入学习备份，统计进入阅读范围；旧 JSON 缺字段时使用新词/无练习/无日历史默认值。恢复时既有记录仍按原合并策略保留；iOS 复习答案及语法来源随书籍 UUID 重映射。
- 统计只写应用数据库；不更改出版物字节、排版或文件日期。新增原句跳转沿用 Readium 打开入口。

## 词典来源

中日基础词条：`shared/dictionaries/ja-zh-core.tsv`，项目自编，v1，128 条，6,755 字节。Android assets 与 iOS Bundle 引用同一个文件；中文反查仅匹配人工列出的别名，不对释义做模糊反向搜索，也不将整句当词条。

来源：[ECDICT](https://github.com/skywind3000/ECDICT)，固定提交 `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`。项目当前标注 [MIT 许可](https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE)，许可全文随应用代码和 `docs/licenses/ECDICT-MIT.txt` 保留。词库释义不代表模型生成或语言水平评定。

SHA-256：`1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`。

## 前一轮验证记录（新增检查另见 UI 校验记录）

- 阶段 1：Android shared + app unit tests，`.build/features-stage1-android.log`，通过。
- 阶段 3：包含原句卡、语法收藏与备份编解码的 Android tests，`.build/features-stage3-android.log`，通过。
- Swift Foundation 主机逻辑：`scripts/test_learning_features.swift`，固定间隔、原句遮盖、编辑卡持久化、跨午夜和夏令时，通过。
- Swift SQLite 主机检查：`scripts/test_offline_dictionary.swift`，本地 Mock 下载/损坏包保留旧索引/删除/带引号 CSV 块边界，通过。另用已下载的真实 ECDICT 文件完成全包哈希、770,611 条输入索引与查询检查，通过；测试套件本身不访问网络。
- 最终 iOS generic Debug arm64 无签名构建：通过，日志 `.build/features-fixed-ios.log`。首次检查发现 Swift 6 对 SwiftData 非 Sendable SDK 类型的声明报错，修复为与原工程一致的 `@preconcurrency import` 后进行修正验证。
- 最终 Android APK / tests / lint：通过，日志 `.build/features-verified-android.log`。共享模块 77 项、App 24 项，共 101 项单元测试，0 失败、0 跳过；lint 0 项问题。包括内容与修改时间的既有 PublicationIntegrity 主机测试；不等于真实 ReaderActivity 设备验收。前次打包时 D8 发现 127 个带 ` 2.class` 名称的生成副本；已仅隔离报错编译目录内、具有正常同名 class 对应项的副本到 `.build/quarantined-class-copies`，未删除源代码。编译中还修正了未提供的日志库引用，继续使用 Android 平台日志。
- SwiftData 主机 schema 升级检查：按发行提交 `5104606` 的原始顶层模型属性创建 V1 磁盘库，再使用当前冻结 V1 与 V2 迁移计划升级，词条收藏字段保留、新复习字段为空，通过。这是 macOS 数据层主机检查，不代替 iOS App 迁移测试。
- Android SQLite 迁移主机检查：`python3 scripts/test_room_learning_migration.py` 实际执行 v5→v9 迁移 SQL，验证与 Room v9 导出字段一致、所有旧表样本行保留、新学习字段为空，通过。这不代替 Room 设备迁移测试。
- 前一轮未运行 iOS Simulator / 实际设备 / UI；后续用户明确要求 UI 校验，已尝试指定的单个模拟器，但因系统负载异常中止。出版物内容＋修改时间正式回归仍待安全设备环境，不能用主机逻辑检查代替。

版本仍为 1.3.5（56），未发布新发行版。最新 Android Debug APK 已生成：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`，149,527,363 字节，仅使用开发调试签名；最新验证结果见 [中日学习与 UI 校验](LEARNING_UI_PARITY_2026-09-07.md)。iOS 仅完成无签名 generic 构建，未打包可分发 IPA。

可重复运行的离线逻辑检查：`scripts/test_learning_features.swift`、`scripts/test_offline_dictionary.swift`、`scripts/test_room_learning_migration.py`。Swift 脚本需用当前 Xcode 的主机 Swift 编译器，与对应的生产 Foundation/SQLite 源文件共同编译；检查须与平台构建串行运行。真实 ECDICT 包验证为独立手工检查，文件预先下载，测试执行不访问外网。
