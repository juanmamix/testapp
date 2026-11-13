package com.juanma.teclado

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup + options screen (built in code to keep the project tiny). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Teclado Adaptativo"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
        }

        col.addView(text("Teclado Adaptativo", 24f, bold = true))
        col.addView(text(
            "Mientras escribes, las zonas táctiles invisibles de las letras más probables " +
                "crecen y las de las improbables encogen, como en el teclado del iPhone. " +
                "Las teclas que ves no cambian.", 15f,
        ))

        status = text("", 15f, bold = true)
        col.addView(status)
        col.addView(button("1. Activar el teclado en Ajustes") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(button("2. Elegir Teclado Adaptativo") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        col.addView(header("Escritura"))
        col.addView(switch("Autocorrección al pulsar espacio", prefs.autocorrect) { prefs.autocorrect = it })
        col.addView(switch("Zonas táctiles adaptativas", prefs.adaptiveKeys) { prefs.adaptiveKeys = it })
        col.addView(switch("Mostrar zonas táctiles (líneas azules)", prefs.showZones) { prefs.showZones = it })
        col.addView(switch("Mayúscula automática", prefs.autoCapitalize) { prefs.autoCapitalize = it })
        col.addView(switch("Doble espacio = punto", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it })

        col.addView(header("Respuesta"))
        col.addView(switch("Vibración", prefs.haptics) { prefs.haptics = it })
        col.addView(switch("Sonido", prefs.sound) { prefs.sound = it })

        val hLabel = text("Altura de tecla: ${prefs.keyHeightDp} dp", 15f)
        col.addView(hLabel)
        col.addView(SeekBar(this).apply {
            max = 30
            progress = prefs.keyHeightDp - 42
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    prefs.keyHeightDp = 42 + v
                    hLabel.text = "Altura de tecla: ${42 + v} dp"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(header("Prueba aquí"))
        col.addView(EditText(this).apply {
            hint = "Escribe algo…"
            minLines = 3
        })

        col.addView(header("Datos"))
        col.addView(text(
            "El teclado aprende tus palabras y dónde sueles tocar cada tecla. " +
                "Todo se guarda solo en este móvil; no usa internet.", 14f,
        ))
        col.addView(button("Borrar lo aprendido") {
            UserStore(this).clear()
            prefs.resetToken = System.currentTimeMillis()
            Toast.makeText(this, "Borrado", Toast.LENGTH_SHORT).show()
        })
        col.addView(text(
            "Diccionario: FrequencyWords (Hermit Dave), basado en OpenSubtitles, CC BY-SA 4.0.", 12f,
        ))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        status.text = when {
            current -> "✓ Es tu teclado actual"
            enabled -> "Activado. Ahora elígelo (paso 2)."
            else -> "Aún no está activado (paso 1)."
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) onResume()
    }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun header(s: String) = text(s, 18f, bold = true).apply { setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt()) }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    @Suppress("DEPRECATION")
    private fun switch(s: String, checked: Boolean, onChange: (Boolean) -> Unit): View = Switch(this).apply {
        text = s
        textSize = 16f
        isChecked = checked
        setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
