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
import com.kynrek.spamreporter.Ui.chip
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.text
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Files queued reports in up to [parallel] WebViews side by side ("lanes"), each visible so the user can watch.
 * Lanes share one queue. Submit taps and FTC key entry are serialized (one lane at a time, at least
 * MIN_SUBMIT_GAP_MS apart), and a refused or rate-limited submission drops filing back to a single lane.
 * FCC: fill via fcc.js, then a real touch on Submit (the site ignores synthetic clicks); ticket number
 * arrives by email and is attached by CaptureNotificationListener. FCC limits anonymous complaints per
 * hour, so FCC jobs are paced (FCC_PER_HOUR) and the queue pauses when the limit message appears.
 * FTC: assistant step, main form via ftc.js, sender number typed with real key events (masked field),
 * submit, and the report number is read from the confirmation page.
 */
class FilingActivity : AppCompatActivity() {
    companion object { const val FCC_PER_HOUR = 8; const val SIGNED_IN_GAP_MS = 3_000L; const val MIN_SUBMIT_GAP_MS = 1_000L
        const val FCC_URL = "https://consumercomplaints.fcc.gov/hc/en-us/requests/new?ticket_form_id=39744" }

    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var store: Store
    private lateinit var lanesBox: LinearLayout
    private val lanes = mutableListOf<Lane>()
    private var job: Job? = null
    /** One submit (real tap / real key entry) at a time across lanes, spaced apart. */
    private val submitLock = Mutex()
    private var lastSubmit = 0L
    /** Set when a site refuses or rate-limits a report: extra lanes stop after their current report. */
    private var singleLane = false

    /** Watch mode (Settings): scroll to and highlight each field as it's filled. Off = fill at full speed. */
    private val watch by lazy { getSharedPreferences("settings", MODE_PRIVATE).getBoolean("watch", false) }
    /** Reports filed side by side (Settings, 1-5). Watch mode uses one so there's one form to follow. */
    private val parallel by lazy { if (watch) 1 else getSharedPreferences("settings", MODE_PRIVATE).getInt("parallel", 3).coerceIn(1, 5) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        NetPolicy.debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        store = Store(this)
        lateinit var progress: LinearProgressIndicator
        screen("Filing reports", "FCC + FTC") {
            add(card {
                status = text("Preparing…", 15f, true); addView(status)
                sub = text("", 13f, alpha = 0.7f); addView(sub)
                progress = LinearProgressIndicator(context).apply { isIndeterminate = true; trackCornerRadius = dp(4) }
                addView(progress, lp(top = dp(10)))
                addView(MaterialButton(context).apply {
                    text = "Start filing"; setOnClickListener {
                        if (text == "Done") finish()   // after a run: close, don't start another pass
                        else if (job?.isActive == true) { job?.cancel(); text = "Start filing"; log("Stopping after the current reports…", "Finished reports are saved and skipped next time.") }
                        else { text = "Stop"; preview.visibility = android.view.View.GONE; job = lifecycleScopeLaunch { runQueue(); text = "Done" } } } }, lp(top = dp(8)))
            })
            // what will be filed, before anything is sent: one card per report
            preview = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
                val q = Jobs.pending(store)
                val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
                val fails = store.filings().filter { !it.ok && it.note.startsWith("error:") }
                addView(text(if (q.isEmpty()) "Nothing new to file." else "Ready to file ${q.size}", 15f, true), lp(bottom = dp(6)))
                q.forEachIndexed { i, (c, a) -> addView(card(12) {
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(text("${i + 1}. ${c.dashed}", 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(chip(c.category.name.lowercase().replaceFirstChar { it.uppercase() }, Ui.AMBER))
                        addView(chip(if (a == Agency.FCC) "FCC" else "FTC · DNC", if (a == Agency.FCC) Ui.NAVY else Ui.GREEN),
                            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
                    })
                    addView(text("${c.msgs.size} text${if (c.msgs.size == 1) "" else "s"} · last ${fmt.format(Date(c.last.ts))}", 12f, alpha = 0.6f))
                    // last failed attempt for this exact report (if any), with the reason
                    fails.firstOrNull { f -> f.agency == a && Phone.digits(f.sender) == Phone.digits(c.dashed) }
                        ?.let { f -> addView(text("Last try ${fmt.format(Date(f.filedAt))} didn't go through: ${f.note.removePrefix("error:").take(140)}", 12f, color = Ui.RED)) }
                    c.prior.firstOrNull()?.let { f -> addView(text("Reported earlier (#${f.confirmation ?: f.status}); these are newer texts", 12f, color = Ui.RED)) }
                    addView(text(c.last.body.replace(Regex("\\s+"), " ").take(160), 13f, alpha = 0.85f), lp(top = dp(4)))
                }) }
            }
            add(preview, dp(8))
            add(text("Live view — your data goes only to the agency sites; trackers on these pages are blocked.", 12f, alpha = 0.6f), dp(4))
            lanesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            add(lanesBox, dp(8))
        }
        this.progress = progress
        val n = minOf(parallel, maxOf(Jobs.pending(store).size, 1))
        repeat(n) { i -> lanes += Lane(i, n) }
        val p = store.profile
        if (!p.complete() || !p.attested) { log("Profile or attestation missing", "Complete step 1 first."); progress.visibility = android.view.View.GONE; return }
        log("Check the list below, then tap Start filing", when {
            watch -> "Watch mode is on: each field scrolls into view and is highlighted as it's filled (one report at a time)."
            n > 1 -> "Files $n reports at once. Tip: turn on Watch mode in Settings to follow each field (slower)."
            else -> "Tip: turn on Watch mode in Settings to see each field as it's filled (slower)." })
        progress.visibility = android.view.View.INVISIBLE
    }
    private lateinit var preview: android.view.View

