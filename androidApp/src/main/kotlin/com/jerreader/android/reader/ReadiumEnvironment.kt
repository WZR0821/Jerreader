package com.jerreader.android.reader

import android.content.Context
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class ReadiumEnvironment(context: Context) {
    private val httpClient = DefaultHttpClient()

    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)

    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            assetRetriever = assetRetriever,
            httpClient = httpClient,
            pdfFactory = PdfiumDocumentFactory(context)
        )
    )
}
