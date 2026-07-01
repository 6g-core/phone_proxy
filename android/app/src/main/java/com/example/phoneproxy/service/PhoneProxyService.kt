package com.example.phoneproxy.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.phoneproxy.data.RuleRepository
import com.example.phoneproxy.net.UsbTetherRuleResolution
import com.example.phoneproxy.net.UsbTetherTargetResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhoneProxyService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actionMutex = Mutex()
    private val engine = PhoneProxyEngine()
    private var usbAutoMonitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RELOAD, ACTION_APPLY_RULES -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting proxy engine...")
                )
                acquireWakeLock()
                logPowerState("start")
                serviceScope.launch {
                    actionMutex.withLock {
                        val repository = RuleRepository(applicationContext)
                        val storedRules = when (intent.action) {
                            ACTION_APPLY_RULES -> {
                                val rawRules = intent.getStringExtra(EXTRA_RULES_JSON)
                                    ?: throw IllegalArgumentException("Missing rules json.")
                                repository.saveRulesJson(rawRules)
                            }
                            else -> repository.loadRules()
                        }
                        val globalUsbAutoEnabled = repository.loadGlobalUsbAutoEnabled()
                        val resolution = refreshUsbAutoTargets(
                            repository = repository,
                            storedRules = storedRules,
                            globalUsbAutoEnabled = globalUsbAutoEnabled,
                        )
                        val rules = resolution.rules
                        try {
                            engine.start(rules)
                            val enabledRuleCount = rules.count { it.enabled }
                            ensureUsbAutoMonitor(repository, resolution, globalUsbAutoEnabled)
                            notifyForeground(
                                buildNotification(
                                    buildRunningMessage(
                                        enabledRuleCount = enabledRuleCount,
                                        resolution = resolution,
                                        globalUsbAutoEnabled = globalUsbAutoEnabled,
                                    )
                                )
                            )
                        } catch (exc: Exception) {
                            Log.e(TAG, "Failed to start proxy engine", exc)
                            usbAutoMonitorJob?.cancel()
                            usbAutoMonitorJob = null
                            releaseWakeLock()
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
                        usbAutoMonitorJob?.cancel()
                        usbAutoMonitorJob = null
                        engine.stop()
                        releaseWakeLock()
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
                usbAutoMonitorJob?.cancel()
                usbAutoMonitorJob = null
                engine.stop()
            }
        }
        serviceScope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(contentText: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Proxy")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
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

    private fun acquireWakeLock() {
        val existingWakeLock = wakeLock
        if (existingWakeLock?.isHeld == true) {
            return
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:phone-proxy")
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

    private fun buildRunningMessage(
        enabledRuleCount: Int,
        resolution: UsbTetherRuleResolution,
        globalUsbAutoEnabled: Boolean,
    ): String {
        if (!globalUsbAutoEnabled) {
            return "Running with $enabledRuleCount enabled rule(s)."
        }
        val resolved = resolution.resolvedTarget
        return if (resolved != null) {
            "Running with $enabledRuleCount enabled rule(s). USB ${resolved.interfaceName}: ${resolved.sourceIp} -> ${resolved.targetIp}"
        } else {
            "Running with $enabledRuleCount enabled rule(s). USB tether target not detected; using saved target IP."
        }
    }

    private fun refreshUsbAutoTargets(
        repository: RuleRepository,
        storedRules: List<com.example.phoneproxy.model.ProxyRule>,
        globalUsbAutoEnabled: Boolean,
    ): UsbTetherRuleResolution {
        val resolution = UsbTetherTargetResolver.applyToRules(
            rules = storedRules,
            enabled = globalUsbAutoEnabled,
        )
        if (resolution.changed) {
            repository.saveRules(resolution.rules)
        }
        return resolution
    }

    private fun ensureUsbAutoMonitor(
        repository: RuleRepository,
        initialResolution: UsbTetherRuleResolution,
        globalUsbAutoEnabled: Boolean,
    ) {
        usbAutoMonitorJob?.cancel()
        if (!globalUsbAutoEnabled) {
            usbAutoMonitorJob = null
            return
        }
        usbAutoMonitorJob = serviceScope.launch {
            var lastKnownTargetIp = initialResolution.resolvedTarget?.targetIp
            while (true) {
                delay(USB_AUTO_REFRESH_INTERVAL_MS)
                actionMutex.withLock {
                    val storedRules = repository.loadRules()
                    val currentGlobalUsbAutoEnabled = repository.loadGlobalUsbAutoEnabled()
                    if (!currentGlobalUsbAutoEnabled) {
                        usbAutoMonitorJob?.cancel()
                        usbAutoMonitorJob = null
                        return@withLock
                    }
                    val resolution = refreshUsbAutoTargets(
                        repository = repository,
                        storedRules = storedRules,
                        globalUsbAutoEnabled = currentGlobalUsbAutoEnabled,
                    )
                    val currentTargetIp = resolution.resolvedTarget?.targetIp
                    if (resolution.changed || currentTargetIp != lastKnownTargetIp) {
                        engine.start(resolution.rules)
                        val enabledRuleCount = resolution.rules.count { it.enabled }
                        notifyForeground(
                            buildNotification(
                                buildRunningMessage(
                                    enabledRuleCount = enabledRuleCount,
                                    resolution = resolution,
                                    globalUsbAutoEnabled = currentGlobalUsbAutoEnabled,
                                )
                            )
                        )
                    }
                    lastKnownTargetIp = currentTargetIp
                }
            }
        }
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
        private const val USB_AUTO_REFRESH_INTERVAL_MS = 3_000L
    }
}
