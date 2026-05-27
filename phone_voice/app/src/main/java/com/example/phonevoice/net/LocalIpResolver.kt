package com.example.phonevoice.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object LocalIpResolver {
    fun resolveLanIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val addresses = mutableListOf<InetAddress>()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val interfaceAddresses = networkInterface.inetAddresses
            while (interfaceAddresses.hasMoreElements()) {
                addresses += interfaceAddresses.nextElement()
            }
        }
        return pickLanIpv4(addresses)
    }

    fun pickLanIpv4(addresses: List<InetAddress>): String? {
        return addresses
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
