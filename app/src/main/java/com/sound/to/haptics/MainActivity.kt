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

        installSplashScreen()
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)

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
