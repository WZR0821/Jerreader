package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class JerreaderM0UiState(
    val status: String = "Android M0 工程验证",
    val importedPublicationName: String? = null,
    val isBusy: Boolean = false
)

@Composable
fun JerreaderM0App(
    state: JerreaderM0UiState = JerreaderM0UiState(),
    onChoosePublication: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3E668C),
            secondary = Color(0xFF607D98),
            background = Color(0xFFF4F6F8),
            surface = Color.White
        )
    ) {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        text = "Jerreader",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Kotlin Multiplatform + Compose Multiplatform",
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = state.status, fontWeight = FontWeight.Medium)
                            Text(
                                text = state.importedPublicationName
                                    ?: "导入副本将保存在应用私有目录，并在阅读前后校验文件不变。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    enabled = !state.isBusy,
                                    onClick = onChoosePublication
                                ) {
                                    Text(if (state.isBusy) "处理中…" else "选择 EPUB/PDF")
                                }
                            }
                        }
                    }

                    Text(
                        text = "M0 只验证工程与原生 Readium 边界，不代表 Android 功能已对齐。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
