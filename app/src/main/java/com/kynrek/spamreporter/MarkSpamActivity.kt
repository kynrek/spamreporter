package com.kynrek.spamreporter

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
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
 */
class MarkSpamActivity : AppCompatActivity() {
    private val labels = mapOf(Category.COMMERCIAL to "Looks commercial", Category.POLITICAL to "Looks political", Category.SURVEY to "Looks like a survey")

    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }

    private fun render() {
        val store = Store(this)
        val marked = store.spamSenders().map(Phone::digits).toMutableSet()
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        val groups = store.messages().filter { Phone.isTenDigit(it.sender) && it.body.isNotBlank() }
            .groupBy { Phone.digits(it.sender) }
            .map { (d, ms) -> Triple(d, ms.sortedBy { it.ts }, Classifier.classify(ms.map { it.body })) }
            .sortedByDescending { it.second.last().ts }
        val (likely, rest) = groups.partition { it.third != Category.IGNORE || it.first in marked || it.second.any { m -> m.spamFlagged } }

        screen("Choose spam senders", "${marked.size} marked as spam") {
            add(card { addView(text("Tick the senders whose texts you didn't ask for. Only ticked senders are reported, and you can " +
                "still review each one before filing. Leave off anyone you know or do business with.", 13f, alpha = 0.8f)) })
            if (likely.isNotEmpty()) add(section("Suggested (${likely.size})"))
            likely.forEach { add(row(store, it, marked, fmt)) }
            if (rest.isNotEmpty()) add(section("Everyone else (${rest.size})"))
            rest.take(150).forEach { add(row(store, it, marked, fmt)) }
        }
    }

    private fun row(store: Store, g: Triple<String, List<Msg>, Category>, marked: MutableSet<String>, fmt: SimpleDateFormat) = card(12) {
        val (d, ms, cat) = g
        val byMessages = ms.any { it.spamFlagged }
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(MaterialCheckBox(context).apply {
                isChecked = d in marked
                setOnCheckedChangeListener { _, on ->
                    if (on) { store.addSpamSenders(listOf(Phone.dashed(d))); marked += d } else { store.removeSpamSenders(d); marked -= d } }
            })
            addView(text(Phone.dashed(d), 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (byMessages) addView(chip("Flagged in Messages", Ui.GREY))
            else labels[cat]?.let { addView(chip(it, Ui.AMBER)) }
        })
        addView(text("${ms.size} text${if (ms.size == 1) "" else "s"} · last ${fmt.format(Date(ms.last().ts))}\n${ms.last().body.take(160)}",
            13f, alpha = 0.75f), lp(top = dp(2)))
    }
}
