package com.vertil.model.runtime.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vertil.core.VertilResult
import com.vertil.model.packaging.ModelPackageLoader
import com.vertil.tokenizer.HfConfigs
import com.vertil.tokenizer.HfTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * REGRESIÓN del bug físico confirmado en dispositivo (Android, SmolLM2 q4f16):
 *
 *   buildSequenceTensors() construía attention_mask con shape [1, S] donde
 *   S = ids.size (S=1 en decode). Con past_key_values de longitud pastLen, la
 *   máscara debe representar la secuencia TOTAL: [1, pastLen + S], todos unos.
 *
 * Síntomas que este fix corrige (reporte físico 2026-09):
 *   - texto degenerado que repite "Hola." durante todo el decode
 *   - nunca llega EOS → corre hasta maxNewTokens (512) → OOM
 *
 * CONTRATO (decoder causal ONNX con past_key_values, export HF):
 *   prefill: input_ids [1,P], attention_mask [1,P], position_ids [1,P] (0..P-1)
 *   decode:  input_ids [1,1], attention_mask [1,pastLen+1] (unos),
 *            position_ids [1,1] (=pastLen), past_key_values len=pastLen
 *
 * El grafo ONNX REAL se localiza vía -Dvertil.test.modelDir /
 * VERTIL_TEST_MODEL_DIR / /home/z/vertil-assets/smolm2. Sin modelo, los tests
 * se OMITEN (JUnit Assume) — nunca se simula un resultado.
 */
class OnnxEngineDecodeMaskTest {

    companion object {
        private const val MODEL_FILE = "model_q4f16.onnx"

        private lateinit var env: OrtEnvironment
        private lateinit var session: OrtSession
        private lateinit var tokenizer: HfTokenizer
        private lateinit var tokenizerConfig: HfConfigs.TokenizerConfig
        private lateinit var modelConfig: HfConfigs.ModelConfig
        private lateinit var plan: ExecutionPlan
        private lateinit var engine: OnnxLmEngine

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

            val pkg = ModelPackageLoader.discover(File(dir, MODEL_FILE)).getOrNull()
                ?: error("paquete inválido")
            tokenizer = HfTokenizer.fromJson(pkg.tokenizerFile.readText())
            modelConfig = pkg.configFile?.readText()?.let { HfConfigs.parseModelConfig(it) }!!
            val genConfig = pkg.generationConfigFile?.readText()?.let { HfConfigs.parseGenerationConfig(it) }
            tokenizerConfig = pkg.tokenizerConfigFile?.readText()?.let { HfConfigs.parseTokenizerConfig(it) }!!

            env = OrtEnvironment.getEnvironment()
            session = env.createSession(pkg.onnxFile.absolutePath, OrtSession.SessionOptions().apply {
                runCatching { setMemoryPatternOptimization(false) }
            })
            plan = ExecutionPlanner.plan(session, modelConfig).getOrNull()!!
            engine = OnnxLmEngine(env, session, plan, modelConfig, tokenizer).apply {
                configure(OnnxLmEngine.defaultConfig(genConfig, modelConfig, null))
            }
        }

        private fun maskOf(t: ai.onnxruntime.OnnxTensor): LongArray {
            val buf = t.longBuffer ?: error("máscara no es int64")
            val arr = LongArray(buf.remaining())
            buf.get(arr)
            buf.position(0)
            return arr
        }
    }

    // ======== Caso directo: la máscara en DECODE cubre pastLen + S ========

    @Test
    fun `decode con pastLen 40 - mask de 41 unos, position_ids 40, input_ids 1 token`() {
        val tensors = engine.buildSequenceTensors(ids = listOf(7), pastLen = 40)
        try {
            // input_ids [1,1] = {7}
            val ids = tensors[plan.tokenInput]!!
            assertEquals(listOf(1L, 1L).toList(), ids.info.getShape().toList())
            assertEquals(7L, ids.longBuffer!!.get(0))

            // attention_mask [1, pastLen+1] = [1,41], TODOS unos — el fix
            val mask = tensors[plan.attentionMaskInput]!!
            assertEquals("máscara debe cubrir pastLen+S", listOf(1L, 41L), mask.info.getShape().toList())
            val maskVals = maskOf(mask)
            assertEquals(41, maskVals.size)
            assertTrue("todos los valores deben ser 1", maskVals.all { it == 1L })

            // position_ids [1,1] = {40}
            val pos = tensors[plan.positionIdsInput]!!
            assertEquals(listOf(1L, 1L), pos.info.getShape().toList())
            assertEquals(40L, pos.longBuffer!!.get(0))
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    // ======== Caso prefill: máscara [1,P] (pastLen=0) sigue intacta ========

    @Test
    fun `prefill con pastLen 0 - mask de unos y posiciones 0 a P-1`() {
        val ids = listOf(11, 22, 33, 44)
        val tensors = engine.buildSequenceTensors(ids = ids, pastLen = 0)
        try {
            val mask = tensors[plan.attentionMaskInput]!!
            assertEquals(listOf(1L, 4L), mask.info.getShape().toList())
            val maskVals = maskOf(mask)
            assertTrue(maskVals.all { it == 1L })

            val pos = tensors[plan.positionIdsInput]!!
            val posBuf = pos.longBuffer!!
            val posVals = LongArray(posBuf.remaining()) { 0L }.also { posBuf.get(it); posBuf.position(0) }
            assertEquals(listOf(0L, 1L, 2L, 3L), posVals.toList())
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    // ======== Regresión e2e: el decode NO degenera en repetición ========

    @Test
    fun `e2e - la generacion multi-token no degenera en repeticion y reporta metricas`() {
        val prompt = ChatTemplateEngine.buildPrompt(
            listOf(ChatTurn("user", "Cuenta: uno, dos,")),
            tokenizerConfig, tokenizer
        ).prompt
        val ids = tokenizer.encode(prompt, addSpecialTokens = false)
        val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 24))
        assertTrue("generate OK: ${(out as? VertilResult.Failure)?.message}", out.isSuccess())
        val v = out.getOrNull()!!

        // Anti-degeneración: ningún id repetido 8+ veces consecutivas
        // (con la máscara rota el decode entraba en bucle "Hola."/"Hola.")
        var maxRun = 1; var run = 1
        for (i in 1 until v.ids.size) {
            run = if (v.ids[i] == v.ids[i - 1]) run + 1 else 1
            if (run > maxRun) maxRun = run
        }
        assertTrue(
            "decode degenerado detectado (run=$maxRun): ${v.ids.take(24)} — ¿máscara rota?",
            maxRun < 8
        )
        assertTrue(v.text.isNotBlank())

        // Métricas obligatorias del reporte físico (spec §C)
        println("[mask-e2e] tokens=${v.ids.size} firstTokenMs=${v.firstTokenMs} " +
            "tok/s=${"%.1f".format(v.tokensPerSecond ?: 0f)} finish=${v.finishReason} " +
            "prefillMs=${v.prefillMs} durMs=${v.durationMs}")
        println("[mask-e2e] texto='${v.text.take(160)}'")
        assertNotNull(v.firstTokenMs)
    }
}
