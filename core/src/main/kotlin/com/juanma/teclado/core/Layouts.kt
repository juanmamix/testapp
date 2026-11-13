package com.juanma.teclado.core

import kotlin.math.hypot

enum class KeyType { LETTER, CHAR, SHIFT, DELETE, SPACE, ENTER, TO_SYMBOLS, TO_SYMBOLS2, TO_LETTERS, EMOJI }

/** One key of a layout. Widths are in "standard key" units; every row sums to 10. */
class KeySpec(
    val type: KeyType,
    val label: String,
    val text: String = label,
    val width: Float = 1f,
    /** Alternatives offered on long-press (accents, etc.). */
    val variants: List<String> = emptyList(),
) {
    /** Lowercase letter for adaptive keys, null for everything else. */
    val letter: Char? = if (type == KeyType.LETTER) text[0] else null
}

class KeyboardSpec(val name: String, val rows: List<List<KeySpec>>)

object Layouts {

    private val VARIANTS = mapOf(
        'a' to listOf("á", "à", "ä", "â", "ª"),
        'e' to listOf("é", "è", "ë", "ê", "€"),
        'i' to listOf("í", "ì", "ï", "î"),
        'o' to listOf("ó", "ò", "ö", "ô", "º"),
        'u' to listOf("ú", "ü", "ù", "û"),
        'n' to listOf("ñ"),
        'c' to listOf("ç"),
        's' to listOf("ß", "$"),
    )

    private fun letters(s: String) = s.map { KeySpec(KeyType.LETTER, it.toString(), variants = VARIANTS[it].orEmpty()) }
    private fun chars(s: String) = s.map { KeySpec(KeyType.CHAR, it.toString()) }

    private fun bottomRow(modeKey: KeySpec) = listOf(
        modeKey,
        KeySpec(KeyType.EMOJI, "☺", width = 1.25f),
        KeySpec(KeyType.CHAR, ",", width = 1f, variants = listOf(";", ":", "-")),
        KeySpec(KeyType.SPACE, "espacio", " ", width = 4f),
        KeySpec(KeyType.CHAR, ".", width = 1f, variants = listOf("?", "!", "¿", "¡", "…")),
        KeySpec(KeyType.ENTER, "⏎", "\n", width = 1.5f),
    )

    val LETTERS = KeyboardSpec(
        "letters",
        listOf(
            letters("qwertyuiop"),
            letters("asdfghjklñ"),
            listOf(KeySpec(KeyType.SHIFT, "⇧", width = 1.5f)) + letters("zxcvbnm") +
                KeySpec(KeyType.DELETE, "⌫", width = 1.5f),
            bottomRow(KeySpec(KeyType.TO_SYMBOLS, "123", width = 1.25f)),
        ),
    )

    val SYMBOLS = KeyboardSpec(
        "symbols",
        listOf(
            chars("1234567890"),
            chars("-/:;()€&@\""),
            listOf(KeySpec(KeyType.TO_SYMBOLS2, "#+=", width = 1.5f)) + chars("_%¿?¡!'") +
                KeySpec(KeyType.DELETE, "⌫", width = 1.5f),
            bottomRow(KeySpec(KeyType.TO_LETTERS, "ABC", width = 1.25f)),
        ),
    )

    val SYMBOLS2 = KeyboardSpec(
        "symbols2",
        listOf(
            chars("[]{}#%^*+="),
            chars("_\\|~<>$£¥•"),
            listOf(KeySpec(KeyType.TO_SYMBOLS, "123", width = 1.5f)) + chars(".,?!'`·") +
                KeySpec(KeyType.DELETE, "⌫", width = 1.5f),
            bottomRow(KeySpec(KeyType.TO_LETTERS, "ABC", width = 1.25f)),
        ),
    )
}

/** Physical distance between letters on the letter layout, used to weight typos. */
object KeyProximity {
    private val centers = HashMap<Char, Pair<Float, Float>>()

    init {
        Layouts.LETTERS.rows.forEachIndexed { r, row ->
            var x = 0f
            for (k in row) {
                k.letter?.let { centers[it] = (x + k.width / 2f) to r.toFloat() }
                x += k.width
            }
        }
    }

    fun distance(a: Char, b: Char): Float {
        val pa = centers[a] ?: return 99f
        val pb = centers[b] ?: return 99f
        return hypot(pa.first - pb.first, pa.second - pb.second)
    }

    /** Substitution cost: typing [typed] when the word wanted [actual]. */
    fun substitutionCost(typed: Char, actual: Char): Float {
        if (typed == actual) return 0f
        val d = distance(typed, actual)
        return when {
            d <= 1.15f -> 0.55f // direct neighbour: the most common fat-finger error
            d <= 1.6f -> 0.8f
            else -> 1f
        }
    }
}
