package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.ui.LocalNetworkGate
import org.vaczi.myiohome.ui.LocalNetworkGate.Companion.decide

/** The Android 17 local-network permission gate decision. */
class LocalNetworkGateTest {

    @Test
    fun belowApi37TheGateIsAlwaysOpen() {
        assertEquals(LocalNetworkGate.OPEN, decide(36, granted = false, denied = false, showRationale = false))
        assertEquals(LocalNetworkGate.OPEN, decide(24, granted = false, denied = true, showRationale = false))
    }

    @Test
    fun grantedOpensTheGate() {
        assertEquals(LocalNetworkGate.OPEN, decide(37, granted = true, denied = true, showRationale = false))
    }

    @Test
    fun notAskedYetShowsTheRationale() {
        assertEquals(LocalNetworkGate.ASK, decide(37, granted = false, denied = false, showRationale = false))
    }

    @Test
    fun deniedWithRationaleOffersRetry() {
        assertEquals(LocalNetworkGate.DENIED_RETRY, decide(37, granted = false, denied = true, showRationale = true))
    }

    @Test
    fun deniedWithoutRationaleSendsToSettings() {
        assertEquals(LocalNetworkGate.DENIED_SETTINGS, decide(37, granted = false, denied = true, showRationale = false))
    }

    @Test
    fun onlyOpenAllowsNetwork() {
        assertTrue(LocalNetworkGate.OPEN.allowsNetwork)
        assertFalse(LocalNetworkGate.ASK.allowsNetwork)
        assertFalse(LocalNetworkGate.DENIED_RETRY.allowsNetwork)
        assertFalse(LocalNetworkGate.DENIED_SETTINGS.allowsNetwork)
    }
}
