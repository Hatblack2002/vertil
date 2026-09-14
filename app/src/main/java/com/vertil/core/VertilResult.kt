package com.vertil.core

/**
 * Resultado de cualquier operación del Core de VERTIL.
 * No propagamos excepciones: el llamador debe tratar el error explícitamente.
 */
sealed class VertilResult<out T> {
    data class Success<T>(val value: T) : VertilResult<T>()
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val module: String = "unknown",
        val code: String? = null
    ) : VertilResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): VertilResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): VertilResult<T> {
        if (this is Success) action(value); return this
    }

    inline fun onFailure(action: (Failure) -> Unit): VertilResult<T> {
        if (this is Failure) action(this); return this
    }

    fun getOrNull(): T? = (this as? Success)?.value
    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    companion object {
        fun <T> ok(value: T): VertilResult<T> = Success(value)
        fun fail(message: String, cause: Throwable? = null, module: String = "unknown", code: String? = null): VertilResult<Nothing> =
            Failure(message, cause, module, code)
    }
}
