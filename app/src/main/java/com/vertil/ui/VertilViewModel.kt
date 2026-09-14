package com.vertil.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vertil.automation.AutomationRule
import com.vertil.automation.AutomationValidator
import com.vertil.automation.Trigger
import com.vertil.automation.TriggerType
import com.vertil.automation.Action
import com.vertil.automation.ActionType
import com.vertil.chat.ChatMessage
import com.vertil.chat.ChatRole
import com.vertil.chat.ChatSessionState
import com.vertil.core.VertilResult
import com.vertil.core.identity.VertilIdentity
import com.vertil.core.log.VertilLog
import com.vertil.device.CompatibilityAssessor
import com.vertil.di.ServiceLocator
import com.vertil.model.ModelInfo
import com.vertil.model.ModelState
import com.vertil.permissions.PermissionRequest
import com.vertil.tools.ToolInput
import com.vertil.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class HomeUiState(
    val activeModel: ModelInfo? = null,
    val modelState: ModelState = ModelState.INSTALLED,
    val ramAvailableMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val taskCount: Int = 0,
    val grantedPermissionCount: Int = 0,
    val toolCount: Int = 0,
    val activityCount: Int = 0,
    val isLoading: Boolean = false
)

class VertilViewModel : ViewModel() {

    private val core = ServiceLocator.core
    private val deviceProfiler = ServiceLocator.deviceProfiler
    private val assessor = ServiceLocator.compatibilityAssessor

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _chat = MutableStateFlow(ChatSessionState())
    val chat: StateFlow<ChatSessionState> = _chat.asStateFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _activity = MutableStateFlow<List<com.vertil.activity.ActivityEntry>>(emptyList())
    val activity: StateFlow<List<com.vertil.activity.ActivityEntry>> = _activity.asStateFlow()

    private val _automations = MutableStateFlow<List<AutomationRule>>(emptyList())
    val automations: StateFlow<List<AutomationRule>> = _automations.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PermissionRequest>> = _pendingRequests.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    init {
        observeModels()
        observeActivity()
        observeAutomations()
        observePermissions()
        refreshHome()
        seedGreeting()
    }

    private fun observeModels() {
        viewModelScope.launch {
            core.modelManager.models.collectLatest { list ->
                _models.value = list
                val active = list.firstOrNull { it.isActive }
                _home.value = _home.value.copy(
                    activeModel = active,
                    modelState = active?.state ?: ModelState.INSTALLED
                )
                _chat.value = _chat.value.copy(
                    activeModelName = active?.name,
                    activeModelSize = active?.sizeHuman
                )
            }
        }
    }

    private fun observeActivity() {
        viewModelScope.launch {
            core.activityRepository.recent.collectLatest { entries ->
                _activity.value = entries
                _home.value = _home.value.copy(activityCount = entries.size)
            }
        }
    }

