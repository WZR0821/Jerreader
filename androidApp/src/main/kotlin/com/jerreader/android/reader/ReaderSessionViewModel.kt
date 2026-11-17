package com.jerreader.android.reader

import android.annotation.SuppressLint
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jerreader.shared.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope

/**
 * The part of a reading session that is not tied to the Readium fragment.
 *
 * It lives through rotation and other configuration changes, so the reading
 * clock keeps running and an unsaved position is not lost while the Activity
 * is rebuilt. The navigator, selection and translation UI stay in
 * [ReaderActivity], since they belong to the views it recreates.
 */
@SuppressLint("LogNotTimber")
class ReaderSessionViewModel(
    repository: LibraryRepository,
    writeScope: CoroutineScope
) : ViewModel() {
    internal val recorder = ReadingSessionRecorder(
        repository = repository,
        writeScope = writeScope,
        onWriteFailure = { what, error -> Log.w(TAG, "Unable to save $what", error) }
    )

    override fun onCleared() {
        // The Activity is finishing for good: hand over whatever is left.
        recorder.pause()
    }

    companion object {
        private const val TAG = "JerreaderReader"

        fun factory(
            repository: LibraryRepository,
            writeScope: CoroutineScope
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderSessionViewModel(repository, writeScope) as T
        }
    }
}
