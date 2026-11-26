package com.jerreader.shared.translation

import kotlin.test.Test
import kotlin.test.assertEquals

/** 存为笔记 (2.1.0 §6) appends to the sentence's note, as iOS `appendedNote`. */
class AIReadingNoteTest {
    @Test
    fun aFirstResultIsTheWholeNote() {
        assertEquals("【读懂这段】\n他在雨中去了车站。", AIReadingActionPolicy.appendedNote(null, "读懂这段", "他在雨中去了车站。"))
        assertEquals("【直译对照】\n直译：A\n差别：B", AIReadingActionPolicy.appendedNote("  \n", " 直译对照 ", "\n直译：A\n差别：B\n"))
    }

    @Test
    fun aLaterResultFollowsTheExistingNoteAfterABlankLine() {
        assertEquals(
            "我的笔记\n\n【追问】\n问：主语是谁？\n答：叙述者。",
            AIReadingActionPolicy.appendedNote(
                "我的笔记\n",
                "追问",
                AIReadingActionPolicy.followUpNote(" 主语是谁？", "叙述者。 ")
            )
        )
    }
}
