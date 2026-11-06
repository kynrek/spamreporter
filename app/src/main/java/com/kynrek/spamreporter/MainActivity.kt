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

    override fun onResume() { super.onResume(); render(); Community.syncInBackground(this); Dnc.followUp(this) { render() }
        if (flowPending() && !MessagesAccessibilityService.running) { MessagesAccessibilityService.armed = false; continueFlow() } }

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
            add(text((if (scanOn) "Reads your inbox and the Spam & blocked folder in Google Messages (RCS included), "
                else "Reads your SMS & MMS inbox, ") + "then takes you through choosing spam senders and filing the reports.", 12f, alpha = 0.6f), dp(4))

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

            val fccIn = FccAccount.likelySignedIn(this@MainActivity); val fccBack = !fccIn && FccAccount.hasAccount(this@MainActivity)
            val fccAuto = FccLogin.saved(this@MainActivity) && FccLogin.autoEnabled(this@MainActivity)
            val lock = FccLogin.lock(this@MainActivity)
            val dncOk = Dnc.done(this@MainActivity); val infoOk = p.complete() && p.attested; val fccOk = fccIn || fccAuto
            // all set: the section is one "Setup ✓" tab (tap to open). Inside, finished parts are one-line rows with a green
            // check; anything still to do gets its full card. Not all set: the section stays open.
            val setupDone = dncOk && infoOk && fccOk
            // set up: the heading itself folds the section (arrow on the right) once everything is done
            if (setupDone) add(foldHeading("Set up", setupOpen) { setupOpen = !setupOpen; render() }) else add(section("Set up"))
            if (setupDone) add(setupCard(look, fccIn))
            if (!setupDone || setupOpen) {
                if (dncOk) add(doneRow(look, "Do Not Call Registry", p.dncRegisteredOn.ifBlank { null }?.let { "Registered since $it" } ?: "Registered") {
                        Dnc.ask(this@MainActivity) { render() } })
                else add(step(look, ++n, "Do Not Call Registry", "Free. Sign up your number, or check that it's already on the list",
                    "Check" to AMBER) { Dnc.ask(this@MainActivity) { render() } })

                if (infoOk) add(doneRow(look, "Your info & attestation", "${p.first} ${p.last} · ${p.phoneDashed()}") { go(ProfileActivity::class.java) })
                else add(step(look, ++n, "Your info & attestation", if (p.complete()) "${p.first} ${p.last} · ${p.phoneDashed()}" else "Name, address, and the number that got the texts",
                    if (p.complete()) "Attest" to AMBER else "Required" to RED) { go(ProfileActivity::class.java) })

                if (fccOk) add(doneRow(look, "FCC account", if (fccIn) "Signed in ${FccAccount.untilText(this@MainActivity)}"
                        else "Signs in automatically with your saved password when you file") { go(FccSignInActivity::class.java) })
                else add(step(look, ++n, if (fccBack) "FCC account" else "FCC account (recommended)", when {
                        fccBack -> "Signed out · tap to sign back in" + (if (FccLogin.saved(this@MainActivity)) " with your saved password" else "") +
                            ". Signed in, reports file much faster, with no FCC hourly limit."
                        else -> "Recommended: signing in files reports much faster, with no FCC hourly limit. Lasts ~${FccAccount.SESSION_HOURS} hours." },
                    if (fccBack) "Sign back in" to AMBER else "Recommended" to AMBER) { go(FccSignInActivity::class.java) })

                if (lock != FccLogin.Lock.NONE) add(doneRow(look, "Fingerprint / PIN", if (lock == FccLogin.Lock.FINGERPRINT) "Fingerprint is set up" else "PIN is set up") {
                        FccLogin.explainLock(this@MainActivity) })
                else add(step(look, ++n, "Fingerprint / PIN (optional)", "Only needed to save your FCC sign-in · tap to set up a fingerprint or PIN",
                    "Optional" to AMBER) { FccLogin.explainLock(this@MainActivity) })
            }

            // Not set up yet: every step, in order. Set up: the Scan button does the importing, so only the things worth
            // opening on their own (Choose spam senders, Capture, Block) are listed, as one-line rows under Settings.
            if (!setupDone) {
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
            }

            if (setupDone) {
                add(section("Settings"))
                add(linkRow(look, "Choose spam senders", "Change what's marked spam, or look back at past senders") { go(MarkSpamActivity::class.java) })
                if (capOn) add(linkRow(look, "Capture new spam & FCC tickets", if (listenerEnabled()) "On" else "Needs access",
                    if (listenerEnabled()) null else AMBER) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                add(linkRow(look, "Block spam numbers", if (CallBlock.active(this@MainActivity)) "On" else "Off · optional") { go(BlockActivity::class.java) })
            }
            if (setupDone) add(fold(look, "report", "Report", if (left > 0) "$left to file · history, export" else "All filed · history, export")) else add(section("Report"))
            if (!setupDone || "report" in open) {
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
            }

            if (setupDone) add(fold(look, "privacy", "Privacy", "Everything stays on this phone · delete all data")) else add(section("Privacy"))
            if (!setupDone || "privacy" in open) {
                add(text("Everything stays on this phone, encrypted with a key in the Android Keystore. The app has no analytics; " +
                    "it only connects to fcc.gov, ftc.gov and donotcall.gov, and blocks trackers on those pages. " +
                    (if (Community.shareOn(this@MainActivity) || Community.downloadOn(this@MainActivity))
                        "You turned on the community list, so it also syncs with ${Community.HOST} (Settings → Community). "
                     else "The optional community spam list (${Community.HOST}) is off unless you turn it on in Settings. ") +
                    "Your FCC password is only saved if you choose to, encrypted and unlocked with your fingerprint or PIN. Contacts (optional) are only compared on this phone.", 13f, alpha = 0.7f))
                add(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Delete all my data"; setTextColor(RED); setOnClickListener { confirmWipe() } }, dp(8))
            }

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

    private var setupOpen = false
    /** One-line row under Settings: title · short detail, chevron; [warn] colors the detail when something needs attention. */
    private fun linkRow(look: CardStyle.Look, title: String, detail: String, warn: Int? = null, onClick: () -> Unit) = card(12) {
        val fg = look.fgOrNull; val k = look.scale
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(text(title, 15f * k, true, fg))
            addView(text(detail, 12f * k, color = warn ?: fg, alpha = if (warn != null) 1f else 0.7f).apply { maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END }) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("\u203A", 22f * k, color = fg, alpha = 0.5f))
    }.apply { if (look.bg != 0) setCardBackgroundColor(look.bg); isClickable = true; setOnClickListener { onClick() } }
    /** Section heading that folds its section: the usual heading text with a ▾ / ▴ arrow on the right; tap anywhere on it. */
    private fun foldHeading(title: String, isOpen: Boolean, toggle: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.BOTTOM
        val h = section(title); addView(h, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(if (isOpen) "\u25B4" else "\u25BE", 18f, color = h.currentTextColor).apply { setPadding(dp(8), 0, dp(12), dp(4)) })
        isClickable = true; setOnClickListener { toggle() }
        contentDescription = "$title, ${if (isOpen) "tap to fold" else "tap to show"}"
    }
    /** Sections the user opened (once setup is done, Collect messages / Report / Privacy start folded). */
    private val open = mutableSetOf<String>()
    /** A folded section: its title and a one-line summary; tap to open or close. */
    private fun fold(look: CardStyle.Look, key: String, title: String, summary: String) = card(12) {
        val fg = look.fgOrNull; val k = look.scale; val on = key in this@MainActivity.open
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(text(title, 15f * k, true, fg)); addView(text(summary, 12f * k, color = fg, alpha = 0.7f).apply { maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END }) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(if (on) "\u25B4" else "\u25BE", 20f * k, color = fg, alpha = 0.6f))
    }.apply { if (look.bg != 0) setCardBackgroundColor(look.bg)
        isClickable = true; setOnClickListener { if (!this@MainActivity.open.remove(key)) this@MainActivity.open += key; render() } }
    private fun check(k: Float, size: Int) = android.widget.TextView(this).apply { text = "\u2713"; gravity = android.view.Gravity.CENTER
        textSize = size * k; setTextColor(0xFFFFFFFF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(GREEN) } }
    /** A finished setup step, collapsed to one line: green check, title, short detail. Tap opens it like the full step. */
    private fun doneRow(look: CardStyle.Look, title: String, detail: String, onClick: () -> Unit) = card(10) {
        val fg = look.fgOrNull; val k = look.scale
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        addView(check(k, 13), LinearLayout.LayoutParams(dp((24 * k).toInt()), dp((24 * k).toInt())).apply { rightMargin = dp(12) })
        addView(text(title, 14f * k, true, fg), LinearLayout.LayoutParams(-2, -2))
        addView(text("  ·  $detail", 13f * k, color = fg, alpha = 0.7f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("\u203A", 22f * k, color = fg, alpha = 0.5f))
    }.apply { if (look.bg != 0) setCardBackgroundColor(look.bg)
        isClickable = true; setOnClickListener { onClick() } }
    /** Collapsed "Setup complete" card with a green check; tap to show or hide the setup steps. */
    private fun setupCard(look: CardStyle.Look, fccIn: Boolean) = card(14) {
        val fg = look.fgOrNull; val k = look.scale
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        addView(check(k, 18),
            LinearLayout.LayoutParams(dp((34 * k).toInt()), dp((34 * k).toInt())).apply { rightMargin = dp(14) })
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(text("Setup complete", 16f * k, true, fg))
            addView(text(if (setupOpen) "Tap to hide" else "Do Not Call · your info · FCC " + (if (fccIn) "signed in" else "signs in when filing") +
                " · tap to show", 13f * k, color = fg, alpha = 0.75f)) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(if (setupOpen) "\u25B4" else "\u25BE", 20f * k, color = fg, alpha = 0.6f))
    }.apply { if (look.bg != 0) setCardBackgroundColor(look.bg)
        isClickable = true; setOnClickListener { setupOpen = !setupOpen; render() } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun export() {
        val u = Jobs.exportReport(this, store) ?: return toast("Export failed")
        toast("Saved to Downloads/SpamReporter")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share report"))
    }

    private fun confirmWipe() = MaterialAlertDialogBuilder(this).setTitle("Delete all data?")
        .setMessage("Removes your profile, imported messages and filing history from this phone, signs you out of the FCC and removes a saved FCC sign-in. Spam reports you shared with the community server are deleted there too. Reports you already filed stay with the agencies.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete") { _, _ ->
            // anything shared with the community server is removed there first (needs this phone's key, which the wipe destroys)
            if (Community.available() && Community.mayHaveShared(this)) Thread { val r = runCatching { Community.deleteMine(this) }
                runOnUiThread { if (r.isFailure) toast("Couldn't reach the community server to remove your shared reports: ${r.exceptionOrNull()?.message}"); wipeLocal() } }.start()
            else wipeLocal() }
        .show()

    private fun wipeLocal() { run { store.wipeAll(); getSharedPreferences("pace", MODE_PRIVATE).edit().clear().apply(); FccAccount.clearLocal(this); FccLogin.forget(this); Community.wipe(this); CallBlock.wipe(this); TextBlock.wipe(this); Dnc.wipe(this); setFlowPending(false); FccAccount.forgetAccount(this); toast("All data deleted"); render() } }

    private var thenSpamFolder = false

    /** One-tap scan: SMS/MMS inbox first, then the Messages Spam & blocked folder. */
    /** "Scan for new spam" walks through everything: import texts (and the spam folder, if that's on) → Choose spam senders
     *  (only if there's something new) → File reports (only if there's something to file). */
    private fun scanAll() { thenSpamFolder = true; importSms() }

    private fun flowPending() = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("scan_flow", false)
    private fun setFlowPending(on: Boolean) = getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("scan_flow", on).apply()

    /** After the scan: the next useful screen, or a short note if there's nothing to do. */
    private fun continueFlow() {
        setFlowPending(false)
        val toReview = MarkSpamActivity.reviewCount(this); val toFile = Jobs.pending(store).size
        when {
            toReview > 0 -> { toast("$toReview sender${if (toReview == 1) "" else "s"} to review"); startActivity(Intent(this, MarkSpamActivity::class.java).putExtra("flow", true)) }
            toFile > 0 -> { toast("No new senders to choose · $toFile report${if (toFile == 1) "" else "s"} ready to file"); fileReports(store.profile) }
            else -> toast("No new spam")
        }
    }

    private var importing: androidx.appcompat.app.AlertDialog? = null
    /** Spinning circle with a message, shown while something runs; can't be dismissed by tapping outside. */
    private fun busy(msg: String) = MaterialAlertDialogBuilder(this)
        .setView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(com.google.android.material.progressindicator.CircularProgressIndicator(context).apply { isIndeterminate = true })
            addView(text(msg, 16f), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(20) }) })
        .setCancelable(false).show()

    private fun importSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), 1); return }
        if (importing != null) return   // already running
        val busy = busy("Importing texts…"); importing = busy
        Thread {
            val r = runCatching { store.addMessages(SmsReader.readAll(this, store.spamSenders())) to MarkSpamActivity.reviewCount(this) }
            runOnUiThread { runCatching { busy.dismiss() }; importing = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                val (n, _) = r.getOrElse { toast("Couldn't import texts: ${it.message}"); thenSpamFolder = false; return@runOnUiThread }
                render()
                if (thenSpamFolder) { thenSpamFolder = false
                    // spam folder on: read it in Google Messages first; the flow continues when they come back (onResume)
                    if (Sources.folderScan(this) && importSpamFolder()) { toast("Imported $n new messages"); setFlowPending(true) }
                    else continueFlow()
                } else toast("Imported $n new messages") }
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

    private fun importSpamFolder(): Boolean {
        if (!a11yEnabled()) {
            MaterialAlertDialogBuilder(this).setTitle("Allow access to Messages")
                .setMessage("RCS chats aren't readable by other apps, so Spam Reporter reads your Spam & blocked folder from the screen.\n\n" +
                    "In the next screen, open Downloaded apps → Spam Reporter and turn it on. It only watches Google Messages, and only while an import is running.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
            return false
        }
        if (!MessagesAccessibilityService.connected) {
            MaterialAlertDialogBuilder(this).setTitle("Restart Spam Reporter's access")
                .setMessage("Android shows Spam Reporter's accessibility access as on, but isn't running it (this happens after an app update on some phones).\n\n" +
                    "In the next screen, open Downloaded apps → Spam Reporter, turn it off, then on again. Then come back and scan.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
            return false
        }
        MessagesAccessibilityService.armed = true
        toast("In Messages: tap your profile icon → Spam & blocked. The import starts on its own; don't touch the screen until it's done.")
        val launch = packageManager.getLaunchIntentForPackage(MessagesAccessibilityService.PKG)
        if (launch != null) { startActivity(launch); return true }
        MaterialAlertDialogBuilder(this).setTitle("Open Google Messages")
            .setMessage("Couldn't open Google Messages automatically. Open it yourself, tap your profile icon → Spam & blocked, and the import will start on its own.")
            .setPositiveButton("OK", null).show()
        return true
    }
}
