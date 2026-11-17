package com.jerreader.android.learning

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LearningUiParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fontScaleManaged: Boolean
        get() = InstrumentationRegistry.getArguments().getString("jerreaderFontScaleManaged") == "true"
    @org.junit.After fun resetFontScale() { if (!fontScaleManaged) setSystemFontScale(false) }
    private val titles = listOf("生词复习", "原句练习卡", "语法知识库", "阅读成长报告")
    private fun find(text: String, last: Boolean = false): AccessibilityNodeInfo? {
        // A new LazyColumn item can render before the accessibility cache is
        // invalidated. Always query the current tree when looking for results.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun search(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true || node.contentDescription?.toString() == text) matches += node
            for (i in 0 until node.childCount) search(node.getChild(i))
        }
        search(instrumentation.uiAutomation.rootInActiveWindow)
        return if (last) matches.lastOrNull() else matches.firstOrNull()
    }
    private fun waitFor(text: String, last: Boolean = false): AccessibilityNodeInfo {
        // Cold software-rendered devices can still be building the local dictionary.
        // Wait for the requested UI state, below the production lookup's 30 s deadline.
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            find(text, last)?.let { return it }
            SystemClock.sleep(200)
        }
        capture("android-failure")
        val visible = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let { visible += it }
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        error("Missing visible UI: $text; visible: ${visible.joinToString(" | ")}")
    }
    private fun tap(text: String, last: Boolean = false) {
        var node: AccessibilityNodeInfo? = waitFor(text, last)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot tap $text" }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }
    private fun back() {
        val now = SystemClock.uptimeMillis()
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
        settleScreen()
    }
    private fun longPress(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isLongClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) { "Cannot long press $text" }
        settleScreen()
    }
    private fun waitForFolderPicker() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val provider = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (provider.contains("documentsui", ignoreCase = true)) return
            SystemClock.sleep(200)
        }
        capture("android-folder-picker-failure")
        error("The system folder picker did not open")
    }
    private fun scrollForward() {
        val bounds = android.graphics.Rect()
        checkNotNull(instrumentation.uiAutomation.rootInActiveWindow).getBoundsInScreen(bounds)
        val x = bounds.exactCenterX()
        val start = bounds.top + bounds.height() * .70f
        val end = bounds.top + bounds.height() * .45f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        repeat(12) { step ->
            SystemClock.sleep(25)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * (step + 1) / 12)
        }
        send(MotionEvent.ACTION_UP, end)
        settleScreen()
    }
    private fun reveal(text: String) {
        // Let a newly inserted LazyColumn result reach accessibility and the
        // renderer before scrolling. Whole-page actions can skip a short card.
        settleScreen()
        repeat(12) {
            if (find(text)?.isVisibleToUser == true) return
            scrollForward()
        }
        waitFor(text)
    }
    /**
     * A one-screen page shows [last] without scrolling, and a drag does not
     * move [anchor]: the page is exactly as tall as the room it has.
     */
    private fun assertOneScreen(anchor: String, last: String? = null) {
        val before = android.graphics.Rect().also { waitFor(anchor).getBoundsInScreen(it) }
        last?.let { check(waitFor(it).isVisibleToUser) { "$it must show without scrolling" } }
        scrollForward()
        val after = android.graphics.Rect().also { waitFor(anchor).getBoundsInScreen(it) }
        assertEquals("$anchor moved: the page must fit one screen without scrolling", before, after)
    }
    private fun settleScreen() {
        instrumentation.waitForIdleSync()
        // Accessibility events can precede the software renderer's next frame.
        // Wait for both asynchronous Room data and the rendered screen to settle.
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        SystemClock.sleep(750)
    }
    private fun capture(name: String) {
        settleScreen()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val nodes = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text != null || node.contentDescription != null) {
                nodes += "text=${node.text}; description=${node.contentDescription}; visible=${node.isVisibleToUser}; selected=${node.isSelected}; clickable=${node.isClickable}"
            }
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        File(directory, "$name.txt").writeText(nodes.joinToString("\n"))
    }
    private fun setSystemFontScale(large: Boolean) {
        if (fontScaleManaged) {
            assertEquals("The supervisor must prepare actual system font scale before attaching UiAutomation",
                if (large) 1.5f else 1f, instrumentation.targetContext.resources.configuration.fontScale, 0.01f)
            return
        }
        // Dialog windows derive density from system configuration, not the content-only LocalDensity override.
        val descriptor = instrumentation.uiAutomation.executeShellCommand("settings put system font_scale " + if (large) "1.5" else "1.0")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        instrumentation.waitForIdleSync()
    }
    private fun launch(empty: Boolean = false, large: Boolean = false): ActivityScenario<LearningUiFixtureActivity> {
        setSystemFontScale(large)
        return ActivityScenario.launch<LearningUiFixtureActivity>(Intent(instrumentation.targetContext, LearningUiFixtureActivity::class.java)
            .putExtra("empty", empty).putExtra("large", large)).also { scenario ->
            scenario.onActivity { assertEquals(if (large) 1.5f else 1f, it.resources.configuration.fontScale, 0.01f) }
        }
    }

    private fun setInput(value: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || node.isEditable) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        var input: AccessibilityNodeInfo? = null
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (input == null && SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            instrumentation.waitForIdleSync()
            input = editable(instrumentation.uiAutomation.rootInActiveWindow)
            if (input == null) SystemClock.sleep(200)
        }
        if (input == null) capture("android-input-not-ready")
        val field = checkNotNull(input) { "The returned page did not expose its editable input" }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        SystemClock.sleep(400)
        waitFor(value)
    }

    private fun launchContextual(large: Boolean = false, failSave: Boolean = false): ActivityScenario<LearningUiFixtureActivity> {
        setSystemFontScale(large)
        return ActivityScenario.launch<LearningUiFixtureActivity>(Intent(instrumentation.targetContext, LearningUiFixtureActivity::class.java)
            .putExtra("contextual", true).putExtra("large", large).putExtra("failSave", failSave)).also { scenario ->
            scenario.onActivity { assertEquals(if (large) 1.5f else 1f, it.resources.configuration.fontScale, 0.01f) }
        }
    }

    @Test fun primaryTabsAndLocalModels() {
        setSystemFontScale(false)
        val intent = Intent(instrumentation.targetContext, com.jerreader.android.MainActivity::class.java)
        ActivityScenario.launch<com.jerreader.android.MainActivity>(intent).use {
            waitFor("书架"); capture("android-library")
            tap("书架更多操作"); waitFor("排序方式"); waitFor("批量整理书籍")
            tap("最近")
            // As on iOS, "+" first asks for a format; the system picker then
            // accepts only that type and greys the other files out.
            tap("导入电子书"); waitFor("先选择文件格式"); waitFor("选择文档"); capture("android-import-options")
            tap("只选 PDF 文件"); reveal("选择 PDF 文件"); capture("android-import-options-pdf")
            tap("选择 PDF 文件"); waitForFolderPicker(); capture("android-import-picker-pdf"); back()
            waitFor("书架"); check(find("先选择文件格式") == null)
            tap("学习"); waitFor("今日练习"); capture("android-main-practice")
            tap("翻译"); waitFor("词语"); capture("android-main-learning"); assertOneScreen("服务")
            setInput("practice draft")
            tap("设置"); waitFor("默认阅读排版"); capture("android-settings"); assertOneScreen("默认阅读排版", last = "Jerry Wong")
            tap("界面主题"); waitFor("海蓝"); waitFor("学习"); capture("android-settings-theme")
            tap("森林"); tap("海蓝"); back()
            tap("默认阅读排版"); waitFor("字体与字号"); waitFor("学习"); capture("android-settings-reader")
            reveal("日文原版排版"); capture("android-settings-reader-lower"); back()
            tap("数据与隐私"); waitFor("写出副本"); waitFor("学习"); capture("android-settings-privacy"); back()
            tap("备份与恢复"); waitFor("备份中心"); waitFor("立即备份"); waitFor("学习"); capture("android-settings-backup")
            // Fresh installations should choose a visible external folder,
            // rather than silently writing a backup into the app sandbox.
            tap("立即备份"); waitForFolderPicker(); capture("android-backup-folder-picker"); back()
            waitFor("备份中心"); reveal("从备份文件夹恢复"); tap("从备份文件夹恢复")
            waitForFolderPicker(); back(); waitFor("备份中心"); back()
            tap("操作指南"); waitFor("快速开始"); waitFor("学习"); capture("android-settings-guide"); back()
            tap("翻译与 AI"); waitFor("翻译服务"); capture("android-settings-translation"); reveal("离线资源"); tap("离线资源")
            waitFor("英语扩展词典"); waitFor("中日句段翻译"); waitFor("下载"); waitFor("卸载")
            check(find("来源与许可") == null)
            check(find("仅使用 Wi-Fi 下载") == null)
            check(find("查词") == null)
            capture("android-offline-resources")
            back(); back(); reveal("Jerry Wong"); capture("android-about")
            tap("学习"); waitFor("practice draft")
            tap("生词本"); tap("翻译"); waitFor("practice draft"); capture("android-draft-restored")
        }
    }

    @Test fun contextualLookupActionsAndAI() {
        launchContextual().use {
            waitFor("吃"); capture("android-contextual-result")
            reveal("加入生词本"); tap("加入生词本"); waitFor("取消收藏")
            tap("取消收藏"); waitFor("加入生词本")
            tap("复制词条"); waitFor("词条已复制。")
            reveal("开始解析"); tap("开始解析"); waitFor("重新解析")
            reveal("展开 AI 语境建议"); tap("展开 AI 语境建议")
            reveal("这是离线测试的语境建议：原句描述已经发生的动作。")
            capture("android-contextual-ai")
        }
    }
    @Test fun contextualInputEditingClearsPreviousResult() {
        launchContextual().use {
            waitFor("吃")
            setInput("こうせい")
            assertNull(find("词典释义 · 中文"))
            tap("查询")
            waitFor("选择词条 / 释义")
            assertNull(find("词典释义 · 中文"))
            reveal("校正（こうせい）"); tap("校正（こうせい）")
            reveal("词典释义 · 中文")
            assertNull(find("选择词条 / 释义"))
            reveal("更换词条 / 释义"); tap("更换词条 / 释义")
            waitFor("选择词条 / 释义"); assertNull(find("词典释义 · 中文"))
            reveal("校正（こうせい）"); tap("校正（こうせい）")
            reveal("词典释义 · 中文"); capture("android-contextual-selected-sense")
        }
    }
    // Two tests rather than one: the device run sets the system font before a
    // test starts, so each test gets a single font size.
    @Test fun contextualLargeFont() {
        launchContextual(large = true).use {
            waitFor("语境查词"); reveal("吃"); capture("android-contextual-large-result")
            reveal("加入生词本"); capture("android-contextual-large-actions")
            // At this font the button sits at the bottom edge, and the
            // confirmation line added above the card pushes it below the
            // screen, so scroll to it.
            tap("加入生词本"); reveal("取消收藏")
        }
    }
    @Test fun contextualPersistenceFailure() {
        launchContextual(failSave = true).use {
            waitFor("历史保存失败，释义仍可查看；可点击收藏重试。")
            waitFor("吃"); capture("android-contextual-save-failure")
        }
    }
    @Test fun aiModelsProvidersAndModels() {
        // The AI 大模型 pages on fixture settings: requests are fakes, the key
        // lives in memory, and nothing leaves the device.
        setSystemFontScale(false)
        ActivityScenario.launch<LearningUiFixtureActivity>(Intent(instrumentation.targetContext, LearningUiFixtureActivity::class.java)
            .putExtra("aiModels", true)).use {
            waitFor("Jerreader 正在用"); waitFor("这个供应商还没有填 API Key，填好后才能翻译。")
            capture("android-ai-models")
            tap("添加供应商"); waitFor("推荐"); capture("android-ai-add-provider")
            tap("Claude"); waitFor("claude-sonnet-5-5")
            tap("显示密钥"); setInput("sk-ui-fixture")
            reveal("测试连接"); tap("测试连接"); waitFor("连接正常，用时 0.4 秒。点“添加”保存。")
            capture("android-ai-provider-setup")
            tap("添加"); waitFor("已添加 Claude，它现在是正在用的模型。")
            tap("用它翻译"); waitFor("翻译服务已换成 AI 大模型。")
            check(find("用它翻译") == null)
            capture("android-ai-models-added")
            tap("换模型"); waitFor("claude-sonnet-5-5"); capture("android-ai-model-switcher")
            tap("claude-sonnet-5-5"); waitFor("已换成 Claude 的 claude-sonnet-5-5。")
            tap("Claude", last = true); waitFor("已填写"); capture("android-ai-provider-detail")
            tap("添加模型"); tap("获取可用模型"); tap("mock-text-latest")
            waitFor("已添加 mock-text-latest。")
            tap("mock-text-latest"); reveal("测试连接"); tap("测试连接"); waitFor("连接正常，用时 0.4 秒")
            capture("android-ai-model-detail")
            reveal("删除这个模型"); tap("删除这个模型"); tap("删除")
            waitFor("已填写")
            check(find("mock-text-latest") == null)
        }
    }

    @Test fun japaneseAndChineseLookupWorkWithoutNetwork() {
        launch(empty = true).use {
            tap("自动识别"); tap("日语"); tap("词语"); setInput("食べる"); tap("翻译", last = true)
            waitFor("吃")
            // The dictionary entry takes the spare height; 清空 and 翻译 must
            // stay in the input area all the same.
            waitFor("清空"); capture("android-japanese-offline-result")
            // Both menus name Chinese "简体中文", as on iOS, and the result card
            // repeats the target as a plain label. Set the target first, while
            // the source still reads 日语, so the first match is always the menu.
            tap("简体中文"); tap("日语", last = true); tap("日语"); tap("简体中文", last = true); setInput("阅读"); capture("android-chinese-before-query"); tap("翻译", last = true)
            waitFor("読む（よむ）"); capture("android-chinese-offline-result")
        }
    }

    @Test fun bundledDictionaryFindsNonCoreWordsAndHandlesMisses() {
        launch(empty = true).use {
            tap("词语"); tap("自动识别"); tap("日语")
            setInput("曖昧"); tap("翻译", last = true); waitFor("含糊，模糊"); waitFor("清空")
            capture("android-dictionary-japanese")
        }
    }

    @Test fun japaneseLearningWorkflow() {
        launch().use {
            waitFor("词语"); check(find("日语 → 中文") == null); check(find("中文 → 日语") == null); capture("android-learning-hub")
            tap("生词本"); waitFor("食べる"); capture("android-vocabulary")
            tap("食べる"); waitFor("词条详情"); waitFor("查询上下文"); capture("android-word-detail")
            tap("复制"); waitFor("词条内容已复制到剪贴板。"); tap("好")
            tap("已收藏"); waitFor("收藏"); tap("收藏"); waitFor("已收藏"); back()
            tap("历史"); waitFor("食べる"); capture("android-history")
            longPress("食べる"); waitFor("取消收藏"); waitFor("删除查词记录"); back()
            tap("翻译"); waitFor("词语")
            tap("书架"); waitFor("所有书籍"); waitFor("中日阅读练习"); capture("android-library-populated")
            tap("中日阅读练习的更多操作"); tap("编辑书籍信息"); waitFor("管理书籍"); waitFor("更换封面"); capture("android-book-editor")
            tap("更换封面"); waitForFolderPicker(); capture("android-cover-picker"); back()
            waitFor("管理书籍"); check(find("撤销新封面") == null); waitFor("中日阅读练习")
            tap("取消"); tap("学习")
            tap("练习与工具"); waitFor("今日练习"); capture("android-practice")
            tap("开始复习"); waitFor("今日复习"); tap("开始复习"); tap("显示释义")
            waitFor("たべる"); capture("android-review-answer"); tap("认识"); waitFor("本组复习完成"); back()
            openTool(titles[1]); capture("android-sentences")
            tap("开始练习 · 可编辑"); tap("显示答案与译文"); waitFor("每天读日语书。"); capture("android-sentence-answer"); back(); back()
            openTool(titles[2]); waitFor("〜ながら：同时进行的动作"); capture("android-grammar"); tap("编辑")
            waitFor("日语"); waitFor("音楽を聞きながら、本を読む。")
            tap("日语"); waitFor("英语"); back(); waitFor("保存语法")
            capture("android-grammar-editor"); tap("取消"); tap("删除"); waitFor("删除这条语法收藏？"); tap("取消"); waitFor("〜ながら：同时进行的动作"); back()
            openTool(titles[3]); waitFor("记录并显示成长报告"); waitFor("20.0 分钟"); capture("android-growth"); back()
            check(find("离线学习包") == null)
        }
    }
    @Test fun emptyLearningPages() { pageStates(empty = true, prefix = "android-empty") }
    @Test fun largeDarkLearningPages() { pageStates(large = true, prefix = "android-large-dark") }
    @Test fun thousandLearningRecordsRemainSearchableAndScrollable() {
        setSystemFontScale(false)
        ActivityScenario.launch<LearningUiFixtureActivity>(Intent(instrumentation.targetContext, LearningUiFixtureActivity::class.java)
            .putExtra("wordCount", 1000)).use {
            tap("生词本"); waitFor("食べる"); waitFor("日语"); capture("android-vocabulary-large-data")
            waitFor("导出生词与译文收藏")
            tap("食べる"); waitFor("词条详情"); waitFor("已收藏"); waitFor("复制"); back()
            setInput("単語1000"); waitForRecord("単語1000")
            tap("単語1000", last = true); waitFor("词条详情"); back(); waitForRecord("単語1000")
            assertNull("Returning from a detail must preserve the active search", find("食べる"))
            setInput("未收录xyz"); waitFor("没有匹配内容")
            tap("历史"); waitFor("食べる"); waitFor("日语"); capture("android-history-large-data")
            waitFor("清空查词历史")
            longPress("食べる"); waitFor("删除查词记录"); back()
            setInput("単語1000"); waitForRecord("単語1000")
            setInput(""); waitForRecord("食べる")
            val before = android.graphics.Rect().also { waitFor("搜索查词历史").getBoundsInScreen(it) }
            scrollForward()
            val after = android.graphics.Rect().also { waitFor("搜索查词历史").getBoundsInScreen(it) }
            assertEquals("Search controls must stay fixed while records scroll", before, after)
            openTool(titles[0]); waitFor("当前待复习：1000 个")
            tap("开始复习"); tap("显示释义"); tap("认识"); waitFor("本组已完成 1 个")
            capture("android-review-large-data")
        }
    }
    private fun openTool(title: String) {
        var tab: AccessibilityNodeInfo? = waitFor("练习与工具")
        var selected = false
        while (tab != null) {
            if (tab.isSelected) { selected = true; break }
            tab = tab.parent
        }
        // Android omits ACTION_CLICK on an already selected accessibility tab.
        // Returning from a tool already leaves the hub on this section.
        if (!selected) tap("练习与工具")
        val action = if (title == "生词复习") "开始复习" else title
        reveal(action); tap(action); waitFor(title)
    }

    private fun pageStates(empty: Boolean = false, large: Boolean = false, prefix: String) {
        launch(empty, large).use {
            tap("练习与工具"); waitFor("今日练习"); capture("$prefix-practice")
            tap("生词本"); capture("$prefix-vocabulary")
            tap("历史"); capture("$prefix-history")
            tap("翻译")
            titles.forEachIndexed { index, title ->
                openTool(title)
                if (index == 0) waitFor("今日复习")
                if (index == 3) waitFor("阅读天数")
                capture("$prefix-$index"); back()
            }
        }
    }

    private fun waitForRecord(text: String) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            var node = find(text, last = true)
            var isInput = false
            val exists = node != null
            while (node != null) { if (node.isEditable) isInput = true; node = node.parent }
            if (exists && !isInput) return
            SystemClock.sleep(200)
        }
        error("Search result row missing: $text")
    }
}
