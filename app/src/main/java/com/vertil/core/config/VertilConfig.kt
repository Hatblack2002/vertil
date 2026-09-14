package com.vertil.core.config

import kotlinx.serialization.Serializable

/**
 * Configuración global de VERTIL. Inmutable.
 * Persistida vía DataStore.
 */
@Serializable
data class VertilConfig(
    val identityVersion: String = "1.0.0",
    val policyVersion: String = "1.0.0",
    val toolApiVersion: String = "1.0",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val advancedMode: Boolean = false,
    val defaultModelId: String? = null,
    val maxContextTokens: Int = 2048,
    val threadCount: Int = 4,
    val allowBackgroundMonitoring: Boolean = false,
    val autoConfirmLevel0: Boolean = true,
    val autoConfirmLevel1: Boolean = false,
    val autoConfirmLevel2: Boolean = false,
    val autoConfirmLevel3: Boolean = false,
    // Nivel 4 siempre requiere confirmación explícita.
) {
    enum class ThemeMode { SYSTEM, DARK, LIGHT }
}
