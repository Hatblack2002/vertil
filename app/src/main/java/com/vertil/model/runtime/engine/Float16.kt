package com.vertil.model.runtime.engine

/**
 * Conversión IEEE-754 half precision (float16) → float32 con manipulación de
 * bits (ORT expone FLOAT16 como ShortBuffer). Sin dependencias externas.
 */
internal object Float16 {

    fun toFloat(halfBits: Short): Float {
        val sign = (halfBits.int() and 0x8000)
        val exp = (halfBits.int() and 0x7C00) ushr 10
        val frac = halfBits.int() and 0x03FF

        val floatBits: Int = when (exp) {
            0 -> when (frac) {
                // cero (con signo)
                0 -> sign
                // subnormal: renormaliza
                else -> {
                    var e = -1
                    var f = frac
                    while (f and 0x0400 == 0) { f = f shl 1; e-- }
                    f = f and 0x03FF
                    sign or ((127 - 15 + e + 1) shl 23) or (f shl 13)
                }
            }
            // Inf / NaN
            0x1F -> sign or 0x7F800000 or (frac shl 13)
            // normal
            else -> sign or ((exp - 15 + 127) shl 23) or (frac shl 13)
        }
        return Float.fromBits(floatBits)
    }

    private fun Short.int(): Int = this.toInt() and 0xFFFF
}
