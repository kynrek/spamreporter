package com.kynrek.spamreporter

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** View and edit "known numbers": senders that are never spam (not necessarily in contacts). */
class KnownNumbersActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }

    private fun render() {
        val store = Store(this)
        val known = store.knownNumbers().entries.sortedByDescending { it.value }
        val last = store.messages().groupBy { Phone.digits(it.sender) }.mapValues { (_, ms) -> ms.maxByOrNull { it.ts } }
        val day = SimpleDateFormat("MMM d, yyyy", Locale.US)
        screen("Known numbers", "${known.size} not spam") {
            add(card { addView(text("Texts from these numbers are never suggested or reported as spam, now or in future. " +
                "Numbers get here when you swipe right on \"Choose spam senders\", or add them below. " +
                "People in your contacts are handled separately (Settings → Contacts are never spam).", 13f, alpha = 0.8f)) })

            val field = TextInputEditText(this@KnownNumbersActivity).apply { inputType = InputType.TYPE_CLASS_PHONE; hint = "Phone number" }
            val box = TextInputLayout(this@KnownNumbersActivity).apply { addView(field) }
            add(LinearLayout(this@KnownNumbersActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(box, LinearLayout.LayoutParams(0, -2, 1f))
                addView(MaterialButton(context).apply { text = "Add"; setOnClickListener {
                    val d = Phone.digits(field.text.toString())
                    if (d.length != 10) box.error = "Enter a 10-digit number" else { store.addKnown(d); render() } } },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }, dp(4))

            if (known.isEmpty()) add(text("No known numbers yet.", 14f, alpha = 0.7f), dp(12))
            for ((d, at) in known) add(card(12) {
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
                        addView(text(Phone.dashed(d), 16f, true))
                        addView(text("Added ${day.format(Date(at))}" + (last[d]?.let { "\nLast text: ${it.body.take(100)}" } ?: ""), 12f, alpha = 0.65f))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "Remove"; setOnClickListener { store.removeKnown(d); render() } })
                })
            }, dp(8))
        }
    }
}
