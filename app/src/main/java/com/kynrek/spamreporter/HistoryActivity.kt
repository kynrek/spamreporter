package com.kynrek.spamreporter

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.kynrek.spamreporter.Ui.GREEN
import com.kynrek.spamreporter.Ui.NAVY
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.chip
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.text
import java.util.Date

/** Every filed report: number, agency, when, and the texts it covered. */
class HistoryActivity : AppCompatActivity() {
    private var filter: Agency? = null

    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); SpamReply.onPermission(this, rc) { render() } }

    private fun render() {
        val store = Store(this)
        val all = HistoryReport.entries(store)
        val list = all.filter { filter == null || it.filing.agency == filter }
        val fmt = HistoryReport.when_
        screen("Report history", "${all.size} reports filed") {
            add(MaterialButton(context).apply { text = "Save PDF report"; minimumHeight = dp(52); setOnClickListener { savePdf(store) } })
            add(ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true
                for ((label, a) in listOf("All (${all.size})" to null, "FCC (${all.count { it.filing.agency == Agency.FCC }})" to Agency.FCC,
                        "FTC / Do Not Call (${all.count { it.filing.agency == Agency.FTC }})" to Agency.FTC))
                    addView(Chip(context).apply { this.text = label; isCheckable = true; id = android.view.View.generateViewId()
                        isChecked = a == filter; setOnClickListener { if (filter != a) { filter = a; render() } } })
            }, dp(8))
            for (e in list) add(card {
                val f = e.filing
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(text("${e.numberLabel} #${f.confirmation ?: "pending"}", 17f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(chip(if (f.agency == Agency.FCC) "FCC" else "FTC · DNC", if (f.agency == Agency.FCC) NAVY else GREEN))
                })
                addView(text("${e.agencyName} · filed ${fmt.format(Date(f.filedAt))}", 12f, alpha = 0.6f))
                addView(text("From ${f.sender}" + (e.category?.let { " · ${it.name.lowercase()}" } ?: ""), 14f, true), lp(top = dp(8)))
                if (e.texts.isEmpty()) addView(text("Text not available on this phone.", 13f, alpha = 0.6f))
                for ((ts, body) in e.texts) {
                    addView(text("Received ${fmt.format(Date(ts))}", 12f, alpha = 0.6f), lp(top = dp(6)))
                    addView(text(body.trim(), 14f, alpha = 0.9f).apply { setTextIsSelectable(true) })
                }
                if (Phone.isTenDigit(f.sender)) addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val at = SpamReply.sentAt(context, Phone.digits(f.sender))
                    addView(text(if (at > 0) "Replied ${fmt.format(Date(at))}" else "", 12f, alpha = 0.6f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Reply"; setOnClickListener { SpamReply.show(this@HistoryActivity, f.sender) { render() } } })
                }, lp(top = dp(8)))
            })
            if (list.isEmpty()) add(text("No reports yet.", 14f, alpha = 0.6f), dp(16))
        }
    }

    private fun savePdf(store: Store) {
        Toast.makeText(this, "Creating PDF…", Toast.LENGTH_SHORT).show()
        Thread {
            val u = runCatching { HistoryReport.pdf(this, store) }.getOrNull()
            runOnUiThread {
                if (u == null) { Toast.makeText(this, "Couldn't create the PDF", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                Toast.makeText(this, "Saved to Downloads/SpamReporter", Toast.LENGTH_LONG).show()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF report"))
            }
        }.start()
    }
}
