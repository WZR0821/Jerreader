package com.jerreader.unified.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These cover the formatters rather than the constants. A constant is its own
 * test — if `libraryTitle` is wrong, both platforms are wrong together, which is
 * the point. The formatters are where the two implementations used to disagree.
 */
class JerreaderCopyTest {

    @Test
    fun `relative time uses the coarse buckets rather than a countdown`() {
        assertEquals("刚刚", JerreaderCopy.relativeTime(0))
        assertEquals("刚刚", JerreaderCopy.relativeTime(59))
        assertEquals("1 分钟前", JerreaderCopy.relativeTime(60))
        assertEquals("59 分钟前", JerreaderCopy.relativeTime(3_599))
        assertEquals("1 小时前", JerreaderCopy.relativeTime(3_600))
        assertEquals("23 小时前", JerreaderCopy.relativeTime(86_399))
        assertEquals("1 天前", JerreaderCopy.relativeTime(86_400))
        assertEquals("30 天前", JerreaderCopy.relativeTime(86_400 * 30))
    }

    @Test
    fun `a clock that ran backwards reads as just now`() {
        assertEquals("刚刚", JerreaderCopy.relativeTime(-5_000))
    }

    @Test
    fun `reading duration keeps the minutes inside an hour`() {
        // Android used to collapse everything from 60 to 119 minutes into
        // 「1 小时」, so the same history read differently on the two phones.
        assertEquals("1小时30分", JerreaderCopy.readingDuration(90 * 60.0))
        assertEquals("2 小时", JerreaderCopy.readingDuration(120 * 60.0))
        assertEquals("45 分钟", JerreaderCopy.readingDuration(45 * 60.0))
    }

    @Test
    fun `a session shorter than a minute is not reported as no reading at all`() {
        assertEquals("<1 分钟", JerreaderCopy.readingDuration(20.0))
        assertEquals("0 分钟", JerreaderCopy.readingDuration(0.0))
        assertEquals("0 分钟", JerreaderCopy.readingDuration(-30.0))
    }

    @Test
    fun `the delete confirmation names the book`() {
        assertTrue(JerreaderCopy.bookDeleteConfirmTitle("动物农场").contains("动物农场"))
    }

    @Test
    fun `section details read as one phrase`() {
        assertEquals("12 本 · 最近", JerreaderCopy.bookCountDetail(12, JerreaderCopy.librarySortRecent))
        assertEquals("3 个", JerreaderCopy.folderCountDetail(3))
        assertEquals("0 本", JerreaderCopy.bookCount(0))
    }

    @Test
    fun `the delete message stays true on a device with no Files app`() {
        // It used to say 「“文件”App 中的原文件」, which is an iOS-only promise.
        assertTrue(!JerreaderCopy.bookDeleteConfirmMessage.contains("文件”App"))
        assertTrue(JerreaderCopy.bookDeleteConfirmMessage.contains("原始导入文件"))
    }
}
