package com.example.phoneproxy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.phoneproxy.data.RuleRepository
import com.example.phoneproxy.model.ProxyRule
import com.example.phoneproxy.model.udpListenPorts
import com.example.phoneproxy.model.udpListenPortLabel
import com.example.phoneproxy.model.udpTargetPortLabel
import com.example.phoneproxy.model.validateAgainst
import com.example.phoneproxy.service.PhoneProxyRuntime
import com.example.phoneproxy.service.PhoneProxyService
import com.example.phoneproxy.ui.RuleDraft

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = RuleRepository(applicationContext)
        applyDebugIntent(intent, repository)
        setContent {
            MaterialTheme {
                val rules = remember { mutableStateListOf<ProxyRule>().apply { addAll(repository.loadRules()) } }
                var editingDraft by remember { mutableStateOf<RuleDraft?>(null) }
                var editingRuleId by remember { mutableStateOf<String?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                val runtimeStatus by PhoneProxyRuntime.status.collectAsState()

                PhoneProxyScreen(
                    rules = rules,
                    isServiceRunning = runtimeStatus.isRunning,
                    serviceMessage = runtimeStatus.message,
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
                    onDeleteRule = { rule ->
                        rules.removeAll { it.id == rule.id }
                        repository.saveRules(rules)
                        reloadServiceIfRunning(runtimeStatus.isRunning)
                    },
                    onToggleRule = { rule, enabled ->
                        val index = rules.indexOfFirst { it.id == rule.id }
                        if (index >= 0) {
                            val updated = rules[index].copy(enabled = enabled)
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
                                val rule = draftResult.getOrThrow()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneProxyScreen(
    rules: List<ProxyRule>,
    isServiceRunning: Boolean,
    serviceMessage: String,
    onToggleService: (Boolean) -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (ProxyRule) -> Unit,
    onDeleteRule: (ProxyRule) -> Unit,
    onToggleRule: (ProxyRule, Boolean) -> Unit,
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
                    }
                }
            }
            items(rules, key = { it.id }) { rule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(rule.name, style = MaterialTheme.typography.titleMedium)
                        Text("TCP: ${rule.listenIp}:${rule.listenPort} -> ${rule.targetIp}:${rule.targetPort}")
                        Text(
                            "UDP: ${rule.listenIp}:${rule.udpListenPortLabel()} -> " +
                                "${rule.targetIp}:${rule.udpTargetPortLabel()}"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Checkbox(
                                    checked = rule.enabled,
                                    onCheckedChange = { onToggleRule(rule, it) },
                                )
                                Text("Enabled")
                            }
                            Button(onClick = { onEditRule(rule) }) {
                                Text("Edit")
                            }
                            Button(onClick = { onDeleteRule(rule) }) {
                                Text("Delete")
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
