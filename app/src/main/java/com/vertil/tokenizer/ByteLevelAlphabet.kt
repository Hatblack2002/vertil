package com.vertil.tokenizer

/**
 * Alfabeto byte↔unicode de GPT-2 (usado por tokenizadores ByteLevel: GPT-2,
 * Llama 1/2, Mistral, SmolLM2, Qwen, etc.).
 *
 * HF "ByteLevel" mapea cada byte a un carácter unicode visible para que el BPE
 * opere sobre texto unicode sin perder bytes. Tabla canónica de GPT-2.
 */
object ByteLevelAlphabet {

    /** Codepoints imprimibles que no requieren offset (orden estándar GPT-2). */
    private val PRINTABLE: List<Int> = buildList {
        addAll(('!'..'~').map { it.code })
        addAll(('¡'..'¬').map { it.code })
        addAll(('®'..'ÿ').map { it.code })
    }

    /** Tabla canónica: byte (0-255) → carácter unicode asignado. */
    val byteToUnicode: List<Char> by lazy {
        // Tabla canónica de GPT-2 (bytes_to_unicode):
        //  - byte imprimible → el MISMO carácter;
        //  - byte no imprimible → 256 + n (n orden de aparición).
        val table = CharArray(256)
        var n = 0
        for (b in 0..255) {
            table[b] = if (b in PRINTABLE) b.toChar() else (256 + n++).toChar()
        }
        table.toList()
    }

    private val unicodeToByte: Map<Char, Int> by lazy {
        val m = HashMap<Char, Int>(256)
        for (b in 0..255) m[byteToUnicode[b]] = b
        m
    }

    /** bytes (UTF-8 del texto) → representación unicode byte-level. */
    fun encode(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val table = byteToUnicode
        val sb = StringBuilder(bytes.size)
        for (b in bytes) sb.append(table[b.toInt() and 0xFF])
        return sb.toString()
    }

    /** representación unicode byte-level → bytes → String UTF-8. */
    fun decode(mapped: String): String {
        val table = unicodeToByte
        val bytes = ByteArray(mapped.length)
        var count = 0
        for (ch in mapped) {
            val b = table[ch]
            if (b != null && count < bytes.size) {
                bytes[count++] = b.toByte()
            }
        }
        return String(bytes, 0, count, Charsets.UTF_8)
    }

    /**
     * Regex GPT-2 estándar de pre-tokenización (ByteLevel.use_regex=true):
     * contracciones + letras + números + resto.
     */
    const val GPT2_REGEX: String =
        "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"

    /**
     * Regex Llama-3 (algunos modelos la declaran vía su propio pre_tokenizer).
     */
    const val LLAMA3_REGEX: String =
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"

    /** División de dígitos (pre_tokenizer "Digits"). */
    fun splitDigits(text: String, individual: Boolean): List<String> {
        if (text.none { it.isDigit() }) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var lastWasDigit = false
        for (ch in text) {
            val isDigit = ch.isDigit()
            if (sb.isNotEmpty() && (isDigit != lastWasDigit || (isDigit && individual))) {
                out.add(sb.toString())
                sb.clear()
            }
            sb.append(ch)
            lastWasDigit = isDigit
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
