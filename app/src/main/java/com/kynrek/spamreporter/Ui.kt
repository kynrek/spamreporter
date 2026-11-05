package com.kynrek.spamreporter

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors

/** Small view-building kit so every screen shares the same look. */
object Ui {
    fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    fun Context.attr(id: Int) = MaterialColors.getColor(this, id, 0)

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = top; bottomMargin = bottom }

    /** Toolbar + scrolling column; returns the column to add content to. */
    fun Activity.screen(title: String, subtitle: String? = null, back: Boolean = true, scroll: Boolean = true,
                        onSettings: (() -> Unit)? = null, content: LinearLayout.() -> Unit): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(attr(android.R.attr.colorBackground)) }
        val bar = MaterialToolbar(this).apply {
            this.title = title; this.subtitle = subtitle
            setBackgroundColor(attr(com.google.android.material.R.attr.colorPrimary))
            setTitleTextColor(attr(com.google.android.material.R.attr.colorOnPrimary))
            setSubtitleTextColor(attr(com.google.android.material.R.attr.colorOnPrimary) and 0xCCFFFFFF.toInt())
            if (back) { setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                navigationIcon?.setTint(attr(com.google.android.material.R.attr.colorOnPrimary)); setNavigationOnClickListener { finish() } }
            onSettings?.let { open -> menu.add("Settings").apply {
                setIcon(R.drawable.ic_settings); icon?.setTint(attr(com.google.android.material.R.attr.colorOnPrimary))
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS); setOnMenuItemClickListener { open(); true } } }
        }
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, ins -> v.updatePadding(top = ins.getInsets(WindowInsetsCompat.Type.statusBars()).top); ins }
        root.addView(bar)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)); content() }
        // re-rendering the same screen (after a toggle, or coming back to it) keeps the scroll position instead of jumping to the top
        val keepY = (window?.decorView?.findViewWithTag<View>("screen_scroll") as? androidx.core.widget.NestedScrollView)?.scrollY ?: 0
        if (scroll) root.addView(androidx.core.widget.NestedScrollView(this).apply { tag = "screen_scroll"; addView(col)
            if (keepY > 0) post { scrollTo(0, keepY) } }, LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(col, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        return col
    }

    fun Context.text(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int? = null, alpha: Float = 1f) =
        TextView(this).apply { text = s; textSize = size; if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color ?: attr(com.google.android.material.R.attr.colorOnSurface)); this.alpha = alpha; setLineSpacing(0f, 1.15f) }

    fun Context.section(title: String) = text(title.uppercase(), 12f, true, attr(com.google.android.material.R.attr.colorPrimary)).apply {
        letterSpacing = 0.08f; setPadding(dp(4), dp(20), 0, dp(8)) }

    fun Context.card(pad: Int = 16, body: LinearLayout.() -> Unit) = MaterialCardView(this).apply {
        layoutParams = lp(bottom = dp(10)); useCompatPadding = false
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); body() })
    }

    /** Numbered step row: badge, title, subtitle, trailing status chip. */
    fun Context.step(n: Int, title: String, sub: String, status: Pair<String, Int>?, onClick: () -> Unit) = step(null, n, title, sub, status, onClick)

    /** Step row in a [CardStyle.Look] (background, text color and size); null = theme default. */
    fun Context.step(look: CardStyle.Look?, n: Int, title: String, sub: String, status: Pair<String, Int>?, onClick: () -> Unit) = card(14) {
        val k = look?.scale ?: 1f; val fg = look?.fgOrNull
        orientation = LinearLayout.HORIZONTAL
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(context).apply {
            text = n.toString(); gravity = Gravity.CENTER; textSize = 15f * k; typeface = Typeface.DEFAULT_BOLD
            // on a colored card the badge flips to text color with the card color inside, so it stays visible
            val dark = look != null && look.bg != 0
            setTextColor(if (dark) look!!.bg else attr(com.google.android.material.R.attr.colorOnPrimary))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                setColor(if (dark) (fg ?: 0xFFFFFFFF.toInt()) else attr(com.google.android.material.R.attr.colorPrimary)) }
        }
        row.addView(badge, LinearLayout.LayoutParams(dp((34 * k).toInt()), dp((34 * k).toInt())).apply { rightMargin = dp(14) })
        row.addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(text(title, 16f * k, true, fg)); addView(text(sub, 13f * k, color = fg, alpha = 0.75f)) }, LinearLayout.LayoutParams(0, -2, 1f))
        status?.let { (label, color) -> row.addView(chip(label, color), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }) }
        row.addView(text("\u203A", 26f * k, color = fg, alpha = 0.5f), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }) // chevron: "tap me"
        addView(row, lp())
    }.apply { if (look != null && look.bg != 0) setCardBackgroundColor(look.bg)
 isClickable = true; isFocusable = true; setOnClickListener { onClick() }
        foreground = android.util.TypedValue().let { tv -> context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            ContextCompat.getDrawable(context, tv.resourceId) } }

    fun Context.chip(label: String, color: Int) = Chip(this).apply {
        text = label; isClickable = false; isCheckable = false; textSize = 12f; chipMinHeight = dp(26).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(color and 0x00FFFFFF or 0x26000000); setTextColor(color)
        chipStrokeWidth = 0f
    }

    /** Big-number stat tile. */
    fun Context.stat(value: String, label: String, accent: Int, look: CardStyle.Look? = null) = MaterialCardView(this).apply {
        val k = look?.scale ?: 1f; val colored = look != null && look.bg != 0
        if (colored) setCardBackgroundColor(look!!.bg)
        // navy numbers would vanish on a navy card: use the text color; lift red/green so they read on dark cards
        val a = when { !colored -> accent; accent == NAVY -> look!!.fgOrNull ?: 0xFFFFFFFF.toInt(); accent == GREEN -> 0xFF66BB6A.toInt(); accent == RED -> 0xFFFF6B7A.toInt(); else -> accent }
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(value, 26f * k, true, a)); addView(text(label, 12f * k, color = look?.fgOrNull, alpha = 0.75f)) })
    }

    fun Context.statsRow(vararg tiles: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, t -> addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(10) }) }
    }

    /** Hero header with the app icon. */
    fun Context.hero(title: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), 0, dp(8))
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(ContextCompat.getColor(context, R.color.brand_cream)) }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_launcher_foreground) }, FrameLayout.LayoutParams(dp(72), dp(72)))
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(14) })
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(text(title, 22f, true)); addView(text(sub, 13f, alpha = 0.7f)) })
    }

    val GREEN = 0xFF2E7D32.toInt(); val AMBER = 0xFFB26A00.toInt(); val RED = 0xFFD7263D.toInt(); val NAVY = 0xFF1F2A44.toInt(); val GREY = 0xFF6B6F76.toInt()
    /** Full-width child; keeps the view's own bottom margin (cards are built with one) so stacked cards don't touch. */
    fun ViewGroup.add(v: View, top: Int = 0) = addView(v, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top; bottomMargin = (v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0 })
}
