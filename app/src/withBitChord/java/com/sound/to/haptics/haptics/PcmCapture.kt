package com.sound.to.haptics.haptics

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records the decoded audio ExoPlayer is about to render — not the device
 * output, not the microphone — into a scratch file that the haptic pipeline
 * converts just like the offline capture path did.
 *
 * Bytes arrive from [HapticPcmTapAudioProcessor] on the audio thread at the
 * decoder's rate (usually 44.1 or 48 kHz). They go to an unbounded queue
 * drained by a writer thread — the queue is fed at the rate the audio is
 * produced and drained at disk speed, so it never holds more than a second
 * or two in practice (≈350 KB), and unlike a bounded queue, it can never
 * drop a chunk and silently shorten the learned profile — any such chunk
 * would corrupt every timestamp after it.
 *
 * The track timeline is the authority for sync. Frames simply stop flowing
 * while the player is paused, so [recordHole] notes the stretch of track
 * timeline for which no audio flowed; [finish] backfills those holes with
 * silence so the cached haptic profile spans the whole track. The anchor is
 * computed from the player's own timeline, not from the writer's position, so
 * a hole can never displace audio that was already decoded.
 *
 * Seeks backwards cannot be represented and are aborted by the caller; the
 * partial pass is discarded.
 */
class PcmCapture {

    data class Result(
        val pcmFile: File,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMs: Long,
        val hadSignal: Boolean,
    )

    private data class Hole(val startFrame: Long, val lengthFrames: Long)

    @Volatile private var writerThread: Thread? = null
    @Volatile private var queue: LinkedBlockingQueue<ByteArray>? = null
    @Volatile private var running = false

    private var outFile: File? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var frameBytes = 0

    @Volatile var hadSignal: Boolean = false
        private set
    @Volatile var framesWritten: Long = 0L
        private set

    private val holes = mutableListOf<Hole>()

    /** VU level for the UI, 0..1; read on any thread. */
    @Volatile var level: Float = 0f
        private set

    private var smoothedLevel = 0f
    private var startedAtElapsedMs = 0L
    private var initialOffsetFrames = 0L
    private var diagnosticBytes = 0L

    fun isCapturing(): Boolean = running

