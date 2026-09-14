package com.vertil.tools.impl

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.vertil.core.VertilResult
import com.vertil.permissions.PermissionCapability
import com.vertil.tools.Tool
import com.vertil.tools.ToolInput
import com.vertil.tools.ToolResult

/**
 * DeviceInfoTool — Información del dispositivo.
 * Nivel: READ.
 */
class DeviceInfoTool(private val context: Context) : Tool {
    override val id = "device_info"
    override val name = "Información del dispositivo"
    override val description = "Devuelve información técnica del dispositivo: CPU, RAM, almacenamiento, Android."
    override val capabilities = listOf(PermissionCapability.ACCESS_DEVICE_INFO)

    override fun validate(input: ToolInput): Boolean = true

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val stat = StatFs(context.filesDir.absolutePath)

        val data = mapOf(
            "manufacturer" to (Build.MANUFACTURER ?: "unknown"),
            "model" to (Build.MODEL ?: "unknown"),
            "android_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "cpu_abis" to Build.SUPPORTED_ABIS.joinToString(","),
            "cpu_cores" to rt.availableProcessors().toString(),
            "ram_total_mb" to (mi.totalMem / 1024 / 1024).toString(),
            "ram_available_mb" to (mi.availMem / 1024 / 1024).toString(),
            "jvm_max_heap_mb" to (rt.maxMemory() / 1024 / 1024).toString(),
            "storage_total_mb" to (stat.totalBytes / 1024 / 1024).toString(),
            "storage_available_mb" to (stat.availableBytes / 1024 / 1024).toString()
        )
        return VertilResult.ok(
            ToolResult.Success(operation = id, data = data,
                message = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        )
    }
}

/**
 * ModelInfoTool — Información del modelo activo.
 * Nivel: READ.
 */
class ModelInfoTool(private val provider: () -> com.vertil.model.LocalModel?) : Tool {
    override val id = "model_info"
    override val name = "Información del modelo"
    override val description = "Devuelve información del modelo local actualmente cargado."
    override val capabilities = listOf(PermissionCapability.ACCESS_MODEL_INFO)

    override fun validate(input: ToolInput): Boolean = true

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val model = provider()
        if (model == null || model.info == null) {
            return VertilResult.ok(
                ToolResult.Error(operation = id, code = "NO_MODEL",
                    message = "No hay modelo cargado")
            )
        }
        val info = model.info!!
        val data = mapOf(
            "name" to info.name,
            "format" to info.format.label,
            "size" to info.sizeHuman,
            "quantization" to (info.quantization ?: "—"),
            "state" to model.state.name,
            "runtime_id" to model.runtimeId
        )
        return VertilResult.ok(
            ToolResult.Success(operation = id, data = data,
                message = "Modelo: ${info.name} (${info.sizeHuman}, ${info.format.label})")
        )
    }
}

/**
 * TaskTool — Lista/consulta tareas en segundo plano activas.
 * Nivel: READ (no ejecuta, solo consulta).
 */
class TaskTool(private val provider: () -> List<String>) : Tool {
    override val id = "task_list"
    override val name = "Lista de tareas"
    override val description = "Lista las tareas activas en segundo plano."
    override val capabilities = listOf(PermissionCapability.ACCESS_DEVICE_INFO)

    override fun validate(input: ToolInput): Boolean = true

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val tasks = provider()
        return VertilResult.ok(
            ToolResult.Success(operation = id,
                data = mapOf("count" to tasks.size.toString(),
                    "tasks" to tasks.joinToString("\n")),
                message = "${tasks.size} tareas activas")
        )
    }
}
