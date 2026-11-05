package com.kynrek.spamreporter

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.chip
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.section
import com.kynrek.spamreporter.Ui.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pick which inbox senders are spam. This is how spam gets identified when the Spam & blocked folder scan is off
 * (and catches spam Google Messages didn't flag when it's on). Nothing is pre-ticked: the app only suggests.
 *
 * "To review" (default) only lists senders with texts you haven't been shown here yet and haven't decided on.
 * Everything else is under "History"; numbers marked not spam (known numbers and contacts) are hidden there too
 * unless "Show not spam" is on. Swipe a row right to mark it not spam: it becomes a known number, so its future
 * texts are never suggested or reported (edit the list in Settings).
 */
class MarkSpamActivity : AppCompatActivity() {
    private val labels = mapOf(Category.COMMERCIAL to "Looks commercial", Category.POLITICAL to "Looks political", Category.SURVEY to "Looks like a survey")
    private var history = false
    private var showNotSpam = false
    /** Senders displayed under "To review" during this visit (digits -> newest text shown); saved as seen on leaving. */
    private val shown = HashMap<String, Long>()

    private var marks: MutableMap<String, Long> = HashMap()
    private var remember = true
    private data class Group(val d: String, val ms: List<Msg>, val cat: Category) { val last get() = ms.last().ts }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        b?.let { history = it.getBoolean("history"); showNotSpam = it.getBoolean("showNotSpam") }
        val asked = getSharedPreferences("settings", MODE_PRIVATE)
        if (Contacts.enabled(this) && !Contacts.granted(this) && !asked.getBoolean("contacts_asked", false)) {
            asked.edit().putBoolean("contacts_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
        }
        render()
    }
    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putBoolean("history", history); o.putBoolean("showNotSpam", showNotSpam) }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); render() }
    override fun onStop() { super.onStop(); Store(this).markSeen(shown) }

    /** Review rows not yet scrolled into view; a row counts as shown once at least half of it has been on screen. */
    private val pending = ArrayList<Pair<View, Group>>()
    private val visibleCheck = android.view.ViewTreeObserver.OnScrollChangedListener { checkVisible() }
    private fun watch(v: View, g: Group) = pending.add(v to g)
    private fun checkVisible() {
        val r = android.graphics.Rect()
        pending.removeAll { (v, g) -> (v.isShown && v.getGlobalVisibleRect(r) && r.height() * 2 >= v.height).also { if (it) shown[g.d] = g.last } }
    }

    private fun render() {
        pending.clear()
        val store = Store(this)
        val marked = store.spamSenders().map(Phone::digits).toMutableSet()
        val known = store.knownNumbers().keys.toMutableSet()
        val contacts = Contacts.numbers(this)
        val responded = Replies.responded(this)
        val overrides = store.spamOverrides()
        // replied-to numbers count as not spam (setting, default on) unless you explicitly marked them spam
        val repliedOk = if (Replies.enabled(this)) responded - overrides else emptySet()
        val seen = store.seen()
        marks = store.spamMarks(); remember = store.rememberSpam()
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        val groups = store.messages().filter { Phone.isTenDigit(it.sender) && it.body.isNotBlank() }
            .groupBy { Phone.digits(it.sender) }
            .map { (d, ms) -> Group(d, ms.sortedBy { it.ts }, Classifier.classify(ms.map { it.body })) }
            .sortedByDescending { it.last }
        val notSpam = { g: Group -> g.d in known || g.d in contacts || g.d in repliedOk }
        // to review: anyone not "not spam" with a text newer than what was shown here before. A sender already marked
        // spam only comes back when it texts again after being marked (pre-ticked if "Remember spam senders" is on).
        val review = groups.filter { !notSpam(it) && (it.d in shown ||
            it.last > maxOf(seen[it.d] ?: -1, if (it.d in marked) marks[it.d] ?: -1 else -1)) }
        val past = groups - review.toSet()
        // History only goes back to the cutoff in Settings (default 3 months), by each sender's newest text
        val cut = HistoryPrefs.cutoffMillis(this)
        val recent = past.filter { it.last >= cut }
        val tooOld = past.size - recent.size
        val pastVisible = if (showNotSpam) recent else recent.filterNot(notSpam)
        val hiddenNotSpam = recent.count(notSpam)

        screen("Choose spam senders", "${marked.size - marked.count { it in known || it in contacts }} marked as spam") {
            addView(MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true; isSelectionRequired = true
                val a = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "To review (${review.size})" }
                val h = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "History (${recent.size - hiddenNotSpam})" }
                addView(a, LinearLayout.LayoutParams(0, -2, 1f)); addView(h, LinearLayout.LayoutParams(0, -2, 1f))
                check(if (history) h.id else a.id)
                addOnButtonCheckedListener { _, id, on -> if (on) { history = id == h.id; render() } }
            }, lp(bottom = dp(8)))

            if (Contacts.enabled(context) && !Contacts.granted(context)) add(card {
                addView(text("Allow contacts so people in your contacts are always treated as not spam. They're only checked on this phone.", 13f, alpha = 0.8f))
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Allow contacts"; setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2) } })
            })

            if (!history) {
                add(card { addView(text("Tick or swipe left on senders whose texts you didn't ask for. Swipe right on anyone you know: they become a " +
                    "known number and their texts won't be suggested again. Senders you've been shown here move to History.", 13f, alpha = 0.8f)) })
                add(swipeHelp())
                if (review.isEmpty()) add(text("Nothing new to review. Earlier senders are under History.", 14f, alpha = 0.7f), dp(12))
                val (likely, rest) = review.partition { it.cat != Category.IGNORE || it.ms.any { m -> m.spamFlagged } }
                if (likely.isNotEmpty()) add(section("Suggested (${likely.size})"))
                likely.forEach { add(row(store, it, marked, known, contacts, responded, fmt).also { v -> watch(v, it) }) }
                val restShown = rest.take(150)
                if (restShown.isNotEmpty()) add(section("Everyone else (${rest.size})"))
                restShown.forEach { add(row(store, it, marked, known, contacts, responded, fmt).also { v -> watch(v, it) }) }
            } else {
                add(card {
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(text("Show not spam ($hiddenNotSpam)", 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(MaterialSwitch(context).apply { isChecked = showNotSpam; setOnCheckedChangeListener { _, on -> showNotSpam = on; render() } })
                    })
                    addView(text("Known numbers and contacts. Edit known numbers in Settings.", 12f, alpha = 0.6f))
                })
                add(swipeHelp())
                if (pastVisible.isEmpty()) add(text("No history yet.", 14f, alpha = 0.7f), dp(12))
                val grouping = HistoryPrefs.grouping(context)
                var lastLabel: String? = null
                val list = pastVisible.take(300)
                val counts = list.groupingBy { HistoryPrefs.groupLabel(grouping, it.last) }.eachCount()
                for (g in list) {
                    val label = HistoryPrefs.groupLabel(grouping, g.last)
                    if (label != null && label != lastLabel) { add(section("$label (${counts[label]})")); lastLabel = label }
                    add(row(store, g, marked, known, contacts, responded, fmt))
                }
                if (tooOld > 0) add(text("$tooOld sender${if (tooOld == 1) "" else "s"} with no texts in the last ${HistoryPrefs.cutoffLabel(context)} " +
                    "${if (tooOld == 1) "is" else "are"} hidden. Change this in Settings → History.", 12f, alpha = 0.6f), dp(12))
            }
            add(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Known numbers (${known.size})"; setOnClickListener { startActivity(Intent(context, KnownNumbersActivity::class.java)) } }, dp(8))
        }.apply { viewTreeObserver.removeOnScrollChangedListener(visibleCheck); viewTreeObserver.addOnScrollChangedListener(visibleCheck)
            post { checkVisible() } }
    }

    override fun onRestart() { super.onRestart(); render() }   // back from Known numbers / Settings

    private fun row(store: Store, g: Group, marked: MutableSet<String>, known: MutableSet<String>, contacts: Set<String>,
                    responded: Set<String>, fmt: SimpleDateFormat): View {
        val isContact = g.d in contacts
        val replied = g.d in responded; val repliedRule = replied && Replies.enabled(this)
        val overrides = store.spamOverrides()
        val look = CardStyle.look(this, CardStyle.Screen.SENDERS)
        val bg = look.bg; val fg = look.fgOrNull; val k = look.scale
        var silent = false
        lateinit var box: MaterialCheckBox
        /** Is this sender's latest text spam right now? Marked spam, not overridden by a not-spam rule, and either
         *  "Remember spam senders" is on or the mark covers its newest text. */
        fun spamNow() = g.d in marked && g.d !in known && !isContact && (!repliedRule || g.d in overrides) &&
            (remember || (marks[g.d]?.let { g.last <= it } ?: true))
        lateinit var tag: LinearLayout
        fun status() { tag.removeAllViews()
            when {
                isContact -> tag.addView(chip("Contact", Ui.GREEN))
                g.d in known -> tag.addView(chip("Not spam", Ui.GREEN))
                spamNow() -> tag.addView(chip("Spam", Ui.RED))
                g.d in marked && !repliedRule -> tag.addView(chip("Was spam", Ui.AMBER))
                repliedRule && g.d !in overrides -> tag.addView(chip("Not spam", Ui.GREEN))
                g.ms.any { it.spamFlagged } -> tag.addView(chip("Flagged in Messages", fg ?: Ui.GREY))
                else -> labels[g.cat]?.let { tag.addView(chip(it, Ui.AMBER)) }
            }
            silent = true; box.isChecked = spamNow(); silent = false
        }
        val card = card(12) {
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                box = MaterialCheckBox(context).apply {
                    isEnabled = !isContact; fg?.let { buttonTintList = android.content.res.ColorStateList.valueOf(it) }
                    setOnCheckedChangeListener { _, on -> if (silent) return@setOnCheckedChangeListener
                        if (on) { store.addSpamSenders(listOf(Phone.dashed(g.d))); store.removeKnown(g.d); marked += g.d; known -= g.d
                            store.setSpamMark(g.d, g.last); marks[g.d] = g.last
                            if (replied) { store.setSpamOverride(g.d, true); overrides += g.d } }
                        else { store.removeSpamSenders(g.d); marked -= g.d; store.setSpamOverride(g.d, false); overrides -= g.d
                            store.setSpamMark(g.d, null); marks.remove(g.d) }
                        status() }
                }
                addView(box)
                addView(text(Phone.dashed(g.d), 16f * k, true, fg), LinearLayout.LayoutParams(0, -2, 1f))
                tag = LinearLayout(context); addView(tag)
            })
            if (replied) addView(chip("You responded", Ui.GREEN), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            addView(text("${g.ms.size} text${if (g.ms.size == 1) "" else "s"} · last ${fmt.format(Date(g.last))}\n${g.ms.last().body.take(160)}",
                13f * k, color = fg, alpha = 0.8f), lp(top = dp(2)))
        }.apply { if (bg != 0) setCardBackgroundColor(bg) }
        status()
        if (isContact) return card

        /** Apply a swipe: [spam] true = mark spam, false = not spam (known number). Undo restores the previous state. */
        fun decide(sr: SwipeRow, spam: Boolean) {
            val wasSpam = g.d in marked; val wasKnown = g.d in known; val wasOverride = g.d in overrides; val wasMark = marks[g.d]
            if (spam) { store.addSpamSenders(listOf(Phone.dashed(g.d))); store.removeKnown(g.d); marked += g.d; known -= g.d
                store.setSpamMark(g.d, g.last); marks[g.d] = g.last
                if (replied) { store.setSpamOverride(g.d, true); overrides += g.d } }
            else { store.addKnown(g.d); marked -= g.d; known += g.d; store.setSpamOverride(g.d, false); overrides -= g.d
                store.setSpamMark(g.d, null); marks.remove(g.d) }
            shown[g.d] = g.last
            // To review: the row is decided, so it leaves the list. History: it stays (updated) unless not-spam is hidden.
            if (!history || (!spam && !showNotSpam)) sr.visibility = View.GONE else sr.reset()
            status()
            Snackbar.make(findViewById(android.R.id.content), "${Phone.dashed(g.d)} marked ${if (spam) "spam" else "not spam"}", 6000)
                .setAction("Undo") {
                    if (wasKnown) { store.addKnown(g.d); known += g.d } else { store.removeKnown(g.d); known -= g.d }
                    if (wasSpam) { store.addSpamSenders(listOf(Phone.dashed(g.d))); marked += g.d } else { store.removeSpamSenders(g.d); marked -= g.d }
                    store.setSpamOverride(g.d, wasOverride); if (wasOverride) overrides += g.d else overrides -= g.d
                    store.setSpamMark(g.d, wasMark); if (wasMark == null) marks.remove(g.d) else marks[g.d] = wasMark
                    status(); sr.reset() }
                .show()
        }
        val canNotSpam = g.d !in known; val canSpam = !spamNow()
        return SwipeRow(this, card, if (canNotSpam) "Not spam" else null, if (canSpam) "Spam" else null,
            if (canNotSpam) { sr -> decide(sr, false) } else null, if (canSpam) { sr -> decide(sr, true) } else null)
    }

    /** Red/green "how to swipe" banner shown above the list. */
    private fun swipeHelp() = card(12) {
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text("\u2190  Swipe left\n     spam", 14f, true, Ui.RED), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Swipe right  \u2192\nnot spam     ", 14f, true, Ui.GREEN).apply { gravity = android.view.Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
}
