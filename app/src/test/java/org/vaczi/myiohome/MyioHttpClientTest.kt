package org.vaczi.myiohome

import org.vaczi.myiohome.data.net.MyioHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the per-request headers: an idle pooled socket would hold one of the W5100's four sockets. */
class MyioHttpClientTest {

    @Test
    fun everyRequestClosesTheConnection() {
        val req = MyioHttpClient.baseRequest("http://10.0.0.5:80/sens_out.json", "Basic eDp5").get().build()
        assertEquals("close", req.header("Connection"))
        assertEquals("Basic eDp5", req.header("Authorization"))
    }

    @Test
    fun commandIsFormPostToInfoJson() {
        val req = MyioHttpClient.commandRequest("http://10.0.0.5:80", "Basic eDp5", "PCA_ON=2046&r_OFF=3")
        assertEquals("POST", req.method)
        assertEquals("/info.json", req.url.encodedPath)
        assertEquals("application/x-www-form-urlencoded", req.body?.contentType()?.let { "${it.type}/${it.subtype}" })
        val buf = okio.Buffer().also { req.body!!.writeTo(it) }
        assertEquals("PCA_ON=2046&r_OFF=3", buf.readUtf8())
        assertEquals("close", req.header("Connection"))
    }
}
