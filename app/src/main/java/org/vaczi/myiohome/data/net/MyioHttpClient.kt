package org.vaczi.myiohome.data.net

import android.util.Base64
import org.vaczi.myiohome.data.model.ServerInfo
import org.vaczi.myiohome.data.model.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Connection parameters for one myIO server (port 80 HTTP REST). */
data class ServerConfig(
    val host: String,
    val port: Int = 80,
    val user: String = "admin",
    val password: String = "admin",
)

/**
 * HTTP client for the myIO port-80 API. The JSON endpoints (info/sens_out/d_sens_out) return
 * standard HTTP/1.1, so plain OkHttp handles them. Port 80 has built-in overflow protection
 * (unlike the legacy port 843), so polling here is safe. The sole exception is `/p_desc.xml`, which
 * replies in bare HTTP/0.9 and is read over a raw socket (see [fetchPcaNames]).
 */
class MyioHttpClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = defaultClient,
) {
    private val base = "http://${config.host}:${config.port}"
    private val authHeader: String = run {
        val raw = "${config.user}:${config.password}"
        "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.US_ASCII), Base64.NO_WRAP)
    }

    /** One-time server metadata. */
    suspend fun getInfo(): ServerInfo = MyioParser.parseInfo(get("/info.json"))

    /** State including device names (heavier; fetch occasionally). */
    suspend fun getNamedState(): ServerState = MyioParser.parseState(get("/d_sens_out.json"))

    /** Lightweight state-only poll (no names). */
    suspend fun pollState(): ServerState = MyioParser.parseState(get("/sens_out.json"))

    /**
     * Sends a control command. Body examples: "r_ON=3", "PCA_OFF=2005",
     * "PCA*2005=128", "fet*1=200", "g_INV=501".
     */
    suspend fun sendCommand(body: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/")
            .header("Authorization", authHeader)
            .post(body.toRequestBody())
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Command failed: HTTP ${resp.code} for '$body'")
        }
    }

    /**
     * Firmware bug workaround: PCA names for id > 2100 are empty in d_sens_out.json but present in
     * `/p_desc.xml`. That endpoint replies in bare HTTP/0.9 (no status line, no headers), which
     * OkHttp rejects — so we read it over a raw socket. Returns real-PCA-id → name (non-blank only).
     */
    suspend fun fetchPcaNames(): Map<Int, String> = MyioParser.parsePcaDesc(getRaw("/p_desc.xml"))

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base$path")
            .header("Authorization", authHeader)
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("GET $path failed: HTTP ${resp.code}")
            resp.body?.string() ?: error("GET $path: empty body")
        }
    }

    /** Raw-socket GET tolerating HTTP/0.9 (no headers) as well as a standard headered response. */
    private suspend fun getRaw(path: String): String = withContext(Dispatchers.IO) {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(config.host, config.port), 5_000)
            sock.soTimeout = 5_000
            val req = "GET $path HTTP/1.0\r\nHost: ${config.host}\r\n" +
                "Authorization: $authHeader\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply {
                write(req.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val text = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
            if (text.startsWith("HTTP/")) {
                val crlf = text.indexOf("\r\n\r\n")
                val lf = text.indexOf("\n\n")
                val bodyStart = when {
                    crlf >= 0 -> crlf + 4
                    lf >= 0 -> lf + 2
                    else -> 0
                }
                text.substring(bodyStart)
            } else {
                text
            }
        }
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
