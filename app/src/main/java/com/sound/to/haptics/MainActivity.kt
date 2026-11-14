package com.sound.to.haptics

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.sound.to.haptics.BuildConfig
import com.sound.to.haptics.debug.DebugFlags
import com.sound.to.haptics.settings.AppSettings
import com.sound.to.haptics.settings.PlaybackActivity
import com.sound.to.haptics.settings.ThemeMode
import com.sound.to.haptics.ui.RootScreen
import com.sound.to.haptics.ui.theme.HapticStudioTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Show the branded splash until the first Compose frame is ready,
        // so the app never flashes an empty window at launch.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)
        // The media notification for BitChord playback needs this on Android 13+;
        // gated to the flavor that actually posts one so offlineOnly stays untouched.
        if (BuildConfig.FLAVOR == "withBitChord" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10_001)
        }
        enableEdgeToEdge()
        setContent {
            // Debug menu's "Monet engine" / "Fallback theme" / "Force dark
            // tonal elevation" toggles live above RootScreen (which is where
            // DebugToolsViewModel is actually scoped), so they're read here
            // straight off the app-global DebugFlags object instead. The
            // user-facing theme choice (Settings → Appearance) is the normal
            // driver; a debug override, when active, wins over it so devs
            // can still force a mode while testing.
            val flags by DebugFlags.featureFlags.collectAsState()
            val userSettings by AppSettings.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val userDark = when (userSettings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            HapticStudioTheme(
                darkTheme = if (flags.forceDarkTonalElevation) true else userDark,
                dynamicColor = userSettings.dynamicColor && flags.monetEngine && !flags.fallbackTheme,
            ) {
                // "Keep screen on while playing": only takes effect when the
                // user has opted in AND something is actually playing right
                // now, so it never keeps the screen on while idle.
                val isPlaying by PlaybackActivity.isPlaying.collectAsState()
                LaunchedEffect(userSettings.keepScreenOnWhilePlaying, isPlaying) {
                    val shouldKeepOn = userSettings.keepScreenOnWhilePlaying && isPlaying
                    if (shouldKeepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RootScreen()
                }
            }
        }
    }
}
