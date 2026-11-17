package com.jerreader.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jerreader.shared.learning.ChapterClosurePlan
import com.jerreader.shared.learning.ContinuationRecapPlan
import com.jerreader.shared.learning.ReadingCompanionMode
import com.jerreader.shared.learning.ReadingConfusionMap
import com.jerreader.shared.translation.AIReadingLensMode

/**
 * Android counterpart of the iOS ReaderCompanionSheet: the reading mode, a
 * recap of where the reader was, and the confusion map for this book. Words
 * have no saved locator, so "back to the text" runs a book search for them.
 */
@Composable
fun ReaderCompanionPanel(
    mode: ReadingCompanionMode,
    recap: ContinuationRecapPlan,
    confusion: ReadingConfusionMap,
    closureChapterTitle: String,
    onModeChange: (ReadingCompanionMode) -> Unit,
    onSearchTerm: (String) -> Unit,
    onStartClosure: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .testTag("reader_companion_panel")
    ) {
        CompanionPanelHeader(title = "${mode.title}伴读", onDismiss = onDismiss)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JerreaderSegmented(
                options = ReadingCompanionMode.entries.map { it to "${it.title}伴读" },
                selected = mode,
                onSelect = onModeChange
            )

            JerreaderCard {
                Text(recap.title, style = MaterialTheme.typography.titleLarge)
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recap.bullets.forEach { bullet ->
                        Text("›  $bullet", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            ReadingConfusionMapCard(confusion = confusion, onSearchTerm = onSearchTerm)

            Button(
                onClick = onStartClosure,
                modifier = Modifier.fillMaxWidth().testTag("start_chapter_closure")
            ) { Text("90 秒章节闭环 · $closureChapterTitle", maxLines = 1) }
        }
    }
}

/**
 * The confusion map, shared by the reader panel and the learning page. With
 * [showBookTitle] each item names its book, for the page that lists them all.
 */
@Composable
fun ReadingConfusionMapCard(
    confusion: ReadingConfusionMap,
    onSearchTerm: ((String) -> Unit)? = null,
    showBookTitle: Boolean = false,
    modifier: Modifier = Modifier
) {
    // The iOS card: an 18-point paper card whose parts are all 10 points apart,
    // with a subheadline summary.
    Surface(
        modifier = modifier.fillMaxWidth().testTag("reading_confusion_map"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline)
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI 困惑地图", style = MaterialTheme.typography.titleLarge)
        Text(
            confusion.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        confusion.items.forEach { item ->
            JerreaderDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.displayTerm,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    item.kind.title,
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                if (showBookTitle && item.sourceBookTitle.isNotBlank()) {
                    "《${item.sourceBookTitle}》 · ${item.reason}"
                } else {
                    item.reason
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.sentenceContext.isNotBlank()) {
                Text(
                    item.sentenceContext,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
            if (onSearchTerm != null) {
                TextButton(onClick = { onSearchTerm(item.displayTerm) }) { Text("在书中查找") }
            }
        }
        Text(
            "只根据重复查词、真实原句和复习结果生成，不调用网络。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } }
}

/** Android counterpart of the iOS ReaderChapterClosureSheet. */
@Composable
fun ReaderChapterClosurePanel(
    plan: ChapterClosurePlan,
    onSearchTerm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .testTag("reader_chapter_closure_panel")
    ) {
        CompanionPanelHeader(
            title = "${plan.estimatedSeconds} 秒章节闭环",
            actionTitle = "完成",
            onDismiss = onDismiss
        )
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "${plan.chapterTitle} · 每步 30 秒，先回想，再回到原文验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            plan.prompts.forEach { prompt ->
                JerreaderCard {
                    Text(
                        prompt.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        prompt.prompt,
                        modifier = Modifier.padding(top = 7.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    prompt.searchTerm?.let { term ->
                        TextButton(
                            onClick = { onSearchTerm(term) },
                            modifier = Modifier.padding(top = 2.dp)
                        ) { Text("在书中查找“$term”") }
                    }
                }
            }
        }
    }
}

/**
 * The lens questions offered on a translated passage. The companion mode is
 * switched here too, because it changes how every lens question is asked.
 */
@Composable
fun AIReadingLensChooser(
    companionMode: ReadingCompanionMode,
    onChoose: (AIReadingLensMode) -> Unit,
    onModeChange: (ReadingCompanionMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().testTag("ai_reading_lens_chooser")) {
        CompanionPanelHeader(title = "AI 阅读透镜", actionTitle = "取消", onDismiss = onDismiss)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            JerreaderSegmented(
                options = ReadingCompanionMode.entries.map { it to "${it.title}伴读" },
                selected = companionMode,
                onSelect = onModeChange
            )
            AIReadingLensMode.entries.forEach { mode ->
                JerreaderCard(
                    modifier = Modifier
                        .testTag("ai_reading_lens_${mode.storageId}")
                        .clickable(role = Role.Button) { onChoose(mode) }
                ) {
                    Text(mode.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        mode.detail,
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "只依据选中文字和有限前后文，不剧透。",
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompanionPanelHeader(
    title: String,
    actionTitle: String = "继续阅读",
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge
        )
        TextButton(onClick = onDismiss) { Text(actionTitle) }
    }
}
