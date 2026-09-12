package com.oki

import com.oki.core.security.DeletionApproval
import org.junit.Assert.*
import org.junit.Test

class DeletionApprovalTest {
    @Test
    fun confirmationAloneDoesNotDeleteAndSuccessIsSingleUse() {
        val gate = DeletionApproval()
        var deletes = 0
        val id = requireNotNull(gate.begin { deletes++ })
        assertEquals(0, deletes)
        assertNull(gate.begin { deletes += 100 })
        gate.approve(id)
        gate.approve(id)
        assertEquals(1, deletes)
    }

    @Test
    fun cancelInvalidatesLateSuccess() {
        val gate = DeletionApproval()
        var deletes = 0
        val id = requireNotNull(gate.begin { deletes++ })
        gate.cancel()
        gate.approve(id)
        assertEquals(0, deletes)
    }

    @Test
    fun oldAuthenticationCannotApproveANewTarget() {
        val gate = DeletionApproval()
        var target = ""
        val old = requireNotNull(gate.begin { target = "old" })
        gate.cancel()
        val current = requireNotNull(gate.begin { target = "new" })
        gate.approve(old)
        assertEquals("", target)
        assertTrue(gate.isPending(current))
        gate.approve(current)
        assertEquals("new", target)
    }
}
