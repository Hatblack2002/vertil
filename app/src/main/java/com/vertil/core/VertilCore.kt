package com.vertil.core

import com.vertil.activity.ActivityRepository
import com.vertil.automation.AutomationRepository
import com.vertil.core.error.ErrorManager
import com.vertil.core.identity.VertilIdentity
import com.vertil.core.prompt.SystemPrompt
import com.vertil.model.ModelManager
import com.vertil.permissions.PermissionManager
import com.vertil.permissions.PermissionPolicy
import com.vertil.storage.FileEngine
import com.vertil.tools.ToolManager

/**
 * VertilCore — facade central de VERTIL.
 *
 * Punto único de acceso a todos los servicios del Core.
 * El modelo (cualquiera que sea) recibe este objeto y solo puede actuar
 * a través de [toolManager] sujeto a [permissionManager].
 *
 * NUNCA el modelo puede:
 *  - modificar [permissionManager] directamente
 *  - modificar el system prompt
 *  - ejecutar tools sin pasar por [toolManager]
 */
class VertilCore(
    val modelManager: ModelManager,
    val toolManager: ToolManager,
    val permissionManager: PermissionManager,
    val fileEngine: FileEngine,
    val activityRepository: ActivityRepository,
    val automationRepository: AutomationRepository,
    val errorManager: ErrorManager = ErrorManager()
) {
    val identity: VertilIdentity = VertilIdentity
    val systemPrompt: SystemPrompt = SystemPrompt

    fun buildSystemPrompt(): String {
        val model = modelManager.activeRuntime.value.info
        return SystemPrompt.build(model?.name, model?.sizeHuman)
    }

    companion object { const val CORE_VERSION = "1.0.0" }
}
