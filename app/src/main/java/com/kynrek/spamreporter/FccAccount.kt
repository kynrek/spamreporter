package com.kynrek.spamreporter

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.text.SimpleDateFormat
import java.util.*

/**
 * Optional FCC account session. The user signs in on the FCC's own page inside the app's WebView; the app never
 * sees or stores the password. The session cookie lives only in the WebView's private cookie store, which is
 * excluded from backups and never read or sent anywhere by the app.
 *
 * The FCC (Zendesk) ends the session about 8 hours after sign-in. We record when the user signed in so the UI can
 * say when it will end, and we verify the real state by loading "My requests" before relying on it.
 */
object FccAccount {
    const val SESSION_HOURS = 8
    const val HOST = "https://consumercomplaints.fcc.gov"
    const val SIGN_IN_URL = "$HOST/auth/v3/signin?brand_id=9210&locale=en-us&role=end_user&return_to=https%3A%2F%2Fconsumercomplaints.fcc.gov%2Fhc%2Fen-us%2Frequests"
    const val MY_REQUESTS = "$HOST/hc/en-us/requests"
    const val SIGN_OUT_URL = "$HOST/access/logout"

    private fun prefs(c: Context) = c.getSharedPreferences("fcc_account", Context.MODE_PRIVATE)

    fun signedInAt(c: Context) = prefs(c).getLong("at", 0)
    fun expiresAt(c: Context) = signedInAt(c).let { if (it == 0L) 0L else it + SESSION_HOURS * 3_600_000L }
    /** Best estimate without a network call; FilingActivity / FccSignInActivity verify for real. */
    fun likelySignedIn(c: Context) = expiresAt(c) > System.currentTimeMillis()

    fun markSignedIn(c: Context) { if (!likelySignedIn(c)) prefs(c).edit().putLong("at", System.currentTimeMillis()).apply()
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("fcc_had_account", true).apply() }
    /** This phone has an FCC account: it signed in before, or a sign-in is saved. Then "signed out" means "sign back in",
     *  not "consider making an account". Kept across sign-outs; "Delete all my data" clears it. */
    fun hasAccount(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("fcc_had_account", false) ||
        FccLogin.saved(c) || FccLogin.savedEmail(c) != null
    fun forgetAccount(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("fcc_had_account").apply()
    fun markSignedOut(c: Context) = prefs(c).edit().clear().apply()

    /** True if this URL means we're looking at account pages rather than being bounced to sign-in. */
    fun isSignedInPage(url: String) = url.startsWith(MY_REQUESTS) && !url.contains("/auth/") && !url.contains("signin")
    fun isSignInPage(url: String) = url.contains("/auth/v3/signin") || url.contains("/access/")

    fun untilText(c: Context): String {
        val t = expiresAt(c); if (t == 0L) return ""
        val left = (t - System.currentTimeMillis()) / 60_000
        val at = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
        return if (left <= 0) "expired" else "until about $at (${left / 60}h ${left % 60}m left)"
    }

    /** Local part of sign-out: drop the session cookie and site storage. (The screen also loads [SIGN_OUT_URL] first.) */
    fun clearLocal(c: Context) {
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        markSignedOut(c)
    }
}
