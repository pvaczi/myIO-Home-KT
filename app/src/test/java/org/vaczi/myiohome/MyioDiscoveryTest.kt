package org.vaczi.myiohome

import org.vaczi.myiohome.data.net.MyioDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
