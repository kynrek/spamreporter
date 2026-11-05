package com.kynrek.spamreporter

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.kynrek.spamreporter.Ui.GREEN
import com.kynrek.spamreporter.Ui.AMBER
import com.kynrek.spamreporter.Ui.GREY
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.chip
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.section
import com.kynrek.spamreporter.Ui.text

/** Which message sources the app uses, and the permissions each one needs. */
class SettingsActivity : AppCompatActivity() {
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        screen("Settings") {
            add(section("Message sources"))
            add(card { addView(text("SMS & MMS inbox", 16f, true))
                addView(text("Always used. Needs the SMS permission (read only; the app can't send texts).", 13f, alpha = 0.7f)) })

            val scan = Sources.folderScan(this@SettingsActivity)
            add(toggle("Scan Spam & blocked folder (RCS)",
                "Reads the texts Google Messages has flagged as spam — including RCS chats, which aren't in the SMS inbox. " +
                "Needs Accessibility access, used only while a scan you started is running.\n\n" +
                "Off: no Accessibility access is needed; you pick spam senders yourself under \"Choose spam senders\".",
                scan, if (!scan) null else if (Sources.a11yGranted(this@SettingsActivity)) "Access on" to GREEN else "Access needed" to AMBER) { on ->
                Sources.setFolderScan(this@SettingsActivity, on)
                if (on) askFor("Turn on Accessibility access", "Open Downloaded apps → Spam Reporter and turn it on.", Settings.ACTION_ACCESSIBILITY_SETTINGS)
                render()
            })

            val cap = Sources.capture(this@SettingsActivity)
            add(toggle("Capture new texts & FCC tickets",
                "Saves new texts as they arrive (RCS included) and picks up FCC ticket numbers from Gmail notifications. Needs Notification access.\n\n" +
                "Off: no Notification access is needed; run a scan to pick up new texts.",
                cap, if (!cap) null else if (Sources.listenerGranted(this@SettingsActivity)) "Access on" to GREEN else "Access needed" to AMBER) { on ->
                Sources.setCapture(this@SettingsActivity, on)
                if (on && !Sources.listenerGranted(this@SettingsActivity)) askFor("Turn on Notification access", "Find Spam Reporter and allow it.", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                render()
            })
            add(text("Turning a source off disables it completely, so it disappears from Android's Accessibility or Notification access list.",
                12f, alpha = 0.6f), dp(4))

            add(section("Not spam"))
            val cOn = Contacts.enabled(this@SettingsActivity)
            add(toggle("Contacts are never spam",
                "Anyone in your contacts is treated as not spam: never suggested and never reported. Contacts are only " +
                "checked on this phone. Needs Contacts access.",
                cOn, if (!cOn) null else if (Contacts.granted(this@SettingsActivity)) "Access on" to GREEN else "Access needed" to AMBER) { on ->
                Contacts.setEnabled(this@SettingsActivity, on)
                if (on && !Contacts.granted(this@SettingsActivity)) requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 2)
                render()
            })
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Known numbers (${Store(this@SettingsActivity).knownNumbers().size})"
                setOnClickListener { startActivity(Intent(context, KnownNumbersActivity::class.java)) } })
            add(text("Numbers you swiped right on (\"not spam\") under Choose spam senders. Their future texts are never suggested or reported.",
                12f, alpha = 0.6f), dp(4))

            add(section("Filing"))
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            add(toggle("Watch mode", "Scrolls to and highlights each field as the form is filled, so you can follow along. " +
                "Adds about 30 seconds per report.", prefs.getBoolean("watch", false), null) { on -> prefs.edit().putBoolean("watch", on).apply() })
            add(card {
                addView(text("Reports at once", 16f, true))
                addView(text("How many report forms are filled and sent side by side. Submissions are still spaced at least a second apart, " +
                    "and filing drops back to one at a time if a site refuses or rate-limits a report. Watch mode always uses one.", 13f, alpha = 0.7f), lp(top = dp(4)))
                addView(com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
                    isSingleSelection = true; isSelectionRequired = true
                    val ids = (1..5).map { n -> android.view.View.generateViewId().also { id ->
                        addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { this.id = id; text = "$n" },
                            LinearLayout.LayoutParams(0, -2, 1f)) } }
                    check(ids[prefs.getInt("parallel", 3).coerceIn(1, 5) - 1])
                    addOnButtonCheckedListener { _, id, on -> if (on) prefs.edit().putInt("parallel", ids.indexOf(id) + 1).apply() }
                }, lp(top = dp(8)))
            }, dp(8))

            add(section("Account"))
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "FCC account"; setOnClickListener { startActivity(Intent(context, FccSignInActivity::class.java)) } })
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Your info & attestation"; setOnClickListener { startActivity(Intent(context, ProfileActivity::class.java)) } })
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); render() }

    private fun toggle(title: String, body: String, on: Boolean, status: Pair<String, Int>?, onChange: (Boolean) -> Unit) = card {
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text(title, 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialSwitch(context).apply { isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } })
        })
        status?.let { (l, c) -> addView(chip(l, c), LinearLayout.LayoutParams(-2, -2)) }
        addView(text(body, 13f, alpha = 0.7f), lp(top = dp(4)))
    }

    private fun askFor(title: String, msg: String, action: String) = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
        .setNegativeButton("Later", null).setPositiveButton("Open settings") { _, _ -> startActivity(Intent(action)) }.show()
}
