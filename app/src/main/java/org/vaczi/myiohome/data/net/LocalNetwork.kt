package org.vaczi.myiohome.data.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * The /24 prefixes (first three octets, e.g. "192.168.1") of the device's own active IPv4 interfaces.
 * Best-effort and dependency-free (no Android context, no extra permission) — used to pre-populate the
 * discovery subnet picker so the user's actual network shows up without anything being hardcoded.
 */
fun localIpv4Subnets(): List<String> = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
        .filter { it.count { c -> c == '.' } == 2 }
        .distinct()
}.getOrDefault(emptyList())
