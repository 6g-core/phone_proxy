package com.example.phonevoice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.phonevoice.R
import com.example.phonevoice.http.WordToVoiceRequestHandler
import com.example.phonevoice.http.WordToVoiceServer
import com.example.phonevoice.net.LocalIpResolver
import com.example.phonevoice.runtime.VoiceRuntimeController
import com.example.phonevoice.state.VoiceServiceStateStore
import com.example.phonevoice.tts.TtsManager

class VoiceHttpForegroundService : Service() {
    private val stateStore = sharedStateStore
    private lateinit var ttsManager: TtsManager
    private lateinit var controller: VoiceRuntimeController

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        ttsManager = TtsManager(this) { ready ->
            stateStore.setTtsReady(ready)
        }
        ttsManager.initialize()

        controller = VoiceRuntimeController(
            stateStore = stateStore,
            dispatcher = ttsManager,
            lanIpProvider = { LocalIpResolver.resolveLanIpv4() },
            serverFactory = {
                WordToVoiceServer(
                    handler = WordToVoiceRequestHandler(
                        dispatcher = ttsManager,
                        onAcceptedText = { text -> stateStore.setLastText(text) },
                    ),
                )
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                controller.start()
                controller.refreshTtsReady()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        ttsManager.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "word2voice_service"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.example.phonevoice.action.START"
        private const val ACTION_STOP = "com.example.phonevoice.action.STOP"

        val sharedStateStore = VoiceServiceStateStore()

        fun startIntent(context: Context): Intent {
            return Intent(context, VoiceHttpForegroundService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, VoiceHttpForegroundService::class.java).setAction(ACTION_STOP)
        }
    }
}
