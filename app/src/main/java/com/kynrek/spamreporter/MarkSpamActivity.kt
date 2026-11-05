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
        val known = store.knownNumbers().keys
        val contacts = Contacts.numbers(this)
        val seen = store.seen()
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        val groups = store.messages().filter { Phone.isTenDigit(it.sender) && it.body.isNotBlank() }
            .groupBy { Phone.digits(it.sender) }
            .map { (d, ms) -> Group(d, ms.sortedBy { it.ts }, Classifier.classify(ms.map { it.body })) }
            .sortedByDescending { it.last }
        val notSpam = { g: Group -> g.d in known || g.d in contacts }
        // to review: undecided (not spam-ticked, not "not spam") with a text newer than what was shown here before
        val review = groups.filter { !notSpam(it) && it.d !in marked && (it.last > (seen[it.d] ?: -1) || it.d in shown) }
        val past = groups - review.toSet()
        val pastVisible = if (showNotSpam) past else past.filterNot(notSpam)
        val hiddenNotSpam = past.count(notSpam)

        screen("Choose spam senders", "${marked.size - marked.count { it in known || it in contacts }} marked as spam") {
            addView(MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true; isSelectionRequired = true
                val a = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "To review (${review.size})" }
                val h = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "History (${past.size - hiddenNotSpam})" }
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
                add(card { addView(text("Tick the senders whose texts you didn't ask for. Swipe right on anyone you know: they become a " +
                    "known number and their texts won't be suggested again. Senders you've been shown here move to History.", 13f, alpha = 0.8f)) })
                if (review.isEmpty()) add(text("Nothing new to review. Earlier senders are under History.", 14f, alpha = 0.7f), dp(12))
                val (likely, rest) = review.partition { it.cat != Category.IGNORE || it.ms.any { m -> m.spamFlagged } }
                if (likely.isNotEmpty()) add(section("Suggested (${likely.size})"))
                likely.forEach { add(row(store, it, marked, known, contacts, fmt).also { v -> watch(v, it) }) }
                val restShown = rest.take(150)
                if (restShown.isNotEmpty()) add(section("Everyone else (${rest.size})"))
                restShown.forEach { add(row(store, it, marked, known, contacts, fmt).also { v -> watch(v, it) }) }
            } else {
                add(card {
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(text("Show not spam ($hiddenNotSpam)", 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(MaterialSwitch(context).apply { isChecked = showNotSpam; setOnCheckedChangeListener { _, on -> showNotSpam = on; render() } })
                    })
                    addView(text("Known numbers and contacts. Edit known numbers in Settings.", 12f, alpha = 0.6f))
                })
                if (pastVisible.isEmpty()) add(text("No history yet.", 14f, alpha = 0.7f), dp(12))
                pastVisible.take(300).forEach { add(row(store, it, marked, known, contacts, fmt)) }
            }
            add(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Known numbers (${known.size})"; setOnClickListener { startActivity(Intent(context, KnownNumbersActivity::class.java)) } }, dp(8))
        }.apply { viewTreeObserver.removeOnScrollChangedListener(visibleCheck); viewTreeObserver.addOnScrollChangedListener(visibleCheck)
            post { checkVisible() } }
    }

    override fun onRestart() { super.onRestart(); render() }   // back from Known numbers / Settings

    private fun row(store: Store, g: Group, marked: MutableSet<String>, known: Set<String>, contacts: Set<String>, fmt: SimpleDateFormat): View {
        val isContact = g.d in contacts; val isKnown = g.d in known
        val card = card(12) {
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(MaterialCheckBox(context).apply {
                    isChecked = g.d in marked && !isContact && !isKnown; isEnabled = !isContact
                    setOnCheckedChangeListener { _, on ->
                        if (on) { store.addSpamSenders(listOf(Phone.dashed(g.d))); store.removeKnown(g.d); marked += g.d }
                        else { store.removeSpamSenders(g.d); marked -= g.d } }
                })
                addView(text(Phone.dashed(g.d), 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
                when {
                    isContact -> addView(chip("Contact", Ui.GREEN))
                    isKnown -> addView(chip("Not spam", Ui.GREEN))
                    g.ms.any { it.spamFlagged } -> addView(chip("Flagged in Messages", Ui.GREY))
                    else -> labels[g.cat]?.let { addView(chip(it, Ui.AMBER)) }
                }
            })
            addView(text("${g.ms.size} text${if (g.ms.size == 1) "" else "s"} · last ${fmt.format(Date(g.last))}\n${g.ms.last().body.take(160)}",
                13f, alpha = 0.75f), lp(top = dp(2)))
        }
        if (isContact || isKnown) return card
        return SwipeRow(this, card, "Not spam") { sr ->
            val wasSpam = g.d in marked
            store.addKnown(g.d); marked -= g.d; shown[g.d] = g.last
            sr.visibility = View.GONE
            Snackbar.make(findViewById(android.R.id.content), "${Phone.dashed(g.d)} marked not spam", 6000)
                .setAction("Undo") { store.removeKnown(g.d); if (wasSpam) { store.addSpamSenders(listOf(Phone.dashed(g.d))); marked += g.d }; sr.reset() }
                .show()
        }
    }
}
