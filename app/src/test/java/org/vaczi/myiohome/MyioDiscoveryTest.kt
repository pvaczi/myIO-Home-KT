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

    @Test
    fun probeWaitsForTheDeviceGate() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            // A port no other test gates on, so the held lock is this test's own.
            val discovery = MyioDiscovery(probePort = server.localPort, httpPort = 18_080)
            val gate = DeviceGate.of("127.0.0.1", 18_080).net
            gate.lock()
            val probe = try {
                val probe = async(Dispatchers.IO) { discovery.probe("127.0.0.1", "admin", "admin") }
                delay(300)
                assertFalse("probe must wait while a poll holds the gate", probe.isCompleted)
                probe
            } finally {
                gate.unlock()
            }
            // Nothing listens on the HTTP port, so the hit carries no name.
            assertEquals(DiscoveredServer("127.0.0.1", null), probe.await())
        }
    }
}