    private fun lifecycleScopeLaunch(block: suspend CoroutineScope.() -> Unit) = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch(block = block)
    override fun onDestroy() {
        job?.cancel()
        // leave nothing behind in the WebViews: cookies, cache, storage, form data
        lanes.forEach { it.web.clearCache(true); it.web.clearFormData(); it.web.clearHistory() }
        WebStorage.getInstance().deleteAllData()
        // keep cookies only while the user has chosen to stay signed in to the FCC; otherwise leave nothing behind
        if (!FccAccount.likelySignedIn(this)) CookieManager.getInstance().removeAllCookies(null)
        lanes.forEach { it.web.destroy() }; super.onDestroy()
    }
    private fun log(s: String, detail: String = "") { runOnUiThread { status.text = s; sub.text = detail } }

    private suspend fun runQueue() {
        // marks a run as in progress; if the app is killed (closed by the system, or updated) the home screen sees this
        // flag still set and offers to resume. Cleared when the run finishes or the user stops / leaves.
        Interrupted.begin(this)
        try { runQueueInner() } finally { Interrupted.end(this) }
    }

    private suspend fun runQueueInner() {
        val p = store.profile
        val queue = Jobs.pending(store)   // only texts not yet reported to each agency
        if (FccAccount.likelySignedIn(this) && queue.any { it.agency == Agency.FCC }) {
            log("Checking FCC sign-in…")
            val u = runCatching { lanes[0].load(FccAccount.MY_REQUESTS) }.getOrDefault("")
            if (!FccAccount.isSignedInPage(u)) FccAccount.markSignedOut(this)
        }
        val signedIn = FccAccount.likelySignedIn(this)
        log("${queue.size} reports to file" + if (lanes.size > 1) " · ${lanes.size} at a time" else "", if (signedIn)
            "Signed in to the FCC (${FccAccount.untilText(this)}) — no hourly limit."
            else "Not signed in to the FCC: limited to $FCC_PER_HOUR per hour; the app waits automatically. Sign in on the home screen to go faster.")
        runOnUiThread { progress.visibility = android.view.View.VISIBLE; progress.isIndeterminate = false; progress.max = maxOf(queue.size, 1) }
        // all lanes run on the main thread, so taking the next item and counting need no locking
        var next = 0; var done = 0; var failedInARow = 0; var whySingle = ""
        val failures = mutableListOf<String>()
        coroutineScope { for (lane in lanes) launch {
            while (isActive) {
                if (lane.idx > 0 && singleLane) { lane.label("stopped — $whySingle, so lane 1 files the rest one at a time"); break }
                val i = next++; if (i >= queue.size) break
                val (c, agency) = queue[i]
                // re-check right before filing (an earlier run, or a retry, may have just covered these texts)
                if (c.msgs.all { store.reported(it, agency) }) { done++; continue }
                lane.label("${agency}: ${c.dashed} · ${i + 1} of ${queue.size} · ${c.category.name.lowercase()}")
                if (lanes.size == 1) log("${agency}: ${c.dashed}", "${i + 1} of ${queue.size} · ${c.category.name.lowercase()}")
                // a report that has started is finished and recorded even if Stop is tapped — Stop takes effect between reports,
                // so a submission is never left unrecorded (which could otherwise lead to filing it twice)
                val r = withContext(NonCancellable) {
                    Interrupted.inFlight(this@FilingActivity, "${agency}: ${c.dashed}", true)
                    var r = try { if (agency == Agency.FCC) lane.fileFcc(Jobs.payload(c, p)) else lane.fileFtc(Jobs.payload(c, p)) } catch (t: Throwable) { "error:" + t.message }
                    // safety net: if the form refused it (still on the form, nothing submitted), retry once with shortened texts
                    if (agency == Agency.FCC && r.startsWith("error:not submitted") && r.contains("/requests/new")) {
                        lane.label("FCC: ${c.dashed} · form didn't accept it — retrying with shortened message text")
                        r = try { lane.fileFcc(Jobs.payload(c, p, compact = true)) } catch (t: Throwable) { "error:" + t.message }
                    }
                    store.upsertFiling(Filing(c.dashed, agency,
                        when { !r.startsWith("ok:") -> "failed"; r.length > 3 -> "confirmed"; else -> "submitted" },
                        r.removePrefix("ok:").takeIf { r.startsWith("ok:") && it.isNotEmpty() }, System.currentTimeMillis(), r,
                        events = c.msgs.map { it.key() }, coversUntil = c.msgs.maxOf { it.ts }, texts = c.msgs.map { it.ts to it.body }))
                    Interrupted.inFlight(this@FilingActivity, "${agency}: ${c.dashed}", false)
                    r
                }
                done++
                if (BuildConfig.DEBUG) android.util.Log.i("SpamReporter", "filing $agency ${c.dashed}: ${r.take(200)}")
                val why = r.removePrefix("error:").take(100)
                if (r.startsWith("ok:")) failedInARow = 0 else { failedInARow++; failures += "$agency ${c.dashed}: $why" }
                // Only a real FCC rate limit, or several failures in a row (site trouble), drops to one lane. A single form
                // error or timeout doesn't slow the other lanes; that report is just retried next run.
                val rateLimited = r.contains("rate limited")
                if (!r.startsWith("ok:") && !singleLane && lanes.size > 1 && (rateLimited || failedInARow >= 3)) { singleLane = true
                    whySingle = if (rateLimited) "the FCC hit its rate limit" else "$failedInARow reports in a row failed"
                    log("Continuing one at a time: $whySingle", why) }
                else if (lanes.size > 1) log("$done of ${queue.size} done", if (signedIn) "${lanes.size} at a time · submissions spaced ${MIN_SUBMIT_GAP_MS / 1000}s+ apart" else "")
                runOnUiThread { progress.setProgressCompat(done, true) }
                lane.label("${agency}: ${c.dashed} · " + if (r.startsWith("ok:")) "filed ${r.removePrefix("ok:")}" else "failed: $why")
            }
        } }
        runOnUiThread { progress.setProgressCompat(progress.max, true) }
        if (failures.isEmpty()) log("All done", "Export the report from the home screen. FCC ticket numbers fill in as the confirmation emails arrive.")
        else log("Done — ${failures.size} didn't go through (they'll be retried next time)", failures.joinToString("\n"))
    }

