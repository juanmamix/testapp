package com.juanma.teclado

import android.content.Context
import java.io.File

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("teclado", Context.MODE_PRIVATE)

    var autocorrect by flag("autocorrect", true)
    var adaptiveKeys by flag("adaptive_keys", true)
    var showZones by flag("show_zones", false)
    var haptics by flag("haptics", true)
    var sound by flag("sound", false)
    var doubleSpacePeriod by flag("double_space", true)
    var autoCapitalize by flag("auto_cap", true)

    var keyHeightDp: Int
        get() = sp.getInt("key_height", 54)
        set(v) = sp.edit().putInt("key_height", v).apply()

    /** Bumped by the settings screen when the user wipes learned data. */
    var resetToken: Long
        get() = sp.getLong("reset_token", 0)
        set(v) = sp.edit().putLong("reset_token", v).apply()

    private fun flag(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }
}

/** Where learned words, word pairs and touch offsets are stored (app-private). */
class UserStore(context: Context) {
    private val dir = context.filesDir
    val words = File(dir, "user_words.tsv")
    val order = File(dir, "user_order.tsv")
    val emoji = File(dir, "user_emoji.tsv")
    val offsets = File(dir, "touch_offsets.tsv")

    fun read(f: File): String = if (f.exists()) f.readText() else ""

    fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    fun clear() {
        words.delete(); order.delete(); emoji.delete(); offsets.delete()
    }
}
