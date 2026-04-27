package com.example.phoneproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.phoneproxy.data.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhoneProxyService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actionMutex = Mutex()
    private val engine = PhoneProxyEngine()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RELOAD -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting proxy engine...")
                )
                serviceScope.launch {
                    actionMutex.withLock {
                        val repository = RuleRepository(applicationContext)
                        val rules = when (intent.action) {
                            ACTION_APPLY_RULES -> {
                                val rawRules = intent.getStringExtra(EXTRA_RULES_JSON)
                                    ?: throw IllegalArgumentException("Missing rules json.")
                                repository.saveRulesJson(rawRules)
                            }
                            else -> repository.loadRules()
                        }
                        try {
                            engine.start(rules)
                            val enabledRuleCount = rules.count { it.enabled }
                            notifyForeground(
                                buildNotification(
                                    "Running with $enabledRuleCount enabled rule(s)."
                                )
                            )
                        } catch (exc: Exception) {
                            Log.e(TAG, "Failed to start proxy engine", exc)
                            PhoneProxyRuntime.update(
                                ProxyRuntimeStatus(
                                    isRunning = false,
                                    enabledRuleCount = 0,
                                    message = "Start failed: ${exc.message}",
                                )
                            )
                            notifyForeground(
                                buildNotification("Start failed: ${exc.message ?: "unknown error"}")
                            )
                        }
                    }
                }
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    actionMutex.withLock {
                        engine.stop()
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            actionMutex.withLock {
                engine.stop()
            }
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(contentText: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Proxy")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .build()
    }

    private fun notifyForeground(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone Proxy",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.phoneproxy.action.START"
        const val ACTION_STOP = "com.example.phoneproxy.action.STOP"
        const val ACTION_RELOAD = "com.example.phoneproxy.action.RELOAD"
        const val ACTION_APPLY_RULES = "com.example.phoneproxy.action.APPLY_RULES"
        const val EXTRA_RULES_JSON = "rules_json"
        private const val CHANNEL_ID = "phone_proxy_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "PhoneProxyService"
    }
}
