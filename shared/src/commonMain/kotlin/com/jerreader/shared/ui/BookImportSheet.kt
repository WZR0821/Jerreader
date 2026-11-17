package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The format filter of the "导入电子书" sheet, mirroring `BookImportFormatFilter`
 * in `Jerreader/Features/Library/LibraryView.swift`.
 *
 * Android's system picker, like the iOS one, cannot hide other files: it greys
 * out everything whose type is not listed, so that is what the copy promises.
 */
enum class BookImportFormat(val title: String, val icon: JerreaderIcon, private val types: List<String>) {
    ALL("全部", JerreaderIcon.GRID, emptyList()),
    EPUB("EPUB", JerreaderIcon.BOOKS, listOf("application/epub+zip")),
    PDF("PDF", JerreaderIcon.DOC_PDF, listOf("application/pdf")),
    WORD("Word", JerreaderIcon.DOC_WORD, listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    TXT("TXT", JerreaderIcon.DOC_PLAIN, listOf("text/plain"));

    /** The MIME types handed to the system document picker. */
    val mimeTypes: List<String>
        get() = if (this == ALL) entries.flatMap { it.types } else types

    val filePickerTitle: String get() = if (this == ALL) "文档" else " $title 文件"
    val filePickerDescription: String get() = if (this == ALL) "支持的四种格式" else "$title 文件"
    val accessibilityTitle: String get() = if (this == ALL) "支持的全部格式" else "$title 文件"
}

/**
 * Android port of the iOS `BookImportOptionsView`: pick a format first, then
 * open the system picker limited to it. Layout, sizes and copy follow iOS; only
 * the two cards that describe platform features say what Android offers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookImportOptionsSheet(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onChooseFile: (mimeTypes: List<String>) -> Unit
) {
    val colors = LocalJerreaderColors.current
    var selected by rememberSaveable { mutableStateOf(BookImportFormat.ALL) }
    // iOS presents this sheet at the large detent with a visible grabber: the
    // format grid comes first, and at half height it pushed the "选择文档"
    // card below the fold. Skip the half-expanded state here as well.
    val sheetTop = pageSheetTop()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PageSheetShape,
        // The grabber is drawn over the bar, as iOS does, instead of above it.
        dragHandle = null,
        containerColor = colors.canvas
    ) {
        Column(Modifier.pageSheetHeight(sheetTop)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                PageSheetGrabber()
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Text("取消", style = MaterialTheme.typography.bodyLarge)
                }
                Text("导入电子书", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.3.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "选择文档的传输方式",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "所有方式都只会将书籍复制或转换到Jerreader的本地空间，不会自动上传。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText
                    )
                }

                ImportPaperCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("先选择文件格式", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "系统文件选择器里，其他格式的文件会变灰、不能选。",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.secondaryText
                        )
                        BookImportFormat.entries.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { format ->
                                    FormatButton(format, format == selected, Modifier.weight(1f)) { selected = format }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                TransferMethodCard(
                    title = "选择${selected.filePickerTitle}",
                    description = "从系统“文件”、下载目录、云盘或外接存储中选择。Jerreader只读取并复制一份到本地，不会改动或写回原文件；当前只能选${selected.filePickerDescription}，其他格式会变灰。",
                    icon = JerreaderIcon.FOLDER,
                    showsDisclosure = true,
                    enabled = !isImporting,
                    onClick = { onChooseFile(selected.mimeTypes) }
                )
                TransferMethodCard(
                    title = "其他 App 与分享",
                    description = "在微信、浏览器、邮件或文件管理器收到支持的文档后，选择“用其他应用打开”或“分享”，再选Jerreader即可导入。",
                    icon = JerreaderIcon.DOWNLOAD
                )
                TransferMethodCard(
                    title = "电脑文件共享",
                    description = "用 USB 数据线连接电脑并选择“文件传输”，把文档复制到手机存储，然后在上方从系统“文件”里选择它。",
                    icon = JerreaderIcon.EXTERNAL_DRIVE
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JerreaderGlyph(JerreaderIcon.INFO, colors.secondaryText, Modifier.padding(top = 1.dp).size(13.dp))
                    Text(
                        "支持 EPUB、PDF、DOCX 和 TXT；扫描 PDF 可按需使用本机 OCR。不支持受 DRM 保护的书籍、旧式 .doc 或整本上传翻译。",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatButton(format: BookImportFormat, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalJerreaderColors.current
    val tint = if (isSelected) colors.onAccent else colors.accent
    Column(
        modifier.height(66.dp).clip(RoundedCornerShape(13.dp))
            .background(if (isSelected) colors.accent else colors.accentFill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "只选 ${format.accessibilityTitle}" },
        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        JerreaderGlyph(format.icon, tint, Modifier.size(20.dp))
        Text(format.title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = tint)
    }
}

/** `.jerreaderPaperCard(padding: 16)` from iOS. */
@Composable
private fun ImportPaperCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalJerreaderColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(colors.paper)
            .border(0.75.dp, colors.line, shape).padding(16.dp)
    ) { content() }
}

@Composable
private fun TransferMethodCard(
    title: String,
    description: String,
    icon: JerreaderIcon,
    showsDisclosure: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalJerreaderColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f).clip(shape).background(colors.paper)
            .border(0.75.dp, colors.line, shape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(44.dp).background(colors.accentFill, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) { JerreaderGlyph(icon, colors.accent, Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
        }
        if (showsDisclosure) {
            JerreaderGlyph(
                JerreaderIcon.CHEVRON_RIGHT,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                Modifier.padding(top = 15.dp).size(13.dp)
            )
        }
    }
}
