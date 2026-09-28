package com.vertil.tokenizer

/**
 * Normalizadores HF (tokenizer.json → "normalizer"). Implementados de forma
 * genérica a partir de la especificación JSON; el tokenizador queda dirigido
 * por el archivo del modelo (no hay nada hardcodeado por modelo).
 */
sealed interface HfNormalizer {
    fun normalize(text: String): String

    object Identity : HfNormalizer {
        override fun normalize(text: String) = text
    }

    class Nfd : HfNormalizer {
        override fun normalize(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
    }

    class Nfc : HfNormalizer {
        override fun normalize(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    }

    class Nfkd : HfNormalizer {
        override fun normalize(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
    }

    class Nfkc : HfNormalizer {
        override fun normalize(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
    }

    /** NFKC_Casefold aproximado: NFKD + quitar marcas de acento + casefold. */
    object NfkcCasefold : HfNormalizer {
        override fun normalize(text: String): String {
            val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
            val stripped = decomposed.filterNot { ch ->
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
            }
            return java.text.Normalizer.normalize(stripped.lowercase(), java.text.Normalizer.Form.NFKC)
        }
    }

    class Lowercase : HfNormalizer {
        override fun normalize(text: String) = text.lowercase()
    }

    data class Strip(val stripLeft: Boolean, val stripRight: Boolean) : HfNormalizer {
        override fun normalize(text: String): String {
            var t = text
            if (stripRight) t = t.trimEnd()
            if (stripLeft) t = t.trimStart()
            return t
        }
    }

    class StripAccents : HfNormalizer {
        override fun normalize(text: String): String {
            val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
            val stripped = decomposed.filterNot { ch ->
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
            }
            return java.text.Normalizer.normalize(stripped, java.text.Normalizer.Form.NFC)
        }
    }

    data class Replace(val pattern: String, val content: String) : HfNormalizer {
        private val regex = Regex(Regex.escape(pattern))
        override fun normalize(text: String) = text.replace(regex, content)
    }

    data class Prepend(val prepend: String) : HfNormalizer {
        override fun normalize(text: String) = prepend + text
    }

    data class SequenceN(val parts: List<HfNormalizer>) : HfNormalizer {
        override fun normalize(text: String): String {
            var t = text
            for (p in parts) t = p.normalize(t)
            return t
        }
    }

    /** BertNormalizer: clean_text, handle_chinese_chars, strip_accents, lowercase. */
    data class Bert(
        val cleanText: Boolean = true,
        val handleChineseChars: Boolean = true,
        val stripAccentsFlag: Boolean? = null,
        val lowercase: Boolean = true
    ) : HfNormalizer {
        override fun normalize(text: String): String {
            var t = text
            if (cleanText) {
                t = buildString {
                    for (ch in t) {
                        if (ch == '\u0000' || ch == '\uFFFD' || ch.isISOControl()) continue
                        if (Character.isWhitespace(ch)) append(' ') else append(ch)
                    }
                }
            }
            if (handleChineseChars) {
                t = buildString {
                    for (ch in t) {
                        if (isChinese(ch)) { append(' '); append(ch); append(' ') } else append(ch)
                    }
                }
            }
            val doStrip = stripAccentsFlag ?: (lowercase)
            if (doStrip) {
                val decomposed = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKD)
                t = java.text.Normalizer.normalize(
                    decomposed.filterNot { ch -> Character.getType(ch) == Character.NON_SPACING_MARK.toInt() },
                    java.text.Normalizer.Form.NFC
                )
            }
            if (lowercase) t = t.lowercase()
            return t
        }

        private fun isChinese(ch: Char): Boolean {
            val block = Character.UnicodeBlock.of(ch)
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
        }
    }
}
