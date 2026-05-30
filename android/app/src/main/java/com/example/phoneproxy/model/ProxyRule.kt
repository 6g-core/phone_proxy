package com.example.phoneproxy.model

import java.util.UUID

data class ProxyRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val listenIp: String,
    val listenPort: Int,
    val targetIp: String,
    val targetPort: Int,
    val udpListenPortStart: Int? = null,
    val udpListenPortEnd: Int? = null,
    val udpTargetPortStart: Int? = null,
    val udpTargetPortEnd: Int? = null,
    val usbTetherAutoTarget: Boolean = false,
    val enabled: Boolean = true,
)

fun ProxyRule.validateAgainst(existingRules: List<ProxyRule>): String? {
    if (name.isBlank()) {
        return "Rule name is required."
    }
    if (listenIp.isBlank()) {
        return "Listen IP is required."
    }
    if (targetIp.isBlank()) {
        return "Target IP is required."
    }
    if (listenPort !in 1..65535) {
        return "Listen port must be between 1 and 65535."
    }
    if (targetPort !in 1..65535) {
        return "Target port must be between 1 and 65535."
    }
    if (udpPortRangeError() != null) {
        return udpPortRangeError()
    }

    val duplicateTcp = existingRules.any {
        it.id != id &&
            it.listenIp == listenIp &&
            it.listenPort == listenPort &&
            it.enabled
    }
    if (duplicateTcp && enabled) {
        return "Another enabled rule already uses $listenIp:$listenPort."
    }

    if (enabled) {
        val udpPorts = udpListenPorts()
        val duplicateUdpPort = existingRules
            .asSequence()
            .filter { it.id != id && it.enabled && it.listenIp == listenIp }
            .flatMap { it.udpListenPorts().asSequence() }
            .firstOrNull { it in udpPorts }
        if (duplicateUdpPort != null) {
            return "Another enabled rule already uses UDP $listenIp:$duplicateUdpPort."
        }
    }
    return null
}

data class PortRange(
    val start: Int,
    val endInclusive: Int,
) {
    val size: Int
        get() = endInclusive - start + 1

    fun ports(): IntRange = start..endInclusive

    override fun toString(): String {
        return if (start == endInclusive) {
            start.toString()
        } else {
            "$start-$endInclusive"
        }
    }
}

data class UdpPortMapping(
    val listenPort: Int,
    val targetPort: Int,
)

fun ProxyRule.udpMappings(): List<UdpPortMapping> {
    val listenRange = udpListenRange()
    val targetRange = udpTargetRange()
    if (listenRange == null || targetRange == null) {
        return listOf(UdpPortMapping(listenPort = listenPort, targetPort = targetPort))
    }
    return listenRange.ports().mapIndexed { index, listen ->
        UdpPortMapping(
            listenPort = listen,
            targetPort = targetRange.start + index,
        )
    }
}

fun ProxyRule.udpListenPorts(): Set<Int> {
    return udpMappings().mapTo(linkedSetOf()) { it.listenPort }
}

fun ProxyRule.udpListenPortLabel(): String {
    return udpListenRange()?.toString() ?: listenPort.toString()
}

fun ProxyRule.udpTargetPortLabel(): String {
    return udpTargetRange()?.toString() ?: targetPort.toString()
}

private fun ProxyRule.udpPortRangeError(): String? {
    val values = listOf(
        udpListenPortStart,
        udpListenPortEnd,
        udpTargetPortStart,
        udpTargetPortEnd,
    )
    val hasUdpRangeValue = values.any { it != null }
    if (!hasUdpRangeValue) {
        return null
    }
    if (values.any { it == null }) {
        return "UDP port range requires listen start/end and target start/end."
    }

    val listenStart = udpListenPortStart!!
    val listenEnd = udpListenPortEnd!!
    val targetStart = udpTargetPortStart!!
    val targetEnd = udpTargetPortEnd!!

    if (listenStart !in 1..65535 || listenEnd !in 1..65535) {
        return "UDP listen ports must be between 1 and 65535."
    }
    if (targetStart !in 1..65535 || targetEnd !in 1..65535) {
        return "UDP target ports must be between 1 and 65535."
    }
    if (listenStart > listenEnd) {
        return "UDP listen port range start must be <= end."
    }
    if (targetStart > targetEnd) {
        return "UDP target port range start must be <= end."
    }
    if ((listenEnd - listenStart) != (targetEnd - targetStart)) {
        return "UDP listen and target ranges must have the same size."
    }
    return null
}

private fun ProxyRule.udpListenRange(): PortRange? {
    val start = udpListenPortStart ?: return null
    val end = udpListenPortEnd ?: return null
    return PortRange(start = start, endInclusive = end)
}

private fun ProxyRule.udpTargetRange(): PortRange? {
    val start = udpTargetPortStart ?: return null
    val end = udpTargetPortEnd ?: return null
    return PortRange(start = start, endInclusive = end)
}
