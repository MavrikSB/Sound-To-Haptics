package com.sound.to.haptics.haptics

import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.io.File

/** Result of validating the persistent haptic OGG artifact before registration. */
data class HapticOggValidationResult(
    val valid: Boolean,
    val reason: String,
    val durationMs: Long = 0L,
    val hasHapticComment: Boolean = false,
)

/**
 * Validates the artifact that the library and haptic player will actually
 * consume. A successful FFmpeg return code alone is not enough: an interrupted
 * encode can still leave a non-empty file, and an ordinary OGG without the
 * ANDROID_HAPTIC comment would play audio without its haptic channel.
 */
object HapticOggValidator {
    private val OGG_MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
    private val HAPTIC_COMMENT = "ANDROID_HAPTIC=1".encodeToByteArray()

    fun validate(file: File): HapticOggValidationResult {
        if (!file.exists() || file.length() < 4_096L) {
            return invalid("Haptic OGG is missing or too small")
        }
        if (!startsWithOgg(file)) {
            return invalid("Output is not an OGG container")
        }
        val hasComment = containsBytes(file, HAPTIC_COMMENT)
        if (!hasComment) {
            return HapticOggValidationResult(
                valid = false,
                reason = "OGG is missing the ANDROID_HAPTIC=1 comment",
            )
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.equals("yes", ignoreCase = true)
                ?: true
            val mimeType = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.lowercase()
            val isAudioContainer = mimeType == null ||
                mimeType.startsWith("audio/") ||
                mimeType.contains("ogg") ||
                mimeType.contains("vorbis")
            if (!hasAudio || !isAudioContainer || duration <= 0L) {
                HapticOggValidationResult(
                    valid = false,
                    reason = "OGG has no readable Vorbis/audio stream or duration",
                    durationMs = duration,
                    hasHapticComment = true,
                )
            } else {
                HapticOggValidationResult(
                    valid = true,
                    reason = "Haptic OGG validated",
                    durationMs = duration,
                    hasHapticComment = true,
                )
            }
        } catch (e: Exception) {
            HapticOggValidationResult(
                valid = false,
                reason = "OGG metadata could not be read: ${e.message ?: e.javaClass.simpleName}",
                hasHapticComment = true,
            )
        } finally {
            retriever.release()
        }
    }

    private fun startsWithOgg(file: File): Boolean = try {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(OGG_MAGIC.size)
            input.read(header) == header.size && header.contentEquals(OGG_MAGIC)
        }
    } catch (_: Exception) {
        false
    }

    private fun containsBytes(file: File, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return try {
            BufferedInputStream(file.inputStream(), 64 * 1024).use { input ->
                var matched = 0
                var found = false
                val buffer = ByteArray(64 * 1024)
                while (!found) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    for (index in 0 until count) {
                        if (buffer[index] == needle[matched]) {
                            matched++
                            if (matched == needle.size) {
                                found = true
                                break
                            }
                        } else {
                            matched = if (buffer[index] == needle[0]) 1 else 0
                        }
                    }
                }
                found
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun invalid(reason: String) = HapticOggValidationResult(valid = false, reason = reason)
}
