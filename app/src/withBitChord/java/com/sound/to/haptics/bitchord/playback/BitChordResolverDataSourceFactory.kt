package com.sound.to.haptics.bitchord.playback

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in
// a few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sound.to.haptics.bitchord.data.innertube.PlayerClient
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
 *
 * The bytes themselves are fetched as a run of bounded ranges rather than one
 * open-ended read, the way BitChord's own playback does
 * ([ChunkedDataSource]): googlevideo paces an unbounded response down to
 * roughly playback speed, so a whole-track request arrives at ~15 kB/s — a
 * player that can only just keep up — while the same bytes asked for as
 * bounded ranges are served at line rate.
 */
class BitChordResolverDataSourceFactory(
    private val okHttp: OkHttpClient,
    private val context: Context? = null,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BitChordResolverDataSource(okHttp, context)

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
    private val context: Context?,
) : BaseDataSource(/* isNetwork = */ true) {

    private val httpFactory = OkHttpDataSource.Factory(okHttp)
    private var upstream: HttpDataSource? = null
    private var openUri: Uri? = null

    // Local file/content delegate: the persisted rendition replays through these.
    private var fileDelegate: DataSource? = null

    // Chunked read state: the resolved URL and where the next range starts.
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var passthrough = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        if (uri.scheme == "file" || uri.scheme == "content") {
            // The persisted rendition plays from disk directly — ExoPlayer's
            // own src for media:local audio. No HTTP, no resolver.
            return try {
                val delegate: DataSource = when (uri.scheme) {
                    "file" -> FileDataSource()
                    else -> ContentDataSource(
                        requireNotNull(context) { "content:// playback needs a Context" },
                    )
                }
                val length = delegate.open(dataSpec)
                fileDelegate = delegate
                openUri = uri
                bytesRemaining = length
                transferStarted(dataSpec)
                length
            } catch (e: Exception) {
                runCatching { fileDelegate?.close() }
                fileDelegate = null
                openUri = null
                Log.e(TAG, "local open failed for $uri", e)
                if (e is IOException) throw e
                throw IOException("BitChord local open failed for $uri: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        var source: androidx.media3.datasource.okhttp.OkHttpDataSource? = null
        return try {
            val httpUri = if (uri.scheme == BitChordResolverDataSourceFactory.SCHEME) {
                Uri.parse(resolve(uri))
            } else {
                uri
            }
            val headers = StreamResolver.mediaHeadersFor(httpUri.toString())
            baseSpec = dataSpec.buildUpon().setUri(httpUri).build()
            position = dataSpec.position

            // The total size has to be known up front, or there is no way to
            // say where the last range ends. Every progressive googlevideo URL
            // carries it as `clen`; anything else is forwarded as one open read.
            val total = httpUri.getQueryParameter("clen")?.toLongOrNull()
            if (total == null) {
                passthrough = true
                chunkOpen = true
                source = openUpstream(baseSpec!!, headers, C.LENGTH_UNSET.toLong())
                upstream = source
                openUri = httpUri
                transferStarted(baseSpec!!)
                return C.LENGTH_UNSET.toLong()
            }

            passthrough = false
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                total
            } else {
                minOf(total, position + dataSpec.length)
            }
            bytesRemaining = (end - position).coerceAtLeast(0L)
            val rangeBytes = minOf(STREAM_CHUNK_BYTES, PlayerClient.rangeBytesFor(httpUri.toString()))
            if (bytesRemaining > 0) {
                // openChunk stores the opened source in [upstream] itself.
                openChunk(headers, minOf(rangeBytes, bytesRemaining))
            }
            openUri = httpUri
            transferStarted(baseSpec!!)
            Log.i(
                TAG,
                "media open ok: host=${httpUri.host} len=$total client=${httpUri.getQueryParameter("c")} " +
                    "cver=${httpUri.getQueryParameter("cver")} rangeBytes=$rangeBytes",
            )
            bytesRemaining
        } catch (e: Exception) {
            runCatching { source?.close() }
            runCatching { upstream?.close() }
            upstream = null
            openUri = null
            Log.e(
                TAG,
                "media open failed for ${uri.host}:${uri.getQueryParameter("v")} -> ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
            if (e is IOException) throw e
            throw IOException("BitChord stream open failed for $uri: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Opens one bounded range of the resolved URL. */
    private fun openChunk(headers: Map<String, String>, length: Long) {
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        val opened = try {
            openUpstream(spec, headers, length)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "range $position-${position + length - 1} refused for ${spec.uri.getQueryParameter("c")}: ${e.message}",
            )
            throw e
        }
        upstream = opened
        chunkRemaining = length
        chunkOpen = true
    }

    private fun openUpstream(
        spec: DataSpec,
        headers: Map<String, String>,
        length: Long,
    ): androidx.media3.datasource.okhttp.OkHttpDataSource {
        val opened = httpFactory.createDataSource()
        opened.setRequestProperty("User-Agent", headers["User-Agent"] ?: USER_AGENT)
        headers.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) opened.setRequestProperty(name, value)
        }
        opened.open(spec.buildUpon().setLength(length).build())
        return opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fileDelegate?.let { return it.read(buffer, offset, length) }
        if (passthrough) {
            val source = upstream ?: return C.RESULT_END_OF_INPUT
            val read = source.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT && read != 0) bytesTransferred(read)
            return read
        }
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // A range that ends early is re-opened for the part that didn't
        // arrive, which is also how the step to the next range happens.
        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
                val headers = StreamResolver.mediaHeadersFor(requireNotNull(baseSpec).uri.toString())
                val rangeBytes = minOf(STREAM_CHUNK_BYTES, PlayerClient.rangeBytesFor(baseSpec!!.uri.toString()))
                openChunk(headers, minOf(rangeBytes, bytesRemaining))
            }
            val source = upstream ?: throw IOException("BitChord stream re-opened to nothing for ${baseSpec?.uri}")
            val read = source.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT && read != 0) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                bytesTransferred(read)
                return read
            }
            chunkRemaining = 0L
        }
        // Several bounded ranges in a row served zero bytes: treat it as the
        // refusal it is instead of letting the player see a clean end-of-stream.
        throw IOException("BitChord stream stopped serving ranges for ${baseSpec?.uri}")
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream?.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = fileDelegate?.uri ?: upstream?.uri ?: baseSpec?.uri

    override fun close() {
        val hadTransfer = upstream != null || openUri != null || fileDelegate != null
        closeChunk()
        runCatching { fileDelegate?.close() }
        fileDelegate = null
        upstream = null
        openUri = null
        if (hadTransfer) transferEnded()
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
        private const val TAG = "BitChord"
        private const val RESOLVE_TIMEOUT_MS = 120_000L
        private const val STREAM_CHUNK_BYTES = 1L * 1024 * 1024
        private const val MAX_EMPTY_RANGES = 3
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
