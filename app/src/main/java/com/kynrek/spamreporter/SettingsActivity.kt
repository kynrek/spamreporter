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

            add(section("Spam"))
            val sp = getSharedPreferences("settings", MODE_PRIVATE)
            add(toggle("Remember spam senders",
                "When a number you've marked spam texts again, it comes back under To review already ticked, and its new texts " +
                "are reported. Off: it comes back unticked, and new texts aren't reported until you tick it again.",
                sp.getBoolean("remember_spam", true), null) { on -> sp.edit().putBoolean("remember_spam", on).apply() })

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
            add(toggle("Texts you replied to are not spam",
                "If you've sent a text to a number, its texts are treated as not spam (tagged \"You responded\"). Replies that are " +
                "only STOP, unsubscribe, cancel and the like don't count. Ticking or swiping left on one still marks it spam.",
                Replies.enabled(this@SettingsActivity), null) { on -> Replies.setEnabled(this@SettingsActivity, on) })
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Known numbers (${Store(this@SettingsActivity).knownNumbers().size})"
                setOnClickListener { startActivity(Intent(context, KnownNumbersActivity::class.java)) } })
            add(text("Numbers you swiped right on (\"not spam\") under Choose spam senders. Their future texts are never suggested or reported.",
                12f, alpha = 0.6f), dp(4))

            add(section("Card style"))
            for (scr in CardStyle.Screen.values()) add(styleEditor(scr), dp(8))

            add(section("History"))
            add(card {
                addView(text("Group history by", 16f, true))
                addView(text("How senders under Choose spam senders → History are grouped, by their latest text.", 13f, alpha = 0.7f), lp(top = dp(4)))
                addView(periodPicker(HistoryPrefs.grouping(this@SettingsActivity)) { HistoryPrefs.setGrouping(this@SettingsActivity, it) }, lp(top = dp(8)))
            }, dp(8))
            add(card {
                val unit = HistoryPrefs.cutoffUnit(this@SettingsActivity); val n = HistoryPrefs.cutoffCount(this@SettingsActivity)
                addView(text("Show history from the last", 16f, true))
                addView(text("Senders with no texts in this time are hidden from History (they're still filed if marked spam). " +
                    "None shows everything. Now: ${HistoryPrefs.cutoffLabel(this@SettingsActivity)}.", 13f, alpha = 0.7f), lp(top = dp(4)))
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    val step = { d: Int -> MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = if (d < 0) "−" else "+"; isEnabled = unit != HistoryPrefs.Period.NONE
                        setOnClickListener { HistoryPrefs.setCutoff(this@SettingsActivity, unit, n + d); render() } } }
                    addView(step(-1)); addView(text(if (unit == HistoryPrefs.Period.NONE) "–" else "$n", 20f, true).apply { gravity = android.view.Gravity.CENTER },
                        LinearLayout.LayoutParams(dp(64), -2)); addView(step(1))
                }, lp(top = dp(8)))
                addView(periodPicker(unit) { HistoryPrefs.setCutoff(this@SettingsActivity, it, n); render() }, lp(top = dp(4)))
            }, dp(8))

            add(section("Community (optional)"))
            if (!Community.available()) add(card { addView(text("Community sharing isn't available in this build.", 13f, alpha = 0.7f)) }, dp(8))
            else {
                val me = this@SettingsActivity
                add(toggle("Share spam with the community", "Off by default. Sends the spam you marked (sender number, date and time" +
                    (if (Community.shareBody(me)) ", and the text" else "") + ") to the Spam Reporter community list at ${Community.HOST}, " +
                    "so other users can spot and report the same spammers. Your name and number are NOT sent unless you turn them on below.",
                    Community.shareOn(me), if (Community.shareOn(me)) "On" to GREEN else null) { on ->
                    if (!on) { Community.set(me, "community_share", false); render() }
                    else MaterialAlertDialogBuilder(me).setTitle("Share spam with the community?")
                        .setMessage("Spam Reporter will send the spam texts you mark — the sender's number, when it arrived" +
                            (if (Community.shareBody(me)) " and the message text" else "") + " — to ${Community.HOST}.\n\n" +
                            "Your phone is identified only by a random app ID, not your name or number. Sharing is optional; you can turn it off any time.")
                        .setNegativeButton("Cancel") { _, _ -> render() }.setOnCancelListener { render() }
                        .setPositiveButton("Share") { _, _ -> Community.set(me, "community_share", true); render() }.show()
                })
                if (Community.shareOn(me)) {
                    fun sensitive(key: String, title: String, body: String, on: Boolean, what: String) = add(toggle(title, body, on, null) { v ->
                        if (!v) { Community.set(me, key, false); render() }
                        else MaterialAlertDialogBuilder(me).setTitle("Share $what?")
                            .setMessage("You're choosing to share $what with the community to help others fight spam. This is optional — " +
                                "spam reports are just as useful without it, and you can turn it off any time.")
                            .setNegativeButton("Don't share") { _, _ -> render() }.setOnCancelListener { render() }
                            .setPositiveButton("Share") { _, _ -> Community.set(me, key, true); render() }.show()
                    }, dp(4))
                    add(toggle("Include message text", "Helps others recognise the same scam. Texts can contain your name or other details — turn off to share numbers and times only.",
                        Community.shareBody(me), null) { v -> Community.set(me, "community_share_body", v); render() }, dp(4))
                    sensitive("community_share_to", "Include the number that got the texts", "Your number from \"Your info\". Off by default.",
                        Community.shareTo(me), "the number that received the spam")
                    sensitive("community_share_name", "Include your name", "Off by default.", Community.shareName(me), "your name")
                    sensitive("community_share_phone", "Include your contact phone", "So the community admin could reach you. Off by default.",
                        Community.sharePhone(me), "your phone number")
                }
                add(toggle("Use the community spam list", "Off by default. Downloads numbers other people reported and tags them " +
                    "\"Community\" on Choose spam senders. A hint only — nothing is marked or reported for you.",
                    Community.downloadOn(me), null) { v -> Community.set(me, "community_download", v); render() }, dp(4))
                if (Community.shareOn(me) || Community.downloadOn(me)) add(card {
                    val last = Community.lastSync(me)
                    val st = text("Waiting to share: ${Community.pending(me).size} · " + (if (last == 0L) "never synced" else
                        "last synced ${java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(last))}") +
                        "\nApp ID: ${Community.installId()} (random, not linked to you)", 13f, alpha = 0.75f)
                    addView(st)
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                        addView(MaterialButton(context).apply { text = "Sync now"; setOnClickListener { b -> b.isEnabled = false; st.text = "Syncing…"
                            Thread { val r = runCatching { Community.syncNow(me) }
                                runOnUiThread { toast(r.getOrElse { "Sync failed: ${it.message}" }); render() } }.start() } })
                        addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Reset app ID"
                            setOnClickListener { Community.resetId(); toast("New app ID made"); render() } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                    }, lp(top = dp(8)))
                }, dp(8))
            }

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

            add(section("FCC sign-in"))
            add(toggle("Save FCC password",
                "After you sign in, offer to save your FCC email and password on this phone, encrypted and unlocked only with your " +
                "fingerprint or PIN. Turning this off removes a saved password.",
                FccLogin.saveEnabled(this@SettingsActivity), if (FccLogin.saved(this@SettingsActivity)) "Saved" to GREEN else null) { on ->
                FccLogin.setSaveEnabled(this@SettingsActivity, on); render() })
            add(toggle("Sign in with saved password",
                "With a saved password, the FCC sign-in button becomes \"Sign in again with saved password\": unlock with your " +
                "fingerprint and it fills and submits the FCC form for you. Off: it only fills the form; you tap Sign in.",
                FccLogin.autoEnabled(this@SettingsActivity), null) { on -> FccLogin.setAutoEnabled(this@SettingsActivity, on) })
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = when (FccLogin.lock(this@SettingsActivity)) { FccLogin.Lock.FINGERPRINT -> "Fingerprint: on"
                    FccLogin.Lock.PIN_ONLY -> "Fingerprint: not set (PIN works) — add one"; FccLogin.Lock.NONE -> "Set up fingerprint or PIN (needed to save)" }
                setOnClickListener { FccLogin.explainLock(this@SettingsActivity) } }, dp(4))

            add(section("Account"))
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "FCC account"; setOnClickListener { startActivity(Intent(context, FccSignInActivity::class.java)) } })
            add(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Your info & attestation"; setOnClickListener { startActivity(Intent(context, ProfileActivity::class.java)) } })
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); render() }

    /** Background, text color and text size for one screen's cards, with a live preview. */
    private fun styleEditor(scr: CardStyle.Screen) = card {
        val ctx = this@SettingsActivity; val look = CardStyle.look(ctx, scr)
        addView(text(scr.label, 16f, true))
        addView(text("Background", 13f, alpha = 0.7f), lp(top = dp(8)))
        addView(swatches(CardStyle.backgrounds, look.bg) { CardStyle.setBackground(ctx, scr, it); render() })
        addView(text("Text color", 13f, alpha = 0.7f), lp(top = dp(8)))
        addView(swatches(CardStyle.texts, look.fg) { CardStyle.setText(ctx, scr, it); render() })
        addView(text("Text size", 13f, alpha = 0.7f), lp(top = dp(8)))
        addView(com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true; isSelectionRequired = true
            val ids = CardStyle.sizes.entries.associateBy { android.view.View.generateViewId() }
            for ((id, e) in ids) addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.id = id; text = e.key; textSize = 11f; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            check(ids.entries.minByOrNull { kotlin.math.abs(it.value.value - look.scale) }!!.key)
            addOnButtonCheckedListener { _, id, on -> if (on) ids[id]?.let { CardStyle.setScale(ctx, scr, it.value); render() } }
        })
        addView(com.google.android.material.card.MaterialCardView(context).apply {
            if (look.bg != 0) setCardBackgroundColor(look.bg)
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(text(if (scr == CardStyle.Screen.HOME) "Choose spam senders" else "555-123-4567", 16f * look.scale, true, look.fgOrNull))
                addView(text("Preview of a card on this screen", 13f * look.scale, color = look.fgOrNull, alpha = 0.8f)) })
        }, lp(top = dp(12)))
    }

    /** Row of named color chips; the selected one is checked. */
    private fun swatches(options: Map<String, Int>, sel: Int, onPick: (Int) -> Unit) = com.google.android.material.chip.ChipGroup(this).apply {
        isSingleSelection = true; isSelectionRequired = true
        for ((name, color) in options) addView(com.google.android.material.chip.Chip(context).apply {
            text = name; isCheckable = true; isChecked = color == sel; id = android.view.View.generateViewId()
            if (color != 0) { chipIcon = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color); setStroke(dp(1), 0xFF888888.toInt()); setSize(dp(18), dp(18)) }; isChipIconVisible = true }
            setOnClickListener { if (color != sel) onPick(color) }
        })
    }

    /** None / Day / Week / Month / Year buttons. */
    private fun periodPicker(sel: HistoryPrefs.Period, onPick: (HistoryPrefs.Period) -> Unit) =
        com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            val ids = HistoryPrefs.Period.values().associateBy { android.view.View.generateViewId() }
            for ((id, p) in ids) addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.id = id; text = p.label; textSize = 12f; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            check(ids.entries.first { it.value == sel }.key)
            addOnButtonCheckedListener { _, id, on -> if (on) ids[id]?.let(onPick) }
        }

    private fun toggle(title: String, body: String, on: Boolean, status: Pair<String, Int>?, onChange: (Boolean) -> Unit) = card {
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text(title, 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialSwitch(context).apply { isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } })
        })
        status?.let { (l, c) -> addView(chip(l, c), LinearLayout.LayoutParams(-2, -2)) }
        addView(text(body, 13f, alpha = 0.7f), lp(top = dp(4)))
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun askFor(title: String, msg: String, action: String) = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
        .setNegativeButton("Later", null).setPositiveButton("Open settings") { _, _ -> startActivity(Intent(action)) }.show()
}
