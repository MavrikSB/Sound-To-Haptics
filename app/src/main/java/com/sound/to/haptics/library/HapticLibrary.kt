package com.sound.to.haptics.library

import android.content.Context
import com.sound.to.haptics.haptics.HapticOggValidator
import com.sound.to.haptics.haptics.HapticProcessing
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * A small, deliberately boring persistence layer for converted audio.
 *
 * Converted songs are library content, not cache content: they live below
 * Context.filesDir/haptic_library and are therefore private to this app and
 * retained across normal process death, upgrades, and app launches. Metadata
 * is kept beside each OGG in a tiny properties file so a damaged entry can
 * be skipped without making the whole library unreadable.
 */
data class HapticSong(
    val id: String,
    val title: String,
    val originalName: String,
    val artist: String = "Imported audio",
    val durationMs: Long = 0L,
    val createdAt: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val processingVersion: Int = 0,
    val filePath: String,
) {
    val file: File get() = File(filePath)
    val onsetFile: File get() = File(file.parentFile, "${file.nameWithoutExtension}.onsets")
    val layerFile: File get() = File(file.parentFile, "${file.nameWithoutExtension}.layers")

    /**
     * Sidecar for the musical-haptic Transient Mode renderer's scheduled
     * event timeline (see [com.sound.to.haptics.haptics.TransientAnalysis]).
     * Independent of [onsetFile]/[layerFile]: those remain the continuous-
     * mode diagnostics/legacy artifacts, versioned by
     * [com.sound.to.haptics.haptics.HapticProcessing.VERSION]; this one is
     * versioned by [com.sound.to.haptics.haptics.TransientConfig.PROCESSING_VERSION]
     * so a Transient-only algorithm change never invalidates Continuous
     * mode's cache, or vice versa.
     */
    val transientFile: File get() = File(file.parentFile, "${file.nameWithoutExtension}.transient")
}

class HapticLibrary(context: Context) {
    companion object {
        private const val DIRECTORY = "haptic_library"
        private const val META_EXTENSION = ".properties"
        private const val OGG_EXTENSION = ".ogg"
    }

    private val directory = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    init {
        // Older builds wrote haptic_<timestamp>.ogg directly into filesDir.
        // Move those entries once so an upgrade does not make a person's
        // existing converted songs disappear from the new library screen.
        migrateLegacyFiles(context.applicationContext.filesDir)
    }

