package com.example.phonevoice.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIpResolverTest {
    @Test
    fun `prefers the first site local ipv4 address`() {
        val addresses = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("192.168.1.23"),
            InetAddress.getByName("10.0.0.9"),
        )

        assertEquals("192.168.1.23", LocalIpResolver.pickLanIpv4(addresses))
    }

    @Test
    fun `returns null when no site local ipv4 exists`() {
        val addresses = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("::1"),
        )

        assertNull(LocalIpResolver.pickLanIpv4(addresses))
    }
}
