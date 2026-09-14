package com.vertil.core.log

import android.util.Log
import com.vertil.BuildConfig

/**
 * Logger centralizado de VERTIL.
 * No oculta excepciones: cualquier error queda registrado con contexto.
 */
object VertilLog {
    private const val PREFIX = "VERTIL"

    fun d(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.d("$PREFIX/$tag", msg, t)
    }
    fun i(tag: String, msg: String, t: Throwable? = null) = Log.i("$PREFIX/$tag", msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = Log.w("$PREFIX/$tag", msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e("$PREFIX/$tag", msg, t)
}