    /** Returns only playable OGGs. One bad file cannot hide the remaining library. */
    @Synchronized
    fun scan(): List<HapticSong> = directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.extension.equals("ogg", ignoreCase = true) && it.length() > 0L }
        ?.mapNotNull { file -> readSong(file) }
        ?.sortedWith(
            compareByDescending<HapticSong> { maxOf(it.lastPlayedAt, it.createdAt) }
                .thenBy { it.title.lowercase() },
        )
        ?.toList()
        .orEmpty()

    /**
     * Atomically-ish promotes a successful conversion into the library. The
     * caller may safely delete [convertedFile] after this returns.
     */
    @Synchronized
    fun addConverted(
        convertedFile: File,
        originalName: String,
        durationMs: Long = 0L,
        artist: String = "Imported audio",
        processingVersion: Int = HapticProcessing.VERSION,
    ): HapticSong {
        require(convertedFile.exists() && convertedFile.length() > 0L) { "Converted audio is missing" }
        val validation = HapticOggValidator.validate(convertedFile)
        require(validation.valid) { "Converted haptic OGG is invalid: ${validation.reason}" }
        directory.mkdirs()

        val base = sanitizeBaseName(originalName)
        val target = uniqueFile(base)
        val id = UUID.randomUUID().toString()
        val staging = File(directory, ".${target.name}.copying")
        staging.delete()
        convertedFile.copyTo(staging, overwrite = true)
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }

        val song = HapticSong(
            id = id,
            title = target.nameWithoutExtension,
            originalName = originalName.ifBlank { "$base.audio" },
            artist = artist,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis(),
            processingVersion = processingVersion,
            filePath = target.absolutePath,
        )
        writeSong(song)
        return song
    }

    @Synchronized
    fun delete(song: HapticSong): Boolean {
        val deletedAudio = song.file.delete() || !song.file.exists()
        song.file.parentFile
            ?.resolve(song.file.nameWithoutExtension + META_EXTENSION)
            ?.delete()
        song.onsetFile.delete()
        song.layerFile.delete()
        song.transientFile.delete()
        return deletedAudio
    }

    @Synchronized
    fun clear(): Int {
        // Report songs, not their sidecar properties/onset files. The return
        // value is useful to callers that want to confirm how much content
        // was removed, while every temporary/staging file is still cleaned.
        val count = directory.listFiles()?.count {
            it.isFile && it.extension.equals(OGG_EXTENSION.removePrefix("."), ignoreCase = true)
        } ?: 0
        directory.listFiles()?.forEach { it.delete() }
        return count
    }

    fun findByPath(path: String): HapticSong? = scan().firstOrNull { it.filePath == path }

    /** Persists recency separately from creation time for stable library ordering. */
    @Synchronized
    fun markPlayed(song: HapticSong): HapticSong? {
        if (!song.file.exists()) return null
        val updated = song.copy(lastPlayedAt = System.currentTimeMillis())
        writeSong(updated)
        return updated
    }

    fun storageBytes(): Long = directory.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private fun migrateLegacyFiles(filesDir: File) {
        filesDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("haptic_") && it.extension.equals("ogg", ignoreCase = true) }
            ?.forEach { legacy ->
                runCatching {
                    val base = sanitizeBaseName(legacy.nameWithoutExtension.removePrefix("haptic_"))
                    val target = uniqueFile(base)
                    if (!legacy.renameTo(target)) {
                        legacy.copyTo(target, overwrite = false)
                        legacy.delete()
                    }
                }
            }
    }

    private fun uniqueFile(base: String): File {
        var candidate = File(directory, "$base$OGG_EXTENSION")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base ($suffix)$OGG_EXTENSION")
            suffix++
        }
        return candidate
    }

    private fun readSong(file: File): HapticSong? {
        val metaFile = File(file.parentFile, file.nameWithoutExtension + META_EXTENSION)
        val props = Properties()
        if (metaFile.exists()) {
            runCatching { metaFile.inputStream().buffered().use { input -> props.load(input) } }
        }
        val fallbackTitle = file.nameWithoutExtension
        return HapticSong(
            id = props.getProperty("id")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            title = props.getProperty("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            originalName = props.getProperty("originalName")?.takeIf { it.isNotBlank() } ?: "$fallbackTitle.audio",
            artist = props.getProperty("artist")?.takeIf { it.isNotBlank() } ?: "Imported audio",
            durationMs = props.getProperty("durationMs")?.toLongOrNull() ?: 0L,
            createdAt = props.getProperty("createdAt")?.toLongOrNull() ?: file.lastModified(),
            lastPlayedAt = props.getProperty("lastPlayedAt")?.toLongOrNull() ?: 0L,
            processingVersion = props.getProperty("processingVersion")?.toIntOrNull() ?: 0,
            filePath = file.absolutePath,
        )
    }

    private fun writeSong(song: HapticSong) {
        val props = Properties().apply {
            setProperty("id", song.id)
            setProperty("title", song.title)
            setProperty("originalName", song.originalName)
            setProperty("artist", song.artist)
            setProperty("durationMs", song.durationMs.toString())
            setProperty("createdAt", song.createdAt.toString())
            setProperty("lastPlayedAt", song.lastPlayedAt.toString())
            setProperty("processingVersion", song.processingVersion.toString())
        }
        val metaFile = File(song.file.parentFile, song.file.nameWithoutExtension + META_EXTENSION)
        val staging = File(metaFile.parentFile, ".${metaFile.name}.writing")
        staging.outputStream().buffered().use { props.store(it, "Sound To Haptics library item") }
        if (!staging.renameTo(metaFile)) {
            staging.copyTo(metaFile, overwrite = true)
            staging.delete()
        }
    }

    private fun sanitizeBaseName(originalName: String): String {
        val withoutExtension = originalName.substringBeforeLast('.', originalName)
        return withoutExtension
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Haptic track" }
            .take(96)
    }
}
