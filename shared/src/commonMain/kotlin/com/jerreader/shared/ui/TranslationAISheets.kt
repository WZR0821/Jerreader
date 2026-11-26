package com.jerreader.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jerreader.shared.translation.AIAnswerSource

/**
 * 先接入一个 AI 模型 (2.1.0 §7): what an AI chip opens while no AI provider
 * is set up. 查词 never comes here, because it needs no model. [message] is
 * why the last sign-in failed; [busy] while the sign-in page is open.
 */
@Composable
fun AISetupSheet(
    busy: Boolean,
    message: String?,
    onSignIn: () -> Unit,
    onEnterKey: () -> Unit,
    onHideButtons: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val shape = RoundedCornerShape(JerreaderRadius.card)
    CompanionSheet(
        title = "先接入一个 AI 模型",
        actionTitle = "取消",
        onAction = onDismiss,
        modifier = modifier.testTag("ai_setup_sheet"),
        actionCancels = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .jerreaderPaperCard(shape)
                .clip(shape)
        ) {
            AISetupRow("用 ChatGPT 账号登录", enabled = !busy, onClick = onSignIn, busy = busy)
            JerreaderDivider(Modifier.padding(start = JerreaderSpacing.md), color = colors.line)
            AISetupRow("填 API Key", enabled = !busy, onClick = onEnterKey)
            JerreaderDivider(Modifier.padding(start = JerreaderSpacing.md), color = colors.line)
            AISetupRow("不再显示这些按钮", enabled = !busy, onClick = onHideButtons, opens = false)
        }
        message?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text,
                modifier = Modifier
                    .padding(horizontal = JerreaderSpacing.xxs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** A row of the setup sheet; the two that go somewhere end in a chevron. */
@Composable
private fun AISetupRow(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
    opens: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = JerreaderSpacing.md, vertical = 12.dp)
            .alpha(if (enabled || busy) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), color = jerreaderPalette.accent, strokeWidth = 2.dp)
            opens -> JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.plainTertiary, Modifier.size(14.dp))
        }
    }
}

/**
 * 发送给模型的内容 (2.1.0 §6), from a source badge: the model, the passage
 * and, when one went along, the context. The 上下文 section is left out when
 * nothing was sent with the passage.
 */
@Composable
fun AISourceSheet(
    source: AIAnswerSource,
    sourceText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompanionSheet(
        title = "发送给模型的内容",
        actionTitle = "完成",
        onAction = onDismiss,
        modifier = modifier.testTag("ai_source_sheet")
    ) {
        AISheetSection("模型", source.modelName.trim().ifEmpty { source.badge })
        AISheetSection("原文", sourceText.trim())
        source.sentContext?.trim()?.takeIf { source.isAIModel && it.isNotEmpty() }?.let { context ->
            AISheetSection("上下文", context)
        }
    }
}

/** 展开看全文 (2.1.0 §4): a whole AI result under its result title. */
@Composable
fun AIFullTextSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompanionSheet(
        title = title,
        actionTitle = "完成",
        onAction = onDismiss,
        modifier = modifier.testTag("ai_full_text_sheet")
    ) {
        SelectionContainer {
            Text(text.trim(), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** An eyebrow over selectable text on paper. */
@Composable
private fun AISheetSection(title: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        JerreaderEyebrow(title, Modifier.padding(horizontal = JerreaderSpacing.xxs).semantics { heading() })
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .jerreaderPaperCard()
                    .padding(JerreaderSpacing.md),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
