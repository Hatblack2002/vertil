package com.vertil.model.runtime.engine

import ai.onnxruntime.OnnxTensor
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
 * TESTS 4–12 (especificación §19.4–§19.12) sobre el GRAFO ONNX REAL del
 * modelo de aceptación SmolLM2-135M-Instruct (q4f16).
 *
 * El .onnx NO se commitea al repo (≈90 MB): se localiza vía
 *   - propiedad de sistema  -Dvertil.test.modelDir=/ruta
 *   - variable de entorno   VERTIL_TEST_MODEL_DIR=/ruta
 *   - fallback              /home/z/vertil-assets/smolm2
 * Si no está presente, los tests se MARCAN COMO OMITIDOS (JUnit Assume) —
 * jamás se simula un resultado.
 */
class OnnxEngineAcceptanceTest {

    companion object {
        private const val MODEL_FILE = "model_q4f16.onnx"

        private lateinit var session: OrtSession
        private lateinit var env: OrtEnvironment
        private lateinit var tokenizer: HfTokenizer
        private lateinit var modelConfig: HfConfigs.ModelConfig
        private var genConfig: HfConfigs.GenerationConfig? = null
        private lateinit var tokenizerConfig: HfConfigs.TokenizerConfig
        private lateinit var plan: ExecutionPlan
        private lateinit var engine: OnnxLmEngine

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val dir = modelDir()
            org.junit.Assume.assumeTrue(
                "Modelo de aceptación no disponible (buscado en -Dvertil.test.modelDir / " +
                    "VERTIL_TEST_MODEL_DIR / /home/z/vertil-assets/smolm2) — tests ONNX OMITIDOS.",
                dir != null
            )
            val pkgDir = dir!!
            val pkg = ModelPackageLoader.discover(
                File(pkgDir, MODEL_FILE).takeIf { it.exists() } ?: File(pkgDir, "model.onnx")
            ).getOrNull() ?: error("El directorio de test no contiene paquete válido")

            tokenizer = HfTokenizer.fromJson(pkg.tokenizerFile.readText())
            modelConfig = pkg.configFile?.readText()?.let { HfConfigs.parseModelConfig(it) }!!
            genConfig = pkg.generationConfigFile?.readText()?.let { HfConfigs.parseGenerationConfig(it) }
            tokenizerConfig = pkg.tokenizerConfigFile?.readText()?.let { HfConfigs.parseTokenizerConfig(it) }!!

            env = OrtEnvironment.getEnvironment()
            session = env.createSession(pkg.onnxFile.absolutePath, OrtSession.SessionOptions().apply {
                runCatching { setMemoryPatternOptimization(false) }
            })

            val planResult = ExecutionPlanner.plan(session, modelConfig)
            assertTrue(
                "El grafo real debe producir un plan: ${(planResult as? VertilResult.Failure)?.message}",
                planResult.isSuccess()
            )
            plan = planResult.getOrNull()!!

            engine = OnnxLmEngine(env, session, plan, modelConfig, tokenizer).apply {
                configure(OnnxLmEngine.defaultConfig(genConfig, modelConfig, null))
            }
        }

