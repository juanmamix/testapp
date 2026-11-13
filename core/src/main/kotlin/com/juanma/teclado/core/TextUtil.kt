package com.juanma.teclado.core

/** Helpers for normalising Spanish text. */
object TextUtil {

    /** Removes accents (but keeps ñ, which has its own key) and lowercases. */
    fun foldChar(c: Char): Char = when (val l = c.lowercaseChar()) {
        'á', 'à', 'ä', 'â', 'ã' -> 'a'
        'é', 'è', 'ë', 'ê' -> 'e'
        'í', 'ì', 'ï', 'î' -> 'i'
        'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
        'ú', 'ù', 'ü', 'û' -> 'u'
        'ç' -> 'c'
        else -> l
    }

    fun fold(s: CharSequence): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(foldChar(c))
        return sb.toString()
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit()

    /** Applies the capitalisation the user typed to a suggestion. */
    fun matchCase(candidate: String, typed: String): String {
        if (typed.isEmpty() || candidate.isEmpty()) return candidate
        val letters = typed.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return candidate.uppercase()
        if (typed[0].isUpperCase()) return candidate.replaceFirstChar { it.uppercaseChar() }
        return candidate
    }

    fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

    /** Last whole word in [text] (ignores trailing spaces), or null. */
    fun lastWord(text: CharSequence?): String? {
        if (text.isNullOrEmpty()) return null
        var end = text.length
        while (end > 0 && text[end - 1] == ' ') end--
        if (end == 0 || !isWordChar(text[end - 1])) return null
        var start = end
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.substring(start, end)
    }
}
