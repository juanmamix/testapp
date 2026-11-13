package com.juanma.teclado.core

import kotlin.math.ln

/**
 * Dictionary + what the user has taught it:
 *  - words (new words enter the dictionary after being used twice);
 *  - the order the user writes them in: what follows each word (bigrams) and each pair
 *    of words (trigrams), including which words they start sentences with;
 *  - which emoji they put after which words, and their most used emoji.
 *
 * Context is a list of preceding words (oldest first), as produced by
 * [TextUtil.contextWords]; it may start with [TextUtil.SENTENCE_START].
 */
class LanguageModel {

    val trie = Trie()

    /** Words the user has typed: display form -> times used. */
    private val userWords = HashMap<String, Int>()
    /** "prev" or "prev2 prev1" (folded) -> next token (word or emoji) -> count. */
    private val next = HashMap<String, HashMap<String, Int>>()
    /** Emoji -> times used. */
    private val emojiUse = HashMap<String, Int>()

    private var defaultPredictions: List<String> = emptyList()
    private var learnCount = 0

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
    fun nextLetterDistribution(prefix: String, letters: Collection<Char>, context: List<String> = emptyList()): Map<Char, Float>? {
        val folded = TextUtil.fold(prefix)
        val node = trie.find(folded) ?: return null
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
        val base = raw.mapValues { (_, v) -> 0.92 * v / sum + 0.08 * uniform }.toMutableMap()

        // What this person usually writes next pulls the letters of those words too.
        val ctxWords = contextCandidates(context)
        if (ctxWords.isNotEmpty()) {
            val boost = HashMap<Char, Double>()
            var total = 0.0
            for ((w, weight) in ctxWords) {
                val f = TextUtil.fold(w)
                if (f.length > folded.length && f.startsWith(folded)) {
                    boost[f[folded.length]] = (boost[f[folded.length]] ?: 0.0) + weight
                    total += weight
                }
            }
            if (total > 0) {
                val mix = minOf(0.5, total) // more evidence -> more influence (max 50%)
                for (c in letters) base[c] = (1 - mix) * base[c]!! + mix * (boost[c] ?: 0.0) / total
            }
        }
        return base.mapValues { it.value.toFloat() }
    }

    // ------------------------------------------------------------------ word order

    private fun keys(context: List<String>): List<String> {
        val c = context.takeLast(2).map { if (it == TextUtil.SENTENCE_START) it else TextUtil.fold(it) }
        return when (c.size) {
            0 -> emptyList()
            1 -> listOf(c[0])
            else -> listOf(c[1], c[0] + " " + c[1])
        }
    }

    private fun countIn(map: Map<String, Int>?, token: String): Int {
        if (map == null) return 0
        return map[token] ?: map[token.lowercase()] ?: 0
    }

    /** How often [token] followed the last word, and the last two words, of [context]. */
    fun contextCounts(context: List<String>, token: String): Pair<Int, Int> {
        val k = keys(context)
        val c2 = k.getOrNull(0)?.let { countIn(next[it], token) } ?: 0
        val c3 = k.getOrNull(1)?.let { countIn(next[it], token) } ?: 0
        return c2 to c3
    }

    /** Words (not emoji) the user tends to write after [context], with a weight 0..1. */
    private fun contextCandidates(context: List<String>): Map<String, Double> {
        val k = keys(context)
        val scores = HashMap<String, Double>()
        fun add(map: Map<String, Int>?, w: Double) {
            if (map == null) return
            val total = map.values.sum().toDouble()
            for ((t, c) in map) if (!TextUtil.isEmoji(t)) scores[t] = (scores[t] ?: 0.0) + w * c / total
        }
        add(k.getOrNull(0)?.let { next[it] }, 0.4)
        add(k.getOrNull(1)?.let { next[it] }, 0.6) // two words of context say more
        return scores
    }

