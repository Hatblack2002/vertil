package com.vertil.model.runtime

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.TensorInfo
import com.vertil.core.VertilResult
import java.io.File
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

/**
 * Inspección REAL de modelos ONNX mediante ONNX Runtime. Todo lo que devuelve
 * esta clase proviene de la sesión real: nada se inventa.
 *
 *  - [validateOpenable] — validación profunda de importación (§8).
 *  - [inspect] — descripción de entradas/salidas reales (meta 2).
 *  - [createCompatibleInputs] — tensores cero para la prueba de ejecución del
 *    grafo (meta 3). NO es generación de texto (ver OnnxModelRuntime).
 */
object OnnxRuntimeInspector {

    /** Descripción de un tensor declarado por el modelo. */
    data class TensorSpec(
        val name: String,
        val elemType: String,
        val shape: List<Long>   // -1 = dimensión dinámica
    ) {
        override fun toString(): String =
            "$name[type=$elemType, shape=${shape.joinToString("×") { if (it == -1L) "?" else it.toString() }}]"
    }

    /** Inspección completa de un modelo. */
    data class Inspection(
        val inputs: List<TensorSpec>,
        val outputs: List<TensorSpec>
    )

    /** Validación profunda (§8): ONNX Runtime debe poder abrir e inspeccionar. */
    fun validateOpenable(file: File): String? {
        return try {
            createSession(file).use { session ->
                session.inputNames.size
                session.outputNames.size
                null
            }
        } catch (oom: OutOfMemoryError) {
            "Memoria insuficiente para abrir el modelo con ONNX Runtime."
        } catch (t: Throwable) {
            "ONNX Runtime: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Inspección real de inputs/outputs (meta 2). */
    fun inspect(file: File): VertilResult<Inspection> {
        return try {
            createSession(file).use { session ->
                val inputs = session.inputNames.map { name -> specOf(name, session.inputInfo[name]) }
                val outputs = session.outputNames.map { name -> specOf(name, session.outputInfo[name]) }
                VertilResult.ok(Inspection(inputs, outputs))
            }
        } catch (t: Throwable) {
            VertilResult.fail(
                "No se pudo inspeccionar el modelo con ONNX Runtime: ${t.message}",
                t, "OnnxInspector", "INSPECTION_FAILED"
            )
        }
    }

    private fun specOf(name: String, nodeInfo: NodeInfo?): TensorSpec {
        val info = nodeInfo?.getInfo()
        return if (info is TensorInfo) {
            TensorSpec(
                name = name,
                elemType = info.type.toString(),
                shape = info.getShape().map { it }
            )
        } else {
            TensorSpec(name, "no-tensor", emptyList())
        }
    }

    /** Versión pública de [specOf] para el runtime (sesión activa). */
    fun specOfPublic(name: String, nodeInfo: NodeInfo?): TensorSpec = specOf(name, nodeInfo)

    private fun createSession(file: File): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        return env.createSession(file.absolutePath, options)
    }

    /** Abre una sesión ORT real (exposición pública para tests/validación manual). */
    fun createSessionForTest(file: File): OrtSession = createSession(file)

    /**
     * Tensores cero compatibles con las declaraciones reales del modelo (meta 3).
     * Dimensiones dinámicas (-1) se instancian con 1.
     */
    fun createCompatibleInputs(
        env: OrtEnvironment,
        session: OrtSession
    ): VertilResult<Map<String, OnnxTensor>> {
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in session.inputInfo) {
                val info = nodeInfo.getInfo()
                if (info !is TensorInfo) {
                    return VertilResult.fail(
                        "La entrada '$name' no es un tensor (${info?.javaClass?.simpleName}); la prueba mínima no la soporta.",
                        module = "OnnxInspector", code = "UNSUPPORTED_INPUT"
                    )
                }
                val shape = info.getShape().map { if (it <= 0) 1L else it }.toLongArray()
                val total = shape.fold(1L) { acc, d -> if (acc == 0L || d == 0L) 0L else acc * d }
                val tensor = when (info.type) {
                    OnnxJavaType.INT64 ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(total.toInt())), shape)
                    OnnxJavaType.INT32 ->
                        OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(total.toInt())), shape)
                    OnnxJavaType.FLOAT ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(total.toInt())), shape)
                    OnnxJavaType.DOUBLE ->
                        OnnxTensor.createTensor(env, DoubleBuffer.wrap(DoubleArray(total.toInt())), shape)
                    OnnxJavaType.FLOAT16 ->
                        OnnxTensor.createTensor(env, ShortBuffer.wrap(ShortArray(total.toInt())), shape)
                    else -> return VertilResult.fail(
                        "Tipo de entrada '${info.type}' no soportado por la prueba mínima (entrada '$name').",
                        module = "OnnxInspector", code = "UNSUPPORTED_INPUT_TYPE"
                    )
                }
                tensors[name] = tensor
            }
            return VertilResult.ok(tensors)
        } catch (t: Throwable) {
            tensors.values.forEach { runCatching { it.close() } }
            return VertilResult.fail(
                "No se pudieron construir las entradas compatibles: ${t.message}",
                t, "OnnxInspector", "INPUT_BUILD_FAILED"
            )
        }
    }

    /** Descripción de un tensor de salida para el resumen técnico. */
    fun describeOutput(tensor: OnnxTensor): String {
        return try {
            val info = tensor.info
            val shape = info.getShape().joinToString("×") { if (it == -1L) "?" else it.toString() }
            "${info.type}[$shape]"
        } catch (t: Throwable) {
            "?"
        }
    }

    fun sampleValues(tensor: OnnxTensor, maxSamples: Int = 6): String? {
        return try {
            when (val v = tensor.value) {
                is FloatArray -> v.take(maxSamples).joinToString(", ") { "%.4f".format(it) }
                is Array<*> -> v.take(maxSamples).joinToString(", ") { it?.toString() ?: "null" }
                else -> v?.toString()?.take(80)
            }
        } catch (t: Throwable) {
            null
        }
    }
}
