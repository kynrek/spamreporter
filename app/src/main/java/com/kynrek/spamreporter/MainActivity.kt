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
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.stat
import com.kynrek.spamreporter.Ui.statsRow
import com.kynrek.spamreporter.Ui.step
import com.kynrek.spamreporter.Ui.text

class MainActivity : AppCompatActivity() {
    private val store by lazy { Store(this) }

    override fun onResume() { super.onResume(); render(); Community.syncInBackground(this) }

    private fun render() {
        val p = store.profile
        val msgs = store.messages()
        val cases = Jobs.cases(store)
        val f = store.filings()
        val filedN = f.count { it.ok }
        val left = Jobs.pending(store).size
        val smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        fun st(ok: Boolean, yes: String, no: String, noColor: Int = AMBER) = if (ok) yes to GREEN else no to noColor

        val scanOn = Sources.folderScan(this)
        val capOn = Sources.capture(this)
        var n = 0
        val look = CardStyle.look(this, CardStyle.Screen.HOME)
        screen("Spam Reporter", back = false, onSettings = { go(SettingsActivity::class.java) }) {
            add(hero("Spam Reporter", "Report spam texts to the FCC and FTC — from your phone, in a few taps."))
            add(statsRow(stat("${cases.size}", "spam senders", NAVY, look), stat("$filedN", "reports filed", GREEN, look),
                stat("$left", "left to file", RED, look)), dp(8))
            add(MaterialButton(context).apply {
                text = "Scan for new spam"; textSize = 16f; minimumHeight = dp(56)
                setOnClickListener { scanAll() } }, dp(12))
            if (left > 0) add(MaterialButton(context).apply {
                text = "File $left new report${if (left == 1) "" else "s"}"; textSize = 16f; minimumHeight = dp(56)
                backgroundTintList = android.content.res.ColorStateList.valueOf(RED)
                setOnClickListener { fileReports(p) } }, dp(4))
            add(text(if (scanOn) "Scanning reads your inbox, then opens Google Messages to read the Spam & blocked folder (RCS included)."
                else "Scanning reads your SMS & MMS inbox; then tick the spam senders under \"Choose spam senders\".", 12f, alpha = 0.6f), dp(4))

            Interrupted.stoppedAt(this@MainActivity)?.let { at ->
                val unsure = Interrupted.unsure(this@MainActivity)
                add(card {
                    addView(text("Filing stopped before it finished", 16f, true, RED))
                    addView(text("The run started ${java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(at))} " +
                        "was cut off — the app was closed by the phone or updated. Reports already filed are saved and won't be filed again; " +
                        "$left still to file." + (if (unsure.isEmpty()) "" else "\n\nThis one was being sent when it stopped and may or may not have gone through " +
                        "(check the FCC confirmation email or My requests): ${unsure.joinToString()}"), 13f, alpha = 0.8f), lp(top = dp(4)))
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                        if (left > 0) addView(MaterialButton(context).apply { text = "Resume filing"
                            setOnClickListener { Interrupted.dismiss(this@MainActivity); fileReports(p) } })
                        addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Dismiss"
                            setOnClickListener { Interrupted.dismiss(this@MainActivity); render() } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                    }, lp(top = dp(8)))
                }, dp(12))
            }

            add(section("Set up"))
            add(step(look, ++n, "Your info & attestation", if (p.complete()) "${p.first} ${p.last} · ${p.phoneDashed()}" else "Name, address, and the number that got the texts",
                st(p.complete() && p.attested, "Done", if (p.complete()) "Attest" else "Required", RED)) { go(ProfileActivity::class.java) })

            add(step(look, ++n, "FCC account (recommended)", if (FccAccount.likelySignedIn(this@MainActivity))
                    "Signed in ${FccAccount.untilText(this@MainActivity)} · tap to sign out"
                    else "Recommended: signing in files reports much faster, with no FCC hourly limit. Lasts ~${FccAccount.SESSION_HOURS} hours.",
                if (FccAccount.likelySignedIn(this@MainActivity)) "Signed in" to GREEN else "Recommended" to AMBER) { go(FccSignInActivity::class.java) })

