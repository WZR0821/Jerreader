package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/** iOS switch geometry with a 44-point accessible hit target. */
@Composable
fun JerreaderToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, label: String = "") {
    Box(Modifier.size(51.dp, 44.dp).alpha(if (enabled) 1f else 0.4f)
        .semantics { contentDescription = label }.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(16.dp))
            .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))) {
            Box(Modifier.padding(start = if (checked) 22.dp else 2.dp, top = 2.dp).size(27.dp)
                .background(Color.White, RoundedCornerShape(14.dp)))
        }
    }
}
