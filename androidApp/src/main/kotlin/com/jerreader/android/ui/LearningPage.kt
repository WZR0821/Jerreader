package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.jerreader.shared.ui.LocalJerreaderColors

/** One scroll owner per tool, including large vocabulary collections. */
@Composable
fun LearningPage(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LearningTypography {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    }
}

/** Matches SwiftUI body/headline/subheadline/caption metrics in the learning tools. */
@Composable
fun LearningTypography(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val body = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 20.5.sp, letterSpacing = 0.sp)
    MaterialTheme(typography = base.copy(
        bodyLarge = body, bodyMedium = body.copy(fontSize = 15.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
        titleMedium = body.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = body.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = body.copy(fontSize = 22.sp, lineHeight = 26.5.sp, fontWeight = FontWeight.Bold),
        labelMedium = body.copy(fontSize = 12.sp, lineHeight = 14.5.sp)
    )) { CompositionLocalProvider(LocalTextStyle provides body, content = content) }
}

/** Native collection rows use body 17, subheadline 15 and title3 20. */
@Composable
fun LearningCollectionTypography(content: @Composable () -> Unit) {
    LearningTypography {
        val base = MaterialTheme.typography
        MaterialTheme(typography = base.copy(
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 18.sp)
        ), content = content)
    }
}

@Composable
fun LearningCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

enum class LearningButtonKind { PLAIN, FILLED, TINTED }

@Composable
fun LearningProgress(value: Float) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        .background(MaterialTheme.colorScheme.primaryContainer)) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun LearningSwitch(checked: Boolean, label: String, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Box(Modifier.size(51.dp, 44.dp).alpha(if (enabled) 1f else 0.4f).semantics { contentDescription = label }
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(16.dp))
            .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))) {
            Box(Modifier.padding(start = if (checked) 22.dp else 2.dp, top = 2.dp).size(27.dp)
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(14.dp)))
        }
    }
}

@Composable
fun LearningTextField(title: String, value: String, onValueChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(value, onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { input ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(10.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    input()
                }
            }
        })
}

/** Shared 44-point tap target and 8-point corner; no Material-only capsule or extra insets. */
@Composable
fun LearningButton(onClick: () -> Unit, enabled: Boolean = true,
    kind: LearningButtonKind = LearningButtonKind.PLAIN, destructive: Boolean = false, compact: Boolean = false,
    content: @Composable RowScope.() -> Unit) {
    val palette = LocalJerreaderColors.current
    val foreground = if (destructive) MaterialTheme.colorScheme.error
        else if (kind == LearningButtonKind.FILLED) palette.onAccent else palette.accent
    val background = when (kind) {
        LearningButtonKind.PLAIN -> androidx.compose.ui.graphics.Color.Transparent
        LearningButtonKind.FILLED -> palette.accent
        LearningButtonKind.TINTED -> palette.accentFill
    }
    Surface(shape = RoundedCornerShape(8.dp), color = background.copy(alpha = if (enabled) background.alpha else background.alpha * 0.4f),
        contentColor = foreground.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Row(Modifier.heightIn(min = if (compact) 34.dp else 44.dp).padding(
            horizontal = if (kind == LearningButtonKind.PLAIN) 0.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            content = content)
    }
}
