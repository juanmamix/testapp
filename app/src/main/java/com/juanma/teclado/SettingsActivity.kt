package com.juanma.teclado

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Setup + options, laid out like iOS Settings: inset grouped sections, one clear
 * primary action at the top, advanced things further down. Built in code to keep the
 * project small.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var stepButton: TextView
    private val dp by lazy { resources.displayMetrics.density }

    private val dark by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val cBg get() = if (dark) Color.BLACK else 0xFFF2F2F7.toInt()
    private val cCell get() = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
    private val cText get() = if (dark) Color.WHITE else Color.BLACK
    private val cSub get() = if (dark) 0xFF8E8E93.toInt() else 0xFF6C6C70.toInt()
    private val cSep get() = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    private val cAccent = 0xFF0A84FF.toInt()
    private val bold: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 700, false) else Typeface.DEFAULT_BOLD
    private val semibold: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        actionBar?.hide()
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(28), px(16), px(32))
        }

        // Large title: bold, tight leading, negative tracking (big text reads too loose otherwise).
        col.addView(TextView(this).apply {
            text = "Teclado"
            textSize = 34f
            typeface = bold
            letterSpacing = -0.02f
            setTextColor(cText)
            setPadding(px(4), 0, 0, px(6))
        })
        col.addView(footnote(
            "Las zonas invisibles de las letras que probablemente vienen después se agrandan " +
                "mientras escribes. Las teclas que ves no cambian.",
        ))

        // --- Setup: one primary action that changes with the state.
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(cSub)
            setPadding(px(16), px(12), px(16), 0)
        }
        stepButton = TextView(this).apply {
            textSize = 17f
            typeface = semibold
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(px(16), px(14), px(16), px(14))
            background = rounded(cAccent, 12f)
            pressable(this)
        }
        col.addView(group(listOf(status, wrap(stepButton))))

        col.addView(header("Escritura"))
        col.addView(group(listOf(
            switchRow("Autocorrección", "Al pulsar espacio o un signo. Borra justo después para deshacerla.",
                prefs.autocorrect) { prefs.autocorrect = it },
            switchRow("Zonas táctiles adaptativas", "Las letras probables captan los toques dudosos.",
                prefs.adaptiveKeys) { prefs.adaptiveKeys = it },
            switchRow("Mayúscula automática", null, prefs.autoCapitalize) { prefs.autoCapitalize = it },
            switchRow("Doble espacio pone punto", null, prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it },
        )))

        col.addView(header("Tacto"))
        val heightLabel = TextView(this)
        col.addView(group(listOf(
            switchRow("Vibración al pulsar", null, prefs.haptics) { prefs.haptics = it },
            switchRow("Sonido al pulsar", null, prefs.sound) { prefs.sound = it },
            sliderRow(heightLabel),
        )))

        col.addView(header("Probar"))
        col.addView(group(listOf(EditText(this).apply {
            hint = "Escribe aquí para probarlo…"
            minLines = 3
            gravity = Gravity.TOP
            background = null
            textSize = 17f
            setTextColor(cText)
            setHintTextColor(cSub)
            setPadding(px(16), px(12), px(16), px(12))
        })))

        col.addView(header("Avanzado"))
        col.addView(group(listOf(
            switchRow("Ver las zonas táctiles", "Dibuja en azul los bordes reales entre letras.",
                prefs.showZones) { prefs.showZones = it },
        )))

        col.addView(header("Lo que ha aprendido"))
        val resetRow = TextView(this).apply {
            text = "Olvidar palabras, orden y emojis aprendidos"
            textSize = 17f
            setTextColor(0xFFFF3B30.toInt())
            setPadding(px(16), px(13), px(16), px(13))
            pressable(this)
        }
        col.addView(group(listOf(resetRow)))
        col.addView(footnote(
            "Aprende tus palabras, el orden en que las usas, los emojis que pones después " +
                "de cada palabra y hacia dónde sueles desviarte en cada tecla. Todo se queda " +
                "en este móvil; el teclado no usa internet ni aprende en campos de contraseña.",
        ))
        resetRow.setOnClickListener {
            if (resetRow.tag == "confirm") {
                UserStore(this).clear()
                prefs.resetToken = System.currentTimeMillis()
                resetRow.text = "Olvidado ✓"
                resetRow.setTextColor(cSub)
                resetRow.isEnabled = false
            } else {
                // Second tap confirms; nothing destructive happens on a single accidental tap.
                resetRow.tag = "confirm"
                resetRow.text = "Toca otra vez para confirmar"
            }
        }

        col.addView(footnote("Diccionario: FrequencyWords (Hermit Dave), OpenSubtitles, CC BY-SA 4.0."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus() // the IME picker is a dialog; re-check when it closes
    }

    private fun refreshStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        when {
            current -> {
                status.text = "✓ Es tu teclado ahora mismo."
                stepButton.text = "Cambiar de teclado"
                stepButton.setOnClickListener { imm.showInputMethodPicker() }
            }
            enabled -> {
                status.text = "Paso 2 de 2: elígelo como teclado."
                stepButton.text = "Elegir Teclado Adaptativo"
                stepButton.setOnClickListener { imm.showInputMethodPicker() }
            }
            else -> {
                status.text = "Paso 1 de 2: actívalo en los ajustes de Android."
                stepButton.text = "Abrir ajustes de teclados"
                stepButton.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun px(v: Int) = (v * dp).toInt()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
    }

    /** Pressed state shows on touch-down and eases back (scale + dim), never waits for the click. */
    private fun pressable(v: View) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.scaleX = 0.97f; view.scaleY = 0.97f; view.alpha = 0.75f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
            }
            false
        }
    }

    private fun header(s: String) = TextView(this).apply {
        text = s.uppercase()
        textSize = 13f
        letterSpacing = 0.03f // small caps-like label: slightly open tracking
        setTextColor(cSub)
        setPadding(px(16), px(26), px(16), px(7))
    }

    private fun footnote(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(cSub)
        setPadding(px(16), px(7), px(16), px(4))
    }

    private fun wrap(v: View) = LinearLayout(this).apply {
        setPadding(px(16), px(12), px(16), px(16))
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** An inset grouped section: rounded card, hairline separators indented like iOS. */
    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCell, 12f)
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(cSep) },
                LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, (0.5f * dp).toInt())).apply { marginStart = px(16) })
            addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    @Suppress("DEPRECATION")
    private fun switchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(11), px(12), px(11))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(cText) })
        if (detail != null) texts.addView(TextView(this).apply {
            text = detail; textSize = 13f; setTextColor(cSub); setPadding(0, px(2), px(8), 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() } // the whole row is the target
        return row
    }

    private fun sliderRow(label: TextView): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(11), px(16), px(8))
        }
        label.textSize = 17f
        label.setTextColor(cText)
        label.text = "Altura de las teclas · ${prefs.keyHeightDp}"
        box.addView(label)
        box.addView(SeekBar(this).apply {
            max = 30
            progress = prefs.keyHeightDp - 42
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    prefs.keyHeightDp = 42 + v
                    label.text = "Altura de las teclas · ${42 + v}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }
}