    // ---------- FCC pacing (shared by all lanes)
    private fun fccTimes(): MutableList<Long> = getSharedPreferences("pace", MODE_PRIVATE).getString("fcc", "")!!
        .split(',').filter { it.isNotBlank() }.map { it.toLong() }.toMutableList()
    private fun saveFccTimes(l: List<Long>) = getSharedPreferences("pace", MODE_PRIVATE).edit().putString("fcc", l.joinToString(",")).apply()

    /** Runs [block] holding the submit lock, at least MIN_SUBMIT_GAP_MS after the previous submit (any lane, any agency). */
    private suspend fun <T> submitTurn(block: suspend () -> T): T = submitLock.withLock {
        val w = lastSubmit + MIN_SUBMIT_GAP_MS - System.currentTimeMillis(); if (w > 0) delay(w)
        try { block() } finally { lastSubmit = System.currentTimeMillis() }
    }

    /** One WebView filing one report at a time. */
    @SuppressLint("SetJavaScriptEnabled")
    inner class Lane(val idx: Int, of: Int) {
        private val results = Channel<String>(Channel.UNLIMITED)
        private val pages = Channel<String>(Channel.CONFLATED)
        /** First paint of a new page (earlier than onPageFinished, which also waits for images, fonts, analytics...). */
        private val commits = Channel<String>(Channel.CONFLATED)
        private val title = text(if (of > 1) "Lane ${idx + 1}" else "", 12f, true, alpha = 0.7f)
        inner class Bridge { @JavascriptInterface fun result(s: String) { results.trySend(s) } }

        val web: WebView = WebView(this@FilingActivity).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // speed: forms don't need images; keep the HTTP cache between reports (it's cleared when the screen closes)
            settings.blockNetworkImage = !watch
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.saveFormData = false; settings.setGeolocationEnabled(false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) { pages.trySend(url) }
                override fun onPageCommitVisible(v: WebView, url: String) { commits.trySend(url) }
                // Block every request that isn't to an agency (or static code the agency form needs).
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (NetPolicy.allowed(r.url) && NetPolicy.allowedMethod(r.url, r.method)) null else { NetPolicy.note(r.url); NetPolicy.blocked() }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = (!NetPolicy.allowed(r.url)).also { if (it) NetPolicy.note(r.url) }
            }
        }
        init {
            if (of > 1) lanesBox.addView(title, lp(top = if (idx == 0) 0 else dp(10)))
            lanesBox.addView(MaterialCardView(this@FilingActivity).apply { addView(web) },
                LinearLayout.LayoutParams(-1, dp(if (of > 1) 420 else 520)).apply { topMargin = dp(4) })
        }
        fun label(s: String) { if (lanes.size > 1) runOnUiThread { title.text = "Lane ${idx + 1} · $s" } }

        /** Navigate and return as soon as the new page is committed; the fill scripts wait for the elements they need. */
        private suspend fun open(url: String): String { while (commits.tryReceive().isSuccess) {}; while (pages.tryReceive().isSuccess) {}
            web.loadUrl(url); return withTimeout(60_000) { commits.receive() } }
        suspend fun load(url: String): String { while (pages.tryReceive().isSuccess) {}; web.loadUrl(url); return withTimeout(60_000) { pages.receive() } }
        private suspend fun runScript(asset: String, payload: JSONObject, step: String? = null): JSONObject {
            while (results.tryReceive().isSuccess) {}
            val js = assets.open(asset).bufferedReader().readText()
            web.evaluateJavascript("window.__P=${payload};window.__STEP=${if (step == null) "null" else "'$step'"};$js", null)
            return JSONObject(withTimeout(90_000) { results.receive() })
        }

        suspend fun fileFcc(payload: JSONObject): String {
            val signedIn = FccAccount.likelySignedIn(this@FilingActivity)
            // anonymous: at most FCC_PER_HOUR in any rolling hour (counted across lanes)
            while (!signedIn) {
                val t = fccTimes().filter { it > System.currentTimeMillis() - 3_600_000 }
                if (t.size < FCC_PER_HOUR) break
                val wait = t.min() + 3_600_000 - System.currentTimeMillis()
                log("Waiting for FCC hourly limit", "Resumes in ${wait / 60000 + 1} min (${SimpleDateFormat("h:mm a", Locale.US).format(Date(t.min() + 3_600_000))})")
                delay(minOf(wait, 60_000))
            }
            open(FCC_URL)
            val r = runScript("fcc.js", payload.put("watch", watch))
            if (!r.optBoolean("ok")) return "error:" + r.optString("error", r.optString("empty"))
            // one lane: a polite gap between FCC complaints (the form fills during it). Several lanes: submitTurn spaces them.
            if (signedIn && lanes.size == 1) fccTimes().maxOrNull()?.let { val w = it + SIGNED_IN_GAP_MS - System.currentTimeMillis()
                if (w > 0) { log("FCC: ${payload.optString("sender")}", "Form filled · submitting in ${w / 1000 + 1}s (pacing)"); delay(w) } }
            val dpr = r.optDouble("dpr", resources.displayMetrics.density.toDouble())
            submitTurn {
                if (!signedIn && fccTimes().count { it > System.currentTimeMillis() - 3_600_000 } >= FCC_PER_HOUR) return@submitTurn "limit"
                while (commits.tryReceive().isSuccess) {}; while (pages.tryReceive().isSuccess) {}
                tap((r.getDouble("x") * dpr).toFloat(), (r.getDouble("y") * dpr).toFloat())
                ""
            }.let { if (it == "limit") return fileFcc(payload) }   // another lane used the last anonymous slot: wait and refill
            // the ticket number is in the URL we land on, known at first paint
            var url = withTimeout(30_000) { commits.receive() }
            if (Regex("/requests/\\d+").find(url) == null && !url.contains("return_to")) url = withTimeoutOrNull(8_000) { pages.receive() } ?: url
            // signed in: lands on /hc/en-us/requests/<ticket>; anonymous: home page with return_to, ticket arrives by email
            val ticket = Regex("/hc/[a-z-]+/requests/(\\d+)").find(url)?.groupValues?.get(1)
            return if (ticket != null || url.contains("return_to")) {
                saveFccTimes((fccTimes() + System.currentTimeMillis()).takeLast(50)); "ok:" + (ticket ?: "")
            } else {
                if (FccAccount.isSignInPage(url)) FccAccount.markSignedOut(this@FilingActivity)
                val limited = evalSync("document.body.innerText.includes('allowed per hour')") == "true"
                if (limited) { saveFccTimes(List(FCC_PER_HOUR) { System.currentTimeMillis() }); "error:rate limited (will retry next run)" }
                else "error:not submitted ($url)"
            }
        }

        suspend fun fileFtc(payload: JSONObject): String {
            open(ftcLink(payload.getString("firstDate"), store.profile.phoneDigits()))
            payload.put("watch", watch)
            var r = runScript("ftc.js", payload, "assistant")
            if (!r.optBoolean("ok")) return "error:" + r.optString("error")
            withTimeoutOrNull(4_000) { commits.receive() }; delay(300)   // next page (or in-page step) — the form script waits for its fields
            r = runScript("ftc.js", payload, "form")
            if (!r.optBoolean("ok")) return "error:" + r.optString("error")
            // key events go to the focused view, so typing the number and submitting happen one lane at a time
            r = submitTurn {
                web.requestFocus(); runScript("ftc_focus.js", payload)
                typeDigits(payload.getString("senderDigits")); delay(100)
                runScript("ftc_submit.js", payload)
            }
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
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); delay(15) } }
        private suspend fun evalSync(js: String): String = suspendCancellableCoroutine { k -> web.evaluateJavascript(js) { k.resume(it) {} } }
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
}

