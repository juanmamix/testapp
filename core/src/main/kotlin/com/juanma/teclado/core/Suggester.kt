package com.juanma.teclado.core

import kotlin.math.ln

enum class SuggestionKind { TYPED, CORRECTION, COMPLETION, PREDICTION }

data class Suggestion(val word: String, val score: Float, val kind: SuggestionKind)

/**
 * @property slots what the suggestion bar shows: [left, middle, right]. The middle one
 *   is the best guess (and the autocorrection, if any). Empty strings = empty slot.
 * @property autoCorrect what the word becomes when the user presses space, or null.
 */
class SuggestResult(val typed: String, val slots: List<String>, val autoCorrect: String?) {
    companion object { val EMPTY = SuggestResult("", listOf("", "", ""), null) }
}

class Suggester(private val lm: LanguageModel) {

    fun candidates(typed: String, prev: String?, limit: Int = 8): List<Suggestion> {
        val folded = TextUtil.fold(typed)
        if (folded.isEmpty() || !folded.all { it.isLetter() }) return emptyList()
        val lowerTyped = typed.lowercase()
        val best = HashMap<String, Suggestion>()
        fun offer(s: Suggestion) {
            val key = s.word.lowercase()
            val cur = best[key]
            if (cur == null || s.score > cur.score) best[key] = s
        }

        val maxCost = when {
            folded.length <= 2 -> 0f
            folded.length <= 4 -> 1f
            folded.length <= 7 -> 1.6f
            else -> 2.1f
        }

        // Exact matches, accent variants and typo corrections.
        lm.trie.fuzzy(folded, maxCost, KeyProximity::substitutionCost) { e, cost ->
            if (e.freq <= 0) return@fuzzy
            var score = ln(e.freq.toFloat()) - COST_WEIGHT * cost + bigramBonus(prev, e.word)
            val kind = if (e.word.lowercase() == lowerTyped) {
                score += EXACT_BONUS
                SuggestionKind.TYPED
            } else SuggestionKind.CORRECTION
            offer(Suggestion(e.word, score, kind))
        }

        // Completions of what has been typed so far.
        lm.trie.find(folded)?.let { node ->
            for (e in lm.trie.topWords(node, 6, minLength = 1)) {
                if (e.freq <= 0) continue
                val extra = TextUtil.fold(e.word).length - folded.length
                val score = ln(e.freq.toFloat()) - COMPLETION_PENALTY - 0.25f * extra + bigramBonus(prev, e.word)
                offer(Suggestion(e.word, score, SuggestionKind.COMPLETION))
            }
        }

        return best.values.sortedByDescending { it.score }.take(limit)
    }

    fun suggest(typed: String, prev: String?, autocorrect: Boolean = true): SuggestResult {
        if (typed.isEmpty()) return SuggestResult.EMPTY
        val cands = candidates(typed, prev)
        val auto = if (autocorrect) pickAutoCorrection(typed, cands) else null

        val others = cands.map { TextUtil.matchCase(it.word, typed) }.filter { it != typed && it != auto }
        val slots = if (auto != null) {
            // Like iOS: literal word on the left (in quotes in the UI), correction in the middle.
            listOf(typed, auto, others.firstOrNull().orEmpty())
        } else {
            listOf(others.getOrElse(0) { "" }, typed, others.getOrElse(1) { "" }).let {
                // If what the user typed isn't a word, put the best guess in the middle.
                if (!lm.isKnown(typed) && others.isNotEmpty()) listOf(typed, others[0], others.getOrElse(1) { "" }) else it
            }
        }
        return SuggestResult(typed, slots, auto)
    }

    /** Suggestions for a word that's already written (cursor placed on it). */
    fun alternativesFor(word: String, prev: String?): List<String> =
        candidates(word, prev, 10)
            .map { TextUtil.matchCase(it.word, word) }
            .filter { it != word }
            .take(3)

    fun predict(prev: String?, sentenceStart: Boolean): List<String> =
        lm.predictNext(prev, 3).map { if (sentenceStart) TextUtil.capitalize(it) else it }

    private fun pickAutoCorrection(typed: String, cands: List<Suggestion>): String? {
        if (typed.length < 2 || !typed.all { it.isLetter() }) return null
        val lower = typed.lowercase()
        // Never touch something the user typed that is a real (or learned) word...
        if (lm.isKnown(lower) || lm.isKnown(typed)) {
            // ...except restoring a missing accent when the plain form doesn't exist.
            return null
        }
        val top = cands.firstOrNull { it.kind == SuggestionKind.CORRECTION } ?: return null
        val folded = TextUtil.fold(top.word) == TextUtil.fold(typed)
        val threshold = if (folded) ACCENT_THRESHOLD else AUTOCORRECT_THRESHOLD
        if (top.score < threshold) return null
        return TextUtil.matchCase(top.word, typed)
    }

    private fun bigramBonus(prev: String?, word: String): Float {
        val c = lm.bigramCount(prev, word)
        return if (c > 0) 1.2f * ln(1f + c) + 1f else 0f
    }

    companion object {
        const val COST_WEIGHT = 3.4f
        const val EXACT_BONUS = 2.5f
        const val COMPLETION_PENALTY = 1.2f
        /** ln(freq) - cost needed to autocorrect: cost 1 ≈ needs freq ≥ ~1600. */
        const val AUTOCORRECT_THRESHOLD = 4.0f
        const val ACCENT_THRESHOLD = 3.0f
    }
}
