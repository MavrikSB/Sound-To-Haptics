package com.sound.to.haptics.haptics

import java.util.Locale

object HapticAudioShaping {

    fun hapticGain(sensitivity: Float): String =
        "%.2f".format(Locale.US, sensitivity.coerceIn(0f, 1f) * 1.2f)

    fun lowpassHz(bassBoost: Float): Int =
        ((1f - bassBoost.coerceIn(0f, 1f)) * 720f + 80f).toInt()

    fun densityMix(density: Float): String =
        "%.2f".format(Locale.US, 0.5f + density.coerceIn(0f, 1f) * 0.15f)

    fun bassBoostDb(bassBoost: Float): String =
        "%.1f".format(Locale.US, bassBoost.coerceIn(0f, 1f) * 9f)

    fun filterChain(
        sensitivity: Float,
        bassBoost: Float,
        density: Float,
        inputLabel: String,
        outputLabel: String,
    ): String {
        val gain = hapticGain(sensitivity)
        val lp = lowpassHz(bassBoost)
        val mix = densityMix(density)
        val bassDb = bassBoostDb(bassBoost)

        return "$inputLabel" +
            "asplit=3[bassIn][impactIn][flowIn];" +
            "[bassIn]lowpass=f=$lp,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "bass=g=$bassDb:f=120:w=0.6," +
            "acompressor=threshold=0.08:ratio=7:attack=10:release=110:makeup=3[bass];" +
            "[impactIn]highpass=f=1200,lowpass=f=8500,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "acompressor=threshold=0.06:ratio=10:attack=3:release=72:makeup=5,volume=1.05[impact];" +

            "[flowIn]highpass=f=180,lowpass=f=3200,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "acompressor=threshold=0.14:ratio=3:attack=28:release=260:makeup=2,volume=0.42[flow];" +
            "[bass][impact][flow]amix=inputs=3:weights=1.0 0.72 0.62:normalize=0," +

            "agate=threshold=0.018:ratio=2.5:attack=15:release=180," +
            "volume=$gain,alimiter=limit=1.0:attack=5:release=70," +

            "aresample=44100:resampler=soxr:precision=28,asetpts=N/SR/TB," +
            "aformat=sample_fmts=fltp:channel_layouts=mono" +
            outputLabel
    }

    fun analysisFilterChain(bassBoost: Float, density: Float, inputLabel: String, outputLabel: String): String {
        val lp = lowpassHz(bassBoost)
        val mix = densityMix(density)
        return "$inputLabel" +
            "lowpass=f=$lp,pan=mono|c0=${mix}*c0+${mix}*c1," +
            "aresample=44100:async=1,aformat=sample_fmts=s16:channel_layouts=mono" +
            outputLabel
    }
}
