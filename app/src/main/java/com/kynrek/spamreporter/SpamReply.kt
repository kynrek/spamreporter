package com.kynrek.spamreporter

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kynrek.spamreporter.Ui.dp
import org.json.JSONObject

/**
 * "Reply" to a spam sender: a text saying your number is on the Do Not Call list (and since when), that their texts
 * break the federal Do Not Call rules, and the FCC ticket / FTC report numbers you filed.
 *
 * Settings → "Reply to spammers" picks how it's sent:
 *  - ASK (default): the Reply button shows the draft; you can edit it, then Send, Copy or open it in Messages.
 *  - AUTO: additionally sent automatically once every report for the sender's newest texts has its number.
 *  - COPY: the app never sends; the draft can only be copied or opened in Messages (no SMS permission needed).
 *
 * Political and survey texts aren't Do Not Call violations, so their draft doesn't claim one.
 */
object SpamReply {
    enum class Mode(val label: String) { ASK("Ask me"), AUTO("Send automatically"), COPY("Copy only") }

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun mode(c: Context) = runCatching { Mode.valueOf(p(c).getString("reply_mode", null)!!) }.getOrDefault(Mode.ASK)
    fun setMode(c: Context, m: Mode) = p(c).edit().putString("reply_mode", m.name).apply()

