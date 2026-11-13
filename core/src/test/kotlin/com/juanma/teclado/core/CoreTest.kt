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
        assertEquals("hola", sug.suggest("hoal", null).autoCorrect)
        assertEquals("que", sug.suggest("qie", null).autoCorrect)
        assertEquals("gracias", sug.suggest("grasias", null).autoCorrect)
        assertEquals("Mañana", sug.suggest("Mañna", null).autoCorrect)
    }

    @Test fun restoresAccents() {
        assertEquals("canción", sug.suggest("cancion", null).autoCorrect)
        assertEquals("después", sug.suggest("despues", null).autoCorrect)
    }

    @Test fun leavesRealWordsAlone() {
        assertNull(sug.suggest("esta", null).autoCorrect)
        assertNull(sug.suggest("casa", null).autoCorrect)
        assertNull(sug.suggest("el", null).autoCorrect)
    }

    @Test fun completes() {
        val r = sug.suggest("grac", null)
        assertTrue(r.slots.toString(), r.slots.contains("gracias"))
    }

    @Test fun learnsUserWords() {
        val m = LanguageModel()
        m.loadBase(sequenceOf("hola\t1000", "holanda\t500"))
        assertTrue(!m.isKnown("holaaa"))
        m.learn(null, "holaaa"); assertTrue(!m.isKnown("holaaa"))
        m.learn(null, "holaaa"); assertTrue(m.isKnown("holaaa"))
        m.learn("hola", "amigo")
        assertEquals("amigo", m.predictNext("hola", 1)[0])
        val m2 = LanguageModel()
        m2.loadBase(sequenceOf("hola\t1000"))
        m2.importUser(m.exportUserWords().lineSequence(), m.exportBigrams().lineSequence())
        assertTrue(m2.isKnown("holaaa"))
        assertEquals(m.frequency("holaaa"), m2.frequency("holaaa"))
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
        repeat(200) { sug.suggest("necesitamoz", "nosotros") }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / 200
        println("suggest: %.2f ms".format(ms))
        assertNotNull(sug.suggest("necesitamoz", null).autoCorrect)
        assertTrue(ms < 20)
    }
}
