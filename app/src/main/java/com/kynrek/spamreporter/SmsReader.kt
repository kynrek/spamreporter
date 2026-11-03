package com.kynrek.spamreporter

import android.content.Context
import android.net.Uri
import android.provider.Telephony

/** Reads inbound SMS and MMS from the system provider (requires READ_SMS). RCS is NOT in here. */
object SmsReader {
    fun readAll(ctx: Context, spamSenders: Set<String>): List<Msg> = readSms(ctx, spamSenders) + readMms(ctx, spamSenders)

    private fun flagged(addr: String, spam: Set<String>) = spam.any { Phone.digits(it) == Phone.digits(addr) }

    private fun readSms(ctx: Context, spam: Set<String>): List<Msg> {
        val out = mutableListOf<Msg>()
        ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                out += Msg(addr, c.getLong(1), c.getString(2) ?: "", Source.SMS, flagged(addr, spam))
            }
        }
        return out
    }

    private fun readMms(ctx: Context, spam: Set<String>): List<Msg> {
        val out = mutableListOf<Msg>()
        val cr = ctx.contentResolver
        cr.query(Telephony.Mms.Inbox.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val ts = c.getLong(1) * 1000
                // sender = addr row with type 137 (PduHeaders.FROM)
                var from: String? = null
                cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type=137", null, null)?.use { a ->
                    if (a.moveToFirst()) from = a.getString(0)
                }
                val text = StringBuilder()
                cr.query(Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use { p ->
                    while (p.moveToNext()) if (p.getString(0) == "text/plain") p.getString(1)?.let { text.append(it).append('\n') }
                }
                val f = from ?: continue
                out += Msg(f, ts, text.toString().trim(), Source.MMS, flagged(f, spam))
            }
        }
        return out
    }
}
