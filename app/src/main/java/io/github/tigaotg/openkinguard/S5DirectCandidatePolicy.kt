package io.github.tigaotg.openkinguard

import java.net.InetAddress
import java.net.Inet6Address

/** Pure candidate admission boundary shared by rendezvous and UDP data plane. */
internal object S5DirectCandidatePolicy {
    data class Candidate(val ip: String, val port: Int, val expires: Long)

    fun accept(ip: String, port: Int, expires: Long, now: Long): Candidate? {
        if (ip.length !in 3..45 || port !in 1..65535 ||
            expires !in (now + 1000L)..(now + 120_000L)) return null
        // Never resolve user-supplied hostnames from a relay message.
        if (ip.contains('%')) return null
        if (!ip.matches(Regex("[0-9.]+")) &&
            !(ip.contains(':') && ip.matches(Regex("[0-9a-fA-F:.%]+")))) return null
        return runCatching {
            val address = InetAddress.getByName(ip)
            val uniqueLocalIpv6 = address is Inet6Address &&
                ((address.address[0].toInt() and 0xfe) == 0xfc)
            require(!address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !uniqueLocalIpv6 && !address.isMulticastAddress)
            Candidate(address.hostAddress.orEmpty(), port, expires)
        }.getOrNull()
    }

    fun fresh(candidate: Candidate, now: Long): Boolean =
        candidate.expires > now && candidate.port in 1..65535
}
