package com.jerreader.android.ui

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Keep system icons readable when the app palette switches between light and dark. */
@Composable
fun JerreaderSystemBars() {
    val view = LocalView.current
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    if (view.isInEditMode) return
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = background.luminance() > 0.5f
        controller.isAppearanceLightNavigationBars = surface.luminance() > 0.5f
        // API 23–25 cannot draw dark navigation icons, so they need a dark bar.
        @Suppress("DEPRECATION")
        fun applyLegacyBarColors() {
            window.statusBarColor = background.toArgb()
            window.navigationBarColor = if (Build.VERSION.SDK_INT < 26) android.graphics.Color.DKGRAY else surface.toArgb()
        }
        applyLegacyBarColors()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }
}
