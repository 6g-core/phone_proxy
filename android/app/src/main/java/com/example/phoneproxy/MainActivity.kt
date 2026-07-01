package com.example.phoneproxy

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.phoneproxy.data.RuleRepository
import com.example.phoneproxy.model.ProxyRule
import com.example.phoneproxy.model.udpListenPorts
import com.example.phoneproxy.model.udpListenPortLabel
import com.example.phoneproxy.model.udpTargetPortLabel
import com.example.phoneproxy.model.validateAgainst
import com.example.phoneproxy.net.UsbTetherTarget
import com.example.phoneproxy.net.UsbTetherTargetResolver
import com.example.phoneproxy.service.PhoneProxyRuntime
import com.example.phoneproxy.service.PhoneProxyService
import com.example.phoneproxy.ui.RuleDraft
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val batteryOptimizationIgnoredState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshBatteryOptimizationState()
        val repository = RuleRepository(applicationContext)
        applyDebugIntent(intent, repository)
        setContent {
            MaterialTheme {
                val batteryOptimizationIgnored by batteryOptimizationIgnoredState
                val rules = remember { mutableStateListOf<ProxyRule>().apply { addAll(repository.loadRules()) } }
                var editingDraft by remember { mutableStateOf<RuleDraft?>(null) }
                var editingRuleId by remember { mutableStateOf<String?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var globalUsbAutoEnabled by remember {
                    mutableStateOf(repository.loadGlobalUsbAutoEnabled())
                }
                var deleteModeEnabled by remember { mutableStateOf(false) }
                var selectedRuleIds by remember { mutableStateOf(emptySet<String>()) }
                val notificationLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { }
                val runtimeStatus by PhoneProxyRuntime.status.collectAsState()
                val usbTetherTarget by produceState<UsbTetherTarget?>(initialValue = UsbTetherTargetResolver.resolve()) {
                    while (true) {
                        value = UsbTetherTargetResolver.resolve()
                        delay(3_000)
                    }
                }

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

                PhoneProxyScreen(
                    rules = rules,
                    usbTetherTarget = usbTetherTarget,
                    globalUsbAutoEnabled = globalUsbAutoEnabled,
                    deleteModeEnabled = deleteModeEnabled,
                    selectedRuleIds = selectedRuleIds,
                    isServiceRunning = runtimeStatus.isRunning,
                    serviceMessage = runtimeStatus.message,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    onRequestBatteryOptimization = {
                        requestIgnoreBatteryOptimizations()
                    },
                    onToggleService = { shouldRun ->
                        dispatchServiceAction(
                            if (shouldRun) PhoneProxyService.ACTION_START else PhoneProxyService.ACTION_STOP
                        )
                    },
                    onAddRule = {
                        val suggestedPort = suggestNextListenPort(rules)
                        editingDraft = RuleDraft(
                            name = "Rule $suggestedPort",
                            listenIp = "0.0.0.0",
                            listenPort = suggestedPort.toString(),
                            targetPort = "10001",
                        )
                        editingRuleId = null
                        errorMessage = null
                    },
                    onEditRule = { rule ->
                        editingDraft = RuleDraft.fromRule(rule)
                        editingRuleId = rule.id
                        errorMessage = null
                    },
                    onToggleRule = { rule, enabled ->
                        val index = rules.indexOfFirst { it.id == rule.id }
                        if (index >= 0) {
                            val updated = rules[index].copy(
                                enabled = enabled,
                                usbTetherAutoTarget = false,
                            )
                            val error = updated.validateAgainst(rules.filter { it.id != rule.id })
                            if (error == null) {
                                rules[index] = updated
                                repository.saveRules(rules)
                                reloadServiceIfRunning(runtimeStatus.isRunning)
                            } else {
                                errorMessage = error
                            }
                        }
                    },
                    onToggleGlobalUsbAuto = { enabled ->
                        globalUsbAutoEnabled = enabled
                        repository.saveGlobalUsbAutoEnabled(enabled)
                        if (enabled && usbTetherTarget != null) {
                            val resolvedTargetIp = usbTetherTarget!!.targetIp
                            val updatedRules = rules.map { rule ->
                                rule.copy(
                                    targetIp = resolvedTargetIp,
                                    usbTetherAutoTarget = false,
                                )
                            }
                            rules.clear()
                            rules.addAll(updatedRules)
                            repository.saveRules(updatedRules)
                        }
                        reloadServiceIfRunning(runtimeStatus.isRunning)
                    },
                    onToggleDeleteMode = { enabled ->
                        deleteModeEnabled = enabled
                        if (!enabled) {
                            selectedRuleIds = emptySet()
                        }
                    },
                    onToggleDeleteSelection = { ruleId, selected ->
                        selectedRuleIds = if (selected) {
                            selectedRuleIds + ruleId
                        } else {
                            selectedRuleIds - ruleId
                        }
                    },
                    onConfirmDeleteSelected = {
                        if (selectedRuleIds.isNotEmpty()) {
                            rules.removeAll { it.id in selectedRuleIds }
                            repository.saveRules(rules)
                            selectedRuleIds = emptySet()
                            deleteModeEnabled = false
                            reloadServiceIfRunning(runtimeStatus.isRunning)
                        }
                    },
                )

                editingDraft?.let { draft ->
                    RuleEditorDialog(
                        draft = draft,
                        errorMessage = errorMessage,
                        onDismiss = {
                            editingDraft = null
                            errorMessage = null
                        },
                        onDraftChanged = { editingDraft = it },
                        onSave = {
                            val draftResult = draft.toRule()
                            if (draftResult.isFailure) {
                                errorMessage = draftResult.exceptionOrNull()?.message
                            } else {
                                val rule = draftResult.getOrThrow().copy(
                                    usbTetherAutoTarget = false,
                                    targetIp = if (globalUsbAutoEnabled && usbTetherTarget != null) {
                                        usbTetherTarget!!.targetIp
                                    } else {
                                        draftResult.getOrThrow().targetIp
                                    },
                                )
                                val validatedRule = if (editingRuleId == null) {
                                    rule
                                } else {
                                    rule.copy(id = editingRuleId!!)
                                }
                                val error = validatedRule.validateAgainst(
                                    rules.filter { it.id != validatedRule.id }
                                )
                                if (error != null) {
                                    errorMessage = error
                                } else {
                                    val index = rules.indexOfFirst { it.id == validatedRule.id }
                                    if (index >= 0) {
                                        rules[index] = validatedRule
                                    } else {
                                        rules.add(validatedRule)
                                    }
                                    repository.saveRules(rules)
                                    reloadServiceIfRunning(runtimeStatus.isRunning)
                                    editingDraft = null
                                    editingRuleId = null
                                    errorMessage = null
                                }
                            }
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

    private fun reloadServiceIfRunning(isRunning: Boolean) {
        if (!isRunning) {
            return
        }
        dispatchServiceAction(PhoneProxyService.ACTION_RELOAD)
    }

    private fun dispatchServiceAction(action: String) {
        val intent = Intent(this, PhoneProxyService::class.java).apply {
            this.action = action
        }
        when (action) {
            PhoneProxyService.ACTION_STOP -> startService(intent)
            else -> ContextCompat.startForegroundService(this, intent)
        }
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

    private fun applyDebugIntent(intent: Intent?, repository: RuleRepository) {
        val rawRules = intent?.getStringExtra(EXTRA_DEBUG_RULES_JSON) ?: return
        val shouldStartService = intent.getBooleanExtra(EXTRA_DEBUG_START_SERVICE, true)
        repository.saveRulesJson(rawRules)
        if (shouldStartService) {
            dispatchServiceAction(PhoneProxyService.ACTION_START)
        }
    }

    companion object {
        const val EXTRA_DEBUG_RULES_JSON = "rules_json"
        const val EXTRA_DEBUG_START_SERVICE = "start_service"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PhoneProxyScreen(
    rules: List<ProxyRule>,
    usbTetherTarget: UsbTetherTarget?,
    globalUsbAutoEnabled: Boolean,
    deleteModeEnabled: Boolean,
    selectedRuleIds: Set<String>,
    isServiceRunning: Boolean,
    serviceMessage: String,
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (ProxyRule) -> Unit,
    onToggleRule: (ProxyRule, Boolean) -> Unit,
    onToggleGlobalUsbAuto: (Boolean) -> Unit,
    onToggleDeleteMode: (Boolean) -> Unit,
    onToggleDeleteSelection: (String, Boolean) -> Unit,
    onConfirmDeleteSelected: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Proxy Rules") },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (isServiceRunning) "Running" else "Stopped")
                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = onToggleService,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule) {
                Text("+")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Service Status", style = MaterialTheme.typography.titleMedium)
                        Text(if (isServiceRunning) "Running" else "Stopped")
                        Text(serviceMessage)
                        Text(
                            "Battery Optimization: " +
                                if (batteryOptimizationIgnored) "Exempted" else "Needs exemption",
                        )
                        if (!batteryOptimizationIgnored) {
                            Button(onClick = onRequestBatteryOptimization) {
                                Text("Allow Background")
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Rule Controls", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Auto")
                                Text(
                                    if (globalUsbAutoEnabled) {
                                        usbTetherTarget?.let {
                                            "${it.interfaceName}: ${it.sourceIp} -> ${it.targetIp}"
                                        } ?: "USB tether interface not detected"
                                    } else {
                                        "Use saved target IPs"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = globalUsbAutoEnabled,
                                onCheckedChange = onToggleGlobalUsbAuto,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (deleteModeEnabled) {
                                Button(
                                    onClick = onConfirmDeleteSelected,
                                    enabled = selectedRuleIds.isNotEmpty(),
                                ) {
                                    Text("Delete (${selectedRuleIds.size})")
                                }
                                OutlinedButton(onClick = { onToggleDeleteMode(false) }) {
                                    Text("Cancel")
                                }
                            } else {
                                OutlinedButton(onClick = { onToggleDeleteMode(true) }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
            items(rules, key = { it.id }) { rule ->
                val effectiveTargetIp = if (globalUsbAutoEnabled) {
                    usbTetherTarget?.targetIp ?: rule.targetIp
                } else {
                    rule.targetIp
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                rule.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (deleteModeEnabled) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("Select", style = MaterialTheme.typography.bodySmall)
                                    Checkbox(
                                        checked = selectedRuleIds.contains(rule.id),
                                        onCheckedChange = { onToggleDeleteSelection(rule.id, it) },
                                    )
                                }
                            }
                        }
                        Text("TCP: ${rule.listenIp}:${rule.listenPort} -> $effectiveTargetIp:${rule.targetPort}")
                        Text(
                            "UDP: ${rule.listenIp}:${rule.udpListenPortLabel()} -> " +
                                "$effectiveTargetIp:${rule.udpTargetPortLabel()}"
                        )
                        if (globalUsbAutoEnabled) {
                            Text(
                                usbTetherTarget?.let {
                                    "USB auto: ${it.interfaceName} ${it.sourceIp} -> ${it.targetIp}"
                                } ?: "USB auto: tether interface not detected; using saved target ${rule.targetIp}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RuleToggle(
                                label = "Enabled",
                                checked = rule.enabled,
                                onCheckedChange = { onToggleRule(rule, it) },
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = { onEditRule(rule) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Edit")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    draft: RuleDraft,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onDraftChanged: (RuleDraft) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Edit Rule") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChanged(draft.copy(name = it)) },
                    label = { Text("Rule name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.listenIp,
                    onValueChange = { onDraftChanged(draft.copy(listenIp = it)) },
                    label = { Text("Listen IP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.listenPort,
                    onValueChange = { onDraftChanged(draft.copy(listenPort = it)) },
                    label = { Text("Listen port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.targetIp,
                    onValueChange = { onDraftChanged(draft.copy(targetIp = it)) },
                    label = { Text("Target IP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.targetPort,
                    onValueChange = { onDraftChanged(draft.copy(targetPort = it)) },
                    label = { Text("Target port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("UDP port range (optional 1:1 mapping)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Leave all four UDP fields empty to reuse the TCP listen/target port.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = draft.udpListenPortStart,
                    onValueChange = { onDraftChanged(draft.copy(udpListenPortStart = it)) },
                    label = { Text("UDP listen port start") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.udpListenPortEnd,
                    onValueChange = { onDraftChanged(draft.copy(udpListenPortEnd = it)) },
                    label = { Text("UDP listen port end") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.udpTargetPortStart,
                    onValueChange = { onDraftChanged(draft.copy(udpTargetPortStart = it)) },
                    label = { Text("UDP target port start") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.udpTargetPortEnd,
                    onValueChange = { onDraftChanged(draft.copy(udpTargetPortEnd = it)) },
                    label = { Text("UDP target port end") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = draft.enabled,
                        onCheckedChange = { onDraftChanged(draft.copy(enabled = it)) },
                    )
                    Text("Enabled")
                }
            }
        },
    )
}

@Composable
private fun RuleToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(label)
    }
}

private fun suggestNextListenPort(rules: List<ProxyRule>, basePort: Int = 10002): Int {
    val usedPorts = buildSet {
        rules.filter { it.enabled }.forEach { rule ->
            add(rule.listenPort)
            addAll(rule.udpListenPorts())
        }
    }
    var candidate = basePort
    while (candidate in usedPorts && candidate < 65535) {
        candidate += 1
    }
    return candidate.coerceAtMost(65535)
}
