package com.kynrek.spamreporter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.kynrek.spamreporter.Ui.GREEN
import com.kynrek.spamreporter.Ui.AMBER
import com.kynrek.spamreporter.Ui.RED
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

/** Block spam numbers: calls automatically (call screening role), texts via the phone's Blocked numbers screen. Per-number undo. */
class BlockActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }
    override fun onResume() { super.onResume(); render() }
    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) { super.onActivityResult(req, res, data); render() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun borderless(label: String, click: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label; setOnClickListener { click() } }

    private fun render() {
        val me = this
        val active = CallBlock.active(me)
        val list = CallBlock.candidates(me)
        val un = CallBlock.unblocked(me)
        val nBlocked = list.count { it.d !in un }
        screen("Block spam numbers", if (active) "$nBlocked numbers blocked from calling" else "Call blocking is off") {
            add(card { addView(text("Calls: Spam Reporter can turn away calls from your spam numbers automatically, once you let it be the phone's " +
                "call screening app. Blocked calls still show in your call log.\n\nTexts: Android only lets the phone's own Messages app block texts, so " +
                "tap \"Block texts\" on a number — it's copied and the phone's Blocked numbers screen opens for you to add it.\n\n" +
                "Contacts, known numbers and anything you marked not spam are never blocked.", 13f, alpha = 0.8f)) })

            add(card {
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(text("Block calls", 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(chip(if (active) "On" else "Off", if (active) GREEN else AMBER))
                })
                if (!CallBlock.supported(me)) addView(text("This phone doesn't support call screening apps.", 13f, alpha = 0.7f), lp(top = dp(4)))
                else if (!active) addView(MaterialButton(context).apply { text = "Turn on call blocking"
                    setOnClickListener { CallBlock.requestRole(me, 7) } }, lp(top = dp(8)))
                else addView(borderless("Turn off (phone's default apps settings)") {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                    toast("Choose a different Caller ID & spam app, or none") }, lp(top = dp(4)))
                if (!active && CallBlock.supported(me)) addView(text("Android will ask to make Spam Reporter your \"caller ID & spam\" app. " +
                    "If another app has that job, it's replaced (your phone app still works normally).", 12f, alpha = 0.6f), lp(top = dp(4)))
            }, dp(8))

            fun sw(title: String, body: String, on: Boolean, set: (Boolean) -> Unit) = add(card {
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(text(title, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialSwitch(context).apply { isChecked = on; setOnCheckedChangeListener { _, v -> set(v); render() } })
                })
                addView(text(body, 12f, alpha = 0.7f), lp(top = dp(2)))
            }, dp(4))
            sw("Numbers you marked as spam", "On by default.", CallBlock.marked(me)) { CallBlock.setMarked(me, it) }
            sw("Community spam numbers", if (Community.downloadOn(me)) "Numbers other Spam Reporter users reported. Off by default."
                else "Turn on \"Use the community spam list\" in Settings first.", CallBlock.community(me) && Community.downloadOn(me)) {
                if (it && !Community.downloadOn(me)) toast("Turn on \"Use the community spam list\" in Settings → Community first")
                else CallBlock.setCommunity(me, it) }

            // ---- texts: optional bulk fill of the phone's Blocked numbers screen (Accessibility, that screen only)
            add(section("Block texts"))
            val bulkOn = Sources.bulkBlock(me); val granted = Sources.bulkBlockGranted(me)
            val textAdded = TextBlock.added(me)
            add(card {
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(text("Fill in the Blocked numbers screen for me", 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialSwitch(context).apply { isChecked = bulkOn; setOnCheckedChangeListener { _, v ->
                        Sources.setBulkBlock(me, v)
                        if (v && !Sources.bulkBlockGranted(me)) explainAccess()
                        render() } })
                })
                if (bulkOn) addView(chip(if (granted) "Accessibility access on" else "Accessibility access needed ›", if (granted) GREEN else AMBER).apply {
                    if (!granted) { isClickable = true; setOnClickListener { openAccess() } } }, LinearLayout.LayoutParams(-2, -2))
                addView(text("Optional. The phone's Blocked numbers list stops both calls and texts, but only the phone's own apps can add to it. " +
                    "With this on, Spam Reporter opens that screen and types each number in for you, so you can block them all at once — " +
                    "and undo any one. It needs Accessibility access, which Android uses for apps that tap buttons on screen; Spam Reporter's " +
                    "is limited to that one Blocked numbers screen and only acts while a job you started is running. " +
                    "Off: use \"Block texts\" on each number and add it yourself.", 12f, alpha = 0.7f), lp(top = dp(4)))
                if (bulkOn && granted) {
                    val todo = list.map { it.d }.filter { it !in un && it !in textAdded }
                    addView(MaterialButton(context).apply { text = if (todo.isEmpty()) "All numbers are blocked for texts" else "Block texts for all ${todo.size} numbers"
                        isEnabled = todo.isNotEmpty(); setOnClickListener { runTextJob(add = todo) } }, lp(top = dp(8)))
                }
                TextBlock.status.takeIf { it.isNotBlank() }?.let { addView(text(it, 12f, alpha = 0.7f), lp(top = dp(4))) }
            }, dp(8))

            val log = CallBlock.log(me)
            if (log.isNotEmpty()) {
                add(section("Recently blocked calls"))
                val f = SimpleDateFormat("MMM d, h:mm a", Locale.US)
                add(card { for ((d, ts) in log.takeLast(10).reversed()) addView(text("${Phone.dashed(d)} · ${f.format(Date(ts))}", 13f)) })
            }

            add(section("Numbers (${list.size})"))
            if (list.isEmpty()) add(text("No numbers yet — mark spam senders on \"Choose spam senders\".", 13f, alpha = 0.7f))
            for (e in list) {
                val isBlocked = e.d !in un
                add(card(12) {
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(text(Phone.dashed(e.d), 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
                        if (e.d in textAdded) addView(chip("Texts blocked", RED), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
                        addView(chip(if (isBlocked) (if (active) "Calls blocked" else "Will block") else "Allowed", if (isBlocked) RED else GREEN))
                    })
                    addView(text(listOfNotNull("You marked spam".takeIf { e.fromMarked }, "Community".takeIf { e.fromCommunity }).joinToString(" · "),
                        12f, alpha = 0.6f))
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                        addView(borderless(if (isBlocked) "Undo (allow)" else "Block again") {
                            CallBlock.setUnblocked(me, e.d, isBlocked); render() })
                        if (e.d in textAdded) addView(borderless("Unblock texts") {
                            if (Sources.bulkBlock(me) && Sources.bulkBlockGranted(me)) runTextJob(remove = listOf(e.d))
                            else { TextBlock.mark(me, e.d, false); openBlockedScreen(); toast("Tap the X next to ${Phone.dashed(e.d)} to unblock it") } })
                        else addView(borderless("Block texts") {
                            if (Sources.bulkBlock(me) && Sources.bulkBlockGranted(me)) runTextJob(add = listOf(e.d)) else blockTexts(e.d) })
                    })
                }, dp(6))
            }
        }
    }

    private fun openBlockedScreen() = runCatching { startActivity(getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }.isSuccess

    /** Hand a job to the Accessibility helper and open the phone's Blocked numbers screen; it comes back here when done. */
    private fun runTextJob(add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        TextBlock.toAdd = add; TextBlock.toRemove = remove; TextBlock.status = "Working…"
        if (!openBlockedScreen()) { TextBlock.toAdd = emptyList(); TextBlock.toRemove = emptyList(); toast("Couldn't open the phone's Blocked numbers screen") }
    }

    private fun openAccess() { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        toast("Tap Downloaded apps → \"Spam Reporter: block numbers\" → turn it on") }

    private fun explainAccess() = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("Allow Accessibility access?")
        .setMessage("This is optional. To block texts in bulk, Spam Reporter types your spam numbers into the phone's own Blocked numbers " +
            "screen for you. Android requires Accessibility access for that.\n\nSpam Reporter's access is limited to that one screen and only " +
            "acts while a job you started is running. Without it, you can still block each number yourself.")
        .setNegativeButton("Not now", null).setPositiveButton("Open settings") { _, _ -> openAccess() }.show()

    /** Copy the number and open the phone's own Blocked numbers screen (only the phone/SMS app may add to it). */
    private fun blockTexts(d: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", d))
        val ok = runCatching { startActivity(getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }.isSuccess
        toast(if (ok) "${Phone.dashed(d)} copied — tap Add, paste it and block. To undo, remove it there."
              else "${Phone.dashed(d)} copied — open your Phone or Messages app → Settings → Blocked numbers to add it.")
    }
}
