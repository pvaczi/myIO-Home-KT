package org.vaczi.myiohome.data.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** One device found on the LAN: its host (IP) and, if credentials worked, its configured name. */
data class DiscoveredServer(val host: String, val name: String?)

/**
 * Discovers myIO servers on a /24 by an auth-free TCP probe.
 *
 * **Identification (auth-free):** port 843 (the myIO "application port") is otherwise virtually never
 * open, so a successful TCP *connect* ≈ "a myIO is here". We never speak the 843 protocol — it's a
 * single connect/close, no payload, no continuous load (the reason 843 is otherwise avoided per the
 * project's design). The dead hosts cost only the connect timeout; each real myIO gets one handshake.
 *
 * **Name (auth-gated):** the server name is not exposed anywhere without credentials — info.json has
 * no name, and the 843 protocol requires `Base64(user:pass)` on its first line. So for each hit we
 * best-effort fetch the port-80 index page with the supplied credentials and read the name from its
 * `<title>` (format `myIO-<name> vezérlő - <page>`, confirmed on fw 3.7.5.1 / 3.9.1 — there is no
 * `MYIOname` JS var despite the API doc). If auth fails or the title doesn't match, the name is null
 * and the user names the slot — a graceful fallback, never an error.
 */
class MyioDiscovery(
    private val connectTimeoutMs: Int = 600,
    private val concurrency: Int = 96,
    private val probePort: Int = 843,
    private val httpPort: Int = 80,
) {
    @Suppress("OPT_IN_USAGE")
    private val dispatcher = Dispatchers.IO.limitedParallelism(concurrency)

    /**
     * Scan [subnet] (the first three octets, e.g. "192.168.1") over hosts .1–.254. [onProgress] reports
     * (done, total) as probes finish (called off the main thread). Returns hits sorted by last octet.
     */
    suspend fun scan(
        subnet: String,
        user: String,
        password: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DiscoveredServer> = coroutineScope {
        val prefix = subnet.trim().trimEnd('.')
        val hosts = (1..254).map { "$prefix.$it" }
        val done = AtomicInteger(0)
        hosts.map { host ->
            async(dispatcher) {
                val hit = probe(host, user, password)
                onProgress(done.incrementAndGet(), hosts.size)
                hit
            }
        }.awaitAll()
            .filterNotNull()
            .sortedBy { it.host.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    /**
     * The port-843 connect and, on a hit, the port-80 name lookup — both under one hold of the device's
     * [DeviceGate]: the probe socket shares the W5100's four sockets with port 80, so neither may open beside
     * a running poll of an already configured server, nor may a poll slip in between them.
     */
    internal suspend fun probe(host: String, user: String, password: String): DiscoveredServer? =
        DeviceGate.of(host, httpPort).net.withLock {
            if (isPortOpen(host, probePort)) DiscoveredServer(host, fetchNameUnlocked(host, user, password)) else null
        }

    private fun isPortOpen(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), connectTimeoutMs); true }
        }.getOrDefault(false)

    /**
     * Best-effort port-80 name lookup; null on any failure (wrong creds, timeout, format mismatch). The
     * caller holds the device's [DeviceGate].
     */
    private fun fetchNameUnlocked(host: String, user: String, password: String): String? = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, httpPort), connectTimeoutMs)
            sock.soTimeout = 2_500
            // ISO-8859-1 (OkHttp's Credentials.basic default): US_ASCII silently maps non-ASCII to '?'.
            val auth = "Basic " + Base64.encodeToString(
                "$user:$password".toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP,
            )
            val req = "GET / HTTP/1.0\r\nHost: $host\r\n" +
                "Authorization: $auth\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            parseName(sock.getInputStream().readBytes().toString(Charsets.UTF_8))
        }
    }.getOrNull()

    companion object {
        private val TITLE_REGEX = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)

        /**
         * Extract the server name from the index page `<title>`. Confirmed format:
         * `myIO-<name> vezérlő - <page>` (e.g. `myIO-Világítás vezérlő - index` → `Világítás`,
         * `myIO-Hűtés-Fűtés vezérlő - index` → `Hűtés-Fűtés`). Requires the `myIO-` marker; returns
         * null otherwise (so a non-myIO page never yields a bogus name). Pure → unit-testable.
         */
        fun parseName(html: String): String? {
            val title = TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim().orEmpty()
            if (!title.startsWith("myIO-")) return null
            val body = title.removePrefix("myIO-")
            val name = when {
                " vezérlő" in body -> body.substringBefore(" vezérlő")
                " - " in body -> body.substringBeforeLast(" - ")
                else -> body
            }.trim()
            return name.takeIf { it.isNotBlank() }
        }
    }
}
