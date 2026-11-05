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
                        addView(chip(if (isBlocked) (if (active) "Calls blocked" else "Will block") else "Allowed", if (isBlocked) RED else GREEN))
                    })
                    addView(text(listOfNotNull("You marked spam".takeIf { e.fromMarked }, "Community".takeIf { e.fromCommunity }).joinToString(" · "),
                        12f, alpha = 0.6f))
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                        addView(borderless(if (isBlocked) "Undo (allow)" else "Block again") {
                            CallBlock.setUnblocked(me, e.d, isBlocked); render() })
                        addView(borderless("Block texts") { blockTexts(e.d) })
                    })
                }, dp(6))
            }
        }
    }

    /** Copy the number and open the phone's own Blocked numbers screen (only the phone/SMS app may add to it). */
    private fun blockTexts(d: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", d))
        val ok = runCatching { startActivity(getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }.isSuccess
        toast(if (ok) "${Phone.dashed(d)} copied — tap Add, paste it and block. To undo, remove it there."
              else "${Phone.dashed(d)} copied — open your Phone or Messages app → Settings → Blocked numbers to add it.")
    }
}