    /** Next-word predictions after [context]: the user's habits first, then common words. */
    fun predictNext(context: List<String>, k: Int): List<String> {
        val scores = HashMap<String, Double>(contextCandidates(context))
        val totalUser = userWords.values.sum().toDouble()
        if (totalUser > 0) {
            for ((w, c) in userWords.entries.sortedByDescending { it.value }.take(20)) {
                if (trie.contains(w)) scores[w] = (scores[w] ?: 0.0) + 0.08 * c / totalUser
            }
        }
        defaultPredictions.forEachIndexed { i, w ->
            scores[w] = (scores[w] ?: 0.0) + 0.02 * (1.0 - i / 12.0)
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }.distinctBy { it.lowercase() }.take(k)
    }

    /** Emoji the user usually puts after [context] (needs to have happened twice). */
    fun emojiAfter(context: List<String>): String? {
        val k = keys(context)
        val best = HashMap<String, Int>()
        for (key in k) next[key]?.forEach { (t, c) -> if (TextUtil.isEmoji(t)) best[t] = maxOf(best[t] ?: 0, c) }
        return best.entries.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
    }

    /** Emoji for a word: what the user used after it, else a built-in default. */
    fun emojiFor(word: String): String? {
        val f = TextUtil.fold(word)
        next[f]?.entries?.filter { TextUtil.isEmoji(it.key) }?.maxByOrNull { it.value }?.let { return it.key }
        return EmojiData.KEYWORDS[f]
    }

    fun recentEmojis(k: Int): List<String> = emojiUse.entries.sortedByDescending { it.value }.take(k).map { it.key }

    // ------------------------------------------------------------------ learning

    /**
     * Records that the user committed [word] after [context]. New words only enter the
     * dictionary after being used twice, unless [force] (e.g. user undid an autocorrection).
     */
    fun learn(context: List<String>, word: String, force: Boolean = false) {
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
        recordNext(context, stored)
    }

    /** Records that the user wrote [emoji] after [context]. */
    fun learnEmoji(context: List<String>, emoji: String) {
        emojiUse[emoji] = (emojiUse[emoji] ?: 0) + 1
        // Emoji after the start of a sentence says nothing about any word.
        if (context.lastOrNull() != TextUtil.SENTENCE_START) recordNext(context, emoji)
        dirty = true
    }

    private fun recordNext(context: List<String>, token: String) {
        for (key in keys(context)) {
            val m = next.getOrPut(key) { HashMap() }
            m[token] = (m[token] ?: 0) + 1
        }
        dirty = true
        if (++learnCount % 200 == 0) prune()
    }

    /** Keeps memory bounded: once large, forget pairs seen only once. */
    private fun prune() {
        val size = next.values.sumOf { it.size }
        if (size < MAX_PAIRS) return
        val it = next.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.values.removeIf { c -> c <= 1 }
            if (e.value.isEmpty()) it.remove()
        }
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

    fun exportOrder(): String = buildString {
        for ((p, m) in next) for ((w, c) in m) append(p).append('\t').append(w).append('\t').append(c).append('\n')
    }

    fun exportEmoji(): String = buildString {
        for ((e, c) in emojiUse) append(e).append('\t').append(c).append('\n')
    }

    fun importUser(words: Sequence<String>, order: Sequence<String>, emoji: Sequence<String> = emptySequence()) {
        for (line in words) {
            val p = line.split('\t')
            if (p.size != 2) continue
            val c = p[1].toIntOrNull() ?: continue
            userWords[p[0]] = c
            if (trie.contains(p[0])) trie.add(p[0], USER_BOOST * c)
            else if (c >= 2) trie.add(p[0], USER_BOOST * c)
        }
        for (line in order) {
            val p = line.split('\t')
            if (p.size != 3) continue
            val c = p[2].toIntOrNull() ?: continue
            next.getOrPut(p[0]) { HashMap() }[p[1]] = c
        }
        for (line in emoji) {
            val p = line.split('\t')
            if (p.size != 2) continue
            emojiUse[p[0]] = p[1].toIntOrNull() ?: continue
        }
    }

    fun markSaved() { dirty = false }

    companion object {
        /** Frequency added each time the user uses a word (≈ a moderately common word). */
        const val USER_BOOST = 3000
        const val MAX_PAIRS = 60_000
    }
}
