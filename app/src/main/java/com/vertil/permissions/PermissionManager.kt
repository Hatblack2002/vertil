package com.vertil.permissions

import com.vertil.core.log.VertilLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PermissionManager.
 *
 * Núcleo del sistema de seguridad de VERTIL.
 * La seguridad REAL está aquí — no en el System Prompt.
 *
 * El modelo nunca puede:
 *  - modificar sus políticas;
 *  - modificar el PermissionManager;
 *  - saltarse confirmaciones;
 *  - ejecutar herramientas sin autorización.
 */
class PermissionManager(
    initialPolicy: PermissionPolicy = PermissionPolicy.DEFAULT
) {
    private val _policy = MutableStateFlow(initialPolicy)
    val policy: StateFlow<PermissionPolicy> = _policy.asStateFlow()

    /** Capacidades confirmadas para esta sesión (no persistente). */
    private val sessionGranted = mutableSetOf<PermissionCapability>()

    /** Decisiones pending — para que la UI las muestre y el usuario confirme. */
    private val _pending = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pending: StateFlow<List<PermissionRequest>> = _pending.asStateFlow()

    /**
     * Evalúa si una herramienta puede ejecutarse con las capacidades dadas.
     * Devuelve:
     *  - Granted: el permiso está concedido (auto o ya confirmado en sesión).
     *  - NeedsConfirmation: requiere confirmación del usuario.
     *  - Denied: la capacidad está deshabilitada o el nivel no es auto-confirmable.
     */
    fun evaluate(
        toolId: String,
        capabilities: List<PermissionCapability>,
        args: Map<String, Any?> = emptyMap()
    ): PermissionDecision {
        if (capabilities.isEmpty()) return PermissionDecision.Granted

        // 1. Capacidades deshabilitadas → denied
        val disabled = capabilities.filter { it in _policy.value.disabledCapabilities }
        if (disabled.isNotEmpty()) {
            VertilLog.w("PermManager", "Denied (disabled): $disabled")
            return PermissionDecision.Denied(
                reason = "Capacidad deshabilitada por política: ${disabled.joinToString { it.label }}",
                capabilities = disabled
            )
        }

        // 2. Si todas están ya confirmadas en sesión → granted
        if (capabilities.all { it in sessionGranted }) {
            return PermissionDecision.Granted
        }

        // 3. Niveles auto-confirmables → granted
        val autoLevels = _policy.value.autoConfirmLevels
        val needsConfirm = capabilities.any { cap ->
            cap.level !in autoLevels && cap !in sessionGranted
        }

        return if (needsConfirm) {
            val pendingCaps = capabilities.filter { cap ->
                cap.level !in autoLevels && cap !in sessionGranted
            }
            // Añadir a pending para que la UI lo muestre
            val req = PermissionRequest(
                id = "req_${System.currentTimeMillis()}_${toolId}",
                toolId = toolId,
                capabilities = pendingCaps,
                args = args,
                createdAt = System.currentTimeMillis()
            )
            _pending.value = _pending.value + req
            VertilLog.i("PermManager", "Pending confirmation: $toolId needs ${pendingCaps.joinToString { it.label }}")
            PermissionDecision.NeedsConfirmation(pendingCaps)
        } else {
            PermissionDecision.Granted
        }
    }

    fun confirm(requestId: String, granted: Boolean): Boolean {
        val req = _pending.value.firstOrNull { it.id == requestId } ?: return false
        _pending.value = _pending.value.filterNot { it.id == requestId }
        if (granted) {
            sessionGranted.addAll(req.capabilities)
            VertilLog.i("PermManager", "Confirmed (granted): ${req.capabilities.joinToString { it.label }}")
        } else {
            VertilLog.i("PermManager", "Confirmed (denied): ${req.capabilities.joinToString { it.label }}")
        }
        return true
    }

    fun cancelRequest(requestId: String) {
        _pending.value = _pending.value.filterNot { it.id == requestId }
    }

    fun updatePolicy(newPolicy: PermissionPolicy) {
        _policy.value = newPolicy
    }

    fun resetSession() {
        sessionGranted.clear()
        _pending.value = emptyList()
    }
}

data class PermissionRequest(
    val id: String,
    val toolId: String,
    val capabilities: List<PermissionCapability>,
    val args: Map<String, Any?>,
    val createdAt: Long
)
