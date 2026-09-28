package com.vertil.tokenizer

/**
 * Decodificadores HF (tokenizer.json → "decoder") y post-procesadores
 * ("post_processor"). Dirigidos por el contenido del archivo del modelo.
 */
sealed interface HfDecoder {
    fun decode(tokens: List<String>): String

    /** ByteLevel: concatena y mapea de vuelta a bytes UTF-8. */
    object ByteLevel : HfDecoder {
        override fun decode(tokens: List<String>) = ByteLevelAlphabet.decode(tokens.joinToString(""))
    }

    /** BPEDecoder (GPT-2/RoBERTa): equivalente a ByteLevel sobre piezas. */
    object Bpe : HfDecoder {
        override fun decode(tokens: List<String>) = ByteLevelAlphabet.decode(tokens.joinToString(""))
    }

    data class Metaspace(val replacement: Char, val prependScheme: String?) : HfDecoder {
        override fun decode(tokens: List<String>): String =
            tokens.joinToString("").replace(replacement, ' ').let {
                // prepend_scheme first: elimina espacio inicial añadido al codificar
                if (prependScheme == "always" || prependScheme == "first") it.trimStart() else it
            }
    }

    data class WordPiece(val prefix: String, val cleanup: Boolean) : HfDecoder {
        override fun decode(tokens: List<String>): String {
            val sb = StringBuilder()
            for (t in tokens) {
                if (t.startsWith(prefix) && sb.isNotEmpty()) sb.append(t.substring(prefix.length))
                else {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            }
            var s = sb.toString()
            if (cleanup) {
                s = s.replace(" .", ".").replace(" ?", "?").replace(" !", "!")
                s = s.replace(" ,", ",").replace(" ' ", "'").replace(" n't", "n't")
                    .replace(" 'm", "'m").replace(" 's", "'s").replace(" 've", "'ve")
                    .replace(" 're", "'re")
            }
            return s
        }
    }

    data class Replace(val pattern: String, val content: String) : HfDecoder {
        override fun decode(tokens: List<String>) = tokens.joinToString("").replace(pattern, content)
    }

    data class StripD(val content: Char, val start: Int, val stop: Int) : HfDecoder {
        override fun decode(tokens: List<String>): String {
            val joined = tokens.joinToString("")
            var s = joined
            var n = start
            while (n > 0 && s.startsWith(content)) { s = s.substring(1); n-- }
            n = stop
            while (n > 0 && s.endsWith(content)) { s = s.dropLast(1); n-- }
            return s
        }
    }

    data class SequenceD(val parts: List<HfDecoder>) : HfDecoder {
        override fun decode(tokens: List<String>): String {
            var pieces = tokens
            for (p in parts) pieces = listOf(p.decode(pieces))
            return pieces.firstOrNull() ?: ""
        }
    }

    /** Fallback: unión simple. */
    object Plain : HfDecoder {
        override fun decode(tokens: List<String>) = tokens.joinToString("")
    }
}

/** Componentes de un post-procesador TemplateProcessing/Bert/Roberta. */
sealed interface HfPostProcessor {
    /**
     * Aplica el post-procesado a los ids ya codificados de la secuencia A
     * (y opcionalmente B). Devuelve los ids finales.
     */
    fun postProcess(idsA: List<Int>, idsB: List<Int>?, addSpecialTokens: Boolean): List<Int>

    object None : HfPostProcessor {
        override fun postProcess(idsA: List<Int>, idsB: List<Int>?, addSpecialTokens: Boolean) =
            if (idsB == null) idsA else idsA + idsB
    }

    /** TemplateProcessing completo (single/pair con SpecialToken/Sequence). */
    data class Template(
        val single: List<Component>,
        val pair: List<Component>?,
        val specialIds: Map<String, List<Int>>
    ) : HfPostProcessor {

        sealed interface Component
        data class SeqA(val type: String) : Component // "A" | "B"
        data class Special(val id: String) : Component

        private fun render(components: List<Component>, a: List<Int>, b: List<Int>?): List<Int> {
            val out = ArrayList<Int>(a.size + (b?.size ?: 0) + 8)
            for (c in components) {
                when (c) {
                    is SeqA -> when (c.type) {
                        "A" -> out.addAll(a)
                        "B" -> out.addAll(b ?: emptyList())
                    }
                    is Special -> out.addAll(specialIds[c.id] ?: emptyList())
                }
            }
            return out
        }

        override fun postProcess(idsA: List<Int>, idsB: List<Int>?, addSpecialTokens: Boolean): List<Int> {
            if (!addSpecialTokens) return if (idsB == null) idsA else idsA + idsB
            return if (idsB == null) render(single, idsA, null)
            else render(pair ?: single, idsA, idsB)
        }
    }

    /** BertProcessing / RobertaProcessing: sep/cls con ids concretos. */
    data class ClsSep(
        val sepIds: List<Int>,
        val clsIds: List<Int>
    ) : HfPostProcessor {
        override fun postProcess(idsA: List<Int>, idsB: List<Int>?, addSpecialTokens: Boolean): List<Int> {
            if (!addSpecialTokens) return if (idsB == null) idsA else idsA + idsB
            return clsIds + idsA + sepIds +
                (idsB?.let { sepIds + it + sepIds } ?: emptyList())
        }
    }

    data class SequenceP(val parts: List<HfPostProcessor>) : HfPostProcessor {
        override fun postProcess(idsA: List<Int>, idsB: List<Int>?, addSpecialTokens: Boolean): List<Int> {
            var a = idsA
            var b = idsB
            for (p in parts) {
                val out = p.postProcess(a, b, addSpecialTokens)
                a = out; b = null
            }
            return a
        }
    }
}