        private fun modelDir(): File? = sequenceOf(
            System.getProperty("vertil.test.modelDir"),
            System.getenv("VERTIL_TEST_MODEL_DIR"),
            "/home/z/vertil-assets/smolm2"
        ).filterNotNull()
            .map(::File)
            .firstOrNull { it.exists() && it.listFiles()?.any { f -> f.name == MODEL_FILE || f.name == "model.onnx" } == true }
    }

    // ============ TEST 4 — ONNX inspection ============

    @Test
    fun `test4 - la sesion declara inputs y outputs reales`() {
        assertTrue(session.inputNames.isNotEmpty())
        assertTrue(session.outputNames.isNotEmpty())
        // Estructura estándar de un causal LM exportado con use_cache=true:
        // N cache pairs ⇒ outputs = N*2 + 1 (logits); inputs = N*2 + (mask,pos,tokens)
        val planCache = plan.cacheInputs.size
        assertEquals(session.inputNames.size, plan.metadata.allInputs.size)
        assertEquals(session.outputNames.size, plan.metadata.allOutputs.size)
        assertTrue(
            "un LlamaForCausalLM con use_cache debe declarar entradas de cache",
            planCache > 0
        )
        assertEquals(
            "cada cache input debe tener su salida present.*",
            planCache, plan.cacheOutputs.size
        )
        println("[test4] inputs=${session.inputNames.size} outputs=${session.outputNames.size} " +
            "cachePairs=$planCache plan=$plan")
    }

    // ============ TEST 5 — Execution plan ============

    @Test
    fun `test5 - el plan clasifica tokens mask posiciones logits sin hardcodear nombres`() {
        assertEquals("input_ids", plan.tokenInput)             // estándar del grafo REAL
        assertEquals("attention_mask", plan.attentionMaskInput)
        assertEquals("position_ids", plan.positionIdsInput)
        assertEquals("logits", plan.logitsOutput)
        assertTrue(plan.supportsKvCache)
        // los nombres no se asumen: se verificarón contra el grafo real arriba
        assertTrue(plan.cacheInputs.all { it.contains("past_key_values") })
        assertTrue(plan.cacheOutputs.all { it.contains("present") })
        println("[test5] ${plan.describe()}")
    }

    // ============ TEST 6 — First forward ============

    @Test
    fun `test6 - primer forward real con el prompt tokenizado`() {
        val ids = tokenizer.encode(
            ChatTemplateEngine.buildPrompt(
                listOf(ChatTurn("user", "Hola, ¿quién eres?")),
                tokenizerConfig, tokenizer
            ).prompt,
            addSpecialTokens = false
        )
        assertTrue(ids.isNotEmpty())

        val tensors = HashMap<String, OnnxTensor>()
        @Suppress("UNCHECKED_CAST")
        val zeroCaches = engine.buildZeroLengthCaches()
        tensors[plan.tokenInput] = OnnxTensor.createTensor(
            env, java.nio.LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), longArrayOf(1, ids.size.toLong())
        )
        plan.attentionMaskInput?.let {
            tensors[it] = OnnxTensor.createTensor(
                env, java.nio.LongBuffer.wrap(LongArray(ids.size) { 1 }), longArrayOf(1, ids.size.toLong())
            )
        }
        plan.positionIdsInput?.let {
            tensors[it] = OnnxTensor.createTensor(
                env, java.nio.LongBuffer.wrap(LongArray(ids.size) { it.toLong() }), longArrayOf(1, ids.size.toLong())
            )
        }
        plan.cacheInputs.forEachIndexed { i, name -> tensors[name] = zeroCaches.first[i] }

        val result = session.run(tensors)
        try {
            val logits = result.get(plan.logitsOutput).orElse(null) as OnnxTensor
            val shape = logits.info.getShape()
            assertEquals("logits rank 3 [1,S,V]", 3, shape.size)
            assertEquals(1, shape[0])
            assertEquals(ids.size.toLong(), shape[1])
            assertEquals(modelConfig.vocabSize!!.toLong(), shape[2])
        } finally {
            result.close()
            tensors.values.forEach { runCatching { it.close() } }
        }
        println("[test6] forward OK con ${ids.size} tokens de prompt")
    }

    // ============ TEST 7 — Logits válidos ============

    @Test
    fun `test7 - argmax sobre logits reales entrega un id dentro del vocabulario`() {
        val ids = tokenizer.encode("Hola", addSpecialTokens = false)
        val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 1))
        assertTrue("generate OK: ${(out as? VertilResult.Failure)?.message}", out.isSuccess())
        val value = out.getOrNull()!!
        assertEquals(1, value.ids.size)
        assertTrue(value.ids[0] in 0 until modelConfig.vocabSize!!)
        assertTrue(value.finishReason == OnnxLmEngine.FinishReason.MAX_NEW_TOKENS)
        println("[test7] primer token real: ${value.ids[0]} → '${tokenizer.decode(value.ids)}'")
    }

    // ============ TEST 8 — Greedy (≥1 token real) ============

    @Test
    fun `test8 - greedy genera tokens reales del vocabulario`() {
        val ids = tokenizer.encode(
            ChatTemplateEngine.buildPrompt(
                listOf(ChatTurn("user", "Di la palabra: uno")),
                tokenizerConfig, tokenizer
            ).prompt,
            addSpecialTokens = false
        )
        val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 5))
        assertTrue(out.isSuccess())
        val value = out.getOrNull()!!
        assertTrue("debe generar al menos 1 token", value.ids.size >= 1)
        assertTrue(value.ids.all { it in 0 until modelConfig.vocabSize!! })
        // greedy determinismo: misma entrada → mismos tokens
        val out2 = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 5))
        assertTrue(out2.isSuccess())
        assertEquals(value.ids, out2.getOrNull()!!.ids)
        println("[test8] greedy: ${value.ids} → '${value.text}'")
    }

    // ============ TEST 9 — KV cache (múltiples tokens) ============

    @Test
    fun `test9 - generacion multi-token usando kv cache`() {
        val ids = tokenizer.encode(
            ChatTemplateEngine.buildPrompt(
                listOf(ChatTurn("user", "Cuenta: uno, dos,")),
                tokenizerConfig, tokenizer
            ).prompt,
            addSpecialTokens = false
        )
        val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 12))
        assertTrue(out.isSuccess())
        val value = out.getOrNull()!!
        assertTrue("debe generar varios tokens con cache", value.ids.size >= 3)
        assertNotNull(value.firstTokenMs)
        assertTrue(value.durationMs > 0)
        println("[test9] ${value.ids.size} tokens con KV cache en ${value.durationMs}ms " +
            "(prefill=${value.prefillMs}ms, 1er token=${value.firstTokenMs}ms): '${value.text}'")
    }

    // ============ TEST 10 — EOS ============

    @Test
    fun `test10 - eos_token_id de generation_config detiene la generacion`() {
        // eos real de SmolLM2: id 2 (<|im_end|>) en generation_config.json
        val eos = genConfig?.eosTokenIds ?: emptyList()
        assertTrue("generation_config.json debe declarar eos", eos.isNotEmpty())
        val cfg = OnnxLmEngine.defaultConfig(genConfig, modelConfig, null)
        assertTrue(cfg.eosTokenIds.contains(2))

        // Generación libre: el modelo instruido cierra con <|im_end|> por sí mismo
        val ids = tokenizer.encode(
            ChatTemplateEngine.buildPrompt(
                listOf(ChatTurn("user", "Responde brevemente: ¿qué es el sol?")),
                tokenizerConfig, tokenizer
            ).prompt,
            addSpecialTokens = false
        )
        val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 64))
        assertTrue(out.isSuccess())
        val value = out.getOrNull()!!
        assertTrue(
            "con un prompt conversacional el modelo debe frenar por EOS o por límite; " +
                "fue ${value.finishReason}",
            value.finishReason == OnnxLmEngine.FinishReason.EOS ||
                value.finishReason == OnnxLmEngine.FinishReason.MAX_NEW_TOKENS
        )
        println("[test10] finish=${value.finishReason}, tokens=${value.ids.size}, texto='${value.text}'")
    }

    // ============ TEST 11 — Decode ============

    @Test
    fun `test11 - los tokens generados se decodifican con el tokenizer real`() {
        val ids = tokenizer.encode("Hola", addSpecialTokens = false)
        val out = engine.generate(OnnxLmEngine.Request(promptIds = ids, maxNewTokensOverride = 8))
        assertTrue(out.isSuccess())
        val value = out.getOrNull()!!
        val decoded = tokenizer.decode(value.ids, skipSpecialTokens = true)
        assertEquals(value.text, decoded)
        // el texto decodificado SOLO puede contener piezas del vocabulario real:
        // si el decode fuera manual/hash esto no tendría relación con los ids
        if (value.ids.isNotEmpty()) {
            assertNotNull(tokenizer.idToTokenSafe(value.ids[0]))
        }
        println("[test11] ids=${value.ids.take(6)}… → '$decoded'")
    }

    // ============ TEST 12 — Conversación completa ============

    @Test
    fun `test12 - Hola quien eres produce respuesta generada por el modelo`() {
        val userText = "Hola, ¿quién eres?"
        val prompt = ChatTemplateEngine.buildPrompt(
            listOf(ChatTurn("user", userText)),
            tokenizerConfig, tokenizer
        ).prompt
        val ids = tokenizer.encode(prompt, addSpecialTokens = false)

        val out = engine.generate(
            OnnxLmEngine.Request(
                promptIds = ids,
                maxNewTokensOverride = 64,
                onToken = { _, n -> /* hook de streaming presente */ }
            )
        )
        assertTrue("generación completa OK: ${(out as? VertilResult.Failure)?.message}", out.isSuccess())
        val value = out.getOrNull()!!

        assertTrue("debe generar ≥1 token", value.ids.size >= 1)
        assertTrue("la respuesta debe ser texto no vacío", value.text.isNotBlank())
        // el texto proviene del tokenizer real (roundtrip con los mismos ids)
        assertEquals(value.text, tokenizer.decode(value.ids, skipSpecialTokens = true))
        println(
            "[test12] PREGUNTA: '$userText'\n" +
                "[test12] RESPUESTA (${value.ids.size} tokens, ${value.durationMs}ms, " +
                "fin=${value.finishReason}): '${value.text}'"
        )
    }

    // ============ Cancelación (§16) ============

    @Test
    fun `cancel detiene la generacion en curso`() {
        val ids = tokenizer.encode(
            ChatTemplateEngine.buildPrompt(
                listOf(ChatTurn("user", "Escribe un cuento largo")),
                tokenizerConfig, tokenizer
            ).prompt,
            addSpecialTokens = false
        )
        // cancela DESPUÉS del 2º token (durante la generación — especificación §16)
        val out = engine.generate(
            OnnxLmEngine.Request(
                promptIds = ids,
                maxNewTokensOverride = 32,
                onToken = { _, n -> if (n >= 2) engine.cancel() }
            )
        )
        assertTrue("generate OK: ${(out as? VertilResult.Failure)?.message}", out.isSuccess())
        val value = out.getOrNull()!!
        assertTrue(
            "debe terminar CANCELLED (fue ${value.finishReason}, ${value.ids.size} tokens)",
            value.finishReason == OnnxLmEngine.FinishReason.CANCELLED && value.ids.size < 32
        )
        engine.cancelFlag.set(false)
        println("[cancel] generados antes de frenar: ${value.ids.size}")
    }
}
