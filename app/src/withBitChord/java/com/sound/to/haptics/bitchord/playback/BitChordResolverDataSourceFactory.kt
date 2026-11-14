package com.sound.to.haptics.bitchord.playback

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sound.to.haptics.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Resolves `bitchord://watch?v=<videoId>` media URIs into the stream URL
 * YouTube actually serves — using BitChord's own stream resolution
 * ([StreamResolver], InnerTubeX-first with the NewPipe failsafe) — and then
 * hands the bytes to OkHttp with the headers the URL was minted for.
 *
 * Resolution runs on ExoPlayer's loader thread, mirroring BitChord's own
 * playback service: ExoPlayer asks for bytes, we answer "which bytes", and a
 * re-open (seek outside the buffer, cache fill) asks again — answered from
 * the resolver's short-lived URL cache when it can. A track the resolver
 * knows is unplayable fails fast and permanently
 * ([StreamResolver.PermanentlyUnplayableException]); anything else surfaces
 * as an ordinary IOException so the player's error handling takes over.
 */
class BitChordResolverDataSourceFactory(
    private val okHttp: OkHttpClient,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BitChordResolverDataSource(okHttp)

    companion object {
        const val SCHEME = "bitchord"
        const val PARAM_VIDEO_ID = "v"

        fun watchUri(videoId: String): Uri =
            Uri.Builder().scheme(SCHEME).authority("watch")
                .appendQueryParameter(PARAM_VIDEO_ID, videoId).build()
    }
}

private class BitChordResolverDataSource(
    okHttp: OkHttpClient,
) : BaseDataSource(/* isNetwork = */ true) {

    private val httpFactory = OkHttpDataSource.Factory(okHttp)
    private var upstream: HttpDataSource? = null
    private var openUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        var source: androidx.media3.datasource.okhttp.OkHttpDataSource? = null
        return try {
            val httpUri = if (uri.scheme == BitChordResolverDataSourceFactory.SCHEME) {
                Uri.parse(resolve(uri))
            } else {
                uri
            }
            val headers = StreamResolver.mediaHeadersFor(httpUri.toString())
            // Keep the range and the byte request ExoPlayer asked for; only the
            // origin the bytes live at changes.
            val httpSpec = dataSpec.buildUpon().setUri(httpUri).build()
            val opened = httpFactory.createDataSource()
            source = opened
            opened.setRequestProperty("User-Agent", headers["User-Agent"] ?: USER_AGENT)
            headers.forEach { (name, value) ->
                if (!name.equals("User-Agent", ignoreCase = true)) opened.setRequestProperty(name, value)
            }
            val length = opened.open(httpSpec)
            upstream = opened
            openUri = httpUri
            transferInitializing(httpSpec)
            length
        } catch (e: Exception) {
            runCatching { source?.close() }
            upstream = null
            openUri = null
            if (e is IOException) throw e
            throw IOException("BitChord stream open failed for $uri: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = upstream ?: return 0
        val read = source.read(buffer, offset, length)
        if (read != C.RESULT_END_OF_INPUT && read != 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openUri

    override fun close() {
        upstream?.close()
        upstream = null
        openUri = null
        transferEnded()
    }

    private fun resolve(uri: Uri): String {
        val videoId = uri.getQueryParameter(BitChordResolverDataSourceFactory.PARAM_VIDEO_ID)
            ?: throw IOException("bitchord:// watch URI without a video id: $uri")
        return try {
            runBlocking { withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) } }
        } catch (e: IOException) {
            // Permanently-unplayable verdicts and other resolution failures
            // carry the reason the user should see; let them through.
            throw e
        } catch (e: Exception) {
            throw IOException("Couldn't resolve $videoId: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        private const val RESOLVE_TIMEOUT_MS = 120_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
