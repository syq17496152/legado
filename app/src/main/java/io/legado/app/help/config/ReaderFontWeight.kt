package io.legado.app.help.config

object ReaderFontWeight {

    const val MIN = 100
    const val MAX = 900
    const val DEFAULT = 400

    fun normalize(storedValue: Int): Int {
        return when (storedValue) {
            0 -> DEFAULT
            1 -> 700
            2 -> 300
            else -> storedValue.coerceIn(MIN, MAX)
        }
    }

    fun titleWeight(bodyWeight: Int): Int {
        return (normalize(bodyWeight) + 300).coerceIn(DEFAULT, MAX)
    }
}