package com.vertil.tokenizer

import com.vertil.model.runtime.engine.ChatTemplateEngine
import com.vertil.model.runtime.engine.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TEST 2 (tokenizer encode/decode) y TEST 3 (chat template) — especificación
 * §19.2/§19.3.
 *
 * Usa el tokenizer.json REAL del modelo de aceptación SmolLM2-135M-Instruct.
 * Los valores dorados fueron generados con las librerías OFICIALES de
 * Hugging Face (tokenizers 0.23 + jinja2 3.1) sobre estos mismos archivos:
 * NO derivan de la implementación bajo test (ver scripts/gen_golden.py).
 */
class HfTokenizerRealFilesTest {

    private lateinit var tokenizer: HfTokenizer
    private lateinit var tokenizerConfig: HfConfigs.TokenizerConfig

    @Before
    fun setup() {
        val dir = resourcesDir()
        tokenizer = HfTokenizer.fromJson(File(dir, "tokenizer.json").readText())
        tokenizerConfig = HfConfigs.parseTokenizerConfig(File(dir, "tokenizer_config.json").readText())!!
    }

    private fun resourcesDir(): File {
        val url = javaClass.classLoader!!.getResource("smollm2/tokenizer.json")
            ?: error("Faltan recursos de test smollm2/")
        return File(url.toURI()).parentFile
    }

    // ======================= TEST 2 — TOKENIZER =======================

    @Test
    fun `golden ids reales de HF para Hola quien eres`() {
        val ids = tokenizer.encode("Hola, ¿quién eres?", addSpecialTokens = false)
        // GOLDEN generado con huggingface/tokenizers sobre este tokenizer.json
        assertEquals(
            listOf(56, 7866, 28, 3351, 140, 385, 89, 25288, 297, 375, 47),
            ids
        )
    }

    @Test
    fun `golden ids reales para casos adicionales`() {
        assertEquals(
            listOf(47416, 51, 7342, 15771, 1264, 31798, 47),
            tokenizer.encode("¿Cómo estás?", addSpecialTokens = false)
        )
        assertEquals(
            listOf(56, 7866, 23902, 95),
            tokenizer.encode("Hola mundo", addSpecialTokens = false)
        )
        assertEquals(
            listOf(504, 2365, 6354, 16438, 27003, 690, 260, 23790, 2767, 216, 33, 34, 35, 36, 37, 17),
            tokenizer.encode("The quick brown fox jumps over the lazy dog 12345!", addSpecialTokens = false)
        )
        assertEquals(emptyList<Int>(), tokenizer.encode("", addSpecialTokens = false))
    }

    @Test
    fun `roundtrip texto-ids-texto con archivos reales`() {
        val samples = listOf(
            "Hola, ¿quién eres?",
            "¿Cómo estás?",
            "El café de la mañana está listo: ¡buen provecho!",
            " emojis 🚀 y acentos áéíóú ü ñ",
            "Tabs\tand\nnewlines  and   spaces",
            "The quick brown fox jumps over the lazy dog 12345!"
        )
        for (s in samples) {
            val ids = tokenizer.encode(s, addSpecialTokens = false)
            val decoded = tokenizer.decode(ids, skipSpecialTokens = true)
            assertEquals("Roundtrip falló para: '$s'", s, decoded)
        }
    }

    @Test
    fun `decode nunca recompone palabras a mano`() {
        // La decodificación viene del vocabulario real + decoder ByteLevel del
        // tokenizer.json. En el vocab real de SmolLM2 "Hola" = 'H'(56)+'ola'(7866).
        assertEquals("H", tokenizer.idToTokenSafe(56))
        assertEquals("ola", tokenizer.idToTokenSafe(7866))
        assertEquals(7866, tokenizer.tokenToId("ola"))
        val ids = tokenizer.encode("Hola", addSpecialTokens = false)
        assertEquals(listOf(56, 7866), ids)
        assertEquals("Hola", tokenizer.decode(ids, skipSpecialTokens = true))
    }

