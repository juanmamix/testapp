package com.juanma.teclado.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class CoreTest {

    companion object {
        lateinit var lm: LanguageModel
        lateinit var sug: Suggester

        @BeforeClass
        @JvmStatic
        fun load() {
            val f = listOf("../app/src/main/assets/dict_es.txt", "app/src/main/assets/dict_es.txt")
                .map(::File).first { it.exists() }
            lm = LanguageModel()
            val t0 = System.nanoTime()
            f.useLines { lm.loadBase(it) }
            println("dictionary loaded in ${(System.nanoTime() - t0) / 1_000_000} ms, ${lm.trie.wordCount} words")
            sug = Suggester(lm)
        }
    }

    @Test fun correctsTypos() {
        assertEquals("hola", sug.suggest("hoal", emptyList()).autoCorrect)
        assertEquals("que", sug.suggest("qie", emptyList()).autoCorrect)
        assertEquals("gracias", sug.suggest("grasias", emptyList()).autoCorrect)
        assertEquals("Mañana", sug.suggest("Mañna", emptyList()).autoCorrect)
    }

    @Test fun restoresAccents() {
        assertEquals("canción", sug.suggest("cancion", emptyList()).autoCorrect)
        assertEquals("después", sug.suggest("despues", emptyList()).autoCorrect)
    }

    @Test fun leavesRealWordsAlone() {
        assertNull(sug.suggest("esta", emptyList()).autoCorrect)
        assertNull(sug.suggest("casa", emptyList()).autoCorrect)
        assertNull(sug.suggest("el", emptyList()).autoCorrect)
    }

    @Test fun completes() {
        val r = sug.suggest("grac", emptyList())
        assertTrue(r.slots.toString(), r.slots.contains("gracias"))
    }

    @Test fun learnsUserWords() {
        val m = LanguageModel()
        m.loadBase(sequenceOf("hola\t1000", "holanda\t500"))
        assertTrue(!m.isKnown("holaaa"))
        m.learn(emptyList(), "holaaa"); assertTrue(!m.isKnown("holaaa"))
        m.learn(emptyList(), "holaaa"); assertTrue(m.isKnown("holaaa"))
        m.learn(listOf("hola"), "amigo")
        assertEquals("amigo", m.predictNext(listOf("hola"), 1)[0])
        val m2 = LanguageModel()
        m2.loadBase(sequenceOf("hola\t1000"))
        m2.importUser(m.exportUserWords().lineSequence(), m.exportOrder().lineSequence(), m.exportEmoji().lineSequence())
        assertTrue(m2.isKnown("holaaa"))
        assertEquals(m.frequency("holaaa"), m2.frequency("holaaa"))
        assertEquals("amigo", m2.predictNext(listOf("hola"), 1)[0])
    }

    @Test fun learnsWordOrderWithTwoWordsOfContext() {
        val m = LanguageModel()
        m.loadBase(sequenceOf("de\t1000", "nada\t900", "verdad\t800", "la\t700", "es\t600", "pues\t10"))
        // "de nada" and "de verdad" both follow "de"; the word before decides
        repeat(3) { m.learn(listOf("gracias", "de"), "nada") }
        repeat(3) { m.learn(listOf("es", "de"), "verdad") }
        m.learn(listOf("es", "de"), "nada")
        assertEquals("nada", m.predictNext(listOf("gracias", "de"), 1)[0])
        assertEquals("verdad", m.predictNext(listOf("es", "de"), 1)[0])
        // sentence starters
        repeat(2) { m.learn(listOf(TextUtil.SENTENCE_START), "pues") }
        assertEquals("pues", m.predictNext(listOf(TextUtil.SENTENCE_START), 1)[0])
    }

    @Test fun learnsEmojiAfterWords() {
        val m = LanguageModel()
        m.loadBase(sequenceOf("jaja\t100", "pizza\t100", "noche\t100"))
        assertEquals("😂", m.emojiFor("jaja")) // built-in default
        m.learnEmoji(listOf("jaja"), "🤣")
        assertEquals("🤣", m.emojiFor("jaja")) // the user's habit wins
        assertNull(m.emojiAfter(listOf("noche")))
        m.learnEmoji(listOf("buenas", "noche"), "🌙"); m.learnEmoji(listOf("buenas", "noche"), "🌙")
        assertEquals("🌙", m.emojiAfter(listOf("buenas", "noche")))
        assertEquals("🌙", Suggester(m).predict(listOf("buenas", "noche"), false).last())
        assertEquals(listOf("🌙", "🤣"), m.recentEmojis(5))
        assertTrue(Suggester(m).suggest("pizza", emptyList()).slots.contains("🍕"))
    }

    @Test fun contextWordsStayInTheSentence() {
        assertEquals(listOf("muchas", "gracias"), TextUtil.contextWords("Hola. Muchas gracias ", true).map { it.lowercase() })
        assertEquals(listOf(TextUtil.SENTENCE_START, "hola"), TextUtil.contextWords("Vale. ¿hola ", true))
        assertEquals(listOf(TextUtil.SENTENCE_START), TextUtil.contextWords("", true))
        assertEquals(listOf("jaja"), TextUtil.contextWords("que jaja 😂 ", false, 1))
        assertTrue(TextUtil.isEmoji("😂")); assertTrue(TextUtil.isEmoji("❤️")); assertTrue(!TextUtil.isEmoji("€"))
    }

    @Test fun letterDistributionFollowsLanguage() {
        val d = lm.nextLetterDistribution("qu", "qwertyuiopasdfghjklñzxcvbnm".toList())!!
        // after "qu" Spanish almost always continues with e or i
        assertTrue(d['e']!! > 0.2f)
        assertTrue(d['e']!! > 10 * d['w']!!)
    }

    // ---- touch model -------------------------------------------------------------

    private fun row(letters: String, keyW: Float = 100f, keyH: Float = 150f): List<KeyBox> =
        letters.mapIndexed { i, c -> KeyBox(i, c, i * keyW, 0f, (i + 1) * keyW, keyH) }

    @Test fun touchesInsideCoreAlwaysWin() {
        val keys = row("qwe")
        val tm = TouchModel()
        // 'q' is extremely likely, but a touch right on 'w' still gives 'w'
        val k = tm.resolve(150f, 75f, keys, mapOf('q' to 0.99f, 'w' to 0.001f, 'e' to 0.009f))
        assertEquals('w', k!!.letter)
    }

    @Test fun likelyKeyGrowsOnTheBorder() {
        val keys = row("qwe")
        val tm = TouchModel()
        val x = 108f // inside 'w' by 8px (key is 100px wide)
        assertEquals('w', tm.resolve(x, 75f, keys, null)!!.letter)
        assertEquals('q', tm.resolve(x, 75f, keys, mapOf('q' to 0.9f, 'w' to 0.05f, 'e' to 0.05f))!!.letter)
        // and the opposite: unlikely 'q' loses its own border to 'w'
        assertEquals('w', tm.resolve(92f, 75f, keys, mapOf('q' to 0.01f, 'w' to 0.9f, 'e' to 0.09f))!!.letter)
    }

    @Test fun specialKeysKeepTheirShape() {
        val keys = row("qw") + KeyBox(2, null, 200f, 0f, 300f, 150f)
        val tm = TouchModel()
        assertNull(tm.resolve(205f, 75f, keys, mapOf('w' to 1f))!!.letter)
    }

    @Test fun learnsUserAim() {
        val keys = row("qwe")
        val tm = TouchModel()
        repeat(200) { tm.learn(keys[1], 175f, 75f) } // user always hits the right part of 'w'
        // a touch slightly into 'e' is now read as 'w'
        assertEquals('w', tm.resolve(204f, 75f, keys, null)!!.letter)
        val back = TouchModel().apply { importOffsets(tm.exportOffsets()) }
        assertEquals('w', back.resolve(204f, 75f, keys, null)!!.letter)
    }

    @Test fun suggestionsAreFast() {
        val t0 = System.nanoTime()
        repeat(200) { sug.suggest("necesitamoz", listOf("nosotros")) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / 200
        println("suggest: %.2f ms".format(ms))
        assertNotNull(sug.suggest("necesitamoz", emptyList()).autoCorrect)
        assertTrue(ms < 20)
    }

    @Test fun everyRowFillsTheWidth() {
        for (spec in listOf(Layouts.LETTERS, Layouts.SYMBOLS, Layouts.SYMBOLS2)) for (row in spec.rows) {
            assertEquals("${spec.name}: ${row.map { it.label }}", 10f, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        }
    }

    @Test fun contextShiftsLetterOdds() {
        val m = LanguageModel()
        m.loadBase(sequenceOf("noches\t100", "nada\t5000"))
        val letters = "abcdefghijklmnñopqrstuvwxyz".toList()
        val plain = m.nextLetterDistribution("n", letters, listOf("buenas"))!!
        repeat(3) { m.learn(listOf("buenas"), "noches") }
        val ctx = m.nextLetterDistribution("n", letters, listOf("buenas"))!!
        assertTrue(ctx['o']!! > plain['o']!! + 0.2f)
    }
}
