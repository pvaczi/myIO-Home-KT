package org.vaczi.myiohome.data.net

import android.util.Base64
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Connection parameters for one myIO server (port 80 HTTP REST). */
data class ServerConfig(
    val host: String,
    val port: Int = 80,
    val user: String = "admin",
    val password: String = "admin",
    /** Optional second address (e.g. DDNS), tried when the first is unreachable. See [AddressFailover]. */
    val altHost: String? = null,
    val altPort: Int = 80,
    /** Key of the remembered active address ([ActiveAddress]); the slot id. */
    val serverId: String = host,
)

/**
 * HTTP client for the myIO port-80 API. The JSON state endpoint (`sens_out.json`) and the device index
 * page (`GET /`) both return standard HTTP/1.1, so plain OkHttp handles them. Port 80 has built-in
 * overflow protection (unlike the legacy port 843), so polling here is safe.
 *
 * Two endpoints, by cadence: the lightweight [pollState] runs on the hot loop (state only, no names);
 * [getIndex] is fetched once per connect for the heavier metadata (names + per-user permissions + on/off
 * levels). The index deliberately replaces the older `d_sens_out.json` + HTTP/0.9 `/p_desc.xml` pair —
 * it is ~3× smaller, carries the PCA names that d_sens_out drops for id > 2100, and is plain HTTP/1.1.
 */
class MyioHttpClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = defaultClient,
) {
    private val base = "http://${config.host}:${config.port}"
    private val altBase: String? = config.altHost?.takeIf { it.isNotBlank() }?.let { "http://$it:${config.altPort}" }
    private val authHeader: String = run {
        val raw = "${config.user}:${config.password}"
        // ISO-8859-1 (OkHttp's Credentials.basic default): US_ASCII silently maps non-ASCII to '?'.
        "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
    }

    // Per-endpoint timeouts: derived clients share [client]'s pool and dispatcher (newBuilder), so only the
    // timeouts differ — the heavy index page needs far longer than the tiny state poll.
    private val metaClient = client.newBuilder().timeouts(META_TIMEOUT_SEC).build()
    private val pollClient = client.newBuilder().timeouts(POLL_TIMEOUT_SEC).build()
    private val commandClient = client.newBuilder().timeouts(COMMAND_TIMEOUT_SEC).build()

    /** One-time server metadata. */
    suspend fun getInfo(): ServerInfo = MyioParser.parseInfo(get("/info.json", metaClient))

    /** The device index page (`GET /`) — its JS header carries names, permissions and on/off levels. */
    suspend fun getIndex(): String = get("/", metaClient)

    /** Lightweight state-only poll (no names). */
    suspend fun pollState(): ServerState = MyioParser.parseState(get("/sens_out.json", pollClient))

    /**
     * Sends a control command. Body examples: "r_ON=3", "PCA_OFF=2005", "PCA*2005=128", "fet*1=200",
     * "g_INV=501"; several commands may be joined with '&' (see [PollBackoff.chunk]). Goes to
     * `POST /info.json` (a ~200 B reply) instead of `/` (the full 16-19 kB index page); any 2xx is success,
     * the reply is not parsed.
     */
    suspend fun sendCommand(body: String): Unit = failover { url ->
        val request = commandRequest(url, authHeader, body)
        commandClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw statusError("Command failed", resp, " for '$body'")
        }
    }

    private suspend fun get(path: String, c: OkHttpClient): String = failover { url ->
        val request = baseRequest("$url$path", authHeader)
            .get()
            .build()
        c.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw statusError("GET $path failed", resp)
            resp.body?.string() ?: error("GET $path: empty body")
        }
    }

    /**
     * Runs [call] against the active address; on a connection-level failure ([AddressFailover]) retries
     * once on the other one, which then becomes the active address of this server. Both failing rethrows the
     * second error (the first attached as suppressed) → an ordinary failure for the backoff. The caller
     * holds the device gate for the whole span, so the two attempts never overlap.
     */
    private suspend fun <T> failover(call: (baseUrl: String) -> T): T = withContext(Dispatchers.IO) {
        val order = AddressFailover.order(altBase != null, ActiveAddress.isAlt(config.serverId))
        var firstError: Exception? = null
        for ((i, which) in order.withIndex()) {
            val url = if (which == Which.Alt) altBase!! else base
            try {
                val result = call(url)
                if (i > 0) ActiveAddress.set(config.serverId, which)
                return@withContext result
            } catch (e: Exception) {
                val last = i == order.lastIndex
                if (last || !AddressFailover.isConnectionFailure(e)) {
                    firstError?.let { e.addSuppressed(it) }
                    throw e
                }
                firstError = e
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun statusError(what: String, resp: Response, suffix: String = ""): HttpStatusException {
        val retry = if (resp.code == 503 || resp.code == 429) PollBackoff.parseRetryAfterMs(resp.header("Retry-After")) else null
        return HttpStatusException(resp.code, "$what: HTTP ${resp.code}$suffix", retry)
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            // No idle connections: backs up the per-request `Connection: close` (see [baseRequest]).
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()

        /**
         * Base request: auth + `Connection: close`, so OkHttp drops the socket after the exchange instead of
         * pooling it — an idle keep-alive connection would hold one of the W5100's four sockets (the bridge
         * sends the same header).
         */
        /** The control-command request: `POST /info.json`, form-urlencoded body. */
        internal fun commandRequest(base: String, authHeader: String, body: String): Request =
            baseRequest("$base/info.json", authHeader)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

        private fun OkHttpClient.Builder.timeouts(sec: Long): OkHttpClient.Builder =
            connectTimeout(sec, TimeUnit.SECONDS).readTimeout(sec, TimeUnit.SECONDS)

        private const val META_TIMEOUT_SEC = 15L
        private const val POLL_TIMEOUT_SEC = 4L
        private const val COMMAND_TIMEOUT_SEC = 5L

        internal fun baseRequest(url: String, authHeader: String): Request.Builder = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Connection", "close")
    }
}