            val lock = FccLogin.lock(this@MainActivity)
            add(step(look, ++n, "Fingerprint / PIN (optional)", when (lock) {
                    FccLogin.Lock.FINGERPRINT -> "Fingerprint is set up · locks your saved FCC sign-in"
                    FccLogin.Lock.PIN_ONLY -> "PIN is set up · add a fingerprint to unlock your saved FCC sign-in with a touch"
                    FccLogin.Lock.NONE -> "Only needed to save your FCC sign-in · tap to set up a fingerprint or PIN" },
                when (lock) { FccLogin.Lock.FINGERPRINT -> "On" to GREEN; FccLogin.Lock.PIN_ONLY -> "PIN only" to GREEN; FccLogin.Lock.NONE -> "Optional" to AMBER }) {
                FccLogin.explainLock(this@MainActivity) })

            add(section("Collect messages"))
            add(step(look, ++n, "Import SMS & MMS", "${msgs.count { it.source == Source.SMS || it.source == Source.MMS }} messages read from your inbox",
                st(smsOk && msgs.isNotEmpty(), "Imported", "Tap to import")) { importSms() })
            if (scanOn) add(step(look, ++n, "Scan Spam & blocked folder", getSharedPreferences("scan", MODE_PRIVATE).getString("last", null)
                    ?.replaceFirstChar { it.uppercase() } ?: "Reads spam-flagged chats (RCS included) from Google Messages",
                if (!a11yEnabled()) "Needs access" to AMBER else if (!MessagesAccessibilityService.connected) "Turn access off & on" to RED else "Tap to scan" to NAVY) { importSpamFolder() })
            add(step(look, ++n, "Choose spam senders", (if (scanOn) "Add spam that Google Messages didn't flag" else "Tick which senders are spam") + " · swipe left = spam, right = not spam",
                if (cases.isEmpty()) "Start here" to NAVY else null) { go(MarkSpamActivity::class.java) })
            if (capOn) add(step(look, ++n, "Capture new spam & FCC tickets", "Saves new texts as they arrive, and FCC ticket numbers from email",
                st(listenerEnabled(), "On", "Needs access", AMBER)) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })

            add(step(look, ++n, "Block spam numbers", "Turn away calls from spam numbers automatically; block texts in a tap or two. Undo any number.",
                if (CallBlock.active(this@MainActivity)) "On" to GREEN else "Optional" to AMBER) { go(BlockActivity::class.java) })

            add(section("Report"))
            add(step(look, ++n, "Review senders", "${cases.count { it.category == Category.COMMERCIAL }} commercial · " +
                "${cases.count { it.category == Category.POLITICAL }} political · ${cases.count { it.category == Category.SURVEY }} survey",
                if (cases.isEmpty()) null else "Check" to NAVY) { go(ReviewActivity::class.java) })
            add(step(look, ++n, "File reports", "New texts only — anything already reported is skipped",
                if (!p.attested) "Attest first" to GREY else st(left == 0 && filedN > 0, "All filed", if (left == 0) "Nothing yet" else "$left to go")) { fileReports(p) })
            add(step(look, ++n, "Report history", "Every report number, who it went to and when, with the texts — save as PDF",
                if (filedN > 0) "$filedN" to GREEN else null) { go(HistoryActivity::class.java) })
            add(step(look, ++n, "Export confirmation report", "CSV + Markdown with every ticket and report number, plus a history file", null) { export() })
            add(step(look, ++n, "Import filing history", "Reports filed outside the app or on another phone — so they're never filed twice",
                f.count { it.note == "imported" }.let { if (it > 0) "$it imported" to GREEN else null }) { pickHistory.launch(arrayOf("application/json", "text/plain", "*/*")) })

            add(section("Privacy"))
            add(text("Everything stays on this phone, encrypted with a key in the Android Keystore. The app has no analytics; " +
                "it only connects to fcc.gov, ftc.gov and donotcall.gov, and blocks trackers on those pages. " +
                (if (Community.shareOn(this@MainActivity) || Community.downloadOn(this@MainActivity))
                    "You turned on the community list, so it also syncs with ${Community.HOST} (Settings → Community). "
                 else "The optional community spam list (${Community.HOST}) is off unless you turn it on in Settings. ") +
                "Your FCC password is only saved if you choose to, encrypted and unlocked with your fingerprint or PIN. Contacts (optional) are only compared on this phone.", 13f, alpha = 0.7f))
            add(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Delete all my data"; setTextColor(RED); setOnClickListener { confirmWipe() } }, dp(8))
            MessagesAccessibilityService.lastStatus.takeIf { it.isNotBlank() && it != "idle" }?.let { add(text("Spam-folder import: $it", 12f, alpha = 0.5f), dp(12)) }
        }
    }

    private val pickHistory = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val r = runCatching { store.importHistory(contentResolver.openInputStream(uri)!!.bufferedReader().readText()) }
        r.onSuccess { (added, known) -> MaterialAlertDialogBuilder(this).setTitle("Filing history imported")
                .setMessage("$added reports added" + (if (known > 0) ", $known were already known" else "") + ".\n\n" +
                    "Texts covered by these reports won't be filed again. New texts from the same senders will get their own reports.\n\n" +
                    "You can delete the history file now if you like.")
                .setPositiveButton("OK", null).show(); render() }
         .onFailure { toast("Couldn't read that file: ${it.message}") }
    }

    private fun fileReports(p: Profile) {
        if (!p.complete() || !p.attested) { toast("Fill in your info and tick the attestation first"); go(ProfileActivity::class.java) }
        else go(FilingActivity::class.java)
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
        .setMessage("Removes your profile, imported messages and filing history from this phone, signs you out of the FCC and removes a saved FCC sign-in. Reports you already filed stay with the agencies.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete") { _, _ -> store.wipeAll(); getSharedPreferences("pace", MODE_PRIVATE).edit().clear().apply(); FccAccount.clearLocal(this); FccLogin.forget(this); Community.wipe(this); CallBlock.wipe(this); TextBlock.wipe(this); toast("All data deleted"); render() }
        .show()

    private var thenSpamFolder = false

    /** One-tap scan: SMS/MMS inbox first, then the Messages Spam & blocked folder. */
    private fun scanAll() { thenSpamFolder = true; importSms() }

    private fun importSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), 1); return }
        Thread {
            val n = store.addMessages(SmsReader.readAll(this, store.spamSenders()))
            val toReview = MarkSpamActivity.reviewCount(this)
            runOnUiThread { render()
                if (thenSpamFolder) { thenSpamFolder = false
                    when {
                        Sources.folderScan(this) -> { toast("Imported $n new messages"); importSpamFolder() }
                        // nothing new to look at: stay here instead of opening an empty review screen
                        toReview == 0 -> toast(if (n == 0) "No new messages" else "Imported $n new messages — nothing new to review")
                        else -> { toast("$toReview sender${if (toReview == 1) "" else "s"} to review"); go(MarkSpamActivity::class.java) }
                    } } else toast("Imported $n new messages") }
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
        if (!MessagesAccessibilityService.connected) {
            MaterialAlertDialogBuilder(this).setTitle("Restart Spam Reporter's access")
                .setMessage("Android shows Spam Reporter's accessibility access as on, but isn't running it (this happens after an app update on some phones).\n\n" +
                    "In the next screen, open Downloaded apps → Spam Reporter, turn it off, then on again. Then come back and scan.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
            return
        }
        MessagesAccessibilityService.armed = true
        toast("In Messages: tap your profile icon → Spam & blocked. The import starts on its own; don't touch the screen until it's done.")
        val launch = packageManager.getLaunchIntentForPackage(MessagesAccessibilityService.PKG)
        if (launch != null) startActivity(launch)
        else MaterialAlertDialogBuilder(this).setTitle("Open Google Messages")
            .setMessage("Couldn't open Google Messages automatically. Open it yourself, tap your profile icon → Spam & blocked, and the import will start on its own.")
            .setPositiveButton("OK", null).show()
    }
}
