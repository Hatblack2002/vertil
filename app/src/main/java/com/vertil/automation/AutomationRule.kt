package com.vertil.automation

import kotlinx.serialization.Serializable

/**
 * Regla de automatización definida por el usuario.
 *
 * v1.0: modelo de datos + persistencia + UI.
 * La ejecución real de reglas se pospone a v1.1 (requiere WorkManager scheduler
 * + observación de MediaStore, que es complejo y necesita tests en device).
 *
 * Lo que SÍ funciona en v1.0:
 *  - Crear/editar/eliminar reglas.
 *  - Persistirlas en Room.
 *  - Mostrarlas en la UI.
 *  - Validarlas.
 */
@Serializable
data class AutomationRule(
    val id: String,
    val name: String,
    val description: String,
    val trigger: Trigger,
    val action: Action,
    val enabled: Boolean = true,
    val requiresConfirmation: Boolean = true,
    val createdAt: Long,
    val lastFiredAt: Long? = null
)

@Serializable
data class Trigger(
    val type: TriggerType,
    val conditions: Map<String, String> = emptyMap()
)

@Serializable
enum class TriggerType(val label: String) {
    NEW_FILE_IN_FOLDER("Nuevo archivo en carpeta"),
    FILE_EXTENSION("Archivo con cierta extensión"),
    MANUAL("Solo manual")
}

@Serializable
data class Action(
    val type: ActionType,
    val params: Map<String, String> = emptyMap()
)

@Serializable
enum class ActionType(val label: String) {
    MOVE_TO_FOLDER("Mover a carpeta"),
    COPY_TO_FOLDER("Copiar a carpeta"),
    RENAME_PATTERN("Renombrar con patrón"),
    NOTIFY("Solo notificar")
}

/**
 * Validador de reglas (testeable sin Android).
 */
object AutomationValidator {
    fun validate(rule: AutomationRule): ValidationResult {
        val errors = mutableListOf<String>()
        if (rule.name.isBlank()) errors.add("El nombre no puede estar vacío")
        if (rule.trigger.type == TriggerType.NEW_FILE_IN_FOLDER && rule.trigger.conditions["folder"].isNullOrBlank()) {
            errors.add("NEW_FILE_IN_FOLDER requiere 'folder'")
        }
        if (rule.trigger.type == TriggerType.FILE_EXTENSION && rule.trigger.conditions["extension"].isNullOrBlank()) {
            errors.add("FILE_EXTENSION requiere 'extension'")
        }
        if (rule.action.type == ActionType.MOVE_TO_FOLDER && rule.action.params["destination"].isNullOrBlank()) {
            errors.add("MOVE_TO_FOLDER requiere 'destination'")
        }
        if (rule.action.type == ActionType.COPY_TO_FOLDER && rule.action.params["destination"].isNullOrBlank()) {
            errors.add("COPY_TO_FOLDER requiere 'destination'")
        }
        if (rule.action.type == ActionType.RENAME_PATTERN && rule.action.params["pattern"].isNullOrBlank()) {
            errors.add("RENAME_PATTERN requiere 'pattern'")
        }
        return ValidationResult(errors)
    }

    data class ValidationResult(val errors: List<String>) {
        val isValid: Boolean get() = errors.isEmpty()
    }
}