/** Remembers a filing run that was cut off (app killed or updated mid-run) so the home screen can offer to resume. */
object Interrupted {
    @Volatile private var live = false   // true only while a run is going in this process
    private fun p(c: android.content.Context) = c.getSharedPreferences("filing_run", android.content.Context.MODE_PRIVATE)
    fun begin(c: android.content.Context) { live = true; p(c).edit().putLong("started", System.currentTimeMillis()).putStringSet("inflight", emptySet()).apply() }
    fun end(c: android.content.Context) { live = false; p(c).edit().clear().apply() }
    fun inFlight(c: android.content.Context, what: String, on: Boolean) {
        val s = (p(c).getStringSet("inflight", null) ?: emptySet()).toMutableSet(); if (on) s += what else s -= what
        p(c).edit().putStringSet("inflight", s).commit()   // commit: must be on disk before the submission starts
    }
    /** Start time of a run that never finished (and isn't running now), or null. */
    fun stoppedAt(c: android.content.Context) = p(c).getLong("started", 0).takeIf { it > 0 && !live }
    /** Reports that were mid-submission when it stopped — they may or may not have reached the agency. */
    fun unsure(c: android.content.Context): Set<String> = p(c).getStringSet("inflight", null) ?: emptySet()
    fun dismiss(c: android.content.Context) = p(c).edit().clear().apply()
}
