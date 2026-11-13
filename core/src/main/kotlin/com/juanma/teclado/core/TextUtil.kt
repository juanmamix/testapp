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

    /** Marker used as "previous word" at the start of a sentence. */
    const val SENTENCE_START = "<s>"

    /** True for emoji (and other pictographic tokens), false for words and punctuation. */
    fun isEmoji(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointAt(0)
        return cp >= 0x2190 && !Character.isLetterOrDigit(cp) && cp !in 0x2000..0x206F
    }

    /**
     * Up to [n] words before the cursor, in reading order, staying inside the current
     * sentence. If the sentence (or the text) starts before them, [SENTENCE_START] is
     * prepended. Emoji and commas are skipped. [complete] = [text] is the whole text
     * before the cursor (not a truncated window).
     */
    fun contextWords(text: CharSequence, complete: Boolean, n: Int = 2): List<String> {
        val out = ArrayList<String>(n + 1)
        var i = text.length
        while (out.size < n) {
            while (i > 0 && !isWordChar(text[i - 1]) && text[i - 1] !in SENTENCE_END) i--
            if (i == 0) {
                if (complete) out.add(SENTENCE_START)
                break
            }
            if (text[i - 1] in SENTENCE_END) {
                out.add(SENTENCE_START)
                break
            }
            val end = i
            while (i > 0 && isWordChar(text[i - 1])) i--
            out.add(text.substring(i, end))
        }
        out.reverse()
        return out
    }

    private const val SENTENCE_END = ".!?\n…"

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
