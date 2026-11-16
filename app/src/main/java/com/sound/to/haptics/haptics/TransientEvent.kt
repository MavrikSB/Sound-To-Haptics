package com.sound.to.haptics.haptics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

enum class TransientEventType {

    KICK,

    SNARE,

    HIHAT,

    PERCUSSIVE_OTHER,

    BASS_BED,
}

enum class TransientLayer {

    BED,

    ACCENT,

    TEXTURE,
}

data class TransientEvent(
    val timestampMs: Long,
    val type: TransientEventType,
    val layer: TransientLayer,
    val intensity: Float,
    val sharpness: Float,
    val durationMs: Long,
    val isMajorAccent: Boolean = false,
)

data class TransientAnalysis(
    val version: Int,
    val sampleRate: Int,
    val durationMs: Long,
    val bpm: Float,
    val beatConfidence: Float,
    val events: List<TransientEvent>,
)

private const val TRANSIENT_MAGIC = 0x54484150

fun TransientAnalysis.saveTo(file: File) {
    file.parentFile?.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(TRANSIENT_MAGIC)
        out.writeInt(version)
        out.writeInt(sampleRate)
        out.writeLong(durationMs)
        out.writeFloat(bpm)
        out.writeFloat(beatConfidence)
        out.writeInt(events.size)
        events.forEach { event ->
            out.writeLong(event.timestampMs)
            out.writeInt(event.type.ordinal)
            out.writeInt(event.layer.ordinal)
            out.writeFloat(event.intensity)
            out.writeFloat(event.sharpness)
            out.writeLong(event.durationMs)
            out.writeBoolean(event.isMajorAccent)
        }
    }
}

fun loadTransientAnalysis(file: File): TransientAnalysis? {
    if (!file.exists() || file.length() < 24L) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != TRANSIENT_MAGIC) return null
            val version = input.readInt()
            if (version != TransientConfig.PROCESSING_VERSION) return null
            val sampleRate = input.readInt()
            val durationMs = input.readLong()
            val bpm = input.readFloat()
            val beatConfidence = input.readFloat()
            val count = input.readInt().coerceIn(0, 500_000)
            val events = List(count) {
                val timestamp = input.readLong()
                val type = TransientEventType.entries.getOrElse(input.readInt()) { TransientEventType.PERCUSSIVE_OTHER }
                val layer = TransientLayer.entries.getOrElse(input.readInt()) { TransientLayer.ACCENT }
                val intensity = input.readFloat()
                val sharpness = input.readFloat()
                val duration = input.readLong().coerceIn(1L, 4_000L)
                val isMajor = input.readBoolean()
                TransientEvent(timestamp, type, layer, intensity, sharpness, duration, isMajor)
            }
            TransientAnalysis(version, sampleRate, durationMs, bpm, beatConfidence, events)
        }
    } catch (_: Exception) {
        null
    }
}