    /**
     * @param outFile scratch file to write raw s16le PCM into.
     * @param initialOffsetMs how far into the track capture begins; silence is
     *   prepended for the stretch before this point so the file spans the
     *   whole timeline.
     */
    fun start(outFile: File, sampleRate: Int, channelCount: Int, initialOffsetMs: Long): Boolean {
        if (running) return false
        this.outFile = outFile
        this.sampleRate = sampleRate.coerceAtLeast(1)
        this.channelCount = channelCount.coerceAtLeast(1)
        this.frameBytes = this.channelCount * 2
        this.initialOffsetFrames = initialOffsetMs * this.sampleRate / 1000L
        holes.clear()
        hadSignal = false
        framesWritten = 0L
        diagnosticBytes = 0L
        smoothedLevel = 0f
        level = 0f
        startedAtElapsedMs = SystemClock.elapsedRealtime()

        val q = LinkedBlockingQueue<ByteArray>()
        queue = q
        running = true

        writerThread = Thread({
            try {
                FileOutputStream(outFile, false).use { stream ->
                    writeSilence(stream, initialOffsetFrames)
                    while (running || q.isNotEmpty()) {
                        val chunk = q.poll(250, TimeUnit.MILLISECONDS) ?: continue
                        stream.write(chunk)
                        framesWritten += chunk.size / frameBytes
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PcmCapture writer failed", e)
            } finally {
                running = false
            }
        }, "bitchord-pcm-writer").apply { isDaemon = true; start() }
        return true
    }

    /** Called on the audio thread; never blocks on disk. */
    fun offer(chunk: ByteArray) {
        val q = queue ?: return
        if (!running) return
        q.offer(chunk)

        diagnosticBytes += chunk.size
        if (diagnosticBytes >= 64 * 1024) {
            diagnosticBytes = 0
            CaptureDiagnostics.progress(framesWritten * frameBytes, hadSignal)
        }

        val channelCountSafe = channelCount.coerceAtLeast(1)
        var sumSquares = 0.0
        var frames = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = (chunk[i].toInt() and 0xFF or (chunk[i + 1].toInt() shl 8)).let {
                if (it >= 0x8000) it - 0x10000 else it
            } / 32768f
            sumSquares += (sample * sample).toDouble()
            frames++
            i += 2 * channelCountSafe
        }
        if (frames > 0) {
            val rms = sqrt(sumSquares / frames).toFloat()
            if (rms > SIGNAL_THRESHOLD) hadSignal = true
            smoothedLevel = smoothedLevel * 0.72f + rms * 0.28f
            level = min(1f, smoothedLevel * 8f)
        }
    }

    /**
     * Notes a region of the track timeline for which no audio flowed.
     *
     * [holeFromPlayerMs] and [holeToPlayerMs] are the player positions
     * bracketing the gap, on the absolute track timeline. The anchor is
     * computed from that timeline — never from the writer's position — so a
     * hole can never displace audio that is already sitting in the file's
     * queue.
     */
    @Synchronized
    fun recordHole(holeFromPlayerMs: Long, holeToPlayerMs: Long) {
        if (!running) return
        if (holeToPlayerMs <= holeFromPlayerMs) return
        holes += Hole(
            startFrame = initialOffsetFrames + holeFromPlayerMs * sampleRate / 1000L,
            lengthFrames = (holeToPlayerMs - holeFromPlayerMs) * sampleRate / 1000L,
        )
        Log.d(TAG, "recordHole: from=$holeFromPlayerMs to=$holeToPlayerMs")
    }

    /**
     * Stops the pass and drains the writer. Returns the punched/validated
     * [Result], or null when nothing usable was captured — the caller must
     * never see a partially-written file. Intended to run on a background
     * thread (it waits on the writer).
     */
    fun finish(): Result? {
        running = false
        val q = queue
        queue = null
        // Do not clear q: the writer drains what is still queued, then exits.
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (writerThread?.isAlive == true && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
        val writer = writerThread
        writerThread = null
        if (writer?.isAlive == true) {
            // The writer never finished; the file may be appended to at any
            // moment. Do not touch it.
            Log.e(TAG, "writer drain deadline expired; discarding capture")
            writer.interrupt()
            outFile?.takeIf { it.exists() }?.delete()
            outFile = null
            holes.clear()
            return null
        }
        val file = outFile ?: return null
        outFile = null
        if (!file.exists() || file.length() < 4096L) {
            file.delete()
            return null
        }
        if (holes.isNotEmpty()) insertHoles(file)
        // Report file-format facts to the debug panel before trusting the data.
        val validation = PcmValidator.validate(file, sampleRate, channelCount)
        CaptureDiagnostics.pcmValidated(validation)
        val durationMs = file.length() / frameBytes / (sampleRate.toDouble() / 1000.0)
        CaptureDiagnostics.finished(file.length(), hadSignal)
        if (!validation.valid || !hadSignal) {
            file.delete()
            return null
        }
        return Result(
            pcmFile = file,
            sampleRate = sampleRate,
            channelCount = channelCount,
            durationMs = durationMs.toLong(),
            hadSignal = hadSignal,
        )
    }

    /** Discards the pass. */
    fun cancel() {
        running = false
        val q = queue
        queue = null
        q?.clear()
        writerThread?.let { thread -> thread.join(1_000L) }
        writerThread = null
        outFile?.takeIf { it.exists() }?.delete()
        outFile = null
        framesWritten = 0L
        holes.clear()
        level = 0f
        CaptureDiagnostics.reset()
    }

    private fun insertHoles(file: File) {
        val sorted = holes.sortedBy { it.startFrame }
        val tmp = File(file.parentFile, file.name + ".holes.tmp")
        val bounce = ByteArray(BUFFER)
        val zero = ByteArray(16 * 1024)
        try {
            BufferedInputStream(FileInputStream(file), BUFFER).use { input ->
                BufferedOutputStream(FileOutputStream(tmp), BUFFER).use { output ->
                    var copiedBytes = 0L
                    sorted.forEach { hole ->
                        val copyBytes = (hole.startFrame * frameBytes - copiedBytes).coerceAtLeast(0)
                        copyExactly(input, output, copyBytes, bounce)
                        copiedBytes += copyBytes
                        var remaining = hole.lengthFrames * frameBytes
                        while (remaining > 0) {
                            val count = min(remaining, zero.size.toLong()).toInt()
                            output.write(zero, 0, count)
                            remaining -= count
                        }
                    }
                    if (file.length() > copiedBytes) {
                        copyExactly(input, output, file.length() - copiedBytes, bounce)
                    }
                }
            }
            if (tmp.exists()) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "insertHoles failed; keeping the unpatched file", e)
            tmp.takeIf { it.exists() }?.delete()
        }
        framesWritten = file.length() / frameBytes
        Log.d(TAG, "insertHoles: ${sorted.size} hole(s) applied")
    }

    /** Copies exactly [bytes] bytes, or stops at end-of-input. */
    private fun copyExactly(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        bytes: Long,
        bounce: ByteArray,
    ) {
        var remaining = bytes
        while (remaining > 0) {
            val count = min(remaining, bounce.size.toLong()).toInt()
            val read = input.read(bounce, 0, count)
            if (read <= 0) return
            output.write(bounce, 0, read)
            remaining -= read
        }
    }

    private fun writeSilence(stream: FileOutputStream, frames: Long) {
        if (frames <= 0) return
        val zeros = ByteArray(16 * 1024)
        var remaining = frames * frameBytes
        while (remaining > 0) {
            val count = min(remaining, zeros.size.toLong()).toInt()
            stream.write(zeros, 0, count)
            framesWritten += count / frameBytes
            remaining -= count
        }
    }

    companion object {
        private const val TAG = "PcmCapture"
        private const val SIGNAL_THRESHOLD = 0.002f
        private const val BUFFER = 1024 * 1024
    }
}