    @Test
    fun `tokens especiales se reconocen y se omiten al decodificar`() {
        val imStart = tokenizer.tokenToId("<|im_start|>")
        val imEnd = tokenizer.tokenToId("<|im_end|>")
        assertNotNull(imStart)
        assertNotNull(imEnd)
        assertEquals(1, imStart) // vocab real de SmolLM2
        assertEquals(2, imEnd)

        val ids = tokenizer.encode("a<|im_end|>b", addSpecialTokens = false)
        assertTrue(ids.contains(imEnd!!))
        assertEquals("ab", tokenizer.decode(ids, skipSpecialTokens = true))
        assertNotEquals("ab", tokenizer.decode(ids, skipSpecialTokens = false))
    }

    @Test
    fun `vocab_size declarado en config coincide con tokenizer real`() {
        val cfg = HfConfigs.parseModelConfig(
            File(resourcesDir(), "config.json").readText()
        )!!
        assertEquals(cfg.vocabSize, tokenizer.vocabSize)
        assertEquals(49152, tokenizer.vocabSize)
    }

    // ======================= TEST 3 — CHAT TEMPLATE =======================

    @Test
    fun `chat template real de SmolLM2 produce el prompt golden de jinja2`() {
        val messages = listOf(
            ChatTurn("system", "Eres VERTIL, un asistente local."),
            ChatTurn("user", "Hola, ¿quién eres?")
        )
        val build = ChatTemplateEngine.buildPrompt(messages, tokenizerConfig, tokenizer)

        assertEquals("template", build.source)
        // GOLDEN generado con jinja2 real sobre este chat_template:
        assertEquals(
            "<|im_start|>system\nEres VERTIL, un asistente local.<|im_end|>\n" +
                "<|im_start|>user\nHola, ¿quién eres?<|im_end|>\n" +
                "<|im_start|>assistant\n",
            build.prompt
        )
    }

    @Test
    fun `template sin system inserta el system por defecto de SmolLM2`() {
        // El chat_template real: si el primer mensaje no es 'system', añade el
        // system preentrenado de SmolLM (comportamiento del modelo, no hardcode).
        val messages = listOf(ChatTurn("user", "Hola"))
        val build = ChatTemplateEngine.buildPrompt(messages, tokenizerConfig, tokenizer)

        assertTrue(build.prompt.startsWith("<|im_start|>system\n"))
        assertTrue(build.prompt.contains("You are a helpful AI assistant named SmolLM"))
        assertTrue(build.prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `prompt renderizado codifica con los ids golden incluyendo especiales`() {
        val messages = listOf(
            ChatTurn("system", "Eres VERTIL, un asistente local."),
            ChatTurn("user", "Hola, ¿quién eres?")
        )
        val prompt = ChatTemplateEngine.buildPrompt(messages, tokenizerConfig, tokenizer).prompt
        val ids = tokenizer.encode(prompt, addSpecialTokens = false)

        // GOLDEN: HF tokenizers sobre el prompt renderizado con jinja2 real.
        assertEquals(
            listOf(1, 9690, 198, 53, 375, 717, 16090, 4192, 28, 551, 347, 11607, 85, 1679, 30, 2, 198,
                1, 4093, 198, 56, 7866, 28, 3351, 140, 385, 89, 25288, 297, 375, 47, 2, 198,
                1, 520, 9531, 198),
            ids
        )
    }

    @Test
    fun `sin chat_template cae a ChatML generico documentado`() {
        val messages = listOf(ChatTurn("user", "Hola"))
        val build = ChatTemplateEngine.buildPrompt(messages, null, tokenizer)

        assertEquals("fallback", build.source)
        assertTrue(build.prompt.contains("<|im_start|>user\nHola<|im_end|>\n"))
        assertTrue(build.prompt.endsWith("<|im_start|>assistant\n"))
    }
}
