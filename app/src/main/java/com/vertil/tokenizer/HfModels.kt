package com.vertil.tokenizer

/**
 * Modelos de tokenización HF (tokenizer.json → "model"): BPE, Unigram,
 * WordPiece y WordLevel. El vocabulario y los merges SIEMPRE provienen del
 * archivo del modelo importado.
 */
sealed interface HfModel {
    val vocab: Map<String, Int>
    val unkId: Int?

    /** Codifica un pre-token (ya normalizado y pre-tokenizado) a ids. */
    fun tokenizePiece(piece: String): List<Int>
}

/** BPE genérico (byte-level o por caracteres, según el pre_tokenizer del modelo). */
class BpeModel(
    override val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
    override val unkId: Int? = null,
    val byteFallback: Boolean = false,
    val ignoreMerges: Boolean = false
) : HfModel {

    private val mergeRanks: HashMap<Pair<String, String>, Int> = HashMap(merges.size * 2)
    private val pieceCache = HashMap<String, List<Int>>()

    init {
        for ((i, pair) in merges.withIndex()) mergeRanks[pair] = i
    }

    override fun tokenizePiece(piece: String): List<Int> {
        if (piece.isEmpty()) return emptyList()
        pieceCache[piece]?.let { return it }

        val result: List<Int> = when {
            // Token ya presente en el vocabulario (caso habitual tras merges del pre-tokenizer).
            vocab.containsKey(piece) -> listOf(vocab[piece]!!)
            ignoreMerges -> fallbackUnknown(listOf(piece))
            else -> {
                // BPE clásico: símbolos iniciales + merges por rango mínimo.
                var symbols = piece.map { it.toString() }
                while (symbols.size > 1) {
                    var bestRank = Int.MAX_VALUE
                    var bestIdx = -1
                    for (i in 0 until symbols.size - 1) {
                        val rank = mergeRanks[Pair(symbols[i], symbols[i + 1])]
                        if (rank != null && rank < bestRank) {
                            bestRank = rank; bestIdx = i
                        }
                    }
                    if (bestIdx < 0) break
                    val merged = ArrayList<String>(symbols.size - 1)
                    for (i in symbols.indices) {
                        if (i == bestIdx) {
                            merged.add(symbols[i] + symbols[i + 1])
                        } else if (i == bestIdx + 1) {
                            // ya fusionado
                        } else {
                            merged.add(symbols[i])
                        }
                    }
                    symbols = merged
                }
                symbols.flatMap { sym ->
                    val id = vocab[sym]
                    if (id != null) listOf(id) else fallbackUnknown(listOf(sym))
                }
            }
        }
        if (pieceCache.size < CACHE_LIMIT) pieceCache[piece] = result
        return result
    }

    private fun fallbackUnknown(symbols: List<String>): List<Int> {
        val id = unkId
        if (byteFallback) {
            // byte_fallback: cada carácter → <0xXX> si existe en el vocabulario.
            val out = ArrayList<Int>()
            for (sym in symbols) {
                for (ch in sym) {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    var allFound = true
                    for (b in bytes) {
                        val tok = "<0x%02X>".format(b)
                        val bid = vocab[tok]
                        if (bid != null) out.add(bid) else { allFound = false; break }
                    }
                    if (!allFound && id != null) out.add(id)
                }
            }
            if (out.isNotEmpty()) return out
        }
        return if (id != null) listOf(id) else emptyList()
    }

    companion object {
        private const val CACHE_LIMIT = 200_000
    }
}

/** WordPiece (BERT y derivados): greedy longest-match con prefijo "##". */
class WordPieceModel(
    override val vocab: Map<String, Int>,
    override val unkId: Int? = null,
    val continuingSubwordPrefix: String = "##",
    val maxInputCharsPerWord: Int = 100
) : HfModel {
    override fun tokenizePiece(piece: String): List<Int> {
        if (piece.length > maxInputCharsPerWord) {
            return unkId?.let { listOf(it) } ?: emptyList()
        }
        val out = ArrayList<Int>()
        var start = 0
        while (start < piece.length) {
            var end = piece.length
            var currentId: Int? = null
            while (start < end) {
                var sub = piece.substring(start, end)
                if (start > 0) sub = continuingSubwordPrefix + sub
                val id = vocab[sub]
                if (id != null) { currentId = id; break }
                end--
            }
            if (currentId == null) {
                return unkId?.let { listOf(it) } ?: emptyList()
            }
            out.add(currentId)
            start = end
        }
        return out
    }
}

/** WordLevel: cada pieza debe existir entera en el vocabulario. */
class WordLevelModel(
    override val vocab: Map<String, Int>,
    override val unkId: Int? = null
) : HfModel {
    override fun tokenizePiece(piece: String): List<Int> =
        vocab[piece]?.let { listOf(it) } ?: unkId?.let { listOf(it) } ?: emptyList()
}

/** Unigram (SentencePiece en JSON): Viterbi con scores por pieza. */
class UnigramModel(
    vocabList: List<Pair<String, Double>>, // (pieza, score) en orden de id
    override val unkId: Int? = null,
    val byteFallback: Boolean = false
) : HfModel {

    override val vocab: Map<String, Int> = HashMap<String, Int>(vocabList.size * 2).apply {
        vocabList.forEachIndexed { idx, (tok, _) -> put(tok, idx) }
    }
    private val scores: List<Double> = vocabList.map { it.second }
    private val maxPieceLen: Int = vocabList.maxOfOrNull { it.first.length } ?: 0

    override fun tokenizePiece(piece: String): List<Int> = segment(piece).map { vocab[it]!! }

    /** Viterbi estándar de SentencePiece. */
    private fun segment(text: String): List<String> {
        val n = text.length
        if (n == 0) return emptyList()
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            val maxJ = minOf(n, i + maxPieceLen)
            for (j in i + 1..maxJ) {
                val sub = text.substring(i, j)
                val id = vocab[sub]
                if (id != null) {
                    val score = best[i] + scores[id]
                    if (score > best[j]) { best[j] = score; back[j] = i }
                }
            }
            // byte fallback por carácter si la posición no avanza
            if (byteFallback && best[i + 1] == Double.NEGATIVE_INFINITY) {
                best[i + 1] = best[i] - 20.0
                back[i + 1] = i
            }
        }
        if (best[n] == Double.NEGATIVE_INFINITY) {
            unkId?.let {
                return listOf(text)
            }
            return emptyList()
        }
        val out = ArrayList<String>()
        var idx = n
        while (idx > 0) {
            val prev = back[idx]
            val piece = text.substring(prev, idx)
            if (vocab.containsKey(piece)) out.add(piece)
            idx = prev
        }
        out.reverse()
        return out
    }
}
