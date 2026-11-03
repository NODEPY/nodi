package dev.nodi.android

/** Explicit numeric IPv4 endpoint; no DNS lookup or implicit/default port.
 * Current app listeners bind IPv4. IPv6 can be added with dual-stack listeners. */
object NodiEndpoint {
    fun normalize(raw: String): String {
        val parts = raw.trim().split(':')
        require(parts.size == 2) { "Use IPv4:port" }
        val octets = parts[0].split('.')
        require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) }) { "Use a numeric IPv4 address" }
        val values = octets.map { it.toInt() }
        require(values.all { it in 0..255 } && values[0] in 1..223) { "Use a unicast IPv4 address" }
        require(parts[1].matches(Regex("[0-9]{1,5}"))) { "Use a numeric port" }
        val port = parts[1].toInt()
        require(port in 1..65535) { "Port must be 1–65535" }
        return values.joinToString(".") + ":" + port
    }
}
