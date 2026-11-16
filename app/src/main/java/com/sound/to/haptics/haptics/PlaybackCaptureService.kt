package com.sound.to.haptics.haptics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sound.to.haptics.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isForeground.value = true
            START_NOT_STICKY
        } catch (e: Exception) {
            CaptureDiagnostics.failure("Foreground capture service failed: ${e.message ?: e.javaClass.simpleName}")
            _isForeground.value = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "haptic_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Haptic capture",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Shows while Sound To Haptics learns a Spotify track." },
                )
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Learning this track's haptics")
            .setContentText("Capturing Spotify playback to build a synchronized profile.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        _isForeground.value = false
        CaptureDiagnostics.projection(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private val _isForeground = MutableStateFlow(false)
        val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()
    }
}
