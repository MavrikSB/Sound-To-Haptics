package com.sound.to.haptics.haptics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class HapticEnvelope(
    val timingsMs: LongArray,
    val amplitudes: IntArray,
)

fun HapticEnvelope.saveTo(file: File) {
    file.parentFile?.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(timingsMs.size)
        for (i in timingsMs.indices) {
            out.writeLong(timingsMs[i])
            out.writeInt(amplitudes[i])
        }
    }
}

fun loadHapticEnvelope(file: File): HapticEnvelope? {
    if (!file.exists()) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val count = input.readInt()
            if (count <= 0) return null
            val timings = LongArray(count)
            val amplitudes = IntArray(count)
            for (i in 0 until count) {
                timings[i] = input.readLong()
                amplitudes[i] = input.readInt()
            }
            HapticEnvelope(timings, amplitudes)
        }
    } catch (e: Exception) {
        null
    }
}

fun HapticEnvelope.sliceFrom(positionMs: Long): Pair<LongArray, IntArray>? {
    if (positionMs < 0) return null
    var cumulative = 0L
    var startIndex = -1
    for (i in timingsMs.indices) {
        if (cumulative + timingsMs[i] > positionMs) {
            startIndex = i
            break
        }
        cumulative += timingsMs[i]
    }
    if (startIndex < 0) return null

    val offsetIntoFirst = (positionMs - cumulative).coerceAtLeast(0)
    val remainingTimings = timingsMs.copyOfRange(startIndex, timingsMs.size)
    remainingTimings[0] = (remainingTimings[0] - offsetIntoFirst).coerceAtLeast(1L)
    val remainingAmplitudes = amplitudes.copyOfRange(startIndex, amplitudes.size)
    return remainingTimings to remainingAmplitudes
}
