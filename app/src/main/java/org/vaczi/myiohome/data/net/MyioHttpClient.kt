package org.vaczi.myiohome.data.net

import android.util.Base64
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Connection parameters for one myIO server (port 80 HTTP REST). */
data class ServerConfig(
    val host: String,
    val port: Int = 80,
    val user: String = "admin",
    val password: String = "admin",
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
    private val authHeader: String = run {
        val raw = "${config.user}:${config.password}"
        // ISO-8859-1 (OkHttp's Credentials.basic default): US_ASCII silently maps non-ASCII to '?'.
        "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
    }

    /** One-time server metadata. */
    suspend fun getInfo(): ServerInfo = MyioParser.parseInfo(get("/info.json"))

    /** The device index page (`GET /`) — its JS header carries names, permissions and on/off levels. */
    suspend fun getIndex(): String = get("/")

    /** Lightweight state-only poll (no names). */
    suspend fun pollState(): ServerState = MyioParser.parseState(get("/sens_out.json"))

    /**
     * Sends a control command. Body examples: "r_ON=3", "PCA_OFF=2005",
     * "PCA*2005=128", "fet*1=200", "g_INV=501".
     */
    suspend fun sendCommand(body: String): Unit = withContext(Dispatchers.IO) {
        val request = baseRequest("$base/", authHeader)
            .post(body.toRequestBody())
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Command failed: HTTP ${resp.code} for '$body'")
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = baseRequest("$base$path", authHeader)
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("GET $path failed: HTTP ${resp.code}")
            resp.body?.string() ?: error("GET $path: empty body")
        }
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
        internal fun baseRequest(url: String, authHeader: String): Request.Builder = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Connection", "close")
    }
}
