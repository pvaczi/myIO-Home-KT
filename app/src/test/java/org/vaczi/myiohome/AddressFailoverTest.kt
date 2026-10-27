package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaczi.myiohome.data.local.AppTheme
import org.vaczi.myiohome.data.local.MyioServer
import org.vaczi.myiohome.data.local.SettingsBackup
import org.vaczi.myiohome.data.local.SettingsStore
import org.vaczi.myiohome.data.local.StateCacheStore
import org.vaczi.myiohome.data.net.ActiveAddress
import org.vaczi.myiohome.data.net.AddressFailover
import org.vaczi.myiohome.data.net.DeviceGate
import org.vaczi.myiohome.data.net.HttpStatusException
import org.vaczi.myiohome.data.net.Which
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AddressFailoverTest {

    @Test
    fun connectionLevelIoFailuresTriggerFailover() {
        assertTrue(AddressFailover.isConnectionFailure(ConnectException("refused")))
        assertTrue(AddressFailover.isConnectionFailure(SocketTimeoutException("connect timed out")))
        assertTrue(AddressFailover.isConnectionFailure(UnknownHostException("ddns.example")))
        assertTrue(AddressFailover.isConnectionFailure(IOException("no route")))
    }

    @Test
    fun httpAnswersAndOtherErrorsDoNot() {
        assertFalse(AddressFailover.isConnectionFailure(HttpStatusException(401, "HTTP 401")))
        assertFalse(AddressFailover.isConnectionFailure(HttpStatusException(503, "HTTP 503", 5)))
        assertFalse(AddressFailover.isConnectionFailure(IllegalStateException("empty body")))
    }

    @Test
    fun orderPutsActiveAddressFirstAndRetriesOnlyWithAnAlt() {
        assertEquals(listOf(Which.Primary), AddressFailover.order(hasAlt = false, activeAlt = false))
        assertEquals(listOf(Which.Primary), AddressFailover.order(hasAlt = false, activeAlt = true))
        assertEquals(listOf(Which.Primary, Which.Alt), AddressFailover.order(hasAlt = true, activeAlt = false))
        assertEquals(listOf(Which.Alt, Which.Primary), AddressFailover.order(hasAlt = true, activeAlt = true))
    }

    @Test
    fun activeAddressIsRememberedPerServerId() {
        ActiveAddress.clear()
        assertFalse(ActiveAddress.isAlt("a"))
        ActiveAddress.set("a", Which.Alt)
        assertTrue(ActiveAddress.isAlt("a"))
        assertFalse(ActiveAddress.isAlt("b"))
        ActiveAddress.set("a", Which.Primary)
        assertFalse(ActiveAddress.isAlt("a"))
        ActiveAddress.clear()
    }

    @Test
    fun oldServerJsonDecodesWithoutAltAddress() {
        val old = """[{"name":"Garázs","host":"10.0.0.9","port":8080,"user":"u","password":"p","id":"x1"}]"""
        val s = SettingsStore.parseServers(old).single()
        assertNull(s.altHost)
        assertNull(s.altPort)
        assertNull(s.toConfig().altHost)
        assertEquals("x1", s.toConfig().serverId)
    }

    @Test
    fun altAddressSurvivesStoreAndBackupRoundTrips() {
        val servers = listOf(
            MyioServer(name = "Fűtés", host = "10.0.0.1", altHost = "home.example.org", altPort = 8081),
            MyioServer(name = "Világítás", host = "10.0.0.2"),
        )
        assertEquals(servers, SettingsStore.parseServers(org.vaczi.myiohome.data.local.StoreJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MyioServer.serializer()), servers,
        )))
        val decoded = SettingsBackup.decode(SettingsBackup.encode(AppTheme.DYNAMIC, servers, emptyList(), emptyMap()))!!
        assertEquals("home.example.org", decoded.servers[0].altHost)
        assertEquals(8081, decoded.servers[0].altPort)
        assertNull(decoded.servers[1].altHost)
        val cfg = servers[0].toConfig()
        assertEquals("home.example.org", cfg.altHost)
        assertEquals(8081, cfg.altPort)
    }

    @Test
    fun blankAltHostIsNoAlt() {
        assertNull(MyioServer(name = "A", host = "h", altHost = "  ").toConfig().altHost)
    }

    @Test
    fun cacheKeyIgnoresTheAltAddress() {
        val base = MyioServer(name = "A", host = "10.0.0.1", id = "s1")
        assertEquals(
            StateCacheStore.cacheKey(base),
            StateCacheStore.cacheKey(base.copy(altHost = "x.example.org", altPort = 81)),
        )
    }

    @Test
    fun bothAddressesOfAServerShareOneGate() {
        // The repository takes DeviceGate.of(config.host, config.port): the primary address, whatever the alt is.
        val a = MyioServer(name = "A", host = "10.0.0.1", altHost = "x.example.org").toConfig()
        val b = MyioServer(name = "A", host = "10.0.0.1", altHost = "y.example.org", altPort = 81).toConfig()
        assertSame(DeviceGate.of(a.host, a.port), DeviceGate.of(b.host, b.port))
        assertNotSame(DeviceGate.of(a.altHost!!, a.altPort), DeviceGate.of(a.host, a.port))
    }
}
