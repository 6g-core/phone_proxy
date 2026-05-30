package com.example.phoneproxy.ui

import com.example.phoneproxy.model.ProxyRule
import java.util.UUID

data class RuleDraft(
    val id: String? = null,
    val name: String = "",
    val listenIp: String = "",
    val listenPort: String = "",
    val targetIp: String = "",
    val targetPort: String = "",
    val udpListenPortStart: String = "",
    val udpListenPortEnd: String = "",
    val udpTargetPortStart: String = "",
    val udpTargetPortEnd: String = "",
    val usbTetherAutoTarget: Boolean = false,
    val enabled: Boolean = true,
) {
    companion object {
        fun fromRule(rule: ProxyRule): RuleDraft {
            return RuleDraft(
                id = rule.id,
                name = rule.name,
                listenIp = rule.listenIp,
                listenPort = rule.listenPort.toString(),
                targetIp = rule.targetIp,
                targetPort = rule.targetPort.toString(),
                udpListenPortStart = rule.udpListenPortStart?.toString().orEmpty(),
                udpListenPortEnd = rule.udpListenPortEnd?.toString().orEmpty(),
                udpTargetPortStart = rule.udpTargetPortStart?.toString().orEmpty(),
                udpTargetPortEnd = rule.udpTargetPortEnd?.toString().orEmpty(),
                usbTetherAutoTarget = rule.usbTetherAutoTarget,
                enabled = rule.enabled,
            )
        }
    }

    fun toRule(): Result<ProxyRule> {
        val parsedListenPort = listenPort.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Listen port must be a number."))
        val parsedTargetPort = targetPort.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Target port must be a number."))
        val parsedUdpRange = parseOptionalUdpRange()
            ?: return Result.failure(
                IllegalArgumentException(
                    "UDP range must fill all four fields with numbers, or leave them all empty."
                )
            )

        return Result.success(
            ProxyRule(
                id = id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                listenIp = listenIp.trim(),
                listenPort = parsedListenPort,
                targetIp = targetIp.trim(),
                targetPort = parsedTargetPort,
                udpListenPortStart = parsedUdpRange?.listenStart,
                udpListenPortEnd = parsedUdpRange?.listenEnd,
                udpTargetPortStart = parsedUdpRange?.targetStart,
                udpTargetPortEnd = parsedUdpRange?.targetEnd,
                usbTetherAutoTarget = usbTetherAutoTarget,
                enabled = enabled,
            )
        )
    }

    private fun parseOptionalUdpRange(): ParsedUdpRange? {
        val values = listOf(
            udpListenPortStart.trim(),
            udpListenPortEnd.trim(),
            udpTargetPortStart.trim(),
            udpTargetPortEnd.trim(),
        )
        if (values.all { it.isEmpty() }) {
            return ParsedUdpRange.EMPTY
        }
        if (values.any { it.isEmpty() }) {
            return null
        }

        return ParsedUdpRange(
            listenStart = values[0].toIntOrNull() ?: return null,
            listenEnd = values[1].toIntOrNull() ?: return null,
            targetStart = values[2].toIntOrNull() ?: return null,
            targetEnd = values[3].toIntOrNull() ?: return null,
        )
    }
}

private data class ParsedUdpRange(
    val listenStart: Int?,
    val listenEnd: Int?,
    val targetStart: Int?,
    val targetEnd: Int?,
) {
    companion object {
        val EMPTY = ParsedUdpRange(
            listenStart = null,
            listenEnd = null,
            targetStart = null,
            targetEnd = null,
        )
    }
}
