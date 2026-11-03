package com.kynrek.spamreporter

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kynrek.spamreporter.Ui.GREEN
import com.kynrek.spamreporter.Ui.GREY
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.chip
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.text

/**
 * Sign in to (or out of) the FCC complaint site. The user types into the FCC's own page; this screen injects no
 * scripts and reads no form fields — it only watches which URL the page lands on.
 */
class FccSignInActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var signIn: MaterialButton
    private lateinit var signOut: MaterialButton
    private lateinit var webCard: MaterialCardView
    private var signingOut = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        NetPolicy.debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        // debug builds only: allow chrome://inspect (release builds never expose WebView contents)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // no addJavascriptInterface here: nothing on this page can call into the app
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (NetPolicy.allowed(r.url) && NetPolicy.allowedMethod(r.url, r.method)) null else { NetPolicy.note(r.url); NetPolicy.blocked() }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = (!NetPolicy.allowed(r.url)).also { if (it) NetPolicy.note(r.url) }
                override fun onPageFinished(v: WebView, url: String) = onLanded(url)
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        screen("FCC account", "Optional — lets the app file without the hourly limit") {
            add(card {
                chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; addView(chipRow)
                status = text("Checking…", 17f, true); addView(status, lp(top = dp(6)))
                detail = text("", 13f, alpha = 0.75f); addView(detail, lp(top = dp(4)))
                signIn = MaterialButton(context).apply { text = "Sign in to FCC"; setOnClickListener { startSignIn() } }
                signOut = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Sign out"; setTextColor(Ui.RED); setOnClickListener { doSignOut() } }
                addView(signIn, lp(top = dp(12))); addView(signOut, lp(top = dp(4)))
            })
            add(card {
                addView(text("How this works", 15f, true))
                addView(text("• You sign in on the FCC's own page below. Spam Reporter never sees, saves or types your password " +
                    "(your password manager can fill it in).\n" +
                    "• The FCC keeps you signed in for about ${FccAccount.SESSION_HOURS} hours, then signs you out on its own. " +
                    "Sign in again any time you want to file.\n" +
                    "• While signed in, complaints are filed under your FCC account: no hourly limit, ticket numbers appear " +
                    "right away, and you can see them all at consumercomplaints.fcc.gov → My requests.\n" +
                    "• The sign-in is stored only in this app's private browser storage and is never sent anywhere except the FCC. " +
                    "Tap Sign out to end it now — this also ends the session on the FCC's side. \"Delete all my data\" signs you out too.\n" +
                    "• Not signed in? The app still works; FCC filing is just slower (about 8 per hour).", 13f, alpha = 0.85f), lp(top = dp(6)))
            })
            webCard = MaterialCardView(context).apply { addView(web); visibility = View.GONE }
            addView(webCard, LinearLayout.LayoutParams(-1, dp(560)).apply { topMargin = dp(8) })
        }
        verify()
    }

    /** Load "My requests": if the FCC bounces us to sign-in, we're signed out. */
    private fun verify() { showState(null); web.loadUrl(FccAccount.MY_REQUESTS) }

    private fun startSignIn() { webCard.visibility = View.VISIBLE; status.text = "Sign in below"; detail.text = "Use your FCC complaint-center email and password."
        web.loadUrl(FccAccount.SIGN_IN_URL) }

    private fun doSignOut() {
        signingOut = true; showState(null); status.text = "Signing out…"
        web.loadUrl(FccAccount.SIGN_OUT_URL)   // ends the session server-side; local cookies are cleared in onLanded
    }

    private fun onLanded(url: String) {
        when {
            signingOut -> { signingOut = false; FccAccount.clearLocal(this); webCard.visibility = View.GONE; showState(false) }
            FccAccount.isSignedInPage(url) -> { FccAccount.markSignedIn(this); webCard.visibility = View.GONE; showState(true) }
            FccAccount.isSignInPage(url) && webCard.visibility == View.GONE -> { FccAccount.markSignedOut(this); showState(false) }
            // otherwise: user is mid-sign-in (password page, 2FA, forgot-password); leave the page visible
        }
    }

    private fun showState(signedIn: Boolean?) {
        chipRow.removeAllViews()
        when (signedIn) {
            null -> { status.text = "Checking…"; detail.text = ""; signIn.isEnabled = false; signOut.isEnabled = false }
            true -> {
                chipRow.addView(chip("Signed in", GREEN))
                status.text = "Signed in to the FCC"
                detail.text = "Session lasts ${FccAccount.untilText(this)}. After that the FCC signs you out automatically."
                signIn.visibility = View.GONE; signOut.visibility = View.VISIBLE; signOut.isEnabled = true
            }
            false -> {
                chipRow.addView(chip("Not signed in", GREY))
                status.text = "Not signed in"
                detail.text = "Signing in is optional. A sign-in lasts about ${FccAccount.SESSION_HOURS} hours."
                signIn.visibility = View.VISIBLE; signIn.isEnabled = true; signOut.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        // keep the FCC session cookie (that's the point), but drop cache, history and form data
        web.clearCache(true); web.clearFormData(); web.clearHistory(); web.destroy(); super.onDestroy()
    }
}
