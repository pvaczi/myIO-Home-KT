package org.vaczi.myiohome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.vaczi.myiohome.data.net.DeviceGate
import org.vaczi.myiohome.data.net.DiscoveredServer
import org.vaczi.myiohome.data.net.MyioDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Pins the server-name scrape used to label discovered servers. The name lives in the index page
 * `<title>` as `myIO-<name> vezérlő - <page>` (confirmed on hardware fw 3.7.5.1 / 3.9.1).
 */
class MyioDiscoveryTest {

    private fun page(title: String) = "<html><head><title>$title</title></head><body>…</body></html>"

    @Test
    fun extractsNameFromTitle() {
        assertEquals("Világítás", MyioDiscovery.parseName(page("myIO-Világítás vezérlő - index")))
        // Name itself may contain hyphens — only the " vezérlő …" wrapper is stripped.
        assertEquals("Hűtés-Fűtés", MyioDiscovery.parseName(page("myIO-Hűtés-Fűtés vezérlő - index")))
    }

    @Test
    fun fallsBackToPageSeparatorWithoutVezerlo() {
        assertEquals("Garázs", MyioDiscovery.parseName(page("myIO-Garázs - setup")))
    }

    @Test
    fun returnsNullForNonMyioOrMissingTitle() {
        assertNull(MyioDiscovery.parseName(page("Some Router Admin")))
        assertNull(MyioDiscovery.parseName("<html>no title here</html>"))
        assertNull(MyioDiscovery.parseName(page("myIO- vezérlő - index"))) // empty name
    }

    private val sample401 = "HTTP/1.1 401 Authorization Required\n" +
        "WWW-Authenticate: Basic realm=\"Secure Area\"\n" +
        "Content-Type: text/html\n" +
        "Connnection: close\n\n"

    @Test
    fun fingerprintAcceptsTheCapturedResponseWithLfAndCrlf() {
        assertTrue(MyioDiscovery.isMyioFingerprint(sample401))
        assertTrue(MyioDiscovery.isMyioFingerprint(sample401.replace("\n", "\r\n")))
    }

    @Test
    fun fingerprintAcceptsEitherMarkerAlone() {
        assertTrue(MyioDiscovery.isMyioFingerprint("HTTP/1.1 401 Authorization Required\nConnnection: close\n\n"))
        assertTrue(MyioDiscovery.isMyioFingerprint("HTTP/1.1 401 X\nWWW-Authenticate: Basic realm=\"Secure Area\"\n\n"))
    }

    @Test
    fun fingerprintRejectsOtherServers() {
        assertFalse(MyioDiscovery.isMyioFingerprint("HTTP/1.1 200 OK\nContent-Type: text/html\nConnection: close\n\n"))
        assertFalse(MyioDiscovery.isMyioFingerprint("HTTP/1.1 401 Unauthorized\nWWW-Authenticate: Basic realm=\"router\"\nConnection: close\n\n"))
        // A marker only in the body (after the blank line) does not count.
        assertFalse(MyioDiscovery.isMyioFingerprint("HTTP/1.1 401 Unauthorized\nServer: x\n\nConnnection: close"))
        assertFalse(MyioDiscovery.isMyioFingerprint(""))
    }

    @Test
    fun probeWaitsForTheDeviceGate() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            // A fake device on its own port, so the held gate lock is this test's own.
            val port = server.localPort
            val fake = Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { s ->
                            s.soTimeout = 2_000
                            val inp = s.getInputStream()
                            val seen = StringBuilder()
                            while (!seen.endsWith("\r\n\r\n")) {
                                val c = inp.read()
                                if (c < 0) break
                                seen.append(c.toChar())
                            }
                            s.getOutputStream().apply { write(sample401.toByteArray()); flush() }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val discovery = MyioDiscovery(httpPort = port)
            val gate = DeviceGate.of("127.0.0.1", port).net
            gate.lock()
            val probe = try {
                val probe = async(Dispatchers.IO) { discovery.probe("127.0.0.1", "admin", "admin") }
                delay(300)
                assertFalse("probe must wait while a poll holds the gate", probe.isCompleted)
                probe
            } finally {
                gate.unlock()
            }
            // The fake answers the fingerprint probe; the name lookup gets a 401 page → no name.
            assertEquals(DiscoveredServer("127.0.0.1", null), probe.await())
            fake.interrupt()
        }
    }
}