    private fun observeAutomations() {
        viewModelScope.launch {
            core.automationRepository.rules.collectLatest { rules ->
                _automations.value = rules
            }
        }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            core.permissionManager.pending.collectLatest { reqs ->
                _pendingRequests.value = reqs
                _home.value = _home.value.copy(
                    grantedPermissionCount = reqs.size + 4 // base 4 del nivel 0
                )
            }
        }
    }

    fun refreshHome() {
        val profile = deviceProfiler.profile()
        _home.value = _home.value.copy(
            ramAvailableMb = profile.ramAvailableMb,
            ramTotalMb = profile.ramTotalMb,
            toolCount = core.toolManager.count()
        )
    }

    private fun seedGreeting() {
        _chat.value = _chat.value.copy(
            messages = listOf(
                ChatMessage(
                    timestamp = System.currentTimeMillis(),
                    role = ChatRole.ASSISTANT,
                    content = VertilIdentity.GREETING
                )
            )
        )
    }

    // === Chat ===

    fun sendMessage(text: String) {
        if (text.isBlank() || _chat.value.isGenerating) return
        val userMsg = ChatMessage(
            timestamp = System.currentTimeMillis(),
            role = ChatRole.USER,
            content = text
        )
        _chat.value = _chat.value.copy(
            messages = _chat.value.messages + userMsg,
            isGenerating = true,
            error = null
        )

        viewModelScope.launch {
            val systemPrompt = core.buildSystemPrompt()
            val result = core.modelManager.generate(text, systemPrompt)
            when (result) {
                is VertilResult.Success -> {
                    val gen = result.value
                    val assistantMsg = ChatMessage(
                        timestamp = System.currentTimeMillis(),
                        role = ChatRole.ASSISTANT,
                        content = gen.text,
                        tokensGenerated = gen.tokensGenerated,
                        durationMs = gen.durationMs,
                        tokensPerSecond = gen.tokensPerSecond,
                        modelId = core.modelManager.activeRuntime.value.info?.id
                    )
                    _chat.value = _chat.value.copy(
                        messages = _chat.value.messages + assistantMsg,
                        isGenerating = false
                    )
                }
                is VertilResult.Failure -> {
                    val errorMsg = ChatMessage(
                        timestamp = System.currentTimeMillis(),
                        role = ChatRole.ASSISTANT,
                        content = "Error: ${result.message}",
                        isError = true
                    )
                    _chat.value = _chat.value.copy(
                        messages = _chat.value.messages + errorMsg,
                        isGenerating = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun cancelGeneration() {
        core.modelManager.cancelGeneration()
        _chat.value = _chat.value.copy(isGenerating = false)
    }

    fun clearChat() {
        seedGreeting()
    }

    // === Models ===

    fun importModel(context: Context, uri: android.net.Uri, name: String?) {
        viewModelScope.launch {
            // Copiar URI a archivo temporal, luego importar
            val tmp = java.io.File(context.cacheDir, "import_${System.currentTimeMillis()}.bin")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    showSnack("No se pudo abrir el archivo"); return@launch
                }
                val result = core.modelManager.importFromFile(tmp.absolutePath, name)
                when (result) {
                    is VertilResult.Success -> showSnack("Modelo importado: ${result.value.name}")
                    is VertilResult.Failure -> showSnack("Error: ${result.message}")
                }
            } catch (t: Throwable) {
                showSnack("Error: ${t.message}")
            } finally {
                tmp.delete()
            }
        }
    }

    fun setActiveModel(id: String) {
        viewModelScope.launch {
            val result = core.modelManager.setActive(id)
            when (result) {
                is VertilResult.Success -> showSnack("Modelo activado: ${result.value.name}")
                is VertilResult.Failure -> showSnack("Error: ${result.message}")
            }
        }
    }

    fun loadActiveModel() {
        viewModelScope.launch {
            val result = core.modelManager.loadActive()
            when (result) {
                is VertilResult.Success -> showSnack("Modelo cargado: ${result.value.name}")
                is VertilResult.Failure -> showSnack("Error: ${result.message}")
            }
        }
    }

    fun unloadActiveModel() {
        viewModelScope.launch {
            val result = core.modelManager.unloadActive()
            when (result) {
                is VertilResult.Success -> showSnack("Modelo descargado")
                is VertilResult.Failure -> showSnack("Error: ${result.message}")
            }
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            val result = core.modelManager.delete(id)
            when (result) {
                is VertilResult.Success -> showSnack("Modelo eliminado")
                is VertilResult.Failure -> showSnack("Error: ${result.message}")
            }
        }
    }

    fun verifyHash(id: String) {
        viewModelScope.launch {
            val result = core.modelManager.verifyHash(id)
            when (result) {
                is VertilResult.Success -> showSnack(if (result.value) "Integridad OK" else "Hash no coincide")
                is VertilResult.Failure -> showSnack("Error: ${result.message}")
            }
        }
    }

    // === Permissions ===

    fun confirmPermission(requestId: String, granted: Boolean) {
        core.permissionManager.confirm(requestId, granted)
    }

    // === Automations ===

    fun createAutomation(name: String, folder: String, ext: String, destination: String) {
        viewModelScope.launch {
            val rule = AutomationRule(
                id = "auto_${System.currentTimeMillis()}",
                name = name,
                description = "Archivos .$ext en $folder → $destination",
                trigger = Trigger(TriggerType.FILE_EXTENSION, mapOf("extension" to ext, "folder" to folder)),
                action = Action(ActionType.MOVE_TO_FOLDER, mapOf("destination" to destination)),
                createdAt = System.currentTimeMillis()
            )
            val validation = AutomationValidator.validate(rule)
            if (!validation.isValid) {
                showSnack("Inválido: ${validation.errors.joinToString()}")
                return@launch
            }
            val result = core.automationRepository.save(rule)
            when (result) {
                is VertilResult.Success -> showSnack("Regla creada")
                is VertilResult.Failure -> showSnack("Error: ${result.message}")
            }
        }
    }

    fun deleteAutomation(id: String) {
        viewModelScope.launch { core.automationRepository.delete(id) }
    }

    fun toggleAutomation(id: String, enabled: Boolean) {
        viewModelScope.launch { core.automationRepository.setEnabled(id, enabled) }
    }

    // === Activity ===

    fun clearActivity() {
        viewModelScope.launch { core.activityRepository.clear() }
    }

    // === Snackbar ===

    fun showSnack(msg: String) { _snackbar.value = msg }
    fun consumeSnack() { _snackbar.value = null }

    // === Compatibility ===

    fun assessCompatibility(model: ModelInfo): CompatibilityAssessor.Assessment {
        return assessor.assess(deviceProfiler.profile(), model.sizeBytes)
    }
}
