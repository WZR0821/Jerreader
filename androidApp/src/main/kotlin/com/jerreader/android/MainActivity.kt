package com.jerreader.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.reader.ReaderActivity
import com.jerreader.shared.ui.JerreaderM0App
import com.jerreader.shared.ui.JerreaderM0UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(JerreaderM0UiState())

    private val choosePublication =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult

            uiState = uiState.copy(status = "正在创建只读副本…", isBusy = true)
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ImmutablePublicationStore(applicationContext).importFrom(uri)
                    }
                }.onSuccess { imported ->
                    uiState = JerreaderM0UiState(
                        status = "导入完成，正在打开 Readium…",
                        importedPublicationName = imported.displayName,
                        isBusy = false
                    )
                    startActivity(
                        Intent(this@MainActivity, ReaderActivity::class.java)
                            .putExtra(ReaderActivity.EXTRA_PUBLICATION_PATH, imported.snapshot.path)
                            .putExtra(ReaderActivity.EXTRA_EXPECTED_SHA256, imported.snapshot.sha256)
                            .putExtra(
                                ReaderActivity.EXTRA_EXPECTED_LAST_MODIFIED,
                                imported.snapshot.lastModified
                            )
                    )
                }.onFailure { error ->
                    uiState = JerreaderM0UiState(
                        status = error.message ?: "无法导入该文件。",
                        isBusy = false
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JerreaderM0App(
                state = uiState,
                onChoosePublication = {
                    choosePublication.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf"
                        )
                    )
                }
            )
        }
    }
}
