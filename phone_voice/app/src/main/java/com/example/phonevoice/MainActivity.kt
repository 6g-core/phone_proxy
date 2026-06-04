package com.example.phonevoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.phonevoice.config.VoiceRuntimeSettings
import com.example.phonevoice.config.VoiceSettingsRepository
import com.example.phonevoice.service.VoiceHttpForegroundService
import com.example.phonevoice.state.VoiceUiState

class MainActivity : ComponentActivity() {
    private val batteryOptimizationIgnoredState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshBatteryOptimizationState()
        setContent {
            val state by VoiceHttpForegroundService.sharedStateStore.state.collectAsStateWithLifecycle()
            val batteryOptimizationIgnored by batteryOptimizationIgnoredState
            val settingsRepository = remember { VoiceSettingsRepository(this@MainActivity) }
            val initialSettings = remember { settingsRepository.load() }
            var listenUrl by remember { mutableStateOf(initialSettings.listenUrl) }
            var speechForwardUrl by remember { mutableStateOf(initialSettings.speechForwardUrl) }
            var forwardUrl by remember { mutableStateOf(initialSettings.forwardUrl) }
            var forwardTimeoutMillis by remember {
                mutableStateOf(initialSettings.normalizedForwardTimeoutMillis().toString())
            }
            val notificationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            MaterialTheme {
                Surface {
                    VoiceScreen(
                        state = state,
                        batteryOptimizationIgnored = batteryOptimizationIgnored,
                        listenUrl = listenUrl,
                        speechForwardUrl = speechForwardUrl,
                        forwardUrl = forwardUrl,
                        forwardTimeoutMillis = forwardTimeoutMillis,
                        onRequestBatteryOptimization = {
                            requestIgnoreBatteryOptimizations()
                        },
                        onListenUrlChange = { value ->
                            listenUrl = value
                            settingsRepository.save(
                                VoiceRuntimeSettings(
                                    listenUrl = value,
                                    speechForwardUrl = speechForwardUrl,
                                    forwardUrl = forwardUrl,
                                    forwardTimeoutMillis = VoiceRuntimeSettings
                                        .parseForwardTimeoutMillis(forwardTimeoutMillis),
                                ),
                            )
                        },
                        onSpeechForwardUrlChange = { value ->
                            speechForwardUrl = value
                            settingsRepository.save(
                                VoiceRuntimeSettings(
                                    listenUrl = listenUrl,
                                    speechForwardUrl = value,
                                    forwardUrl = forwardUrl,
                                    forwardTimeoutMillis = VoiceRuntimeSettings
                                        .parseForwardTimeoutMillis(forwardTimeoutMillis),
                                ),
                            )
                        },
                        onForwardUrlChange = { value ->
                            forwardUrl = value
                            settingsRepository.save(
                                VoiceRuntimeSettings(
                                    listenUrl = listenUrl,
                                    speechForwardUrl = speechForwardUrl,
                                    forwardUrl = value,
                                    forwardTimeoutMillis = VoiceRuntimeSettings
                                        .parseForwardTimeoutMillis(forwardTimeoutMillis),
                                ),
                            )
                        },
                        onForwardTimeoutMillisChange = { value ->
                            forwardTimeoutMillis = value
                            settingsRepository.save(
                                VoiceRuntimeSettings(
                                    listenUrl = listenUrl,
                                    speechForwardUrl = speechForwardUrl,
                                    forwardUrl = forwardUrl,
                                    forwardTimeoutMillis = VoiceRuntimeSettings
                                        .parseForwardTimeoutMillis(value),
                                ),
                            )
                        },
                        onStart = {
                            settingsRepository.save(
                                VoiceRuntimeSettings(
                                    listenUrl = listenUrl,
                                    speechForwardUrl = speechForwardUrl,
                                    forwardUrl = forwardUrl,
                                    forwardTimeoutMillis = VoiceRuntimeSettings
                                        .parseForwardTimeoutMillis(forwardTimeoutMillis),
                                ),
                            )
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                VoiceHttpForegroundService.startIntent(this@MainActivity),
                            )
                        },
                        onStop = {
                            startService(
                                VoiceHttpForegroundService.stopIntent(this@MainActivity),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptimizationState()
    }

    private fun refreshBatteryOptimizationState() {
        batteryOptimizationIgnoredState.value = isIgnoringBatteryOptimizations()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations()) {
            refreshBatteryOptimizationState()
            return
        }

        val packageUri = Uri.parse("package:$packageName")
        val openedRequest = startActivitySafely(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
        )
        if (!openedRequest) {
            startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        refreshBatteryOptimizationState()
    }

    private fun startActivitySafely(intent: Intent): Boolean {
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

@Composable
private fun VoiceScreen(
    state: VoiceUiState,
    batteryOptimizationIgnored: Boolean,
    listenUrl: String,
    speechForwardUrl: String,
    forwardUrl: String,
    forwardTimeoutMillis: String,
    onRequestBatteryOptimization: () -> Unit,
    onListenUrlChange: (String) -> Unit,
    onSpeechForwardUrlChange: (String) -> Unit,
    onForwardUrlChange: (String) -> Unit,
    onForwardTimeoutMillisChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val topGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFFF2D8),
            Color(0xFFFFD8B8),
            Color(0xFFFFF8EF),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(topGradient)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A44)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Word2Voice",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Local LAN speech trigger for Android phones",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE9EEF7),
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF5)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusRow(label = "Status", value = state.status.name)
                StatusRow(label = "Speech URL", value = state.lanUrl)
                StatusRow(label = "Speech + Forward URL", value = state.forwardLanUrl)
                StatusRow(label = "TTS Ready", value = state.ttsReady.toString())
                StatusRow(
                    label = "Battery Optimization",
                    value = if (batteryOptimizationIgnored) "Exempted" else "Needs exemption",
                )
                if (!batteryOptimizationIgnored) {
                    OutlinedButton(
                        onClick = onRequestBatteryOptimization,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Allow Background")
                    }
                }
                StatusRow(
                    label = "Last Text",
                    value = state.lastText.ifBlank { "None" },
                )
                if (state.lastError.isNotBlank()) {
                    StatusRow(label = "Last Error", value = state.lastError)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF5)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Runtime Config",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF111827),
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = listenUrl,
                    onValueChange = onListenUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Speech URL") },
                    placeholder = { Text("Auto LAN URL") },
                )
                OutlinedTextField(
                    value = speechForwardUrl,
                    onValueChange = onSpeechForwardUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Speech + Forward URL") },
                    placeholder = { Text("Auto LAN URL") },
                )
                OutlinedTextField(
                    value = forwardUrl,
                    onValueChange = onForwardUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Forward Target URL") },
                    placeholder = { Text("Disabled") },
                )
                OutlinedTextField(
                    value = forwardTimeoutMillis,
                    onValueChange = onForwardTimeoutMillisChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Forward Timeout (ms)") },
                    placeholder = { Text("3000") },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Start Service")
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Stop Service")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0x66FFFFFF),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(18.dp),
        ) {
            Text(
                text = "Send POST JSON with text to the speech URL. Send text and arm_body to the speech + forward URL when forwarding is needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF2D3748),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF6B7280),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF111827),
        )
    }
}
