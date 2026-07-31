package com.jerreader.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderThemeOption

data class ReaderChapter(
    val id: String,
    val title: String,
    val depth: Int
)

@Composable
fun ReaderToolbar(
    title: String,
    onBack: () -> Unit,
    onTableOfContents: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onTableOfContents) { Text("目录") }
            TextButton(onClick = onAppearance) { Text("Aa") }
        }
    }
}

@Composable
fun ReaderTableOfContents(
    chapters: List<ReaderChapter>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Text(
            "目录",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (chapters.isEmpty()) {
            Text("该 EPUB 没有可用目录。", modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(chapters, key = ReaderChapter::id) { chapter ->
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (chapter.depth * 18).dp),
                        onClick = { onSelect(chapter.id) }
                    ) {
                        Text(
                            text = chapter.title,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderAppearancePanel(
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("字号")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = appearance.fontScale > 0.8,
                    onClick = {
                        onChange(appearance.copy(fontScale = (appearance.fontScale - 0.1).coerceAtLeast(0.8)))
                    }
                ) { Text("−") }
                Text(
                    text = "${(appearance.fontScale * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Button(
                    enabled = appearance.fontScale < 2.0,
                    onClick = {
                        onChange(appearance.copy(fontScale = (appearance.fontScale + 0.1).coerceAtMost(2.0)))
                    }
                ) { Text("+") }
            }
        }
        Text("主题")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeButton("浅色", ReaderThemeOption.LIGHT, appearance, onChange)
            ThemeButton("护眼", ReaderThemeOption.SEPIA, appearance, onChange)
            ThemeButton("深色", ReaderThemeOption.DARK, appearance, onChange)
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun ThemeButton(
    label: String,
    theme: ReaderThemeOption,
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit
) {
    if (appearance.theme == theme) {
        Button(onClick = { onChange(appearance.copy(theme = theme)) }) { Text(label) }
    } else {
        TextButton(onClick = { onChange(appearance.copy(theme = theme)) }) { Text(label) }
    }
}
