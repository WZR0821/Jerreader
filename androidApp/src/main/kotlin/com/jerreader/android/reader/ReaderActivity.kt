package com.jerreader.android.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.jerreader.android.R
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.library.PublicationSnapshot
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.getOrElse
import java.io.File

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : AppCompatActivity() {
    private lateinit var expectedSnapshot: PublicationSnapshot
    private var publication: Publication? = null
    private lateinit var rootView: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android can restore Navigator fragments before the publication has
        // been reopened. Readium's dummy factories make that restoration safe.
        val publicationPath = intent.getStringExtra(EXTRA_PUBLICATION_PATH).orEmpty()
        supportFragmentManager.fragmentFactory = if (publicationPath.endsWith(".pdf", true)) {
            PdfNavigatorFragment.createDummyFactory(PdfiumEngineProvider())
        } else {
            EpubNavigatorFragment.createDummyFactory()
        }
        super.onCreate(savedInstanceState)

        // A dummy navigator must not reach onResume. Reopen the immutable file
        // below and replace it with a real Navigator fragment instead.
        supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)?.let { restored ->
            supportFragmentManager.commitNow { remove(restored) }
        }

        expectedSnapshot = intentSnapshot() ?: run {
            finish()
            return
        }
        if (!PublicationIntegrity.isUnchanged(expectedSnapshot)) {
            showRoot()
            showError("出版物副本在打开前已发生变化。")
            return
        }

        showRoot()
        lifecycleScope.launch { openPublication(File(expectedSnapshot.path)) }
    }

    @SuppressLint("LogNotTimber")
    override fun onDestroy() {
        publication?.close()
        publication = null

        if (::expectedSnapshot.isInitialized && !PublicationIntegrity.isUnchanged(expectedSnapshot)) {
            Log.e(INTEGRITY_TAG, "Readium session changed immutable publication bytes or mtime")
        }
        super.onDestroy()
    }

    private fun showRoot() {
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            id = R.id.reader_container
        }
        rootView.addView(
            ProgressBar(this).apply { id = R.id.reader_loading },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        setContentView(rootView)
    }

    private suspend fun openPublication(file: File) {
        val readium = ReadiumEnvironment(applicationContext)
        val asset = readium.assetRetriever.retrieve(file).getOrElse { error ->
            showError(error.toString())
            return
        }
        val openedPublication = readium.publicationOpener.open(
            asset,
            allowUserInteraction = false
        ).getOrElse { error ->
            showError(error.toString())
            return
        }

        if (openedPublication.isRestricted) {
            openedPublication.close()
            showError("当前出版物受保护，Jerreader 不会尝试绕过 DRM。")
            return
        }

        publication = openedPublication
        when {
            openedPublication.conformsTo(Publication.Profile.EPUB) -> {
                val factory = EpubNavigatorFactory(openedPublication).createFragmentFactory(
                    initialLocator = null
                )
                attachNavigator(factory, EpubNavigatorFragment::class.java)
            }
            openedPublication.conformsTo(Publication.Profile.PDF) -> {
                val factory = PdfNavigatorFactory(
                    openedPublication,
                    PdfiumEngineProvider()
                ).createFragmentFactory()
                attachNavigator(factory, PdfNavigatorFragment::class.java)
            }
            else -> showError("当前出版物不支持阅读。")
        }
    }

    private fun attachNavigator(
        factory: FragmentFactory,
        fragmentClass: Class<out androidx.fragment.app.Fragment>
    ) {
        findViewById<View>(R.id.reader_loading)?.visibility = View.GONE
        supportFragmentManager.fragmentFactory = factory
        supportFragmentManager.commitNow {
            replace(R.id.reader_container, fragmentClass, Bundle(), NAVIGATOR_TAG)
        }
    }

    private fun showError(message: String) {
        rootView.removeAllViews()
        rootView.addView(
            TextView(this).apply {
                text = message
                setTextColor(Color.DKGRAY)
                textSize = 16f
                setPadding(48, 48, 48, 48)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun intentSnapshot(): PublicationSnapshot? {
        val path = intent.getStringExtra(EXTRA_PUBLICATION_PATH) ?: return null
        val sha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256) ?: return null
        val lastModified = intent.getLongExtra(EXTRA_EXPECTED_LAST_MODIFIED, Long.MIN_VALUE)
        if (lastModified == Long.MIN_VALUE) return null
        return PublicationSnapshot(path, sha256, lastModified)
    }

    companion object {
        const val EXTRA_PUBLICATION_PATH = "com.jerreader.reader.PUBLICATION_PATH"
        const val EXTRA_EXPECTED_SHA256 = "com.jerreader.reader.EXPECTED_SHA256"
        const val EXTRA_EXPECTED_LAST_MODIFIED = "com.jerreader.reader.EXPECTED_LAST_MODIFIED"

        private const val NAVIGATOR_TAG = "JerreaderNavigator"
        private const val INTEGRITY_TAG = "JerreaderIntegrity"
    }
}
