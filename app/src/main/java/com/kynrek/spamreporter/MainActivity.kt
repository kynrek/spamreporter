package com.kynrek.spamreporter

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kynrek.spamreporter.Ui.AMBER
import com.kynrek.spamreporter.Ui.GREEN
import com.kynrek.spamreporter.Ui.GREY
import com.kynrek.spamreporter.Ui.NAVY
import com.kynrek.spamreporter.Ui.RED
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.hero
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.section
import com.kynrek.spamreporter.Ui.stat
import com.kynrek.spamreporter.Ui.statsRow
import com.kynrek.spamreporter.Ui.step
import com.kynrek.spamreporter.Ui.text

class MainActivity : AppCompatActivity() {
    private val store by lazy { Store(this) }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val p = store.profile
        val msgs = store.messages()
        val cases = Jobs.cases(store)
        val f = store.filings()
        val rcs = msgs.count { it.source == Source.RCS_SPAM_FOLDER }
        val filedN = f.count { it.ok }
        val left = Jobs.pending(store).size
        val smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        fun st(ok: Boolean, yes: String, no: String, noColor: Int = AMBER) = if (ok) yes to GREEN else no to noColor

        screen("Spam Reporter", back = false) {
            add(hero("Spam Reporter", "Report spam texts to the FCC and FTC — from your phone, in a few taps."))
            add(statsRow(stat("${cases.size}", "spam senders", NAVY), stat("$filedN", "reports filed", GREEN),
                stat("$left", "left to file", RED)), dp(8))

            add(section("Set up"))
            add(step(1, "Your info & attestation", if (p.complete()) "${p.first} ${p.last} · ${p.phoneDashed()}" else "Name, address, and the number that got the texts",
                st(p.complete() && p.attested, "Done", if (p.complete()) "Attest" else "Required", RED)) { go(ProfileActivity::class.java) })

            add(section("Collect messages"))
            add(step(2, "Import SMS & MMS", "${msgs.count { it.source == Source.SMS || it.source == Source.MMS }} messages read from your inbox",
                st(smsOk && msgs.isNotEmpty(), "Imported", "Tap to import")) { importSms() })
            add(step(3, "Import Spam & blocked folder", "Reads RCS chats from Google Messages (${rcs} so far)",
                st(rcs > 0, "$rcs RCS", if (a11yEnabled()) "Ready" else "Needs access")) { importSpamFolder() })
            add(step(4, "Capture new spam & FCC tickets", "Saves new spam as it arrives, and FCC ticket numbers from email",
                st(listenerEnabled(), "On", "Off", GREY)) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })

            add(section("Report"))
            add(step(5, "Review senders", "${cases.count { it.category == Category.COMMERCIAL }} commercial · " +
                "${cases.count { it.category == Category.POLITICAL }} political · ${cases.count { it.category == Category.SURVEY }} survey",
                if (cases.isEmpty()) null else "Check" to NAVY) { go(ReviewActivity::class.java) })
            add(step(6, "File reports", "New texts only — anything already reported is skipped",
                if (!p.attested) "Attest first" to GREY else st(left == 0 && filedN > 0, "All filed", if (left == 0) "Nothing yet" else "$left to go")) {
                if (!p.complete() || !p.attested) toast("Finish step 1 first") else go(FilingActivity::class.java) })
            add(step(7, "Export confirmation report", "CSV + Markdown with every ticket and report number", null) { export() })

            add(section("Privacy"))
            add(text("Everything stays on this phone, encrypted with a key in the Android Keystore. The app has no analytics or " +
                "servers of its own; it only connects to fcc.gov, ftc.gov and donotcall.gov, and blocks trackers on those pages.", 13f, alpha = 0.7f))
            add(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Delete all my data"; setTextColor(RED); setOnClickListener { confirmWipe() } }, dp(8))
            MessagesAccessibilityService.lastStatus.takeIf { it.isNotBlank() && it != "idle" }?.let { add(text("Spam-folder import: $it", 12f, alpha = 0.5f), dp(12)) }
        }
    }

    private fun go(c: Class<*>) = startActivity(Intent(this, c))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun export() {
        val u = Jobs.exportReport(this, store) ?: return toast("Export failed")
        toast("Saved to Downloads/SpamReporter")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share report"))
    }

    private fun confirmWipe() = MaterialAlertDialogBuilder(this).setTitle("Delete all data?")
        .setMessage("Removes your profile, imported messages, and filing history from this phone. Reports you already filed stay with the agencies.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete") { _, _ -> store.wipeAll(); getSharedPreferences("pace", MODE_PRIVATE).edit().clear().apply(); toast("All data deleted"); render() }
        .show()

    private fun importSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), 1); return }
        Thread {
            val n = store.addMessages(SmsReader.readAll(this, store.spamSenders()))
            runOnUiThread { toast("Imported $n new messages"); render() }
        }.start()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 1 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) importSms()
    }

    private fun a11yEnabled(): Boolean {
        val want = ComponentName(this, MessagesAccessibilityService::class.java).flattenToString()
        return (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').any { it.equals(want, true) }
    }
    private fun listenerEnabled(): Boolean {
        val want = ComponentName(this, CaptureNotificationListener::class.java).flattenToString()
        return (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").split(':').any { it.equals(want, true) }
    }

    private fun importSpamFolder() {
        if (!a11yEnabled()) {
            MaterialAlertDialogBuilder(this).setTitle("Allow access to Messages")
                .setMessage("RCS chats aren't readable by other apps, so Spam Reporter reads your Spam & blocked folder from the screen.\n\n" +
                    "In the next screen, open Downloaded apps → Spam Reporter and turn it on. It only watches Google Messages, and only while an import is running.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
            return
        }
        MessagesAccessibilityService.armed = true
        toast("In Messages: tap your profile icon → Spam & blocked. The import starts on its own; don't touch the screen until it's done.")
        packageManager.getLaunchIntentForPackage(MessagesAccessibilityService.PKG)?.let(::startActivity)
    }
}
