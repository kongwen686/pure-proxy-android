package com.v2ray.ang.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class VpnLifecycleTest {
    @Test fun repeatedStartDoesNotRecreateRunningTunnel() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginStart())
        assertFalse(lifecycle.beginStart())
        assertTrue(lifecycle.connected())
        assertFalse(lifecycle.beginStart())
        assertEquals(VpnLifecycle.Phase.CONNECTED, lifecycle.phase)
    }

    @Test fun lateCoreShutdownAndDestroyCannotReplaceUserStop() {
        val lifecycle = VpnLifecycle()
        lifecycle.beginStart()
        lifecycle.connected()
        assertTrue(lifecycle.beginStop(VpnStopReason.USER_STOP))
        assertFalse(lifecycle.beginStop(VpnStopReason.CORE_STOPPED))
        lifecycle.stopped()
        assertFalse(lifecycle.beginStop(VpnStopReason.SERVICE_DESTROYED))
        assertEquals(VpnStopReason.USER_STOP, lifecycle.stopReason)
        assertEquals(VpnLifecycle.Phase.STOPPED, lifecycle.phase)
        assertFalse(lifecycle.beginStart())
    }

    @Test fun revokedDuringStartupCannotBecomeConnected() {
        val lifecycle = VpnLifecycle()
        lifecycle.beginStart()
        assertTrue(lifecycle.beginStop(VpnStopReason.VPN_REVOKED))
        assertFalse(lifecycle.connected())
        assertFalse(lifecycle.beginStop(VpnStopReason.START_FAILED))
        assertEquals(VpnStopReason.VPN_REVOKED, lifecycle.stopReason)
    }

    @Test fun failedStartupAndUnexpectedDestructionAllowCleanupExactlyOnce() {
        for (reason in listOf(VpnStopReason.START_FAILED, VpnStopReason.SERVICE_DESTROYED, VpnStopReason.CORE_STOPPED)) {
            val lifecycle = VpnLifecycle()
            lifecycle.beginStart()
            assertTrue(lifecycle.beginStop(reason))
            assertFalse(lifecycle.beginStop(reason))
            lifecycle.stopped()
            assertFalse(lifecycle.beginStart())
            assertEquals(reason, lifecycle.stopReason)
        }
    }

    @Test fun simultaneousStopCallbacksOnlyOneCanOwnCleanup() {
        val lifecycle = VpnLifecycle()
        lifecycle.beginStart()
        lifecycle.connected()
        val gate = CountDownLatch(1)
        val owners = AtomicInteger()
        val threads = (1..16).map {
            Thread {
                gate.await()
                if (lifecycle.beginStop(VpnStopReason.CORE_STOPPED)) owners.incrementAndGet()
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, owners.get())
    }
}
