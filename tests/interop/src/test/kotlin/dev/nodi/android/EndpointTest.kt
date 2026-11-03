package dev.nodi.android

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun validNumericAddresses() {
        mapOf("100.64.0.2:7331" to "100.64.0.2:7331",
            " 192.168.001.002:00080\n" to "192.168.1.2:80",
            "127.0.0.1:1" to "127.0.0.1:1",
            "223.255.255.255:65535" to "223.255.255.255:65535").forEach { (input, expected) ->
            assertEquals(expected, NodiEndpoint.normalize(input))
        }
    }
    @Test fun rejectsAmbiguousOrUnusableAddresses() {
        listOf("", "example.com:7331", "https://100.64.0.2:7331", "100.64.0.2",
            "100.64.0.2:0", "100.64.0.2:65536", "100.64.0.2:+80", "100.64.0.2:8 0",
            "256.1.2.3:80", "0.0.0.0:80", "224.0.0.1:80", "255.255.255.255:80",
            "1.2.3:80", "1..2.3:80", "1.2.3.4:80/path", "[::1]:80", "1.2.3.4:８０").forEach {
            assertThrows("Should reject $it", IllegalArgumentException::class.java) { NodiEndpoint.normalize(it) }
        }
    }
}
