package com.kynrek.spamreporter

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.CookieManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.text
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Files queued reports one at a time in a visible WebView so the user can watch.
 * FCC: fill via fcc.js, then a real touch on Submit (the site ignores synthetic clicks); ticket number
 * arrives by email and is attached by CaptureNotificationListener. FCC limits anonymous complaints per
 * hour, so FCC jobs are paced (FCC_PER_HOUR) and the queue pauses when the limit message appears.
 * FTC: assistant step, main form via ftc.js, sender number typed with real key events (masked field),
 * submit, and the report number is read from the confirmation page.
 */
class FilingActivity : AppCompatActivity() {
    companion object { const val FCC_PER_HOUR = 8; const val FCC_URL = "https://consumercomplaints.fcc.gov/hc/en-us/requests/new?ticket_form_id=39744" }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var store: Store
    private val results = Channel<String>(Channel.UNLIMITED)
    private val pages = Channel<String>(Channel.CONFLATED)
    private var job: Job? = null

    inner class Bridge { @JavascriptInterface fun result(s: String) { results.trySend(s) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.saveFormData = false; settings.setGeolocationEnabled(false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) { pages.trySend(url) }
                // Block every request that isn't to an agency (or static code the agency form needs).
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (NetPolicy.allowed(r.url) && NetPolicy.allowedMethod(r.url, r.method)) null else NetPolicy.blocked()
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !NetPolicy.allowed(r.url)
            }
        }
        lateinit var progress: LinearProgressIndicator
        screen("Filing reports", "FCC + FTC") {
            add(card {
                status = text("Preparing…", 15f, true); addView(status)
                sub = text("", 13f, alpha = 0.7f); addView(sub)
                progress = LinearProgressIndicator(context).apply { isIndeterminate = true; trackCornerRadius = dp(4) }
                addView(progress, lp(top = dp(10)))
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Stop"; setOnClickListener { job?.cancel(); log("Stopped", "You can resume any time — finished reports are skipped.") } }, lp(top = dp(8)))
            })
            add(text("Live view — your data goes only to the agency sites; trackers on these pages are blocked.", 12f, alpha = 0.6f), dp(4))
            addView(MaterialCardView(context).apply { addView(web) }, LinearLayout.LayoutParams(-1, dp(520)).apply { topMargin = dp(8) })
        }
        this.progress = progress
        val p = store.profile
        if (!p.complete() || !p.attested) { log("Profile or attestation missing", "Complete step 1 first."); progress.visibility = android.view.View.GONE; return }
        job = lifecycleScopeLaunch { runQueue() }
    }

    private fun lifecycleScopeLaunch(block: suspend CoroutineScope.() -> Unit) = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch(block = block)
    override fun onDestroy() {
        job?.cancel()
        // leave nothing behind in the WebView: cookies, cache, storage, form data
        web.clearCache(true); web.clearFormData(); web.clearHistory()
        WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null)
        web.destroy(); super.onDestroy()
    }
    private fun log(s: String, detail: String = "") { runOnUiThread { status.text = s; sub.text = detail } }

    private suspend fun runQueue() {
        val p = store.profile
        val queue = Jobs.pending(store)   // only texts not yet reported to each agency
        log("${queue.size} reports to file", "FCC is limited to $FCC_PER_HOUR per hour; the app waits automatically.")
        runOnUiThread { progress.isIndeterminate = false; progress.max = maxOf(queue.size, 1) }
        for ((i, pair) in queue.withIndex()) {
            val (c, agency) = pair
            // re-check right before filing (an earlier run, or a retry, may have just covered these texts)
            if (c.msgs.all { store.reported(it, agency) }) continue
            val payload = Jobs.payload(c, p)
            log("${agency}: ${c.dashed}", "${i + 1} of ${queue.size} · ${c.category.name.lowercase()}")
            runOnUiThread { progress.setProgressCompat(i, true) }
            val r = try { if (agency == Agency.FCC) fileFcc(payload) else fileFtc(payload) } catch (t: Throwable) { "error:" + t.message }
            store.upsertFiling(Filing(c.dashed, agency,
                when { r.startsWith("ok:") -> if (agency == Agency.FTC) "confirmed" else "submitted"; else -> "failed" },
                r.removePrefix("ok:").takeIf { agency == Agency.FTC && r.startsWith("ok:") }, System.currentTimeMillis(), r,
                events = c.msgs.map { it.key() }))
            delay(4000)
        }
        runOnUiThread { progress.setProgressCompat(progress.max, true) }
        log("All done", "Export the report from the home screen. FCC ticket numbers fill in as the confirmation emails arrive.")
    }

    private suspend fun load(url: String): String { while (pages.tryReceive().isSuccess) {}; web.loadUrl(url); return withTimeout(60_000) { pages.receive() } }
    private suspend fun runScript(asset: String, payload: JSONObject, step: String? = null): JSONObject {
        while (results.tryReceive().isSuccess) {}
        val js = assets.open(asset).bufferedReader().readText()
        web.evaluateJavascript("window.__P=${payload};window.__STEP=${if (step == null) "null" else "'$step'"};$js", null)
        return JSONObject(withTimeout(90_000) { results.receive() })
    }

    // ---------- FCC
    private fun fccTimes(): MutableList<Long> = getSharedPreferences("pace", MODE_PRIVATE).getString("fcc", "")!!
        .split(',').filter { it.isNotBlank() }.map { it.toLong() }.toMutableList()
    private fun saveFccTimes(l: List<Long>) = getSharedPreferences("pace", MODE_PRIVATE).edit().putString("fcc", l.joinToString(",")).apply()

    private suspend fun fileFcc(payload: JSONObject): String {
        // pacing: at most FCC_PER_HOUR submissions in any rolling hour
        while (true) {
            val t = fccTimes().filter { it > System.currentTimeMillis() - 3_600_000 }
            if (t.size < FCC_PER_HOUR) break
            val wait = t.min() + 3_600_000 - System.currentTimeMillis()
            log("Waiting for FCC hourly limit", "Resumes in ${wait / 60000 + 1} min (${SimpleDateFormat("h:mm a", Locale.US).format(Date(t.min() + 3_600_000))})")
            delay(minOf(wait, 60_000))
        }
        load(FCC_URL); delay(1500)
        val r = runScript("fcc.js", payload)
        if (!r.optBoolean("ok")) return "error:" + r.optString("error", r.optString("empty"))
        val dpr = r.optDouble("dpr", resources.displayMetrics.density.toDouble())
        tap((r.getDouble("x") * dpr).toFloat(), (r.getDouble("y") * dpr).toFloat())
        val url = withTimeout(30_000) { pages.receive() }
        delay(1500)
        return if (url.contains("return_to")) {
            saveFccTimes(fccTimes() + System.currentTimeMillis()); "ok:"
        } else {
            val limited = evalSync("document.body.innerText.includes('allowed per hour')") == "true"
            if (limited) { saveFccTimes(List(FCC_PER_HOUR) { System.currentTimeMillis() }); "error:rate limited (will retry next run)" }
            else "error:not submitted ($url)"
        }
    }

    // ---------- FTC
    private suspend fun ftcLink(senderDate: String, myDigits: String): String = withContext(Dispatchers.IO) {
        // the same hand-off donotcall.gov uses for text complaints; pre-fills "text message" + your number
        runCatching {
            val c = URL(NetPolicy.DNC_HANDOFF).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json;charset=utf-8"); c.setRequestProperty("Origin", "https://www.donotcall.gov")
            c.outputStream.write(JSONObject().put("PhoneNumber", myDigits).put("CallDate", senderDate).toString().toByteArray())
            c.inputStream.bufferedReader().readText().substringBefore('~').replace("&amp;", "&")
        }.getOrNull()?.takeIf { it.startsWith("https://reportfraud.ftc.gov") } ?: "https://reportfraud.ftc.gov/assistant"
    }

    private suspend fun fileFtc(payload: JSONObject): String {
        load(ftcLink(payload.getString("firstDate"), store.profile.phoneDigits())); delay(2500)
        var r = runScript("ftc.js", payload, "assistant")
        if (!r.optBoolean("ok")) return "error:" + r.optString("error")
        delay(3500)
        r = runScript("ftc.js", payload, "form")
        if (!r.optBoolean("ok")) return "error:" + r.optString("error")
        typeDigits(payload.getString("senderDigits")); delay(500)
        r = runScript("ftc_submit.js", payload)
        return if (r.optBoolean("ok")) "ok:" + r.getString("report") else "error:" + r.optString("error")
    }

    // ---------- real input events
    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        web.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        web.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }
    private suspend fun typeDigits(d: String) { for (ch in d) {
        val code = KeyEvent.KEYCODE_0 + (ch - '0')
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); delay(60) } }
    private suspend fun evalSync(js: String): String = suspendCancellableCoroutine { k -> web.evaluateJavascript(js) { k.resume(it) {} } }
}
