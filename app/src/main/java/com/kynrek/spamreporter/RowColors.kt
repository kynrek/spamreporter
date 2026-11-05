package com.kynrek.spamreporter

import android.content.Context

/**
 * Card look (background, text color, text size) for the home screen and for "Choose spam senders",
 * set in Settings → Card style. A color of 0 means the app theme's default.
 */
object CardStyle {
    enum class Screen(val key: String, val label: String) { HOME("home", "Home screen"), SENDERS("row", "Choose spam senders") }
    data class Look(val bg: Int, val fg: Int, val scale: Float) { val fgOrNull get() = fg.takeIf { it != 0 } }

    val backgrounds = linkedMapOf("Navy" to 0xFF1F2A44.toInt(), "Black" to 0xFF000000.toInt(), "Charcoal" to 0xFF2B2D31.toInt(),
        "Forest" to 0xFF1E3A2F.toInt(), "Burgundy" to 0xFF4A1C2A.toInt(), "Light" to 0)
    val texts = linkedMapOf("White" to 0xFFFFFFFF.toInt(), "Light gray" to 0xFFD0D4DC.toInt(), "Cream" to 0xFFF3E9D2.toInt(),
        "Yellow" to 0xFFFFD54F.toInt(), "Black" to 0xFF000000.toInt(), "Theme" to 0)
    val sizes = linkedMapOf("Small" to 0.9f, "Normal" to 1f, "Large" to 1.15f, "Extra large" to 1.3f)

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun look(c: Context, s: Screen) = prefs(c).let { p ->
        Look(p.getInt("${s.key}_bg", backgrounds.getValue("Navy")), p.getInt("${s.key}_fg", texts.getValue("White")), p.getFloat("${s.key}_scale", 1f)) }
    fun setBackground(c: Context, s: Screen, v: Int) = prefs(c).edit().putInt("${s.key}_bg", v).apply()
    fun setText(c: Context, s: Screen, v: Int) = prefs(c).edit().putInt("${s.key}_fg", v).apply()
    fun setScale(c: Context, s: Screen, v: Float) = prefs(c).edit().putFloat("${s.key}_scale", v).apply()
}
