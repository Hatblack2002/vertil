package com.vertil.tokenizer

/**
 * Pre-tokenizadores HF (tokenizer.json → "pre_tokenizer"). Cada uno divide el
 * texto normalizado en "words" (pre-tokens) que luego se codifican con el
 * modelo (BPE/Unigram/WordPiece/WordLevel).
 */
sealed interface HfPreTokenizer {
    /** Divide [text] ya normalizado en pre-tokens. */
    fun split(text: String): List<String>

    object Identity : HfPreTokenizer {
        override fun split(text: String) = listOf(text)
    }

    /** ByteLevel: aplica regex GPT-2 (use_regex) y mapea a alfabeto byte-level. */
    data class ByteLevel(val addPrefixSpace: Boolean, val useRegex: Boolean) : HfPreTokenizer {
        private val regex = Regex(ByteLevelAlphabet.GPT2_REGEX)

        override fun split(text: String): List<String> {
            var src = text
            if (addPrefixSpace && src.isNotEmpty() && !src.startsWith(" ")) src = " $src"
            val pieces = if (useRegex) regex.findAll(src).map { it.value }.toList() else listOf(src)
            return pieces.filter { it.isNotEmpty() }.map { ByteLevelAlphabet.encode(it) }
        }
    }

    /** Metaspace: reemplaza espacios por replacement (típicamente ▁). */
    data class Metaspace(
        val replacement: Char,
        val addPrefixSpace: Boolean,
        val splitFlag: Boolean
    ) : HfPreTokenizer {
        override fun split(text: String): List<String> {
            var src = text
            if (addPrefixSpace && src.isNotEmpty() && !src.startsWith(" ")) src = " $src"
            val replaced = src.replace(' ', replacement)
            return if (splitFlag) {
                // Divide dejando el replacement al inicio de cada pieza
                val out = ArrayList<String>()
                val sb = StringBuilder()
                for (ch in replaced) {
                    if (ch == replacement && sb.isNotEmpty()) {
                        out.add(sb.toString()); sb.clear()
                    }
                    sb.append(ch)
                }
                if (sb.isNotEmpty()) out.add(sb.toString())
                out
            } else listOf(replaced)
        }
    }

    /** Digits: agrupa o separa dígitos individuales. */
    data class Digits(val individual: Boolean) : HfPreTokenizer {
        override fun split(text: String) = ByteLevelAlphabet.splitDigits(text, individual)
    }

    /** Whitespace: divide en runs de whitespace GLOB (regex \w+|[^\w\s]+). */
    object Whitespace : HfPreTokenizer {
        private val regex = Regex("\\w+|[^\\w\\s]+")
        override fun split(text: String) = regex.findAll(text).map { it.value }.toList()
    }

    /** WhitespaceSplit: divide únicamente por whitespace. */
    object WhitespaceSplit : HfPreTokenizer {
        override fun split(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    /** Punctuation: separa la puntuación en piezas propias. */
    object Punctuation : HfPreTokenizer {
        private val regex = Regex("[^\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+")
        override fun split(text: String) = regex.findAll(text).map { it.value }.toList()
    }

    /** Split genérico con patrón (String literal o Regex), inverted opcional. */
    data class Split(val pattern: Regex, val invert: Boolean) : HfPreTokenizer {
        override fun split(text: String): List<String> {
            return if (invert) {
                pattern.findAll(text).map { it.value }.toList()
            } else {
                text.split(pattern).filter { it.isNotEmpty() }
            }
        }
    }

    /** BertPreTokenizer: whitespace + puntuación. */
    object BertPre : HfPreTokenizer {
        private val regex = Regex("\\s*\\p{L}+|\\s*\\p{N}+|\\s*[^\\s\\p{L}\\p{N}]+|\\s+")
        override fun split(text: String) = regex.findAll(text).map { it.value.trim().ifEmpty { " " } }.filter { it.isNotEmpty() }.toList()
    }

    data class SequenceP(val parts: List<HfPreTokenizer>) : HfPreTokenizer {
        override fun split(text: String): List<String> {
            var current = listOf(text)
            for (p in parts) {
                current = current.flatMap { p.split(it) }.filter { it.isNotEmpty() }
            }
            return current
        }
    }
}
