package com.kynrek.spamreporter

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.kynrek.spamreporter.Ui.GREEN
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.chip
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.text
import java.text.SimpleDateFormat
import java.util.*

/** Lists every spam sender with its messages; the user confirms or changes the category before filing. */
class ReviewActivity : AppCompatActivity() {
    private val labels = linkedMapOf(Category.COMMERCIAL to "Commercial", Category.POLITICAL to "Political",
        Category.SURVEY to "Survey", Category.IGNORE to "Don't file")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val store = Store(this)
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        val filed = store.filings().groupBy { it.sender }
        val cases = Jobs.cases(store)
        screen("Review senders", "${cases.size} spam senders") {
            add(card { addView(text("Commercial → FCC and FTC. Political and survey texts aren't Do Not Call violations, so they go to the FCC " +
                "only and are labeled that way. Pick \"Don't file\" for anyone you have a relationship with.", 13f, alpha = 0.8f)) })
            for (c in cases) add(card {
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                    addView(text(c.dashed, 17f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    filed[c.dashed]?.forEach { addView(chip("${it.agency} ${it.confirmation ?: it.status}", GREEN), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) }) }
                })
                addView(text("${c.msgs.size} message${if (c.msgs.size == 1) "" else "s"} · last ${fmt.format(Date(c.last.ts))}", 12f, alpha = 0.6f))
                for (m in c.msgs.takeLast(3)) addView(text("${fmt.format(Date(m.ts))} · ${if (m.source == Source.RCS_SPAM_FOLDER) "RCS" else m.source.name}\n${m.body.take(260)}",
                    13f, alpha = 0.85f), lp(top = dp(8)))
                addView(ChipGroup(context).apply {
                    isSingleSelection = true; isSelectionRequired = true
                    for ((cat, label) in labels) addView(Chip(context).apply { isCheckable = true; isCheckedIconVisible = true
                        text = label; id = android.view.View.generateViewId(); isChecked = cat == c.category
                        setOnCheckedChangeListener { _, on -> if (on) store.setCategory(c.digits, cat) } })
                }, lp(top = dp(8)))
            })
        }
    }
}
