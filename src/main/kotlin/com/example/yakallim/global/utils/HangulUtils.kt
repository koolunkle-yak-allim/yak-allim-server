package com.example.yakallim.global.utils

object HangulUtils {
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3
    private const val MEDIAL_COUNT = 21
    private const val FINAL_COUNT = 28
    private const val INITIAL_STEP = MEDIAL_COUNT * FINAL_COUNT

    private val INITIAL_JAMOS = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )
    private val MEDIAL_JAMOS = charArrayOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    )
    private val FINAL_JAMOS = charArrayOf(
        '\u0000', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    fun normalizeToJamo(text: String): String = buildString(text.length * 3) {
        for (c in text) {
            if (c in HANGUL_BASE.toChar()..HANGUL_END.toChar()) {
                val base = c.code - HANGUL_BASE
                val initial = base / INITIAL_STEP
                val medial = (base % INITIAL_STEP) / FINAL_COUNT
                val final = base % FINAL_COUNT

                append(INITIAL_JAMOS[initial])
                append(MEDIAL_JAMOS[medial])

                if (final > 0) {
                    append(FINAL_JAMOS[final])
                }
            } else {
                append(c)
            }
        }
    }

    fun isSubsequence(query: String, target: String): Boolean {
        var i = 0
        for (ch in target) {
            if (i < query.length && query[i] == ch) i++
        }
        return i == query.length
    }

    fun levenshteinDistanceTo(text: String, target: String): Int {
        if (text == target) return 0
        if (text.isEmpty()) return target.length
        if (target.isEmpty()) return text.length

        var prevRow = IntArray(target.length + 1) { it }
        var currRow = IntArray(target.length + 1)

        for (i in 1..text.length) {
            currRow[0] = i
            val sourceChar = text[i - 1]
            for (j in 1..target.length) {
                val cost = if (sourceChar == target[j - 1]) 0 else 1
                currRow[j] = minOf(
                    currRow[j - 1] + 1, prevRow[j] + 1, prevRow[j - 1] + cost
                )
            }
            prevRow = currRow.also { currRow = prevRow }
        }
        return prevRow[target.length]
    }
}
