package com.vertil.core.error

import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog

/**
 * Manejador de errores central.
 * Los errores se registran SIEMPRE; el llamador decide si propagarlos al usuario.
 */
class ErrorManager {

    fun report(module: String, message: String, cause: Throwable? = null, code: String? = null) {
        VertilLog.e(module, message, cause)
    }

    fun report(module: String, result: VertilResult.Failure) {
        VertilLog.e(module, "${result.message} (code=${result.code ?: "n/a"})", result.cause)
    }
}
