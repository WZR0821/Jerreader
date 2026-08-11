package com.jerreader.unified.design

/**
 * The words the user reads, for every place both platforms show the same thing.
 *
 * Before this existed each label was a string literal on each side, and they
 * drifted exactly the way two copies always do: the shelf was 「书架」 on iOS
 * and 「书库」 on Android for several releases, the same card said 「继续阅读 /
 * 上次打开的书」 in one app and 「最近查看 / 继续上次阅读」 in the other, and one
 * delete confirmation offered 「删除本地副本」 while the other offered 「删除」.
 * None of it was a decision anybody made; it was two literals nobody diffed.
 *
 * Compose reads these directly. Swift reads the same constants through
 * `JerreaderCore.framework` (`JerreaderCopy.shared.libraryTitle`). A label that
 * has to differ per platform does not belong here — put it in the platform's own
 * source and say why.
 */
object JerreaderCopy {

    // MARK: 主导航

    val libraryTab: String = "书架"
    val learningTab: String = "学习"
    val settingsTab: String = "设置"

    // MARK: 书架

    /** 页面大标题。曾经 iOS 是「书架」、Android 是「书库」。 */
    val libraryTitle: String = "书架"
    val librarySearchPrompt: String = "搜索书名、作者、系列、文件夹或标签"

    val libraryRecentSectionTitle: String = "继续阅读"
    val libraryRecentSectionDetail: String = "上次打开的书"
    val libraryFolderSectionTitle: String = "文件夹"
    val libraryAllBooksSectionTitle: String = "所有书籍"
    val libraryAllBooksFilter: String = "全部书籍"

    val libraryOverviewTitle: String = "阅读统计"
    val libraryOverviewIdleDetail: String = "从一本喜欢的书开始"
    val libraryOverviewActiveDetail: String = "只记录本机阅读进度"
    val libraryOverviewTotalLabel: String = "累计阅读"
    val libraryOverviewStartedLabel: String = "已开始"
    val libraryOverviewAverageLabel: String = "平均进度"

    val libraryEmptyTitle: String = "把下一本书放进来"
    val libraryEmptyMessage: String = "导入 EPUB、PDF、DOCX 或 TXT。书籍只保存在这台设备上。"
    val libraryImportAction: String = "导入书籍"
    val libraryRestoreAction: String = "从备份恢复"
    val libraryRestoreHint: String =
        "装过 Jerreader 的话，选中以前的备份文件即可恢复书架、进度和备份计划。"
    val libraryPrivacyNote: String = "本机保存，不会上传"

    val libraryNoMatchTitle: String = "没有匹配的书籍"
    val libraryNoMatchMessage: String = "请清除搜索文字或切换文件夹。"

    val libraryBatchAction: String = "批量整理书籍"
    val libraryMoreActions: String = "书架更多操作"
    val librarySortLabel: String = "排序方式"
    val librarySortRecent: String = "最近"
    val librarySortTitle: String = "书名"
    val librarySortAuthor: String = "作者"

    val bookManageAction: String = "编辑书籍信息"
    val bookChangeCoverAction: String = "更换封面"
    val bookDeleteAction: String = "删除本地副本"
    /** 书籍信息编辑页/对话框自己的标题（入口叫「编辑书籍信息」，页面叫「管理书籍」）。 */
    val bookEditorTitle: String = "管理书籍"

    /**
     * Names the book. iOS asked 「删除这本电子书？」 and Android asked
     * 「删除《书名》？」; the named form is the one worth keeping, because a
     * confirmation that cannot be wrong about which book it means is the whole
     * point of asking.
     */
    fun bookDeleteConfirmTitle(bookTitle: String): String = "删除《$bookTitle》？"

    /**
     * Deliberately says 「原始导入文件」 rather than iOS's old 「“文件”App 中的原文件」:
     * the reassurance has to be true on a phone that has no 「文件」App.
     */
    val bookDeleteConfirmMessage: String =
        "将同时删除书架记录、本地阅读副本和封面。此操作不会影响原始导入文件。"
    val unknownAuthor: String = "未知作者"

    /** 「3 个」「12 本」这类计数后缀。 */
    fun folderCountDetail(count: Int): String = "$count 个"

    fun bookCount(count: Int): String = "$count 本"

    // MARK: 学习

    val learningTitle: String = "学习"
    val learningTranslateSection: String = "翻译"
    val learningReviewSection: String = "复习"
    val learningVocabularySection: String = "生词本"
    val learningHistorySection: String = "历史"

    // MARK: 设置

    val settingsTitle: String = "设置"

    // MARK: 通用

    val cancel: String = "取消"
    val save: String = "保存"

    /**
     * Named `deleteAction` rather than `delete`: `delete` is a C++ keyword, so
     * Kotlin/Native mangles it on the way into the Objective-C header and Swift
     * cannot see it under the name it was written with.
     */
    val deleteAction: String = "删除"

    /**
     * The one relative-time policy for the whole app.
     *
     * Both sides had grown their own: iOS bucketed to minute/hour/day so static
     * metadata would stop behaving like a countdown, Android had a near-copy in
     * Compose. Same buckets, two implementations, and nothing keeping them
     * equal.
     *
     * @param elapsedSeconds seconds since the moment being described; negative
     *   values are treated as zero, because a clock that ran backwards should
     *   read 「刚刚」 rather than a negative age.
     */
    fun relativeTime(elapsedSeconds: Long): String {
        val elapsed = if (elapsedSeconds < 0) 0L else elapsedSeconds
        return when {
            elapsed < 60 -> "刚刚"
            elapsed < 3_600 -> "${maxOf(elapsed / 60, 1)} 分钟前"
            elapsed < 86_400 -> "${maxOf(elapsed / 3_600, 1)} 小时前"
            else -> "${maxOf(elapsed / 86_400, 1)} 天前"
        }
    }

    /**
     * 累计阅读时长。
     *
     * This is iOS's formatter, not Android's. Android used to say 「1 小时」 for
     * anything from an hour to an hour and fifty-nine minutes, and 「0 分钟」 for
     * a session that had genuinely happened but lasted under sixty seconds — so
     * the same shelf reported different totals on the two phones.
     */
    fun readingDuration(totalSeconds: Double): String {
        val totalMinutes = maxOf((totalSeconds / 60).toInt(), 0)
        if (totalMinutes < 1) return if (totalSeconds > 0) "<1 分钟" else "0 分钟"
        if (totalMinutes < 60) return "$totalMinutes 分钟"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) "$hours 小时" else "${hours}小时${minutes}分"
    }

    /** 「12 本 · 最近」这类区块副标题。 */
    fun bookCountDetail(count: Int, sortTitle: String): String = "$count 本 · $sortTitle"
}
