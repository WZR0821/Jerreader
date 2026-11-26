package com.jerreader.android.learning
import com.jerreader.android.lexical.JapaneseLookupInflection
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
class InflectionRuleParityTest {
    @Test fun allPreviousRulesKeepTheirAmbiguityPriority() {
        val text = JapaneseLookupInflection.rules.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals("cc9579c6178c6f57aa03b3abbaf096fa9673ccd9427ab642550f122ae8019c10", hash)
    }
    // 2.1.0: the extended rules behind a short tap's whole word, the same table as iOS's.
    @Test fun extendedRulesMatchIOS() {
        val text = JapaneseLookupInflection.allRules.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(1411, JapaneseLookupInflection.allRules.size)
        assertEquals("cd6e5829333e5499127bb162b7f4fc84cb8ab70c6efeaaaf4cb724824839cee5", hash)
    }
}
