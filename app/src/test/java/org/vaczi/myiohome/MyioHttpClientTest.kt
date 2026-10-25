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
}
