package com.jerreader.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jerreader.shared.learning.ReadingConfusionMap
import com.jerreader.shared.ui.*

/** A visible entry point for the existing learning tools; no separate learning store. */
@Composable
fun LearningPracticeScreen(
    wordCount: Int,
    sentenceCount: Int,
    confusion: ReadingConfusionMap? = null,
    onOpen: (String) -> Unit
) {
    LearningPage {
        item {
            LearningCard {
                Text("今日练习", style = MaterialTheme.typography.titleLarge)
                Text("从阅读中积累，每天巩固一点。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PracticeCount("收藏词语", wordCount, Modifier.weight(1f))
                    PracticeCount("原句收藏", sentenceCount, Modifier.weight(1f))
                }
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen("review") },
                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        JerreaderGlyph(JerreaderIcon.REVIEW, LocalContentColor.current, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("开始复习")
                    }
                }
                Text("先回忆，再看释义；每组最多 10 个词。", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Real reading difficulties across books, built locally from lookups and reviews.
        confusion?.let { map -> item { ReadingConfusionMapCard(confusion = map, showBookTitle = true) } }
        item { Text("练习与积累", style = MaterialTheme.typography.titleMedium) }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(.75.dp, MaterialTheme.colorScheme.outline)) {
                Column {
                    PracticeToolRow("原句练习卡", "用收藏的原句回忆词语与表达", JerreaderIcon.SENTENCES) { onOpen("sentences") }
                    JerreaderListDivider(Modifier.padding(start = 60.dp))
                    PracticeToolRow("语法知识库", "整理解释、标签与原书出处", JerreaderIcon.NOTE) { onOpen("grammar") }
                    JerreaderListDivider(Modifier.padding(start = 60.dp))
                    PracticeToolRow("阅读成长报告", "回顾本周阅读与复习记录", JerreaderIcon.CHART) { onOpen("growth") }
                }
            }
        }
    }
}

@Composable
private fun PracticeCount(label: String, count: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PracticeToolRow(title: String, detail: String, icon: JerreaderIcon, inset: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(if (inset) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                JerreaderGlyph(icon, MaterialTheme.colorScheme.primary, Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(13.dp))
    }
}