    fun canSend(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    // ---- replies already sent: digits -> {"at": when sent, "upTo": report cutoff it was about}
    private fun sent(c: Context) = runCatching { JSONObject(p(c).getString("replies_sent", "{}")!!) }.getOrDefault(JSONObject())
    fun sentAt(c: Context, digits: String) = sent(c).optJSONObject(digits)?.optLong("at") ?: 0L
    private fun sentUpTo(c: Context, digits: String) = sent(c).optJSONObject(digits)?.optLong("upTo") ?: 0L
    private fun recordSent(c: Context, digits: String, upTo: Long) =
        p(c).edit().putString("replies_sent", sent(c).put(digits, JSONObject().put("at", System.currentTimeMillis()).put("upTo", upTo)).toString()).apply()

    /** One SMS: 160 GSM-7 characters (the basic set counts 1, ^{}[]~|\\€ count 2; anything else forces 70-char UCS-2). */
    const val ONE_SMS = 160
    private const val GSM = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXT = "^{}\\[~]|€"
    /** Length in GSM-7 units, or -1 if the text needs UCS-2 (then one SMS holds only 70). */
    fun gsmLength(t: String): Int { var n = 0; for (ch in t) n += when { ch in GSM -> 1; ch in GSM_EXT -> 2; else -> return -1 }; return n }
    fun fitsOneSms(t: String) = gsmLength(t).let { it in 0..ONE_SMS }

    /** Our own reply text, so it doesn't count as "you replied to this number" (which would make it not spam). */
    fun isOurs(body: String) = listOf("Do Not Call Registry", "Do not text this number again", "Stop texting me", "Filed: FCC")
        .any { body.contains(it, true) }

    /** The sender's successful reports, newest first. */
    private fun reports(store: Store, digits: String) =
        store.filings().filter { it.ok && Phone.digits(it.sender) == digits }.sortedByDescending { it.filedAt }

    /**
     * The reply, kept to one SMS (160 characters): the most detailed wording that fits is used. Wordings get shorter
     * step by step (full sentence, then short citations, then only the newest report number per agency).
     */
    fun draft(c: Context, digits: String): String {
        val store = Store(c); val prof = store.profile; val fs = reports(store, digits)
        val since = prof.dncRegisteredOn.trim()
        val onList = since.isNotEmpty() || Dnc.done(c)
        val commercial = fs.any { it.agency == Agency.FTC } ||
            (store.categories()[digits] ?: store.categories()[Phone.dashed(digits)]).let { it == null || it == Category.COMMERCIAL }

        // report numbers: every report, or just the newest per agency when space is short
        fun nums(a: Agency, newestOnly: Boolean): String? {
            val mine = fs.filter { it.agency == a }.ifEmpty { return null }
            val ns = (if (newestOnly) mine.take(1) else mine).mapNotNull { it.confirmation }.distinct()
            return if (ns.isEmpty()) "$a # pending" else "$a " + ns.joinToString(", ") { "#$it" }
        }
        fun filed(newestOnly: Boolean) = listOfNotNull(nums(Agency.FCC, newestOnly), nums(Agency.FTC, newestOnly))
            .joinToString(", ").let { if (it.isEmpty()) "" else " Filed: $it." }

        val candidates = mutableListOf<String>()
        for (newestOnly in listOf(false, true)) {
            val r = filed(newestOnly)
            if (commercial && onList) {
                val d = if (since.isNotEmpty()) " since $since" else ""
                candidates += "This number has been on the National Do Not Call Registry$d. Your texts violate the TCPA (47 USC 227(c)) and 47 CFR 64.1200(c)(2).$r Stop texting me."
                candidates += "On the Do Not Call Registry$d. Your texts violate 47 USC 227(c) & 47 CFR 64.1200(c)(2).$r Stop texting me."
                candidates += "On the Do Not Call Registry$d. Your texts violate 47 USC 227(c).$r Stop texting me."
                if (d.isNotEmpty()) candidates += "On the Do Not Call Registry. Your texts violate 47 USC 227(c).$r Stop texting me."
                candidates += "On Do Not Call Registry. Texts violate 47 USC 227(c).$r Stop."
            } else {
                candidates += "I never agreed to receive text messages from you.$r Stop texting me."
                candidates += "I never agreed to get texts from you.$r Stop."
            }
        }
        return candidates.firstOrNull(::fitsOneSms) ?: candidates.last()
    }

    /** Reply button: editable draft with Send (unless Copy only), Copy, and Open in Messages. */
    fun show(a: Activity, sender: String, onDone: () -> Unit = {}) {
        val digits = Phone.digits(sender)
        if (!Phone.isTenDigit(sender)) { Toast.makeText(a, "Can't reply to a short code or name sender", Toast.LENGTH_LONG).show(); return }
        val input = EditText(a).apply { setText(draft(a, digits)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; minLines = 4; textSize = 15f }
        val count = android.widget.TextView(a).apply { textSize = 12f; setPadding(0, a.dp(4), 0, 0) }
        fun recount() { val t = input.text.toString()
            // Android's own count: [texts, units used, units left, unit size (1 = GSM-7, 3 = UCS-2)]
            val r = android.telephony.SmsMessage.calculateLength(t, false)
            val limit = if (r[3] == android.telephony.SmsMessage.ENCODING_16BIT) 70 else ONE_SMS
            count.text = if (r[0] <= 1) "${r[1]} / $limit characters · fits in one text"
                else "${r[1]} characters · sends as ${r[0]} texts (one text holds $limit" +
                    (if (limit == 70) "; an emoji or special character cut it from 160" else "") + ")"
            count.setTextColor(if (r[0] <= 1) Ui.GREEN else Ui.RED) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = recount() })
        recount()
        val prev = sentAt(a, digits)
        val b = MaterialAlertDialogBuilder(a)
            .setTitle("Reply to ${Phone.dashed(digits)}")
            .setMessage((if (prev > 0) "You already replied on ${HistoryReport.when_.format(java.util.Date(prev))}. " else "") +
                "Edit the text if you like. Replying tells the sender your number is active, and spam numbers are often faked, so the reply may reach someone else.")
            .setView(LinearLayout(a).apply { setPadding(a.dp(20), a.dp(8), a.dp(20), 0); orientation = LinearLayout.VERTICAL
                addView(input, LinearLayout.LayoutParams(-1, -2)); addView(count) })
            .setNegativeButton("Copy") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Reply", input.text))
                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show() }
            .setNeutralButton("Open in Messages") { _, _ ->
                Store(a).setSpamOverride(digits, true)   // a reply you send doesn't make them "not spam"
                runCatching { a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).putExtra("sms_body", input.text.toString())) }
                    .onFailure { Toast.makeText(a, "No messaging app found", Toast.LENGTH_LONG).show() } }
        if (mode(a) != Mode.COPY) b.setPositiveButton("Send") { _, _ ->
            if (!canSend(a)) { pending = digits to input.text.toString(); a.requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ); return@setPositiveButton }
            send(a, digits, input.text.toString()); onDone() }
        b.show()
    }

    const val REQ = 4711
    private var pending: Pair<String, String>? = null
    /** Call from the activity's onRequestPermissionsResult: sends the reply that was waiting for the permission. */
    fun onPermission(a: Activity, rc: Int, onDone: () -> Unit) {
        if (rc != REQ) return
        val (d, t) = pending ?: return; pending = null
        if (canSend(a)) { send(a, d, t); onDone() }
        else Toast.makeText(a, "SMS permission not given. Use Copy or Open in Messages instead.", Toast.LENGTH_LONG).show()
    }

    private fun send(c: Context, digits: String, text: String): Boolean {
        val ok = runCatching {
            val sms = c.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            if (parts.size == 1) sms.sendTextMessage(digits, null, text, null, null)
            else sms.sendMultipartTextMessage(digits, null, parts, null, null)
        }.isSuccess
        if (ok) { Store(c).setSpamOverride(digits, true)
            recordSent(c, digits, reports(Store(c), digits).maxOfOrNull { it.cutoff } ?: System.currentTimeMillis()) }
        if (c is Activity) Toast.makeText(c, if (ok) "Reply sent to ${Phone.dashed(digits)}" else "Couldn't send the reply", Toast.LENGTH_LONG).show()
        return ok
    }

    /**
     * "Send automatically": after a report is filed or an FCC ticket number arrives. Sends once per batch of reported
     * texts, and only when every report about the sender's newest texts has its number. Returns true if it sent.
     */
    fun autoSend(c: Context, sender: String): Boolean {
        val digits = Phone.digits(sender)
        if (mode(c) != Mode.AUTO || !Phone.isTenDigit(sender) || !canSend(c)) return false
        val fs = reports(Store(c), digits).ifEmpty { return false }
        val newest = fs.maxOf { it.cutoff / 60_000 }
        val batch = fs.filter { it.cutoff / 60_000 == newest }
        if (batch.any { it.confirmation == null } || sentUpTo(c, digits) / 60_000 >= newest) return false
        val text = draft(c, digits)
        if (!fitsOneSms(text)) return false   // too many report numbers for one text: leave it for the Reply button
        return send(c, digits, text)
    }
}
