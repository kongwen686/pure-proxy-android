package com.v2ray.ang.handler

import org.junit.Assert.*
import org.junit.Test

class ChainStorageTest {
    @Test fun secondRecordFailureRestoresBothRecords() {
        var group = "old-group"
        var selected = "old-entry"
        try {
            MmkvManager.writeProxyChainSelection("new-group", "new-entry",
                writeGroup = { group = it },
                writeSelected = { selected = it; throw IllegalStateException("failed selection write") },
                restoreGroup = { group = "old-group" },
                restoreSelected = { selected = "old-entry" })
            fail("Must propagate write failure")
        } catch (_: IllegalStateException) { }
        assertEquals("old-group", group)
        assertEquals("old-entry", selected)
    }

    @Test fun firstRecordFailureNeverCommitsSelection() {
        var selectedWrites = 0
        var restored = false
        try {
            MmkvManager.writeProxyChainSelection("new-group", "new-entry",
                writeGroup = { throw IllegalStateException("failed group write") },
                writeSelected = { selectedWrites++ },
                restoreGroup = { restored = true }, restoreSelected = {})
            fail("Must propagate write failure")
        } catch (_: IllegalStateException) { }
        assertEquals(0, selectedWrites)
        assertTrue(restored)
    }

    @Test fun rollbackFailureStillAttemptsOtherRecordAndIsReported() {
        var restoredSelected = false
        val original = IllegalStateException("failed selection write")
        try {
            MmkvManager.writeProxyChainSelection("new-group", "new-entry", writeGroup = {},
                writeSelected = { throw original }, restoreGroup = { throw IllegalStateException("failed rollback") },
                restoreSelected = { restoredSelected = true })
            fail("Must propagate write failure")
        } catch (e: IllegalStateException) {
            assertSame(original, e)
            assertEquals(1, e.suppressed.size)
        }
        assertTrue(restoredSelected)
    }
}
