# Jerreader Android M1 + M2

## M1：导入与书架

M1 使用 Android Storage Access Framework 选择 EPUB，随后立即复制到 App 私有目录。不保留对源文件的长期权限，不在原地打开或写回源文件。

导入流程：

1. 复制为 staging 副本，计算 SHA-256 和修改时间。
2. 使用 Readium Kotlin 验证 EPUB，拒绝损坏、非 EPUB 或受 DRM 限制的出版物。
3. 读取标题、作者、语言和可用封面，按 SHA-256 防止重复导入。
4. 将 EPUB 以只读副本保存到 App 私有目录，元数据和阅读状态保存到 Room。
5. 书架支持打开、封面或文字占位、最近阅读排序和带确认的删除。

## M2：EPUB 基础阅读

M2 由 `ReaderActivity` 承载 Readium `EpubNavigatorFragment`，Compose Multiplatform 提供工具栏、目录和阅读设置界面。

- 打开书籍时从 Room 取得完整 Locator JSON，作为 Readium `initialLocator`。
- 位置变化后节流保存；阅读器进入后台或销毁时再刷新一次。
- 目录保留层级并通过 Readium `go(Link)` 跳转。
- 字号与浅色、护眼、深色主题通过 `EpubPreferences` 应用和序列化。
- Readium 会话只读 EPUB；目录跳转、改设置、退出和重新打开都不能改变 EPUB 字节或修改时间。

## 已自动验收

API 36 纯 AOSP 模拟器使用本地合成 EPUB，测试不访问外网，覆盖：

- Readium 直接打开/关闭和完整 `ReaderActivity` 生命周期不改文件。
- 导入元数据、重复检测、Room 重开持久化与删除清理。
- 目录跳到第二章，保存字号/深色主题，关闭后用完整 Locator 恢复到第二章。
- 源 EPUB 与 App 内 EPUB 副本的 SHA-256 和修改时间不变。

M1/M2 没有引入登录、云同步、支付、数据迁移、DRM 绕过、整本翻译或新的 PDF 产品功能。既有 iOS 源码、Xcode target 和产物仍然冻结。
