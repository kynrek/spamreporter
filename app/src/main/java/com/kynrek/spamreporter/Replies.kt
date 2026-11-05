package com.kynrek.spamreporter

import android.content.Context
import android.net.Uri
import android.provider.Telephony

/**
 * Numbers you've replied to (sent SMS/MMS, read with the existing READ_SMS permission). Replies that are only an
 * opt-out keyword ("STOP", "unsubscribe", ...) don't count. Optional setting: treat these numbers as not spam.
 */
object Replies {
    private val OPT_OUT = setOf("STOP", "STOPALL", "STOP ALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT", "OPTOUT", "OPT OUT",
        "REVOKE", "REMOVE", "STOP PLEASE", "PLEASE STOP", "STOP TEXTING", "STOP TEXTING ME", "UNSUB", "NO", "N")
    fun isOptOut(body: String) = body.uppercase().replace(Regex("[^A-Z ]"), " ").replace(Regex("\\s+"), " ").trim() in OPT_OUT

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("replied_not_spam", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("replied_not_spam", on).apply()

    /** Numbers treated as not spam because you replied (empty when the setting is off). */
    fun numbers(c: Context): Set<String> = if (enabled(c)) responded(c) else emptySet()

    /** Ten-digit numbers you've sent at least one real (non opt-out) text to. */
    fun responded(c: Context): Set<String> {
        val out = HashSet<String>()
        runCatching {
            c.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                "${Telephony.Sms.TYPE}=${Telephony.Sms.MESSAGE_TYPE_SENT}", null, null)?.use { cur ->
                while (cur.moveToNext()) { val d = Phone.digits(cur.getString(0) ?: continue)
                    if (d.length == 10 && d !in out && !isOptOut(cur.getString(1) ?: "")) out += d }
            }
        }
        runCatching {
            val cr = c.contentResolver
            cr.query(Telephony.Mms.Sent.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, null)?.use { m ->
                while (m.moveToNext()) {
                    val id = m.getLong(0)
                    val to = mutableListOf<String>()   // recipients = addr rows with type 151 (PduHeaders.TO)
                    cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type=151", null, null)?.use { a ->
                        while (a.moveToNext()) Phone.digits(a.getString(0) ?: "").takeIf { it.length == 10 }?.let(to::add) }
                    if (to.all { it in out }) continue
                    val text = StringBuilder()
                    cr.query(Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use { p ->
                        while (p.moveToNext()) if (p.getString(0) == "text/plain") p.getString(1)?.let { text.append(it).append(' ') } }
                    if (!isOptOut(text.toString())) out += to
                }
            }
        }
        return out
    }
}
