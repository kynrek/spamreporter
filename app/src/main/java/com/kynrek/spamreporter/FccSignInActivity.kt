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
import com.kynrek.spamreporter.Ui.AMBER
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
 * Sign in to (or out of) the FCC complaint site. The user types into the FCC's own page. The page gets no way to call
 * into the app. If the user opts in, the app reads the sign-in fields (only on the FCC's sign-in page) so it can offer
 * to save them after a successful sign-in, encrypted and locked behind fingerprint/PIN (see [FccLogin]), and fills
 * them back in next time after the user unlocks. The user still taps the FCC's Sign in button.
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
    private lateinit var howBody: TextView
    private lateinit var howHeader: TextView
    /** "How this works" collapses while the FCC page is shown so the sign-in form is in view; tap the heading to reopen. */
    private fun setHowOpen(open: Boolean) { howBody.visibility = if (open) View.VISIBLE else View.GONE
        howHeader.text = "How this works  " + if (open) "\u25BE" else "\u25B8" }
    private lateinit var savedRow: LinearLayout
    // sign-in fields seen on the FCC page (memory only; cleared after the save offer)
    private var typedEmail = ""; private var typedPassword = ""
    private var filledFromSaved = false
    /** Password we filled from the saved sign-in (memory only), to tell whether the user then typed a different one. */
    private var filledWith: String? = null
    /** Saved sign-in unlocked for a one-tap sign-in, waiting for the FCC page to load (memory only). */
    private var pendingCreds: Pair<String, String>? = null
    /** "Sign in another way": don't fill the saved sign-in this time. */
    private var skipSaved = false
    private lateinit var otherWay: MaterialButton
    private val poll = android.os.Handler(android.os.Looper.getMainLooper())
    private val reader = object : Runnable { override fun run() {
        if (onFccSignInPage()) web.evaluateJavascript(FccLogin.READ_JS) { r -> runCatching {
            val o = org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String)
            o.optString("e").takeIf { it.isNotBlank() }?.let { typedEmail = it }
            o.optString("p").takeIf { it.isNotEmpty() }?.let { typedPassword = it } } }
        poll.postDelayed(this, 500) } }
    private fun onFccSignInPage() = web.url?.let { android.net.Uri.parse(it) }?.let { it.scheme == "https" &&
        it.host == "consumercomplaints.fcc.gov" && it.path.orEmpty().startsWith("/auth/v3/signin") } == true

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

        screen("FCC account", "Recommended — files reports faster, with no hourly limit") {
            add(card {
                chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; addView(chipRow)
                status = text("Checking…", 17f, true); addView(status, lp(top = dp(6)))
                detail = text("", 13f, alpha = 0.75f); addView(detail, lp(top = dp(4)))
                signIn = MaterialButton(context).apply { text = "Sign in to FCC"; setOnClickListener {
                    if (FccLogin.saved(context) && FccLogin.autoEnabled(context)) quickSignIn() else startSignIn() } }
                otherWay = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "Sign in another way"; visibility = View.GONE; setOnClickListener { skipSaved = true; startSignIn() } }
                signOut = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Sign out"; setTextColor(Ui.RED); setOnClickListener { doSignOut() } }
                addView(signIn, lp(top = dp(12))); addView(otherWay); addView(signOut, lp(top = dp(4)))
                savedRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }; addView(savedRow, lp(top = dp(8)))
            })
            add(card {
                val header = text("How this works  \u25BE", 15f, true); addView(header)
                howBody = text("• You sign in on the FCC's own page below (your password manager can fill it in).\n" +
                    "• Optional: after you sign in, the app can save your email and password on this phone, encrypted with a key " +
                    "that only works right after you unlock with your fingerprint or PIN. Next time it fills them in on the FCC page " +
                    "after you unlock; you still tap Sign in. They're never sent anywhere but the FCC sign-in page.\n" +
                    "• The FCC keeps you signed in for about ${FccAccount.SESSION_HOURS} hours, then signs you out on its own. " +
                    "Sign in again any time you want to file.\n" +
                    "• While signed in, complaints are filed under your FCC account: no hourly limit, ticket numbers appear " +
                    "right away, and you can see them all at consumercomplaints.fcc.gov → My requests.\n" +
                    "• The sign-in is stored only in this app's private browser storage and is never sent anywhere except the FCC. " +
                    "Tap Sign out to end it now — this also ends the session on the FCC's side. \"Delete all my data\" signs you out too.\n" +
                    "• Recommended: with an account, reports are filed much faster (several at once, no hourly limit). " +
                    "Not signed in? The app still works; FCC filing is just slower (about 8 per hour).", 13f, alpha = 0.85f); addView(howBody, lp(top = dp(6)))
                header.setOnClickListener { setHowOpen(howBody.visibility != View.VISIBLE) }
                howHeader = header
            })
            webCard = MaterialCardView(context).apply { addView(web); visibility = View.GONE }
            addView(webCard, LinearLayout.LayoutParams(-1, dp(560)).apply { topMargin = dp(8) })
        }
        renderSaved()
        verify()
    }

    /** "Saved sign-in: …  [Forget]" line under the buttons. */
    private fun renderSaved() {
        savedRow.removeAllViews()
        if (!FccLogin.saved(this)) return
        savedRow.addView(text("Saved sign-in: ${FccLogin.savedEmail(this) ?: "FCC account"} (locked with your fingerprint / PIN)", 13f, alpha = 0.75f))
        savedRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Forget saved sign-in"; setOnClickListener { FccLogin.forget(this@FccSignInActivity); toast("Saved sign-in removed"); renderSaved() } })
    }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** One tap: unlock, read the saved sign-in, open the FCC page; [onLanded] fills it and presses Sign in. */
    private fun quickSignIn() {
        FccLogin.unlock(this, "Sign in to the FCC", {
            val creds = runCatching { FccLogin.load(this) }.getOrNull()
            if (creds == null) { toast("Couldn't read the saved sign-in — sign in below"); startSignIn(); return@unlock }
            pendingCreds = creds; startSignIn(); status.text = "Signing in with your saved password…"
        }, { /* cancelled */ })
    }

    /** Manual mode (auto sign-in off): on the FCC sign-in page with a saved sign-in, unlock and fill; the user taps Sign in. */
    private fun offerFill() {
        if (!FccLogin.saved(this) || filledFromSaved || skipSaved) return
        FccLogin.unlock(this, "Fill your FCC sign-in", {
            runCatching { FccLogin.load(this) }.getOrNull()?.let { fill(it, submit = false) } ?: toast("Couldn't read the saved sign-in")
        }, { /* cancelled: user types it */ })
    }

    /** Fill the FCC form (it renders after page load, so retry for a few seconds); optionally press its Sign in button. */
    private fun fill(creds: Pair<String, String>, submit: Boolean) {
        fun attempt(n: Int) { if (!onFccSignInPage()) return
            web.evaluateJavascript(FccLogin.fillJs(creds.first, creds.second)) { ok ->
                when {
                    ok == "true" -> { filledFromSaved = true; filledWith = creds.second
                        if (!submit) status.text = "Filled in — tap Sign in on the FCC page"
                        else poll.postDelayed({ web.evaluateJavascript(FccLogin.SUBMIT_JS, null)
                            // still on the sign-in page a while later: wrong password, a check, or 2-step — let the user finish
                            poll.postDelayed({ if (onFccSignInPage()) { status.text = "Finish signing in below"
                                detail.text = "The FCC needs something more (or the saved password didn't work)." } }, 8_000) }, 400) }
                    n < 15 -> poll.postDelayed({ attempt(n + 1) }, 300)
                    else -> toast("Couldn't find the FCC sign-in fields")
                } } }
        attempt(0)
    }

    /** Right after a successful sign-in the user typed themselves: offer to save it (unlock required). */
    private fun offerSave() {
        val e = typedEmail; val p = typedPassword; typedPassword = ""
        val same = filledFromSaved && p == filledWith; filledWith = null
        if (!FccLogin.saveEnabled(this) || same || e.isBlank() || p.isEmpty()) return
        if (FccLogin.lock(this) == FccLogin.Lock.NONE) { FccLogin.explainLock(this); return }   // nothing to lock it with: explain, don't save
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (FccLogin.saved(this)) "Update saved FCC sign-in?" else "Save your FCC sign-in?")
            .setMessage("Saves $e and its password on this phone, encrypted and unlocked only with your fingerprint or PIN, " +
                "so next time it's filled in for you. You can remove it here any time.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Save") { _, _ -> FccLogin.unlock(this, "Save your FCC sign-in", {
                runCatching { FccLogin.save(this, e, p) }.onSuccess { toast("Sign-in saved") }.onFailure { toast("Couldn't save: ${it.message}") }
                renderSaved() }, { toast("Not saved") }) }
            .show()
    }

    /** Load "My requests": if the FCC bounces us to sign-in, we're signed out. */
    /** Check with the FCC whether the session is still good. If the app already knows it's signed out (never signed in, or the
     *  session ran out), the Sign in button shows right away instead of waiting on the FCC site; the check still runs and
     *  corrects it if needed. */
    private fun verify() { showState(if (FccAccount.likelySignedIn(this)) null else false); web.loadUrl(FccAccount.MY_REQUESTS) }

    private fun startSignIn() { filledFromSaved = false; filledWith = null; typedEmail = ""; typedPassword = ""; webCard.visibility = View.VISIBLE; setHowOpen(false); status.text = "Sign in below"; detail.text = "Use your FCC complaint-center email and password."
        web.loadUrl(FccAccount.SIGN_IN_URL) }

    private fun doSignOut() {
        signingOut = true; showState(null); status.text = "Signing out…"
        web.loadUrl(FccAccount.SIGN_OUT_URL)   // ends the session server-side; local cookies are cleared in onLanded
    }

    private fun onLanded(url: String) {
        when {
            signingOut -> { signingOut = false; FccAccount.clearLocal(this); webCard.visibility = View.GONE; showState(false) }
            FccAccount.isSignedInPage(url) -> { val fresh = webCard.visibility == View.VISIBLE
                CookieManager.getInstance().flush()   // write the new FCC session to disk now, not "eventually"
                FccAccount.markSignedIn(this); webCard.visibility = View.GONE; showState(true); if (fresh) offerSave() }
            onFccSignInPage() && webCard.visibility == View.VISIBLE -> pendingCreds?.let { pendingCreds = null; fill(it, submit = true) } ?: offerFill()
            FccAccount.isSignInPage(url) && webCard.visibility == View.GONE -> { FccAccount.markSignedOut(this); showState(false) }
            // otherwise: user is mid-sign-in (password page, 2FA, forgot-password); leave the page visible
        }
    }

    private fun showState(signedIn: Boolean?) {
        chipRow.removeAllViews()
        when (signedIn) {
            null -> { status.text = "Checking…"; detail.text = ""; signIn.isEnabled = false; signOut.isEnabled = false
                if (!signingOut) { signIn.visibility = View.GONE; otherWay.visibility = View.GONE } }
            true -> {
                chipRow.addView(chip("Signed in", GREEN))
                status.text = "Signed in to the FCC"
                detail.text = "Session lasts ${FccAccount.untilText(this)}. After that the FCC signs you out automatically."
                signIn.visibility = View.GONE; otherWay.visibility = View.GONE; signOut.visibility = View.VISIBLE; signOut.isEnabled = true
                skipSaved = false
            }
            false -> {
                val back = FccAccount.hasAccount(this)
                chipRow.addView(chip(if (back) "Signed out" else "Not signed in", if (back) AMBER else GREY))
                status.text = if (back) "Signed out — sign back in" else "Not signed in"
                detail.text = if (back) "Your FCC sign-in ends after about ${FccAccount.SESSION_HOURS} hours. Sign back in to file reports much faster, with no hourly limit."
                    else "Recommended: signing in lets the app file reports much faster, with no hourly limit. A sign-in lasts about ${FccAccount.SESSION_HOURS} hours."
                signIn.visibility = View.VISIBLE; signIn.isEnabled = true; signOut.visibility = View.GONE
                val quick = FccLogin.saved(this) && FccLogin.autoEnabled(this)
                signIn.text = if (quick) "Sign in again with saved password" else "Sign in to FCC"
                otherWay.visibility = if (quick) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() { super.onResume(); poll.post(reader) }
    override fun onPause() { poll.removeCallbacks(reader); super.onPause() }

    override fun onDestroy() {
        typedEmail = ""; typedPassword = ""; pendingCreds = null; filledWith = null
        // keep the FCC session cookie (that's the point), but drop cache, history and form data
        web.clearCache(true); web.clearFormData(); web.clearHistory(); web.destroy(); super.onDestroy()
    }
}
