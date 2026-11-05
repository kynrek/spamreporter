package com.kynrek.spamreporter

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The ONLY network destinations this app will talk to. Your data is entered into the FCC and FTC
 * complaint forms and nowhere else. Everything not on this list — including the analytics/tag-manager
 * scripts the government pages embed (Google Tag Manager, Digital Analytics Program, Azure Monitor),
 * font CDNs and accessibility overlays — is blocked inside the WebView before any request is made,
 * so third-party scripts can't observe what you type.
 */
object NetPolicy {
    /** Agency hosts: page loads + form submissions. */
    private val agencyHosts = setOf(
        "consumercomplaints.fcc.gov",      // FCC complaint center (FCC's Zendesk instance)
        "fcctest.zendesk.com",             // the same FCC Zendesk account's own host (HelpCenter.account.subdomain = "fcctest",
                                           // environment "production"); sign-in/session hand-off goes through /access here
        "reportfraud.ftc.gov",             // FTC ReportFraud
        "www2.reportfraud.ftc.gov",        // FTC ReportFraud API (/PROD_EAST/complaintsubmission, verifydnc, address check), since Nov 2026
        "www.donotcall.gov", "www2.donotcall.gov", // FTC DNC hand-off link for text complaints
    )
    /** Static code the forms cannot run without (no form data is sent to these: GET of scripts/styles only). */
    private val requiredStaticHosts = setOf(
        "static.zdassets.com",             // Zendesk front-end bundle used by the FCC form
        "cdn.jsdelivr.net", "cdnjs.cloudflare.com", // libraries loaded by the FCC theme
        "challenges.cloudflare.com",       // Cloudflare bot check in front of the FCC site
    )

    fun allowed(uri: Uri?): Boolean {
        val u = uri ?: return false
        if (u.scheme == "data" || u.scheme == "blob" || u.scheme == "about") return true
        if (u.scheme != "https") return false
        val h = u.host?.lowercase() ?: return false
        return h in agencyHosts || h in requiredStaticHosts
    }

    /** Static hosts may only be fetched with GET (no POSTing form data to a CDN). */
    fun allowedMethod(uri: Uri?, method: String?): Boolean {
        val h = uri?.host?.lowercase() ?: return true
        return h in agencyHosts || method.equals("GET", true) || h == "challenges.cloudflare.com"
    }

    /** Debug builds log the *host* of blocked requests (never paths, queries or bodies) to tune the allowlist. */
    var debug = false
    fun note(uri: Uri?) { if (debug) android.util.Log.d("NetPolicy", "blocked host=${uri?.scheme}://${uri?.host}") }

    fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Spam Reporter", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    const val DNC_HANDOFF = "https://www2.donotcall.gov/complaint-text-message-link"
}
