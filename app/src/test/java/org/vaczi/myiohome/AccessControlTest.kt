package org.vaczi.myiohome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.vaczi.myiohome.data.local.AccessControl
import org.vaczi.myiohome.data.local.memberRef
import org.junit.Test

class AccessControlTest {
    private val prot = setOf(memberRef("s1", "R3"), memberRef("s2", "C5"))

    @Test fun unlockWindowIs30Seconds() {
        val until = AccessControl.unlockedUntil(1_000)
        assertEquals(31_000L, until)
        assertTrue(AccessControl.isUnlocked(until, 30_999))
        assertFalse(AccessControl.isUnlocked(until, 31_000))
    }

    @Test fun backgroundTimeoutBoundary() {
        assertFalse(AccessControl.shouldLockAfterBackground(null, 1_000_000))
        assertFalse(AccessControl.shouldLockAfterBackground(0, 59_999))
        assertTrue(AccessControl.shouldLockAfterBackground(0, 60_000))
        assertTrue(AccessControl.shouldLockAfterBackground(10_000, 200_000))
    }

    @Test fun needsAuthOnlyForProtectedRefsOutsideWindow() {
        assertTrue(AccessControl.needsAuth(listOf(memberRef("s1", "R3")), prot, 0, 100))
        assertFalse(AccessControl.needsAuth(listOf(memberRef("s1", "R4")), prot, 0, 100))
        // same key on another server is a different output
        assertFalse(AccessControl.needsAuth(listOf(memberRef("s2", "R3")), prot, 0, 100))
        // any protected member of a group is enough
        assertTrue(AccessControl.needsAuth(listOf(memberRef("s1", "R4"), memberRef("s2", "C5")), prot, 0, 100))
        // open window / nothing protected / empty action
        assertFalse(AccessControl.needsAuth(listOf(memberRef("s1", "R3")), prot, 5_000, 100))
        assertFalse(AccessControl.needsAuth(listOf(memberRef("s1", "R3")), emptySet(), 0, 100))
        assertFalse(AccessControl.needsAuth(emptyList(), prot, 0, 100))
    }

    @Test fun sceneRefsIncludeProtectedOthersOnTouchedServersOnly() {
        val entries = listOf(memberRef("s1", "R1"))
        assertEquals(entries, AccessControl.sceneRefs(entries, false, prot))
        val all = AccessControl.sceneRefs(entries, true, prot)
        assertTrue(memberRef("s1", "R3") in all)
        assertFalse(memberRef("s2", "C5") in all)
    }

    @Test fun keysOfFiltersByServer() {
        assertEquals(setOf("R3"), AccessControl.keysOf(prot, "s1"))
        assertEquals(emptySet<String>(), AccessControl.keysOf(prot, "s9"))
    }
}
