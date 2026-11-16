package com.sound.to.haptics.haptics

import android.content.Context
import java.io.File
import java.security.MessageDigest

class TrackHapticCache(context: Context) {

    private val dir = File(context.filesDir, "bitchord_haptics_v${HapticProcessing.VERSION}").apply {

        context.filesDir.listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name.startsWith("bitchord_haptics") &&
                    it.absolutePath != absolutePath
            }
            ?.forEach { it.deleteRecursively() }
        mkdirs()

        listFiles()
            ?.filter {
                it.name.endsWith(".tmp", ignoreCase = true) ||
                    it.name.endsWith(".encoding.tmp.ogg", ignoreCase = true) ||

                    it.name.endsWith(".stereo.ogg", ignoreCase = true)
            }
            ?.forEach { it.delete() }
    }

    private fun keyFor(trackId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun lyricsFileFor(trackId: String): File = File(dir, "${keyFor(trackId)}.lrc")

    fun fileFor(trackId: String): File = File(dir, "${keyFor(trackId)}.ogg")

    fun exists(trackId: String): Boolean = fileFor(trackId).let { it.exists() && it.length() > 0 }

    fun scratchPcmFile(trackId: String): File = File(dir, "${keyFor(trackId)}.pcm.tmp")

    fun analysisFile(trackId: String): File = File(dir, "${keyFor(trackId)}.layers")

    fun transientAnalysisFile(trackId: String): File = File(dir, "${keyFor(trackId)}.transient")

    fun delete(trackId: String) {
        fileFor(trackId).takeIf { it.exists() }?.delete()
        lyricsFileFor(trackId).takeIf { it.exists() }?.delete()
        analysisFile(trackId).takeIf { it.exists() }?.delete()
        transientAnalysisFile(trackId).takeIf { it.exists() }?.delete()
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
