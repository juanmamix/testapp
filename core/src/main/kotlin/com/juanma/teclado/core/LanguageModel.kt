package com.juanma.teclado.core

import kotlin.math.ln

/**
 * Dictionary + what the user has taught it. Base words come from a frequency list;
 * words the user types are added on top, and word pairs (bigrams) the user writes
 * are remembered for next-word prediction.
 */
class LanguageModel {

    val trie = Trie()

    /** Words the user has typed: display form -> times used. */
    private val userWords = HashMap<String, Int>()
    /** folded previous word -> (next word display form -> count). */
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    private var defaultPredictions: List<String> = emptyList()

    var dirty = false
        private set

    fun loadBase(lines: Sequence<String>) {
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val f = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            trie.add(line.substring(0, tab), f)
        }
        defaultPredictions = trie.topWords(trie.root, 12).map { it.word }
    }

    fun isKnown(word: String) = trie.contains(word)

    fun frequency(word: String): Int = trie.lookup(word).firstOrNull { it.word.equals(word, true) }?.freq ?: 0

    /**
     * Probability of each letter being the next one typed, given the letters of the
     * current word so far. Returns null if the prefix is not in the dictionary (we then
     * have no opinion and the keyboard behaves like a normal one).
     */
    fun nextLetterDistribution(prefix: String, letters: Collection<Char>): Map<Char, Float>? {
        val node = trie.find(TextUtil.fold(prefix)) ?: return null
        var sum = 0.0
        val raw = HashMap<Char, Double>(letters.size)
        for (c in letters) {
            val t = node.child(c)?.total ?: 0L
            // log-damped counts: plain counts would let "de"/"que" swamp everything else
            val v = if (t > 0) ln(1.0 + t) else 0.0
            raw[c] = v
            sum += v
        }
        if (sum <= 0.0) return null
        val uniform = 1.0 / letters.size
        return raw.mapValues { (_, v) -> (0.92 * v / sum + 0.08 * uniform).toFloat() }
    }

    fun bigramCount(prev: String?, word: String): Int {
        if (prev == null) return 0
        return bigrams[TextUtil.fold(prev)]?.entries?.firstOrNull { it.key.equals(word, true) }?.value ?: 0
    }

    /** Next-word predictions after [prev] (null = start of text). */
    fun predictNext(prev: String?, k: Int): List<String> {
        val out = LinkedHashSet<String>()
        if (prev != null) {
            bigrams[TextUtil.fold(prev)]?.entries?.sortedByDescending { it.value }?.take(k)?.forEach { out.add(it.key) }
        }
        for (w in defaultPredictions) {
            if (out.size >= k) break
            out.add(w)
        }
        return out.take(k)
    }

    /**
     * Records that the user committed [word] after [prev]. New words only enter the
     * dictionary after being used twice, unless [force] (e.g. user undid an autocorrection).
     */
    fun learn(prev: String?, word: String, force: Boolean = false) {
        if (word.isEmpty() || !word.any { it.isLetter() }) return
        val lower = word.lowercase()
        // Keep capitals only for words the dictionary doesn't know (names, brands...)
        val stored = if (word.length == 1 || isKnown(lower)) lower else word
        val before = userWords[stored] ?: 0
        var after = before + 1
        if (force && after < 2) after = 2
        userWords[stored] = after
        if (trie.contains(stored)) trie.add(stored, USER_BOOST * (after - before))
        else if (after >= 2) trie.add(stored, USER_BOOST * after)
        if (prev != null) {
            val m = bigrams.getOrPut(TextUtil.fold(prev)) { HashMap() }
            m[stored] = (m[stored] ?: 0) + 1
        }
        dirty = true
    }

    /** User rejected an autocorrection to [word]: make it a bit less eager next time. */
    fun penalize(word: String) {
        trie.lookup(word).firstOrNull { it.word == word }?.let { trie.add(word, -minOf(it.freq / 4, USER_BOOST)) }
        dirty = true
    }

    // ---- persistence (plain text so the Android layer just reads/writes files) ----

    fun exportUserWords(): String = buildString {
        for ((w, c) in userWords) append(w).append('\t').append(c).append('\n')
    }

    fun exportBigrams(): String = buildString {
        for ((p, m) in bigrams) for ((w, c) in m) append(p).append('\t').append(w).append('\t').append(c).append('\n')
    }

    fun importUser(words: Sequence<String>, pairs: Sequence<String>) {
        for (line in words) {
            val p = line.split('\t')
            if (p.size != 2) continue
            val c = p[1].toIntOrNull() ?: continue
            userWords[p[0]] = c
            if (trie.contains(p[0])) trie.add(p[0], USER_BOOST * c)
            else if (c >= 2) trie.add(p[0], USER_BOOST * c)
        }
        for (line in pairs) {
            val p = line.split('\t')
            if (p.size != 3) continue
            val c = p[2].toIntOrNull() ?: continue
            bigrams.getOrPut(p[0]) { HashMap() }[p[1]] = c
        }
    }

    fun markSaved() { dirty = false }

    companion object {
        /** Frequency added each time the user uses a word (≈ a moderately common word). */
        const val USER_BOOST = 3000
    }
}
