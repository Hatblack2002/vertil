package com.vertil.tools

import com.vertil.core.VertilResult
import com.vertil.core.error.ErrorManager
import com.vertil.core.log.VertilLog
import com.vertil.permissions.PermissionCapability
import com.vertil.permissions.PermissionDecision
import com.vertil.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * ToolManager: registro y ejecución de herramientas.
 *
 * Es el PUENTE entre el modelo (que pide acciones) y Android (que las ejecuta).
 *
 * Flujo de seguridad obligatorio:
 *
 *   Modelo solicita tool
 *     ↓
 *   ToolManager.execute
 *     ↓
 *   PermissionManager.evaluate(capabilities)  ← política real
 *     ↓
 *   Granted → ejecutar tool → ToolResult.Success/Error estructurado
 *   NeedsConfirmation → esperar confirmación de la UI
 *   Denied → ToolResult.Error(code=PERMISSION_DENIED)
 *
 * El modelo NUNCA puede bypassear esto.
 */
class ToolManager(
    private val permissionManager: PermissionManager,
    private val errorManager: ErrorManager = ErrorManager()
) {
    private val tools: MutableMap<String, Tool> = ConcurrentHashMap()

    private val _lastResults = MutableStateFlow<List<Pair<String, ToolResult>>>(emptyList())
    val lastResults: StateFlow<List<Pair<String, ToolResult>>> = _lastResults.asStateFlow()

    fun register(tool: Tool): Boolean {
        val prev = tools.putIfAbsent(tool.id, tool)
        if (prev != null) {
            VertilLog.w("ToolManager", "Tool id '${tool.id}' ya registrado por ${prev.javaClass.name}")
            return false
        }
        VertilLog.i("ToolManager", "Registrada tool '${tool.id}' (${tool.javaClass.simpleName})")
        return true
    }

    fun unregister(id: String) = tools.remove(id)

    fun byId(id: String): Tool? = tools[id]
    fun all(): List<Tool> = tools.values.sortedBy { it.id }
    fun count(): Int = tools.size

    /**
     * Ejecuta la herramienta [toolId] con el input dado.
     * Aplica políticas de permisos. Si necesita confirmación, devuelve un
     * VertilResult.Failure con code="PERMISSION_PENDING" para que el llamador
     * muestre el diálogo.
     */
    suspend fun execute(toolId: String, input: ToolInput = ToolInput.EMPTY): VertilResult<ToolResult> {
        val tool = tools[toolId] ?: return VertilResult.fail(
            "Unknown tool: $toolId",
            module = "ToolManager", code = "UNKNOWN_TOOL"
        )

        if (!tool.validate(input)) {
            return VertilResult.ok(
                ToolResult.Error(
                    operation = toolId,
                    code = "INVALID_INPUT",
                    message = "Validación de input fallida para $toolId"
                )
            )
        }

        // Policy check real
        val decision = permissionManager.evaluate(toolId, tool.capabilities, input.args)
        return when (decision) {
            is PermissionDecision.Granted -> {
                runToolInternal(tool, input)
            }
            is PermissionDecision.NeedsConfirmation -> {
                VertilResult.fail(
                    message = "Esperando confirmación del usuario para $toolId (${decision.capabilities.joinToString { it.label }})",
                    module = "ToolManager",
                    code = "PERMISSION_PENDING"
                )
            }
            is PermissionDecision.Denied -> {
                val err = ToolResult.Error(
                    operation = toolId,
                    code = "PERMISSION_DENIED",
                    message = decision.reason
                )
                _lastResults.value = _lastResults.value + (toolId to err)
                VertilResult.ok(err)
            }
        }
    }

    private suspend fun runToolInternal(tool: Tool, input: ToolInput): VertilResult<ToolResult> {
        return try {
            val result = tool.execute(input)
            when (result) {
                is VertilResult.Success -> {
                    _lastResults.value = (_lastResults.value + (tool.id to result.value)).takeLast(50)
                    result
                }
                is VertilResult.Failure -> {
                    errorManager.report("ToolManager", result)
                    result
                }
            }
        } catch (t: Throwable) {
            errorManager.report("ToolManager", "Tool '${tool.id}' threw ${t.javaClass.simpleName}: ${t.message}", t)
            VertilResult.fail(
                "Tool '${tool.id}' threw: ${t.message}", t, "ToolManager", "TOOL_EXCEPTION"
            )
        }
    }
}
