package com.sound.to.haptics.bitchord

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.sound.to.haptics.BuildConfig
import com.sound.to.haptics.bitchord.data.MonotonicClock
import com.sound.to.haptics.bitchord.data.TrackLog
import com.sound.to.haptics.bitchord.data.innertube.InnerTubeXResolver
import com.sound.to.haptics.bitchord.data.innertube.StreamResolver
import com.sound.to.haptics.bitchord.data.innertube.potoken.PoTokenGenerator
import com.sound.to.haptics.bitchord.data.innertube.potoken.PoTokenWebView

object BitChordStreamHooks {

    private const val TAG = "BitChord"

    @Volatile private var installed = false

    fun ensureInstalled(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            MonotonicClock.nowMs = SystemClock::elapsedRealtime

            if (BuildConfig.DEBUG) {
                TrackLog.echo = { level, tag, message, error ->
                    when (level) {
                        'D' -> Log.d(tag, message, error)
                        'I' -> Log.i(tag, message, error)
                        'W' -> Log.w(tag, message, error)
                        else -> Log.e(tag, message, error)
                    }
                }
            }
            StreamResolver.maxKbps = { Int.MAX_VALUE }
            InnerTubeXResolver.debugLogging = BuildConfig.DEBUG
            installed = true
        }
    }

    fun initInnerTubeX(context: Context) {
        ensureInstalled(context)
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

            val webViewSupported by lazy { runCatching { android.webkit.CookieManager.getInstance() }.isSuccess }
            val poTokens = PoTokenGenerator(
                createMinter = { PoTokenWebView.getNewPoTokenGenerator(app) },
                available = { webViewSupported },
            )
            InnerTubeXResolver.init(
                filesDir = app.filesDir,
                store = object : InnerTubeXResolver.ConfigStore {
                    override fun getString(key: String): String? = prefs.getString(key, null)
                    override fun getLong(key: String): Long? =
                        if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                    override fun putString(key: String, value: String) =
                        prefs.edit().putString(key, value).apply()
                    override fun putLong(key: String, value: Long) =
                        prefs.edit().putLong(key, value).apply()
                },
                poTokenProvider = poTokens.asTokenProvider(),
            )
        }.onFailure { Log.e(TAG, "InnerTubeX init failed; age-restricted tracks may be unplayable", it) }
    }
}
