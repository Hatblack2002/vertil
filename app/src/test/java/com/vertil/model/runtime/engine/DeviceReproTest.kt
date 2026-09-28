package com.vertil.model.runtime.engine

import com.vertil.model.packaging.ModelPackageLoader
import com.vertil.tokenizer.HfConfigs
import com.vertil.tokenizer.HfTokenizer
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * DIAGNÓSTICO — reproduce EXACTAMENTE la entrada que la app Android envía al
 * motor en el dispositivo: SystemPrompt completo de VERTIL + turno user,
 * con el modelo de aceptación real. Sin simulación: si el modelo produce
 * texto incoherente, este test lo demuestra con la salida literal.
 */
class DeviceReproTest {

    companion object {
        private const val MODEL_FILE = "model_q4f16.onnx"

        private lateinit var engine: OnnxLmEngine
        private lateinit var tokenizer: HfTokenizer
        private lateinit var tokenizerConfig: HfConfigs.TokenizerConfig

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val dir = sequenceOf(
                System.getProperty("vertil.test.modelDir"),
                System.getenv("VERTIL_TEST_MODEL_DIR"),
                "/home/z/vertil-assets/smolm2"
            ).filterNotNull().map(::File)
                .firstOrNull { it.exists() && File(it, MODEL_FILE).exists() }
            org.junit.Assume.assumeTrue("modelo no disponible — OMITIDO", dir != null)
            val pkg = ModelPackageLoader.discover(File(dir, MODEL_FILE)).getOrNull() ?: error("paquete inválido")

            tokenizer = HfTokenizer.fromJson(pkg.tokenizerFile.readText())
            val modelConfig = pkg.configFile?.readText()?.let { HfConfigs.parseModelConfig(it) }!!
            val genConfig = pkg.generationConfigFile?.readText()?.let { HfConfigs.parseGenerationConfig(it) }
            tokenizerConfig = pkg.tokenizerConfigFile?.readText()?.let { HfConfigs.parseTokenizerConfig(it) }!!

            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val session = env.createSession(pkg.onnxFile.absolutePath, ai.onnxruntime.OrtSession.SessionOptions().apply {
                runCatching { setMemoryPatternOptimization(false) }
            })
            val plan = ExecutionPlanner.plan(session, modelConfig).getOrNull()!!
            engine = OnnxLmEngine(env, session, plan, modelConfig, tokenizer).apply {
                configure(OnnxLmEngine.defaultConfig(genConfig, modelConfig, null))
            }
        }

        /** SystemPrompt EXACTO que VertilCore.buildSystemPrompt() construye para un paquete "model". */
        private fun appSystemPrompt(modelName: String, modelSize: String): String = buildString {
            appendLine("# Identidad")
            appendLine("Tu nombre es VERTIL.")
            appendLine("Fuiste desarrollada por Vertil Jivenson.")
            appendLine("Eres un entorno local inteligente, no un simple chatbot.")
            appendLine("Utilizas como motor lingüístico un modelo local llamado \"$modelName\"")
            appendLine("(tamaño aproximado: $modelSize).")
            appendLine("Pero tu identidad, tu comportamiento y tus políticas te pertenecen a ti, no al modelo.")
            appendLine()
            appendLine("# Comportamiento")
            appendLine("- Responde de forma clara, concisa y útil.")
            appendLine("- Admite cuando no sabes algo. No inventes información.")
            appendLine("- Cuando necesites hacer algo en el dispositivo (mover archivos, crear carpetas, etc.), pídelo explícitamente usando las herramientas disponibles.")
            appendLine("- NUNCA afirmes haber ejecutado una acción que no se realizó mediante una herramienta.")
            appendLine("- NUNCA afirms tener acceso a APIs sensibles del sistema directamente. Solo puedes actuar a través de las herramientas definidas por VERTIL CORE.")
            appendLine()
            appendLine("# Uso de herramientas")
            appendLine("- Las herramientas son la única forma de interactuar con el dispositivo.")
            appendLine("- Cada herramienta requiere permisos específicos. Algunas requieren confirmación del usuario.")
            appendLine("- Si una herramienta devuelve ERROR, comunica al usuario el motivo y propón alternativas.")
            appendLine("- No intentes repetir una herramienta que ha sido denegada sin modificar el enfoque.")
            appendLine()
            appendLine("# Seguridad")
            appendLine("- No solicites al usuario credenciales ni información sensible innecesaria.")
            appendLine("- No propongas ejecutar comandos arbitrarios del sistema.")
            appendLine("- Si una acción requiere confirmación del usuario, explícala claramente antes de pedirla.")
            appendLine()
            appendLine("# Información insuficiente")
            appendLine("- Si no tienes suficiente información para responder, pide aclaración.")
            appendLine("- Si una herramienta no está disponible, indícalo y propón una alternativa.")
            appendLine()
            appendLine("# Versión de políticas")
            appendLine("Policy version: 1.0.0")
            appendLine("Tool API version: 1.0")
        }

        private fun run(label: String, turns: List<ChatTurn>, maxNew: Int) {
            val prompt = ChatTemplateEngine.buildPrompt(turns, tokenizerConfig, tokenizer).prompt
            val ids = tokenizer.encode(prompt, addSpecialTokens = false)
            val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = maxNew))
            assertTrue(out.isSuccess())
            val v = out.getOrNull()!!
            println("[repro:$label] promptTokens=${ids.size} outTokens=${v.ids.size} fin=${v.finishReason}")
            println("[repro:$label] PROMPT >>> ${prompt.replace("\n", "\\n").take(200)}...")
            println("[repro:$label] RESPUESTA >>> '${v.text}'")
        }
    }

    @Test
    fun `A - sin system prompt (como los tests de aceptacion JVM)`() {
        run("sin-sys", listOf(ChatTurn("user", "Hola, ¿quién eres?")), 64)
    }

    @Test
    fun `B - con el SystemPrompt completo de la app (como en el dispositivo)`() {
        val sys = appSystemPrompt("model", "111 MB")
        run("con-sys", listOf(ChatTurn("system", sys), ChatTurn("user", "Hola, ¿quién eres?")), 64)
    }

    @Test
    fun `C - con system prompt corto alternativo`() {
        val sys = "Eres VERTIL, un asistente local. Responde de forma breve y clara."
        run("sys-corto", listOf(ChatTurn("system", sys), ChatTurn("user", "Hola, ¿quién eres?")), 64)
    }

    @Test
    fun `D - SystemPrompt completo con maxNewTokens por defecto de la app (256)`() {
        val sys = appSystemPrompt("model", "111 MB")
        run("con-sys-256", listOf(ChatTurn("system", sys), ChatTurn("user", "Hola, ¿quién eres?")), 256)
    }
}
