package com.example.phonevoice.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.phonevoice.R
import com.example.phonevoice.config.VoiceSettingsRepository
import com.example.phonevoice.http.ForwardPostClient
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
    private lateinit var settingsRepository: VoiceSettingsRepository
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsRepository = VoiceSettingsRepository(this)

        ttsManager = TtsManager(this) { ready ->
            stateStore.setTtsReady(ready)
        }
        ttsManager.initialize()

        controller = VoiceRuntimeController(
            stateStore = stateStore,
            dispatcher = ttsManager,
            lanIpProvider = { LocalIpResolver.resolveLanIpv4() },
            settingsProvider = { settingsRepository.load() },
            serverFactory = { settings ->
                WordToVoiceServer(
                    handler = WordToVoiceRequestHandler(
                        dispatcher = ttsManager,
                        settingsProvider = { settingsRepository.load() },
                        forwarder = ForwardPostClient(),
                        requestPath = settings.normalizedListenPath(),
                        forwardRequestPath = settings.normalizedSpeechForwardPath(),
                        onAcceptedText = { text -> stateStore.setLastText(text) },
                    ),
                    listenHost = settings.normalizedListenHost(),
                    listenPort = settings.normalizedListenPort(),
                )
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireWakeLock()
                logPowerState("start")
                controller.start()
                controller.refreshTtsReady()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        ttsManager.shutdown()
        releaseWakeLock()
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

    private fun acquireWakeLock() {
        val existingWakeLock = wakeLock
        if (existingWakeLock?.isHeld == true) {
            return
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice-http")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        val existingWakeLock = wakeLock
        if (existingWakeLock?.isHeld == true) {
            existingWakeLock.release()
            Log.i(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun logPowerState(reason: String) {
        Log.i(
            TAG,
            "powerState reason=$reason ignoringBatteryOptimizations=${isIgnoringBatteryOptimizations()} " +
                "notificationPermissionGranted=${notificationPermissionGranted()} " +
                "wakeLockHeld=${wakeLock?.isHeld == true}",
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
    }

    private fun notificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "VoiceHttpForegroundService"
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
