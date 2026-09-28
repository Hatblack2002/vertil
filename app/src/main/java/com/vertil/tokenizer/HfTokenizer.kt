package com.vertil.tokenizer

import com.vertil.core.log.VertilLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tokenizador REAL compatible con el formato `tokenizer.json` de Hugging Face
 * (librería `tokenizers`).
 *
 * Soporta los 4 tipos de modelo: BPE (byte-level y por caracteres), Unigram,
 * WordPiece y WordLevel; normalizadores, pre-tokenizadores, post-procesadores
 * y decodificadores estándar; added/special tokens.
 *
 * NADA está hardcodeado por modelo: todo se construye desde el archivo
 * `tokenizer.json` del modelo importado (SmolLM2, Llama, Qwen, Mistral,
 * Gemma, GPT-2, BERT, T5, etc. según qué archivos importe el usuario).
 *
 * Limitaciones documentadas:
 *  - Normalizador "Precompiled" (SentencePiece charsmap binario) se trata como
 *    identidad: puede desviar tokens en modelos SentencePiece con caracteres
 *    quirúrgicos (raro en texto conversacional).
 */
class HfTokenizer private constructor(
    private val model: HfModel,
    private val normalizer: HfNormalizer,
    private val preTokenizer: HfPreTokenizer,
    private val postProcessor: HfPostProcessor,
    private val decoder: HfDecoder,
    addedTokens: List<AddedToken>
) {

    data class AddedToken(
        val id: Int,
        val content: String,
        val special: Boolean,
        val lstrip: Boolean,
        val rstrip: Boolean,
        val normalized: Boolean
    )

    val vocabSize: Int get() = model.vocab.size
    val hasByteLevelDecoder: Boolean get() = decoder is HfDecoder.ByteLevel || decoder is HfDecoder.Bpe

    /** id → token (incluye added tokens). */
    val idToToken: Array<String?> = run {
        val maxId = (model.vocab.values.maxOrNull() ?: -1).coerceAtLeast(addedTokens.maxOfOrNull { it.id } ?: -1)
        Array(maxId + 1) { null as String? }
    }

    /** Tokens añadidos ordenados por longitud descendente (match greedy). */
    private val addedByLength: List<AddedToken>
    private val addedByFirstChar: Map<Char, List<AddedToken>>
    private val addedTokenIds: Set<Int>

    init {
        for (t in addedTokens) {
            if (t.id in idToToken.indices) idToToken[t.id] = t.content
        }
        for ((tok, id) in model.vocab) {
            if (id in idToToken.indices) idToToken[id] = tok
        }
        addedByLength = addedTokens.sortedByDescending { it.content.length }
        addedByFirstChar = addedTokens.groupBy { it.content.firstOrNull() ?: ' ' }
        addedTokenIds = addedTokens.map { it.id }.toSet()
    }

    fun tokenToId(token: String): Int? = model.vocab[token]
    fun idToTokenSafe(id: Int): String? = idToToken.getOrNull(id)
    fun tokenIsSpecial(id: Int): Boolean = id in addedTokenIds

    // ============================ ENCODE ============================

    fun encode(text: String, addSpecialTokens: Boolean = true): List<Int> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Int>(text.length / 3 + 8)
        for (segment in splitAddedTokens(text)) {
            if (segment.second != null) {
                out.add(segment.second!!)
            } else {
                out.addAll(encodePlain(segment.first))
            }
        }
        return postProcessor.postProcess(out, null, addSpecialTokens)
    }

    /** Lista de (texto, id de added token si corresponde). */
    private fun splitAddedTokens(text: String): List<Pair<String, Int?>> {
        if (addedByLength.isEmpty()) return listOf(text to null)
        val out = ArrayList<Pair<String, Int?>>(8)
        var i = 0
        val plain = StringBuilder()
        while (i < text.length) {
            val candidates = addedByFirstChar[text[i]] ?: emptyList()
            var matched: AddedToken? = null
            for (cand in candidates) {
                if (text.startsWith(cand.content, i)) { matched = cand; break }
            }
            if (matched != null) {
                var piece = plain.toString()
                if (matched.lstrip && piece.endsWith(' ')) plain.setLength(plain.length - 1)
                if (plain.isNotEmpty()) { out.add(plain.toString() to null); plain.clear() }
                out.add(matched.content to matched.id)
                i += matched.content.length
                if (matched.rstrip && i < text.length && text[i] == ' ') i++
            } else {
                plain.append(text[i])
                i++
            }
        }
        if (plain.isNotEmpty()) out.add(plain.toString() to null)
        return out
    }

    private fun encodePlain(text: String): List<Int> {
        val normalized = normalizer.normalize(text)
        val pretokens = preTokenizer.split(normalized)
        val out = ArrayList<Int>(pretokens.size + 8)
        for (p in pretokens) out.addAll(model.tokenizePiece(p))
        return out
    }

    // ============================ DECODE ============================

    /** Decodifica ids a texto. [skipSpecialTokens]=true omite tokens especiales. */
    fun decode(ids: List<Int>, skipSpecialTokens: Boolean = true): String {
        val tokens = ArrayList<String>(ids.size)
        for (id in ids) {
            val tok = idToToken.getOrNull(id) ?: continue
            if (skipSpecialTokens && id in addedTokenIds && isSpecialAdded(id)) continue
            tokens.add(tok)
        }
        return decoder.decode(tokens)
    }

    private val specialSet: Set<Int> = addedTokens.filter { it.special }.map { it.id }.toSet()
    private fun isSpecialAdded(id: Int): Boolean = id in specialSet

    companion object {
        private const val TAG = "HfTokenizer"

        /** Parsea un tokenizer.json y construye el tokenizador. */
        fun fromJson(jsonText: String): HfTokenizer {
            val root = Json.parseToJsonElement(jsonText).jsonObject

            // ---- added tokens ----
            val addedTokens = (root["added_tokens"] as? JsonArray)?.map { el ->
                val o = el.jsonObject
                AddedToken(
                    id = o["id"]?.jsonPrimitive?.intOrNull ?: -1,
                    content = o["content"]?.jsonPrimitive?.contentOrNull ?: "",
                    special = o["special"]?.jsonPrimitive?.booleanOrNull ?: false,
                    lstrip = o["lstrip"]?.jsonPrimitive?.booleanOrNull ?: false,
                    rstrip = o["rstrip"]?.jsonPrimitive?.booleanOrNull ?: false,
                    normalized = o["normalized"]?.jsonPrimitive?.booleanOrNull ?: true
                )
            } ?: emptyList()

            // ---- model ----
            val modelObj = root["model"]?.jsonObject
                ?: error("tokenizer.json sin objeto 'model'")
            val model = parseModel(modelObj)

            return HfTokenizer(
                model = model,
                normalizer = parseNormalizer(root["normalizer"]),
                preTokenizer = parsePreTokenizer(root["pre_tokenizer"]),
                postProcessor = parsePostProcessor(root["post_processor"]),
                decoder = parseDecoder(root["decoder"]),
                addedTokens = addedTokens
            )
        }

        // ------------------------- parsing helpers -------------------------

        private fun parseModel(o: JsonObject): HfModel {
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "BPE"
            val unkId = o["unk_id"]?.jsonPrimitive?.intOrNull
            return when (type) {
                "BPE" -> {
                    val vocab = HashMap<String, Int>(o["vocab"]!!.jsonObject.size * 2)
                    (o["vocab"]!!.jsonObject).forEach { (tok, idEl) ->
                        idEl.jsonPrimitive.intOrNull?.let { vocab[tok] = it }
                    }
                    val merges = ArrayList<Pair<String, String>>(vocab.size)
                    (o["merges"] as? JsonArray)?.forEach { m ->
                        when (m) {
                            is JsonPrimitive -> {
                                val parts = m.content.split(" ", limit = 2)
                                if (parts.size == 2) merges.add(parts[0] to parts[1])
                            }
                            is JsonArray -> {
                                val a = m.map { it.jsonPrimitive.content }
                                if (a.size == 2) merges.add(a[0] to a[1])
                            }
                            else -> {}
                        }
                    }
                    BpeModel(
                        vocab = vocab,
                        merges = merges,
                        unkId = unkId,
                        byteFallback = o["byte_fallback"]?.jsonPrimitive?.booleanOrNull ?: false,
                        ignoreMerges = o["ignore_merges"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }
                "Unigram" -> {
                    val list = ArrayList<Pair<String, Double>>()
                    (o["vocab"] as? JsonArray)?.forEach { el ->
                        val arr = el.jsonArray
                        if (arr.size >= 2) {
                            list.add(
                                arr[0].jsonPrimitive.content to
                                    (arr[1].jsonPrimitive.toString().toDoubleOrNull() ?: -20.0)
                            )
                        }
                    }
                    UnigramModel(
                        vocabList = list,
                        unkId = unkId,
                        byteFallback = o["byte_fallback"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }
                "WordPiece" -> {
                    val vocab = HashMap<String, Int>()
                    (o["vocab"]?.jsonObject)?.forEach { (tok, idEl) ->
                        idEl.jsonPrimitive.intOrNull?.let { vocab[tok] = it }
                    }
                    WordPieceModel(
                        vocab = vocab,
                        unkId = unkId,
                        continuingSubwordPrefix = o["continuing_subword_prefix"]?.jsonPrimitive?.contentOrNull ?: "##",
                        maxInputCharsPerWord = o["max_input_chars_per_word"]?.jsonPrimitive?.intOrNull ?: 100
                    )
                }
                "WordLevel" -> {
                    val vocab = HashMap<String, Int>()
                    (o["vocab"]?.jsonObject)?.forEach { (tok, idEl) ->
                        idEl.jsonPrimitive.intOrNull?.let { vocab[tok] = it }
                    }
                    WordLevelModel(vocab, unkId)
                }
                else -> error("Tipo de modelo de tokenizador no soportado: $type")
            }
        }

        private fun parseNormalizer(el: JsonElement?): HfNormalizer {
            if (el == null || el is kotlinx.serialization.json.JsonNull) return HfNormalizer.Identity
            val o = el.jsonObject
            return when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "NFD" -> HfNormalizer.Nfd()
                "NFC" -> HfNormalizer.Nfc()
                "NFKD" -> HfNormalizer.Nfkd()
                "NFKC" -> HfNormalizer.Nfkc()
                "NFKCCasefold" -> HfNormalizer.NfkcCasefold
                "Lowercase" -> HfNormalizer.Lowercase()
                "Strip" -> HfNormalizer.Strip(
                    o["strip_left"]?.jsonPrimitive?.booleanOrNull ?: true,
                    o["strip_right"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                "StripAccents" -> HfNormalizer.StripAccents()
                "Replace" -> HfNormalizer.Replace(
                    o["pattern"]?.jsonObject?.get("String")?.jsonPrimitive?.contentOrNull
                        ?: o["pattern"]?.jsonObject?.get("Regex")?.jsonPrimitive?.contentOrNull ?: "",
                    o["content"]?.jsonPrimitive?.contentOrNull ?: ""
                )
                "Prepend" -> HfNormalizer.Prepend(o["prepend"]?.jsonPrimitive?.contentOrNull ?: "")
                "Sequence" -> HfNormalizer.SequenceN(
                    (o["normalizers"]?.jsonArray ?: JsonArray(emptyList())).map { parseNormalizer(it) }
                )
                "BertNormalizer" -> HfNormalizer.Bert(
                    cleanText = o["clean_text"]?.jsonPrimitive?.booleanOrNull ?: true,
                    handleChineseChars = o["handle_chinese_chars"]?.jsonPrimitive?.booleanOrNull ?: true,
                    stripAccentsFlag = o["strip_accents"]?.jsonPrimitive?.booleanOrNull,
                    lowercase = o["lowercase"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                "Precompiled" -> {
                    VertilLog.w(TAG, "Normalizador 'Precompiled' tratado como identidad (limitación documentada)")
                    HfNormalizer.Identity
                }
                else -> HfNormalizer.Identity
            }
        }

        private fun parsePreTokenizer(el: JsonElement?): HfPreTokenizer {
            if (el == null || el is kotlinx.serialization.json.JsonNull) return HfPreTokenizer.Identity
            val o = el.jsonObject
            return when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "ByteLevel" -> HfPreTokenizer.ByteLevel(
                    addPrefixSpace = o["add_prefix_space"]?.jsonPrimitive?.booleanOrNull ?: true,
                    useRegex = o["use_regex"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                "Metaspace" -> {
                    val repl = o["replacement"]?.jsonPrimitive?.contentOrNull ?: "▁"
                    HfPreTokenizer.Metaspace(
                        replacement = repl.firstOrNull() ?: '▁',
                        addPrefixSpace = o["add_prefix_space"]?.jsonPrimitive?.booleanOrNull ?: true,
                        splitFlag = o["split"]?.jsonPrimitive?.booleanOrNull ?: true
                    )
                }
                "Prepend" -> HfPreTokenizer.Metaspace(
                    replacement = (o["prepend"]?.jsonPrimitive?.contentOrNull ?: "▁").firstOrNull() ?: '▁',
                    addPrefixSpace = true,
                    splitFlag = true
                )
                "Digits" -> HfPreTokenizer.Digits(
                    o["individual_digits"]?.jsonPrimitive?.booleanOrNull ?: false
                )
                "Whitespace" -> HfPreTokenizer.Whitespace
                "WhitespaceSplit" -> HfPreTokenizer.WhitespaceSplit
                "Punctuation" -> HfPreTokenizer.Punctuation
                "BertPreTokenizer" -> HfPreTokenizer.BertPre
                "Split" -> {
                    val patternObj = o["pattern"]?.jsonObject
                    val regexStr = patternObj?.get("Regex")?.jsonPrimitive?.contentOrNull
                        ?: patternObj?.get("String")?.jsonPrimitive?.contentOrNull ?: ""
                    HfPreTokenizer.Split(Regex(regexStr), o["invert"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
                "Sequence" -> HfPreTokenizer.SequenceP(
                    (o["pretokenizers"]?.jsonArray ?: JsonArray(emptyList())).map { parsePreTokenizer(it) }
                )
                else -> HfPreTokenizer.Identity
            }
        }

        private fun parsePostProcessor(el: JsonElement?): HfPostProcessor {
            if (el == null || el is kotlinx.serialization.json.JsonNull) return HfPostProcessor.None
            val o = el.jsonObject
            return when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "TemplateProcessing" -> {
                    val specialIds = HashMap<String, List<Int>>()
                    (o["special_tokens"]?.jsonObject)?.forEach { (id, def) ->
                        val ids = def.jsonObject["ids"] as? JsonArray
                        specialIds[id] = ids?.map { it.jsonPrimitive.intOrNull ?: -1 } ?: emptyList()
                    }
                    HfPostProcessor.Template(
                        single = parseTemplate(o["single"]?.jsonArray ?: JsonArray(emptyList())),
                        pair = (o["pair"] as? JsonArray)?.let { parseTemplate(it) },
                        specialIds = specialIds
                    )
                }
                "BertProcessing" -> HfPostProcessor.ClsSep(
                    sepIds = listOf(o["sep"]?.jsonArray?.first()?.jsonPrimitive?.intOrNull ?: 102),
                    clsIds = listOf(o["cls"]?.jsonArray?.first()?.jsonPrimitive?.intOrNull ?: 101)
                )
                "RobertaProcessing" -> HfPostProcessor.ClsSep(
                    sepIds = listOf(o["sep"]?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull ?: 2),
                    clsIds = listOf(o["cls"]?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull ?: 0)
                )
                "ByteLevel" -> HfPostProcessor.None
                "Sequence" -> HfPostProcessor.SequenceP(
                    (o["processors"]?.jsonArray ?: JsonArray(emptyList())).map { parsePostProcessor(it) }
                )
                else -> HfPostProcessor.None
            }
        }

        private fun parseTemplate(arr: JsonArray): List<HfPostProcessor.Template.Component> {
            val out = ArrayList<HfPostProcessor.Template.Component>(arr.size)
            for (el in arr) {
                val o = el.jsonObject
                for ((kind, def) in o) {
                    when (kind) {
                        "SpecialToken" -> out.add(
                            HfPostProcessor.Template.Special(def.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "")
                        )
                        "Sequence" -> out.add(
                            HfPostProcessor.Template.SeqA(def.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "A")
                        )
                    }
                }
            }
            return out
        }

        private fun parseDecoder(el: JsonElement?): HfDecoder {
            if (el == null || el is kotlinx.serialization.json.JsonNull) return HfDecoder.Plain
            val o = el.jsonObject
            return when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "ByteLevel" -> HfDecoder.ByteLevel
                "BPEDecoder" -> HfDecoder.Bpe
                "Metaspace" -> {
                    val repl = o["replacement"]?.jsonPrimitive?.contentOrNull ?: "▁"
                    HfDecoder.Metaspace(
                        replacement = repl.firstOrNull() ?: '▁',
                        prependScheme = o["prepend_scheme"]?.jsonPrimitive?.contentOrNull
                            ?: o["add_prefix_space"]?.jsonPrimitive?.booleanOrNull?.toString()
                    )
                }
                "WordPiece" -> HfDecoder.WordPiece(
                    prefix = o["prefix"]?.jsonPrimitive?.contentOrNull ?: "##",
                    cleanup = o["cleanup"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                "Replace" -> HfDecoder.Replace(
                    o["pattern"]?.jsonObject?.get("String")?.jsonPrimitive?.contentOrNull ?: "",
                    o["content"]?.jsonPrimitive?.contentOrNull ?: ""
                )
                "Strip" -> HfDecoder.StripD(
                    content = (o["content"]?.jsonPrimitive?.contentOrNull ?: " ").firstOrNull() ?: ' ',
                    start = o["start"]?.jsonPrimitive?.intOrNull ?: 0,
                    stop = o["stop"]?.jsonPrimitive?.intOrNull ?: 0
                )
                "Sequence" -> HfDecoder.SequenceD(
                    (o["decoders"]?.jsonArray ?: JsonArray(emptyList())).map { parseDecoder(it) }
                )
                else -> HfDecoder.Plain
            }
        }
    }
}
