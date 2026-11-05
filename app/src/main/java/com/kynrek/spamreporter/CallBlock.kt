package com.kynrek.spamreporter

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Optional call blocking. Android lets only the default phone/SMS app write the phone's own blocked-numbers list, so
 * calls are blocked here as the phone's "call screening" app (a role the user grants), and texts are blocked by the user
 * in the phone's Blocked numbers screen (the app opens it with the number copied).
 * Contacts, known numbers and anything you marked "not spam" are never blocked.
 */
object CallBlock {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun marked(c: Context) = p(c).getBoolean("block_marked", true)
    fun community(c: Context) = p(c).getBoolean("block_community", false)
    fun setMarked(c: Context, on: Boolean) = p(c).edit().putBoolean("block_marked", on).apply()
    fun setCommunity(c: Context, on: Boolean) = p(c).edit().putBoolean("block_community", on).apply()

    /** Is this app the phone's call screening app (so blocking is active)? */
    fun active(c: Context) = Build.VERSION.SDK_INT >= 29 && c.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
    fun supported(c: Context) = Build.VERSION.SDK_INT >= 29 && c.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
    /** Shows Android's own "set as call screening app" prompt; the user decides. */
    fun requestRole(a: Activity, code: Int) {
        if (Build.VERSION.SDK_INT >= 29) a.startActivityForResult(a.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), code)
    }

    // per-number undo: numbers the user un-blocked stay allowed until blocked again
    private fun exFile(c: Context) = File(c.filesDir, "unblocked.json")
    fun unblocked(c: Context): MutableSet<String> = runCatching { JSONArray(exFile(c).readText()).let { a -> (0 until a.length()).map(a::getString) } }
        .getOrDefault(emptyList()).toMutableSet()
    @Synchronized fun setUnblocked(c: Context, d: String, unblock: Boolean) {
        val s = unblocked(c); if (if (unblock) s.add(d) else s.remove(d)) exFile(c).writeText(JSONArray(s.toList()).toString())
    }

    data class Entry(val d: String, val fromMarked: Boolean, val fromCommunity: Boolean)
    /** Every candidate number (blocked or un-blocked by the user), with where it came from. */
    fun candidates(c: Context): List<Entry> {
        val s = Store(c); val notSpam = s.notSpam()
        val mine = if (marked(c)) s.spamSenders().map(Phone::digits).toSet() else emptySet()
        val comm = if (community(c)) Community.numbers(c).keys else emptySet()
        return (mine + comm).filter { it.length >= 5 && it !in notSpam }.sorted().map { Entry(it, it in mine, it in comm) }
    }
    fun blockedSet(c: Context): Set<String> = candidates(c).map { it.d }.toSet() - unblocked(c)

    // log of calls that were blocked (number + time), shown on the screen
    private fun logFile(c: Context) = File(c.filesDir, "blocked_calls.json")
    fun log(c: Context): List<Pair<String, Long>> = runCatching { JSONArray(logFile(c).readText()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("d") to o.getLong("ts") } } } }.getOrDefault(emptyList())
    @Synchronized fun addLog(c: Context, d: String) {
        val l = (log(c) + (d to System.currentTimeMillis())).takeLast(200)
        logFile(c).writeText(JSONArray(l.map { JSONObject().put("d", it.first).put("ts", it.second) }).toString())
    }
    fun wipe(c: Context) { exFile(c).delete(); logFile(c).delete() }
}

/** Android calls this for each incoming call while Spam Reporter is the call screening app. Must answer within ~5 s. */
class SpamCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        val d = Phone.digits(details.handle?.schemeSpecificPart ?: "")
        val block = incoming && d.isNotEmpty() && runCatching { d in CallBlock.blockedSet(this) }.getOrDefault(false)
        val r = CallResponse.Builder()
        if (block) { r.setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(false); runCatching { CallBlock.addLog(this, d) } }
        respondToCall(details, r.build())
    }
}
