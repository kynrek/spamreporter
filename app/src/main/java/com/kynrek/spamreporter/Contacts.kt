package com.kynrek.spamreporter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Optional: treat everyone in the phone's contacts as "not spam". Contacts are only read on the phone to
 * compare phone numbers; nothing from them is stored or sent anywhere.
 */
object Contacts {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("contacts_not_spam", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("contacts_not_spam", on).apply()
    fun granted(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Ten-digit numbers of all contacts (empty when the setting is off or access wasn't given). */
    fun numbers(c: Context): Set<String> {
        if (!enabled(c) || !granted(c)) return emptySet()
        val out = HashSet<String>()
        runCatching {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cur ->
                while (cur.moveToNext()) cur.getString(0)?.let { Phone.digits(it) }?.takeIf { it.length == 10 }?.let(out::add)
            }
        }
        return out
    }
}
