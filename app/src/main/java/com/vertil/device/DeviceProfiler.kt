package com.vertil.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/**
 * Perfila el dispositivo para evaluar compatibilidad con un modelo local.
 */
class DeviceProfiler(private val context: Context) {

    data class Profile(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val sdkInt: Int,
        val cpuAbis: List<String>,
        val cpuCores: Int,
        val ramTotalBytes: Long,
        val ramAvailableBytes: Long,
        val jvmMaxHeapBytes: Long,
        val storageTotalBytes: Long,
        val storageAvailableBytes: Long
    ) {
        val ramTotalMb: Long get() = ramTotalBytes / 1024 / 1024
        val ramAvailableMb: Long get() = ramAvailableBytes / 1024 / 1024
        val jvmMaxHeapMb: Long get() = jvmMaxHeapBytes / 1024 / 1024
        val storageAvailableMb: Long get() = storageAvailableBytes / 1024 / 1024
    }

    fun profile(): Profile {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val stat = StatFs(context.filesDir.absolutePath)

        return Profile(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            cpuAbis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = rt.availableProcessors(),
            ramTotalBytes = mi.totalMem,
            ramAvailableBytes = mi.availMem,
            jvmMaxHeapBytes = rt.maxMemory(),
            storageTotalBytes = stat.totalBytes,
            storageAvailableBytes = stat.availableBytes
        )
    }
}

/**
 * Evalúa compatibilidad de un modelo con el dispositivo.
 *
 * NO afirma velocidades de generación que no se han medido.
 * Solo marca ACEPTABLE / AJUSTADO / INSUFICIENTE basándose en memoria.
 */
class CompatibilityAssessor {

    enum class MemoryFit { ACEPTABLE, AJUSTADO, INSUFICIENTE }
    enum class ExpectedPerformance { ALTO, MODERADO, BAJO, NO_MEDIBLE }

    data class Assessment(
        val ramAvailableMb: Long,
        val modelSizeMb: Long,
        val cpuCores: Int,
        val abiCompatible: Boolean,
        val memoryFit: MemoryFit,
        val expectedPerformance: ExpectedPerformance,
        val notes: List<String>
    )

    fun assess(profile: DeviceProfiler.Profile, modelSizeBytes: Long): Assessment {
        val ramMb = profile.ramAvailableMb
        val modelMb = modelSizeBytes / 1024 / 1024

        // Estimación: en runtime, un modelo cuantizado Q4 ocupa ~1.3x su tamaño en RAM
        // (pesos + KV cache + buffers). Para ONNX float32 ~3x.
        val estimatedRamNeeded = (modelMb * 1.5).toLong()

        val memFit = when {
            estimatedRamNeeded < ramMb * 0.5 -> MemoryFit.ACEPTABLE
            estimatedRamNeeded < ramMb * 0.8 -> MemoryFit.AJUSTADO
            else -> MemoryFit.INSUFICIENTE
        }

        val abiOk = profile.cpuAbis.any { it in setOf("arm64-v8a", "x86_64") }

        val perf = when {
            !abiOk -> ExpectedPerformance.NO_MEDIBLE
            memFit == MemoryFit.INSUFICIENTE -> ExpectedPerformance.NO_MEDIBLE
            profile.cpuCores >= 6 && memFit == MemoryFit.ACEPTABLE -> ExpectedPerformance.MODERADO
            profile.cpuCores >= 4 && memFit != MemoryFit.INSUFICIENTE -> ExpectedPerformance.MODERADO
            else -> ExpectedPerformance.BAJO
        }

        val notes = mutableListOf<String>()
        if (!abiOk) notes.add("ABI no compatible (se requiere arm64-v8a o x86_64)")
        if (memFit == MemoryFit.AJUSTADO) notes.add("Memoria ajustada: cierra otras apps antes de cargar")
        if (memFit == MemoryFit.INSUFICIENTE) notes.add("Memoria insuficiente para este modelo")
        if (profile.cpuCores < 4) notes.add("CPU de bajo núcleo: la generación será lenta")

        return Assessment(
            ramAvailableMb = ramMb,
            modelSizeMb = modelMb,
            cpuCores = profile.cpuCores,
            abiCompatible = abiOk,
            memoryFit = memFit,
            expectedPerformance = perf,
            notes = notes
        )
    }
}
