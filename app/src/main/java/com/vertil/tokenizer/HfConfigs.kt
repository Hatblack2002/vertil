package com.vertil.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lectura genérica de los archivos de configuración del modelo (provenientes
 * del repositorio HF del modelo):
 *  - config.json → arquitectura (model_type, architectures, capas, cabezas,
 *    vocab_size, max_position_embeddings, ids especiales, use_cache);
 *  - generation_config.json → eos_token_id (int o lista), max_new_tokens…;
 *  - tokenizer_config.json → chat_template, eos/bos_token, model_max_length;
 *  - special_tokens_map.json → tokens especiales adicionales.
 *
 * Nada se asume de un modelo concreto: los campos son opcionales y el engine
 * se adapta a lo que exista.
 */
object HfConfigs {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text).let { if (it is JsonNull) null else it.jsonObject }
    } catch (_: Exception) {
        null
    }

    fun objStr(o: JsonObject?, key: String): String? =
        (o?.get(key) as? JsonPrimitive)?.contentOrNull

    fun objInt(o: JsonObject?, key: String): Int? =
        (o?.get(key) as? JsonPrimitive)?.intOrNull

    fun objLong(o: JsonObject?, key: String): Long? =
        (o?.get(key) as? JsonPrimitive)?.let { p ->
            p.contentOrNull?.toLongOrNull() ?: p.doubleOrNull?.toLong()
        }

    fun objBool(o: JsonObject?, key: String): Boolean? =
        (o?.get(key) as? JsonPrimitive)?.booleanOrNull

    /** token puede ser string directo o {"content": "..."} (formato AddedToken). */
    fun tokenContent(o: JsonObject?, key: String): String? = when (val el = o?.get(key)) {
        is JsonPrimitive -> el.contentOrNull
        is JsonObject -> (el["content"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    /** eos_token_id puede ser int o lista de ints (múltiples paradas). */
    fun intList(o: JsonObject?, key: String): List<Int> = when (val el = o?.get(key)) {
        is JsonPrimitive -> listOfNotNull(el.intOrNull)
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        else -> emptyList()
    }

    // ============================ Modelos de datos ============================

    data class ModelConfig(
        val architectures: List<String>,
        val modelType: String?,
        val vocabSize: Int?,
        val numHiddenLayers: Int?,
        val numAttentionHeads: Int?,
        val numKeyValueHeads: Int?,
        val hiddenSize: Int?,
        val maxPositionEmbeddings: Int?,
        val useCache: Boolean?,
        val eosTokenIds: List<Int>,
        val bosTokenId: Int?,
        val padTokenId: Int?,
        val raw: JsonObject
    )

    data class GenerationConfig(
        val eosTokenIds: List<Int>,
        val bosTokenId: Int?,
        val padTokenId: Int?,
        val maxNewTokens: Int?,
        val doSample: Boolean?,
        val raw: JsonObject
    )

    data class TokenizerConfig(
        val chatTemplate: String?,
        val eosToken: String?,
        val bosToken: String?,
        val padToken: String?,
        val modelMaxLength: Long?,
        val addBosToken: Boolean?,
        val raw: JsonObject
    )

    fun parseModelConfig(text: String?): ModelConfig? {
        val o = text?.let { parse(it) } ?: return null
        return ModelConfig(
            architectures = (o["architectures"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            modelType = objStr(o, "model_type"),
            vocabSize = objInt(o, "vocab_size"),
            numHiddenLayers = objInt(o, "num_hidden_layers"),
            numAttentionHeads = objInt(o, "num_attention_heads"),
            numKeyValueHeads = objInt(o, "num_key_value_heads"),
            hiddenSize = objInt(o, "hidden_size"),
            maxPositionEmbeddings = objInt(o, "max_position_embeddings"),
            useCache = objBool(o, "use_cache"),
            eosTokenIds = intList(o, "eos_token_id"),
            bosTokenId = objInt(o, "bos_token_id"),
            padTokenId = objInt(o, "pad_token_id"),
            raw = o
        )
    }

    fun parseGenerationConfig(text: String?): GenerationConfig? {
        val o = text?.let { parse(it) } ?: return null
        return GenerationConfig(
            eosTokenIds = intList(o, "eos_token_id"),
            bosTokenId = objInt(o, "bos_token_id"),
            padTokenId = objInt(o, "pad_token_id"),
            maxNewTokens = objInt(o, "max_new_tokens"),
            doSample = objBool(o, "do_sample"),
            raw = o
        )
    }

    fun parseTokenizerConfig(text: String?): TokenizerConfig? {
        val o = text?.let { parse(it) } ?: return null
        return TokenizerConfig(
            chatTemplate = objStr(o, "chat_template"),
            eosToken = tokenContent(o, "eos_token"),
            bosToken = tokenContent(o, "bos_token"),
            padToken = tokenContent(o, "pad_token"),
            modelMaxLength = objLong(o, "model_max_length"),
            addBosToken = objBool(o, "add_bos_token"),
            raw = o
        )
    }
}
