package com.juanma.teclado.core

import java.util.PriorityQueue

/**
 * Word trie keyed on accent-folded lowercase letters. Every node keeps the summed
 * frequency of its subtree, which is what lets us compute "how likely is the next
 * letter to be X" in O(1) per key, and the max frequency, which lets us find the
 * top completions with a best-first search.
 */
class Trie {

    class Entry(val word: String, var freq: Int)

    class Node {
        internal var keys: CharArray = EMPTY_KEYS
        internal var kids: Array<Node?> = EMPTY_KIDS
        internal var size = 0
        /** Sum of frequencies of every word in this subtree. */
        var total: Long = 0
            internal set
        /** Highest single word frequency in this subtree. */
        var maxFreq: Int = 0
            internal set
        /** Words (display forms, possibly with accents) that end exactly here. */
        var words: MutableList<Entry>? = null
            internal set

        fun child(c: Char): Node? {
            for (i in 0 until size) if (keys[i] == c) return kids[i]
            return null
        }

        fun childCount() = size
        fun keyAt(i: Int) = keys[i]
        fun childAt(i: Int) = kids[i]!!

        internal fun getOrAdd(c: Char): Node {
            child(c)?.let { return it }
            if (size == keys.size) {
                val n = if (size == 0) 2 else size * 2
                keys = keys.copyOf(n)
                kids = kids.copyOf(n)
            }
            val node = Node()
            keys[size] = c
            kids[size] = node
            size++
            return node
        }

        companion object {
            private val EMPTY_KEYS = CharArray(0)
            private val EMPTY_KIDS = arrayOfNulls<Node>(0)
        }
    }

    val root = Node()
    var wordCount = 0
        private set

    /** Adds [delta] to the frequency of [word] (creating it if needed). Returns the entry. */
    fun add(word: String, delta: Int): Entry {
        val key = TextUtil.fold(word)
        var n = root
        for (c in key) n = n.getOrAdd(c)
        val list = n.words ?: ArrayList<Entry>(1).also { n.words = it }
        var entry = list.firstOrNull { it.word == word }
        if (entry == null) {
            entry = Entry(word, 0)
            list.add(entry)
            wordCount++
        }
        entry.freq = (entry.freq.toLong() + delta).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val f = entry.freq
        // Second pass: update aggregates along the path.
        n = root
        n.total += delta
        if (f > n.maxFreq) n.maxFreq = f
        for (c in key) {
            n = n.child(c)!!
            n.total += delta
            if (f > n.maxFreq) n.maxFreq = f
        }
        return entry
    }

    fun find(foldedPrefix: String): Node? {
        var n: Node? = root
        for (c in foldedPrefix) n = n?.child(c) ?: return null
        return n
    }

    /** Display forms of a word (all accent variants), or empty. */
    fun lookup(word: String): List<Entry> = find(TextUtil.fold(word))?.words ?: emptyList()

    fun contains(word: String): Boolean = lookup(word).any { it.word.equals(word, ignoreCase = true) }

    /** Top [k] words under [node] by frequency (best-first on maxFreq). */
    fun topWords(node: Node, k: Int, minLength: Int = 0, depth: Int = 0): List<Entry> {
        data class Item(val prio: Int, val node: Node?, val entry: Entry?, val depth: Int)
        val pq = PriorityQueue<Item>(compareByDescending { it.prio })
        pq.add(Item(node.maxFreq, node, null, depth))
        val out = ArrayList<Entry>(k)
        while (pq.isNotEmpty() && out.size < k) {
            val it = pq.poll()
            if (it.entry != null) {
                out.add(it.entry)
                continue
            }
            val n = it.node!!
            if (it.depth >= minLength) n.words?.forEach { e -> pq.add(Item(e.freq, null, e, it.depth)) }
            for (i in 0 until n.size) {
                val ch = n.kids[i]!!
                pq.add(Item(ch.maxFreq, ch, null, it.depth + 1))
            }
        }
        return out
    }

    /**
     * Weighted Damerau-Levenshtein search over the trie. [subCost] gives the cost of
     * typing [typed] where the word has [actual]; neighbouring keys should be cheaper.
     */
    fun fuzzy(
        typed: String,
        maxCost: Float,
        subCost: (typed: Char, actual: Char) -> Float,
        emit: (Entry, Float) -> Unit,
    ) {
        val n = typed.length
        val first = FloatArray(n + 1) { it.toFloat() }
        for (i in 0 until root.size) {
            fuzzyRec(root.kids[i]!!, root.keys[i], ' ', null, first, typed, maxCost, subCost, emit)
        }
    }

    private fun fuzzyRec(
        node: Node,
        ch: Char,
        prevCh: Char,
        prevPrev: FloatArray?,
        prev: FloatArray,
        typed: String,
        maxCost: Float,
        subCost: (Char, Char) -> Float,
        emit: (Entry, Float) -> Unit,
    ) {
        val n = typed.length
        val row = FloatArray(n + 1)
        row[0] = prev[0] + 1f
        var best = row[0]
        for (j in 1..n) {
            val t = typed[j - 1]
            var v = minOf(row[j - 1] + 1f, prev[j] + 1f)
            val rep = prev[j - 1] + if (t == ch) 0f else subCost(t, ch)
            if (rep < v) v = rep
            if (prevPrev != null && j > 1 && t == prevCh && typed[j - 2] == ch) {
                val tr = prevPrev[j - 2] + 0.7f
                if (tr < v) v = tr
            }
            row[j] = v
            if (v < best) best = v
        }
        if (row[n] <= maxCost) node.words?.forEach { emit(it, row[n]) }
        if (best <= maxCost) {
            for (i in 0 until node.size) {
                fuzzyRec(node.kids[i]!!, node.keys[i], ch, prev, row, typed, maxCost, subCost, emit)
            }
        }
    }
}
