package com.example.phoneproxy.data

import android.content.Context
import com.example.phoneproxy.model.ProxyRule
import org.json.JSONArray
import org.json.JSONObject

class RuleRepository(context: Context) {
    private val prefs = context.getSharedPreferences("phone_proxy_rules", Context.MODE_PRIVATE)

    fun loadRules(): List<ProxyRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return decodeRules(raw)
    }

    fun decodeRules(raw: String): List<ProxyRule> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(item.toRule())
            }
        }
    }

    fun saveRules(rules: List<ProxyRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(rule.toJson())
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    fun saveRulesJson(raw: String): List<ProxyRule> {
        val rules = decodeRules(raw)
        saveRules(rules)
        return rules
    }

    private fun ProxyRule.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("listenIp", listenIp)
            .put("listenPort", listenPort)
            .put("targetIp", targetIp)
            .put("targetPort", targetPort)
            .put("udpListenPortStart", udpListenPortStart)
            .put("udpListenPortEnd", udpListenPortEnd)
            .put("udpTargetPortStart", udpTargetPortStart)
            .put("udpTargetPortEnd", udpTargetPortEnd)
            .put("enabled", enabled)
    }

    private fun JSONObject.toRule(): ProxyRule {
        return ProxyRule(
            id = getString("id"),
            name = getString("name"),
            listenIp = getString("listenIp"),
            listenPort = getInt("listenPort"),
            targetIp = getString("targetIp"),
            targetPort = getInt("targetPort"),
            udpListenPortStart = optionalInt("udpListenPortStart"),
            udpListenPortEnd = optionalInt("udpListenPortEnd"),
            udpTargetPortStart = optionalInt("udpTargetPortStart"),
            udpTargetPortEnd = optionalInt("udpTargetPortEnd"),
            enabled = optBoolean("enabled", true),
        )
    }

    private fun JSONObject.optionalInt(key: String): Int? {
        return if (has(key) && !isNull(key)) {
            getInt(key)
        } else {
            null
        }
    }

    companion object {
        private const val KEY_RULES = "rules_json"
    }
}
