package com.juanma.teclado

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.juanma.teclado.core.KeyBox
import com.juanma.teclado.core.KeyType
import com.juanma.teclado.core.LanguageModel
import com.juanma.teclado.core.Layouts
import com.juanma.teclado.core.Suggester
import com.juanma.teclado.core.TextUtil
import com.juanma.teclado.core.TouchModel
import java.util.concurrent.Executors

class TecladoService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var store: UserStore
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // Language model is built off the main thread; until then the keyboard works without it.
    private var lm: LanguageModel? = null
    private var suggester: Suggester? = null
    private val touchModel = TouchModel()
    private var resetToken = 0L

    private var view: KeyboardView? = null

    // ---- input state
    private class Touch(val box: KeyBox, val x: Float, val y: Float)
    private class AutoFix(val typed: String, val corrected: String, val context: List<String>, var sep: String = "")
    private class CursorWord(val start: Int, val end: Int, val word: String)

    private val composing = StringBuilder()
    private val touches = ArrayList<Touch?>()
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L
    private var spec = Layouts.LETTERS
    private var predictionOn = false
    private var learnOn = false
    private var autocorrectOn = false
    private var lastAuto: AutoFix? = null
    private var autoSpace = false
    private var cursorWord: CursorWord? = null
    private var selStart = -1
    private var selEnd = -1
    private var lastSpaceTime = 0L
    private var slots = listOf("", "", "")

    private val ic: InputConnection? get() = currentInputConnection
    private val letters = Layouts.LETTERS.rows.flatten().mapNotNull { it.letter }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = UserStore(this)
        resetToken = prefs.resetToken
        loadModel()
    }

    private fun loadModel() {
        io.execute {
            val m = LanguageModel()
            assets.open("dict_es.txt").bufferedReader().useLines { m.loadBase(it) }
            m.importUser(
                store.read(store.words).lineSequence(),
                store.read(store.order).lineSequence(),
                store.read(store.emoji).lineSequence(),
            )
            val offsets = store.read(store.offsets)
            handler.post {
                lm = m
                suggester = Suggester(m)
                touchModel.importOffsets(offsets)
                refresh()
            }
        }
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        v.touchModel = touchModel
        view = v
        applyPrefs()
        return v
    }

    private fun applyPrefs() {
        touchModel.enabled = prefs.adaptiveKeys
        view?.apply {
            haptics = prefs.haptics
            sound = prefs.sound
            showZones = prefs.showZones
            keyHeightDp = prefs.keyHeightDp
        }
    }

    /** Never take over the whole screen in landscape. */
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (prefs.resetToken != resetToken) {
            resetToken = prefs.resetToken
            touchModel.clearOffsets()
            lm = null; suggester = null
            loadModel()
        }
        applyPrefs()

        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val isPassword = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val isAddress = variation in setOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )
        predictionOn = cls == InputType.TYPE_CLASS_TEXT && !isPassword && !isAddress
        learnOn = predictionOn && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        autocorrectOn = predictionOn && prefs.autocorrect &&
            (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0

        spec = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Layouts.SYMBOLS
            else -> Layouts.LETTERS
        }
        view?.enterLabel = enterLabel(info)

        composing.clear(); touches.clear()
        lastAuto = null; autoSpace = false; cursorWord = null
        selStart = info.initialSelStart; selEnd = info.initialSelEnd
        view?.emojiVisible = false
        shift = ShiftState.OFF
        updateShift()
        detectCursorWord()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (composing.isNotEmpty()) {
            ic?.finishComposingText()
            composing.clear(); touches.clear()
        }
        save()
    }

    override fun onDestroy() {
        save()
        io.shutdown()
        super.onDestroy()
    }

    private fun save() {
        val m = lm ?: return
        val offsets = touchModel.exportOffsets()
        if (!m.dirty) { io.execute { store.write(store.offsets, offsets) }; return }
        val w = m.exportUserWords()
        val o = m.exportOrder()
        val e = m.exportEmoji()
        m.markSaved()
        io.execute {
            store.write(store.words, w)
            store.write(store.order, o)
            store.write(store.emoji, e)
            store.write(store.offsets, offsets)
        }
    }

    private fun enterLabel(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "⏎"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "ir"
            EditorInfo.IME_ACTION_SEARCH -> "buscar"
            EditorInfo.IME_ACTION_SEND -> "enviar"
            EditorInfo.IME_ACTION_NEXT -> "sig."
            EditorInfo.IME_ACTION_DONE -> "OK"
            else -> "⏎"
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The previous one or two words of the current sentence (for word-order learning
     * and prediction), excluding the word being typed / under the cursor.
     */
    private fun context(): List<String> {
        val before = ic?.getTextBeforeCursor(CONTEXT_WINDOW, 0)?.toString() ?: return emptyList()
        val complete = before.length < CONTEXT_WINDOW
        val cut = when {
            composing.isNotEmpty() -> composing.length
            cursorWord != null -> (selStart - cursorWord!!.start).coerceAtLeast(0)
            else -> 0
        }
        if (cut > before.length) return emptyList()
        return TextUtil.contextWords(before.substring(0, before.length - cut), complete)
    }

    private fun sentenceStart(): Boolean {
        val i = ic ?: return false
        val type = currentInputEditorInfo?.inputType ?: return false
        return i.getCursorCapsMode(type or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
    }

    private fun updateShift() {
        if (shift == ShiftState.LOCKED) return
        val type = currentInputEditorInfo?.inputType ?: 0
        val caps = prefs.autoCapitalize && composing.isEmpty() && type != 0 &&
            (ic?.getCursorCapsMode(type) ?: 0) != 0
        shift = if (caps) ShiftState.ON else ShiftState.OFF
        view?.shift = shift
    }

    /** Recomputes the suggestion bar and the letter probabilities. */
    private fun refresh() {
        val v = view ?: return
        v.spec = spec
        v.shift = shift
        val sug = suggester
        val m = lm
        if (sug == null || m == null || !predictionOn) {
            slots = listOf("", "", "")
            v.setSuggestions(slots, -1, -1)
            v.prior = null
            return
        }
        val prev = context()
        var bold = -1
        var quote = -1
        val cw = cursorWord
        when {
            composing.isNotEmpty() -> {
                val r = sug.suggest(composing.toString(), prev, autocorrectOn)
                slots = r.slots
                bold = 1
                if (r.autoCorrect != null) quote = 0
            }
            cw != null -> {
                val alts = sug.alternativesFor(cw.word, prev)
                slots = listOf(alts.getOrElse(1) { "" }, alts.getOrElse(0) { "" }, alts.getOrElse(2) { "" })
            }
            else -> {
                val p = sug.predict(prev, sentenceStart())
                slots = listOf(p.getOrElse(1) { "" }, p.getOrElse(0) { "" }, p.getOrElse(2) { "" })
            }
        }
        v.setSuggestions(slots, bold, quote)

        v.recentEmojis = m.recentEmojis(24)
        val prefix = when {
            composing.isNotEmpty() -> composing.toString()
            cw != null && selStart == cw.end -> cw.word
            else -> ""
        }
        v.prior = if (touchModel.enabled) m.nextLetterDistribution(prefix, letters, prev) else null
    }

    /** Looks at the text around the cursor and remembers the word it's touching. */
    private fun detectCursorWord() {
        cursorWord = null
        val i = ic
        if (i != null && predictionOn && composing.isEmpty() && selStart >= 0 && selStart == selEnd) {
            val before = i.getTextBeforeCursor(48, 0)?.toString() ?: ""
            val after = i.getTextAfterCursor(48, 0)?.toString() ?: ""
            var b = 0
            while (b < before.length && TextUtil.isWordChar(before[before.length - 1 - b])) b++
            var a = 0
            while (a < after.length && TextUtil.isWordChar(after[a])) a++
            val word = before.substring(before.length - b) + after.substring(0, a)
            if (word.isNotEmpty() && word.length < 40 && word.all { it.isLetter() }) {
                cursorWord = CursorWord(selStart - b, selStart + a, word)
            }
        }
        refresh()
    }

    private val detectRunnable = Runnable { detectCursorWord() }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (composing.isNotEmpty() &&
            (newSelStart != newSelEnd || candidatesStart == -1 || newSelEnd != candidatesEnd)
        ) {
            // The user moved the cursor away from the word being typed.
            ic?.finishComposingText()
            composing.clear(); touches.clear()
            lastAuto = null; autoSpace = false
        }
        if (composing.isEmpty()) {
            handler.removeCallbacks(detectRunnable)
            handler.postDelayed(detectRunnable, 30)
            if (oldSelStart != newSelStart) updateShift()
        }
    }

    /** Turns the word under the cursor back into an editable "composing" word. */
    private fun resumeComposing(cw: CursorWord): Boolean {
        val i = ic ?: return false
        if (!i.setComposingRegion(cw.start, cw.end)) return false
        composing.append(cw.word)
        repeat(cw.word.length) { touches.add(null) }
        cursorWord = null
        return true
    }

    private fun commitComposing(autocorrect: Boolean) {
        if (composing.isEmpty()) return
        val i = ic ?: return
        val typed = composing.toString()
        val prev = context()
        val m = lm
        val sug = suggester
        val final = if (autocorrect && autocorrectOn && sug != null) {
            sug.suggest(typed, prev).autoCorrect ?: typed
        } else typed
        i.commitText(final, 1)
        if (final != typed) {
            lastAuto = AutoFix(typed, final, prev)
        } else {
            lastAuto = null
            // The word was typed right: use the touches to learn where this person aims.
            if (m != null && m.isKnown(typed)) touches.forEach { t -> t?.let { touchModel.learn(it.box, it.x, it.y) } }
        }
        if (learnOn && m != null) m.learn(prev, final)
        composing.clear(); touches.clear()
    }

    // ------------------------------------------------------------------ key events

    override fun onLetter(text: String, box: KeyBox?, x: Float, y: Float) {
        val i = ic ?: return
        lastAuto = null
        autoSpace = false
        if (!predictionOn) {
            i.commitText(text, 1)
        } else {
            val cw = cursorWord
            if (composing.isEmpty() && cw != null && selStart != cw.end) {
                // Typing in the middle of an existing word: plain insert.
                i.commitText(text, 1)
            } else {
                if (composing.isEmpty() && cw != null) resumeComposing(cw)
                composing.append(text)
                touches.add(box?.let { Touch(it, x, y) })
                i.setComposingText(composing, 1)
            }
        }
        cursorWord = null
        if (shift == ShiftState.ON) shift = ShiftState.OFF
        refresh()
    }

    override fun onChar(text: String) {
        val i = ic ?: return
        val wasAutoSpace = autoSpace
        val isPunct = text in PUNCTUATION
        val hadWord = composing.isNotEmpty()
        commitComposing(autocorrect = isPunct || text in CLOSERS)
        val fix = if (hadWord) lastAuto else null
        if (wasAutoSpace && (isPunct || text in CLOSERS)) {
            // "palabra |" + "." -> "palabra. |"
            if (i.getTextBeforeCursor(1, 0) == " ") i.deleteSurroundingText(1, 0)
        }
        i.commitText(text, 1)
        if (wasAutoSpace && isPunct) {
            i.commitText(" ", 1)
            autoSpace = true
        } else autoSpace = false
        lastAuto = fix?.also { it.sep = text }
        cursorWord = null
        updateShift()
        refresh()
    }

    override fun onSpace() {
        val i = ic ?: return
        val now = SystemClock.uptimeMillis()
        when {
            composing.isNotEmpty() -> {
                commitComposing(autocorrect = true)
                i.commitText(" ", 1)
                lastAuto?.sep = " "
            }
            autoSpace -> {
                // A space was already added after a picked suggestion.
                autoSpace = false
                lastSpaceTime = 0
                return
            }
            prefs.doubleSpacePeriod && now - lastSpaceTime < 450 && endsWithWordAndSpace(i) -> {
                i.deleteSurroundingText(1, 0)
                i.commitText(". ", 1)
                lastAuto = null
                lastSpaceTime = 0
                updateShift(); refresh()
                return
            }
            else -> {
                i.commitText(" ", 1)
                lastAuto = null
            }
        }
        autoSpace = false
        lastSpaceTime = now
        if (spec !== Layouts.LETTERS) spec = Layouts.LETTERS
        cursorWord = null
        updateShift()
        refresh()
    }

    private fun endsWithWordAndSpace(i: InputConnection): Boolean {
        val b = i.getTextBeforeCursor(2, 0) ?: return false
        return b.length == 2 && b[1] == ' ' && b[0].isLetterOrDigit()
    }

    override fun onDelete(repeat: Int) {
        val i = ic ?: return
        val fix = lastAuto
        lastAuto = null
        autoSpace = false
        if (repeat == 0 && fix != null && fix.sep.isNotEmpty()) {
            // Backspace right after an autocorrection: undo it and keep what was typed.
            val expected = fix.corrected + fix.sep
            if (i.getTextBeforeCursor(expected.length, 0)?.toString() == expected) {
                i.beginBatchEdit()
                i.deleteSurroundingText(expected.length, 0)
                i.commitText(fix.typed + fix.sep, 1)
                i.endBatchEdit()
                if (learnOn) lm?.learn(fix.context, fix.typed, force = true)
                refresh()
                return
            }
        }
        if (composing.isEmpty() && repeat == 0 && predictionOn && selStart == selEnd) {
            cursorWord?.let { if (selStart == it.end) resumeComposing(it) }
        }
        if (composing.isNotEmpty()) {
            if (repeat >= WORD_DELETE_AFTER) {
                composing.clear()
                touches.clear()
            } else {
                composing.setLength(composing.length - 1)
                if (touches.isNotEmpty()) touches.removeAt(touches.size - 1)
            }
            if (composing.isEmpty()) i.commitText("", 1) else i.setComposingText(composing, 1)
        } else if (repeat >= WORD_DELETE_AFTER && selStart == selEnd) {
            val before = i.getTextBeforeCursor(64, 0) ?: ""
            var n = 0
            while (n < before.length && before[before.length - 1 - n] == ' ') n++
            while (n < before.length && TextUtil.isWordChar(before[before.length - 1 - n])) n++
            i.deleteSurroundingText(maxOf(n, 1), 0)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        cursorWord = null
        if (composing.isEmpty()) updateShift()
        refresh()
    }

    override fun onEnter() {
        val i = ic ?: return
        commitComposing(autocorrect = true)
        lastAuto = null
        autoSpace = false
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            i.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateShift()
        refresh()
    }

    override fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            now - lastShiftTap < 350 -> ShiftState.LOCKED
            shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
        lastShiftTap = now
        view?.shift = shift
    }

    override fun onMode(type: KeyType) {
        if (type == KeyType.EMOJI) {
            view?.recentEmojis = lm?.recentEmojis(24) ?: emptyList()
            view?.emojiVisible = true
            return
        }
        view?.emojiVisible = false
        spec = when (type) {
            KeyType.TO_SYMBOLS -> Layouts.SYMBOLS
            KeyType.TO_SYMBOLS2 -> Layouts.SYMBOLS2
            else -> Layouts.LETTERS
        }
        refresh()
    }

    override fun onEmoji(emoji: String) {
        val i = ic ?: return
        commitComposing(autocorrect = true)
        insertEmoji(i, emoji)
    }

    /** Inserts [emoji] and remembers which words it came after. */
    private fun insertEmoji(i: InputConnection, emoji: String) {
        val ctx = context()
        i.commitText(emoji, 1)
        if (learnOn) lm?.learnEmoji(ctx, emoji)
        lastAuto = null
        autoSpace = false
        cursorWord = null
        updateShift()
        refresh()
    }

    override fun onSuggestion(index: Int) {
        val i = ic ?: return
        val word = slots.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        val m = lm
        val cw = cursorWord
        if (TextUtil.isEmoji(word)) {
            // Emoji suggestion: keep the word, then add the emoji after it.
            if (composing.isNotEmpty()) {
                commitComposing(autocorrect = true)
                i.commitText(" ", 1)
            }
            insertEmoji(i, word)
            return
        }
        when {
            composing.isNotEmpty() -> {
                val typed = composing.toString()
                val prev = context()
                i.commitText(word, 1)
                // Picking the literal word means "this is a real word": learn it now.
                if (learnOn) m?.learn(prev, word, force = word == typed)
                composing.clear(); touches.clear()
                i.commitText(" ", 1)
                autoSpace = true
            }
            cw != null -> {
                // Replace the word the cursor is on (correction of an existing word).
                val prev = context()
                i.beginBatchEdit()
                i.finishComposingText()
                i.setComposingRegion(cw.start, cw.end)
                i.commitText(word, 1)
                i.endBatchEdit()
                if (learnOn) m?.learn(prev, word)
                autoSpace = false
            }
            else -> {
                val prev = context()
                i.commitText("$word ", 1)
                if (learnOn) m?.learn(prev, word)
                autoSpace = true
            }
        }
        lastAuto = null
        cursorWord = null
        updateShift()
        refresh()
    }

    override fun onCursorMove(steps: Int) {
        val i = ic ?: return
        if (composing.isNotEmpty()) {
            i.finishComposingText()
            composing.clear(); touches.clear()
        }
        lastAuto = null
        autoSpace = false
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    companion object {
        private const val WORD_DELETE_AFTER = 14
        private const val CONTEXT_WINDOW = 160
        private val PUNCTUATION = setOf(".", ",", ";", ":", "!", "?", "…")
        private val CLOSERS = setOf(")", "]", "}", "\"", "'", "»")
    }
}
