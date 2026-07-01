package com.example.phoneproxy.net

import com.example.phoneproxy.model.ProxyRule
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class UsbTetherTarget(
    val interfaceName: String,
    val sourceIp: String,
    val targetIp: String,
)

data class UsbTetherRuleResolution(
    val rules: List<ProxyRule>,
    val resolvedTarget: UsbTetherTarget?,
    val changed: Boolean,
    val autoRuleCount: Int,
)

object UsbTetherTargetResolver {
    private val usbTetherNamePatterns = listOf(
        Regex("^rndis\\d*$"),
        Regex("^ncm\\d*$"),
    )

    fun resolve(): UsbTetherTarget? {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            emptyList()
        }

        val candidates = interfaces
            .asSequence()
            .filter { networkInterface ->
                !networkInterface.isLoopback &&
                    networkInterface.isUp &&
                    isUsbTetherInterface(networkInterface)
            }
            .sortedBy { it.name.orEmpty() }

        candidates.forEach { networkInterface ->
            val interfaceName = buildInterfaceLabel(networkInterface)
            val ipv4Address = Collections.list(networkInterface.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    address.isSiteLocalAddress &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                }
                ?: return@forEach

            val sourceIp = ipv4Address.hostAddress ?: return@forEach
            val targetIp = deriveTargetIp(sourceIp) ?: return@forEach
            return UsbTetherTarget(
                interfaceName = interfaceName,
                sourceIp = sourceIp,
                targetIp = targetIp,
            )
        }
        return null
    }

    fun applyToRules(rules: List<ProxyRule>, enabled: Boolean): UsbTetherRuleResolution {
        if (!enabled) {
            return UsbTetherRuleResolution(
                rules = rules,
                resolvedTarget = null,
                changed = false,
                autoRuleCount = 0,
            )
        }
        val resolved = resolve()
        var changed = false
        val autoRuleCount = rules.size
        val updatedRules = rules.map { rule ->
            if (resolved == null || rule.targetIp == resolved.targetIp) {
                return@map rule
            }
            changed = true
            rule.copy(targetIp = resolved.targetIp)
        }
        return UsbTetherRuleResolution(
            rules = updatedRules,
            resolvedTarget = resolved,
            changed = changed,
            autoRuleCount = autoRuleCount,
        )
    }

    private fun deriveTargetIp(sourceIp: String): String? {
        val parts = sourceIp.split(".")
        if (parts.size != 4) {
            return null
        }
        return "${parts[0]}.${parts[1]}.${parts[2]}.200"
    }

    private fun isUsbTetherInterface(networkInterface: NetworkInterface): Boolean {
        val interfaceName = networkInterface.name.orEmpty().lowercase()
        return usbTetherNamePatterns.any { pattern -> pattern.matches(interfaceName) }
    }

    private fun buildInterfaceLabel(networkInterface: NetworkInterface): String {
        val displayName = networkInterface.displayName?.takeIf { it.isNotBlank() }
        return if (displayName == null || displayName == networkInterface.name) {
            networkInterface.name
        } else {
            "${networkInterface.name} ($displayName)"
        }
    }
}
