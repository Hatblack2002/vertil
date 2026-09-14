package com.vertil.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionManagerTest {

    @Test
    fun `READ level auto-confirmed by default policy`() {
        val mgr = PermissionManager(PermissionPolicy.DEFAULT)
        val decision = mgr.evaluate("file_search", listOf(PermissionCapability.SEARCH_FILES))
        assertTrue("READ debe estar auto-confirmado", decision is PermissionDecision.Granted)
    }

    @Test
    fun `ORGANIZE level needs confirmation by default`() {
        val mgr = PermissionManager(PermissionPolicy.DEFAULT)
        val decision = mgr.evaluate("file_move", listOf(PermissionCapability.MOVE_FILE))
        assertTrue("ORGANIZE debe requerir confirmación", decision is PermissionDecision.NeedsConfirmation)
    }

    @Test
    fun `SENSITIVE level always needs confirmation`() {
        val mgr = PermissionManager(PermissionPolicy.DEFAULT)
        val decision = mgr.evaluate("delete_tool", listOf(PermissionCapability.DELETE_FILE))
        assertTrue(decision is PermissionDecision.NeedsConfirmation)
    }

    @Test
    fun `disabled capability is denied`() {
        val mgr = PermissionManager(PermissionPolicy(
            autoConfirmLevels = setOf(PermissionLevel.READ, PermissionLevel.ORGANIZE),
            disabledCapabilities = setOf(PermissionCapability.DELETE_FILE)
        ))
        val decision = mgr.evaluate("delete_tool", listOf(PermissionCapability.DELETE_FILE))
        assertTrue(decision is PermissionDecision.Denied)
    }

    @Test
    fun `confirmed capability persists in session`() {
        val mgr = PermissionManager(PermissionPolicy.DEFAULT)
        val decision1 = mgr.evaluate("file_move", listOf(PermissionCapability.MOVE_FILE))
        assertTrue(decision1 is PermissionDecision.NeedsConfirmation)
        val req = (decision1 as PermissionDecision.NeedsConfirmation)
        // Simular que el pending fue añadido — obtener ID del pending
        val pendingReq = mgr.pending.value.first()
        mgr.confirm(pendingReq.id, granted = true)
        // Segunda llamada con misma capacidad → debe estar Granted
        val decision2 = mgr.evaluate("file_move", listOf(PermissionCapability.MOVE_FILE))
        assertTrue(decision2 is PermissionDecision.Granted)
    }

    @Test
    fun `denied confirmation stays denied`() {
        val mgr = PermissionManager(PermissionPolicy.DEFAULT)
        mgr.evaluate("file_move", listOf(PermissionCapability.MOVE_FILE))
        val pendingReq = mgr.pending.value.first()
        mgr.confirm(pendingReq.id, granted = false)
        // Volver a evaluar → debería volver a pedir confirmación
        val decision = mgr.evaluate("file_move", listOf(PermissionCapability.MOVE_FILE))
        assertTrue(decision is PermissionDecision.NeedsConfirmation)
    }

    @Test
    fun `empty capabilities always granted`() {
        val mgr = PermissionManager(PermissionPolicy.DEFAULT)
        val decision = mgr.evaluate("noop", emptyList())
        assertTrue(decision is PermissionDecision.Granted)
    }

    @Test
    fun `PermissionLevel ordering`() {
        assertTrue(PermissionLevel.SENSITIVE.isAtLeast(PermissionLevel.READ))
        assertTrue(PermissionLevel.AUTOMATE.isAtLeast(PermissionLevel.MODIFY))
        assertFalse(PermissionLevel.READ.isAtLeast(PermissionLevel.ORGANIZE))
    }
}
