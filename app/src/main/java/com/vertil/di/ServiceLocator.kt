package com.vertil.di

import android.content.Context
import com.vertil.activity.ActivityRepository
import com.vertil.automation.AutomationRepository
import com.vertil.core.VertilCore
import com.vertil.core.error.ErrorManager
import com.vertil.device.CompatibilityAssessor
import com.vertil.device.DeviceProfiler
import com.vertil.model.ModelManager
import com.vertil.model.ModelRuntimeRegistry
import com.vertil.permissions.PermissionManager
import com.vertil.permissions.PermissionPolicy
import com.vertil.persistence.VertilDatabase
import com.vertil.storage.FileEngine
import com.vertil.tools.ToolManager
import com.vertil.tools.impl.DeviceInfoTool
import com.vertil.tools.impl.FileCopyTool
import com.vertil.tools.impl.FileHashTool
import com.vertil.tools.impl.FileListTool
import com.vertil.tools.impl.FileMoveTool
import com.vertil.tools.impl.FileRenameTool
import com.vertil.tools.impl.FileSearchTool
import com.vertil.tools.impl.FolderCreateTool
import com.vertil.tools.impl.ModelInfoTool
import com.vertil.tools.impl.TaskTool

/**
 * ServiceLocator manual (sin Hilt para no añadir dependencias).
 */
object ServiceLocator {

    lateinit var db: VertilDatabase
        private set
    lateinit var fileEngine: FileEngine
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var toolManager: ToolManager
        private set
    lateinit var modelManager: ModelManager
        private set
    lateinit var activityRepository: ActivityRepository
        private set
    lateinit var automationRepository: AutomationRepository
        private set
    lateinit var deviceProfiler: DeviceProfiler
        private set
    lateinit var compatibilityAssessor: CompatibilityAssessor
        private set
    lateinit var errorManager: ErrorManager
        private set
    lateinit var core: VertilCore
        private set

    @Volatile private var initialized = false

    fun isInitialized(): Boolean = initialized

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext

        db = VertilDatabase.get(appCtx)
        fileEngine = FileEngine(appCtx)
        permissionManager = PermissionManager(PermissionPolicy.DEFAULT)
        errorManager = ErrorManager()
        activityRepository = ActivityRepository(db)
        automationRepository = AutomationRepository(db)
        modelManager = ModelManager(db)
        deviceProfiler = DeviceProfiler(appCtx)
        compatibilityAssessor = CompatibilityAssessor()

        toolManager = ToolManager(permissionManager, errorManager)
        // Registrar las 9 tools iniciales
        toolManager.register(FileSearchTool(fileEngine))
        toolManager.register(FileListTool(fileEngine))
        toolManager.register(FileMoveTool(fileEngine))
        toolManager.register(FileCopyTool(fileEngine))
        toolManager.register(FileRenameTool(fileEngine))
        toolManager.register(FolderCreateTool(fileEngine))
        toolManager.register(FileHashTool(fileEngine))
        toolManager.register(DeviceInfoTool(appCtx))
        toolManager.register(ModelInfoTool { modelManager.activeRuntime.value })
        toolManager.register(TaskTool { emptyList() }) // v1.1: integrar WorkManager

        core = VertilCore(
            modelManager = modelManager,
            toolManager = toolManager,
            permissionManager = permissionManager,
            fileEngine = fileEngine,
            activityRepository = activityRepository,
            automationRepository = automationRepository,
            errorManager = errorManager
        )

        initialized = true
        com.vertil.core.log.VertilLog.i("ServiceLocator",
            "VERTIL CORE ${VertilCore.CORE_VERSION} inicializado — ${toolManager.count()} tools registradas")
    }
}
