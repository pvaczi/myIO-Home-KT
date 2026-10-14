package org.vaczi.myiohome

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.vaczi.myiohome.data.net.DeviceGate

/** One gate per physical device: every client of the same host:port must share it. */
class DeviceGateTest {

    @Test
    fun sameHostAndPortShareOneGate() {
        assertSame(DeviceGate.of("10.0.0.5", 80), DeviceGate.of("10.0.0.5", 80))
    }

    @Test
    fun hostIsMatchedCaseInsensitivelyAndTrimmed() {
        assertSame(DeviceGate.of("MyIO.lan", 80), DeviceGate.of(" myio.lan ", 80))
    }

    @Test
    fun differentHostOrPortGetsItsOwnGate() {
        assertNotSame(DeviceGate.of("10.0.0.5", 80), DeviceGate.of("10.0.0.6", 80))
        assertNotSame(DeviceGate.of("10.0.0.5", 80), DeviceGate.of("10.0.0.5", 8080))
    }
}
