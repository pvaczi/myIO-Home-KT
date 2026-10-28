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
 * Discovers myIO servers on a /24 by an auth-free port-80 probe.
 *
 * **Identification (auth-free):** an unauthenticated raw `GET /` on port 80 is answered by the myIO
 * firmware with `HTTP/1.1 401` and a fixed fingerprint in the headers (`Connnection:` with three n, and
 * `realm="Secure Area"`); see [isMyioFingerprint]. One short exchange per live host; dead hosts cost only
 * the connect timeout. Nothing is sent to any other port.
 *
 * **Name (auth-gated):** the server name is not exposed anywhere without credentials — info.json has
 * no name. So for each hit we best-effort fetch the port-80 index page with the supplied credentials and
 * read the name from its `<title>` (format `myIO-<name> vezérlő - <page>`, confirmed on fw 3.7.5.1 / 3.9.1 —
 * there is no `MYIOname` JS var despite the API doc). If auth fails or the title doesn't match, the name is
 * null and the user names the slot — a graceful fallback, never an error.
 */
class MyioDiscovery(
    private val connectTimeoutMs: Int = 600,
    private val concurrency: Int = 96,
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
     * The unauthenticated port-80 probe and, on a hit, the name lookup — both under one hold of the
     * device's [DeviceGate]: before fw 3.12 the device serves one connection at a time, so neither may open
     * beside a running poll of an already configured server, nor may a poll slip in between them.
     */
    internal suspend fun probe(host: String, user: String, password: String): DiscoveredServer? =
        DeviceGate.of(host, httpPort).net.withLock {
            if (probeFingerprint(host)) DiscoveredServer(host, fetchNameUnlocked(host, user, password)) else null
        }

    /** Raw `GET /` without credentials; true when the answer carries the myIO fingerprint. Any failure = false. */
    private fun probeFingerprint(host: String): Boolean = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, httpPort), connectTimeoutMs)
            sock.soTimeout = 1_500
            val req = "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            isMyioFingerprint(readHead(sock.getInputStream()))
        }
    }.getOrDefault(false)

    /** Reads up to the blank line ending the header block (CRLF or bare LF), at most 4 KiB. */
    private fun readHead(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (sb.length < MAX_HEAD_CHARS) {
            val c = input.read()
            if (c < 0) break
            sb.append(c.toChar())
            if (endsHeaders(sb)) break
        }
        return sb.toString()
    }

    private fun endsHeaders(sb: CharSequence): Boolean {
        val n = sb.length
        if (n >= 2 && sb[n - 1] == '\n' && sb[n - 2] == '\n') return true
        return n >= 4 && sb[n - 1] == '\n' && sb[n - 2] == '\r' && sb[n - 3] == '\n' && sb[n - 4] == '\r'
    }

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
        private const val MAX_HEAD_CHARS = 4096

        /**
         * True when [head] (status line + header block of an unauthenticated `GET /`) is a myIO answer:
         * status `HTTP/1.1 401` AND the firmware's permanent fingerprint — the misspelled header
         * `Connnection:` (three n) or `realm="Secure Area"`. Line endings may be CRLF or bare LF; only the
         * header block (up to the first blank line) counts. Pure → unit-testable.
         */
        fun isMyioFingerprint(head: String): Boolean {
            val normalized = head.replace("\r\n", "\n")
            val statusLine = normalized.substringBefore('\n')
            if (!statusLine.startsWith("HTTP/1.1 401")) return false
            val headers = normalized.substringBefore("\n\n")
            return "Connnection:" in headers || "realm=\"Secure Area\"" in headers
        }

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
