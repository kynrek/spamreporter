package com.kynrek.spamreporter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** One sender's texts (all of them, or — from [Jobs.pending] — only the ones not yet reported). */
data class SenderCase(val sender: String, val msgs: List<Msg>, val category: Category, val prior: List<Filing> = emptyList()) {
    val digits = Phone.digits(sender)
    val dashed = Phone.dashed(sender)
    val last: Msg get() = msgs.maxBy { it.ts }
    val first: Msg get() = msgs.minBy { it.ts }
    fun agencies(): List<Agency> = when (category) {
        Category.COMMERCIAL -> listOf(Agency.FCC, Agency.FTC)
        Category.POLITICAL, Category.SURVEY -> listOf(Agency.FCC)   // DNC rules exempt these; FCC still takes them
        Category.IGNORE -> emptyList()
    }
}

object Jobs {
    private val mt = SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.US)
    private val longDate = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    private val shortDate = SimpleDateFormat("MM/dd/yyyy", Locale.US)
    private val hm = SimpleDateFormat("h:mm a", Locale.US)

    val STATES = mapOf("AL" to "Alabama","AK" to "Alaska","AZ" to "Arizona","AR" to "Arkansas","CA" to "California","CO" to "Colorado",
        "CT" to "Connecticut","DE" to "Delaware","DC" to "District of Columbia","FL" to "Florida","GA" to "Georgia","HI" to "Hawaii",
        "ID" to "Idaho","IL" to "Illinois","IN" to "Indiana","IA" to "Iowa","KS" to "Kansas","KY" to "Kentucky","LA" to "Louisiana",
        "ME" to "Maine","MD" to "Maryland","MA" to "Massachusetts","MI" to "Michigan","MN" to "Minnesota","MS" to "Mississippi",
        "MO" to "Missouri","MT" to "Montana","NE" to "Nebraska","NV" to "Nevada","NH" to "New Hampshire","NJ" to "New Jersey",
        "NM" to "New Mexico","NY" to "New York","NC" to "North Carolina","ND" to "North Dakota","OH" to "Ohio","OK" to "Oklahoma",
        "OR" to "Oregon","PA" to "Pennsylvania","RI" to "Rhode Island","SC" to "South Carolina","SD" to "South Dakota",
        "TN" to "Tennessee","TX" to "Texas","UT" to "Utah","VT" to "Vermont","VA" to "Virginia","WA" to "Washington",
        "WV" to "West Virginia","WI" to "Wisconsin","WY" to "Wyoming","PR" to "Puerto Rico")
    fun stateName(s: String) = STATES[s.trim().uppercase()] ?: s.trim()

    /** Spam-flagged senders (from the Messages spam folder or marked in-app), grouped, with the user's category. */
    fun cases(store: Store): List<SenderCase> {
        val spam = store.spamSenders().map(Phone::digits).toSet() - store.notSpam()   // known numbers / contacts are never filed
        val cats = store.categories()
        return store.messages()
            .filter { Phone.isTenDigit(it.sender) && Phone.digits(it.sender) in spam && it.body.isNotBlank() }
            .groupBy { Phone.digits(it.sender) }
            .map { (d, ms) -> SenderCase(Phone.dashed(d), ms.sortedBy { it.ts }, cats[d] ?: Classifier.classify(ms.map { it.body })) }
            .sortedByDescending { it.last.ts }
    }

    /** One report to file: [case].msgs holds only texts not yet reported to [agency]. */
    data class Pending(val case: SenderCase, val agency: Agency)

    /** Everything still to file. A sender that texts again after being reported produces a new, separate report. */
    fun pending(store: Store): List<Pending> {
        val fs = store.filings()
        return cases(store).flatMap { c -> c.agencies().mapNotNull { a ->
            val fresh = c.msgs.filter { !store.reported(it, a, fs) }
            if (fresh.isEmpty()) null
            else Pending(c.copy(msgs = fresh, prior = fs.filter { it.ok && it.agency == a && Phone.digits(it.sender) == c.digits }), a)
        } }
    }

    /** Strip characters the FCC help desk rejects (styled math letters, emoji) — keeps the text readable. */
    private fun clean(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "").replace("\r", "")

    private const val FTC_MAX = 2000

    /**
     * Join message texts within [budget] characters. If they don't all fit, every text is shortened by the same
     * rule (longest ones first, each keeps at least a fair share) instead of dropping the newest messages.
     */
    private fun fit(items: List<Pair<String, String>>, budget: Int, sep: String, fmt: (Pair<String, String>) -> String): String {
        fun total(xs: List<Pair<String, String>>) = xs.sumOf { fmt(it).length } + sep.length * (xs.size - 1).coerceAtLeast(0)
        if (total(items) <= budget) return items.joinToString(sep, transform = fmt)
        var cap = items.maxOf { it.second.length }
        var lo = 0; var hi = cap
        while (lo < hi) { val mid = (lo + hi + 1) / 2   // largest per-text length that fits
            if (total(items.map { (t, b) -> t to if (b.length > mid) b.take(mid).trimEnd() + "…" else b }) <= budget) lo = mid else hi = mid - 1 }
        cap = lo
        return items.map { (t, b) -> t to if (b.length > cap) b.take(cap).trimEnd() + "…" else b }.joinToString(sep, transform = fmt).take(budget)
    }

    /** [compact] = safety-net retry: much shorter text, in case a long one is what stopped the form from submitting. */
    fun payload(c: SenderCase, p: Profile, compact: Boolean = false): JSONObject {
        val dnc = p.dncRegisteredOn.ifBlank { null }
        val when_ = "${longDate.format(Date(c.last.ts))} at ${hm.format(Date(c.last.ts))}"
        val bodies = c.msgs.map { it.body }
        val n = c.msgs.size
        val desc = when (c.category) {
            Category.COMMERCIAL -> "On $when_, my wireless number ${p.phoneDashed()}${dnc?.let { " (on the National Do Not Call Registry since $it)" } ?: ""} " +
                "received an unsolicited telemarketing text from ${c.dashed}. I have no prior business relationship with the sender, made no inquiry, and never gave consent to be contacted."
            Category.POLITICAL -> "On $when_, my wireless number ${p.phoneDashed()} received unsolicited political text message(s) from ${c.dashed}. " +
                "I never gave this sender consent to text me. I am reporting this as an unwanted political text; I am not alleging it is a telemarketing call."
            else -> "On $when_, my wireless number ${p.phoneDashed()} received an unsolicited survey/poll text message from ${c.dashed}. I never gave this sender consent to text me."
        } + (if (n > 1) " $n messages total; most recent is listed as the date of issue." else "") +
            (c.prior.maxByOrNull { it.filedAt }?.let { " This is a new text received after my earlier complaint about this sender" +
                (it.confirmation?.let { t -> " (ticket/report $t)" } ?: "") + "; the sender continued texting me." } ?: "")
        val stamped = c.msgs.map { "[${mt.format(Date(it.ts))}]" to clean(it.body).trim() }
        val add = "Message text as received:\n" + fit(stamped, if (compact) 800 else 1900 - 30, "\n") { (t, b) -> "$t $b" }
        val kind = when (c.category) { Category.COMMERCIAL -> "DNC telemarketing"; Category.POLITICAL -> "Unwanted political"; else -> "Unwanted survey" }
        val (ftcCat, ftcSub) = Classifier.ftcCategory(bodies)
        val prof = JSONObject().put("first", p.first).put("last", p.last).put("address1", p.address1).put("address2", p.address2)
            .put("city", p.city).put("stateName", stateName(p.state)).put("zip", p.zip).put("email", p.email).put("phone", p.phoneDashed())
        return JSONObject()
            .put("profile", prof).put("sender", c.dashed).put("senderDigits", c.digits)
            .put("subject", "$kind text from ${c.dashed} to ${p.phoneDashed()}".take(70))
            // the complaint description has no practical length limit: every text, in full
            .put("desc", clean(desc) + "\n\nMessage text as received:\n" + fit(stamped, if (compact) 2500 else 12000, "\n") { (t, b) -> "$t $b" })
            .put("add", add)
            .put("date", longDate.format(Date(c.last.ts))).put("time", hm.format(Date(c.last.ts)).lowercase())
            .put("adv", if (c.category == Category.SURVEY) "No" else "Yes")
            .put("cat", if (c.category == Category.POLITICAL) "Political (including campaign calls/elections)" else Classifier.fccGoodsCategory(bodies))
            .put("named", "No").put("org", "").put("start", "No").put("onDnc", dnc != null)
            .put("ftcCat", ftcCat).put("ftcSub", ftcSub ?: JSONObject.NULL)
            .put("firstDate", shortDate.format(Date(c.first.ts)))
            .put("ftcDesc", run {
                val head = clean("Unsolicited telemarketing text to my wireless number ${p.phoneDashed()}" +
                    (dnc?.let { ", which has been on the National Do Not Call Registry since $it" } ?: "") + ". ")
                val tail = " I have no prior business relationship with the sender, made no inquiry, and never gave consent. Also reported to the FCC."
                // the statement and closing are kept whole; the message texts share whatever room is left
                head + fit(stamped.map { (t, b) -> t.trim('[', ']') to b.replace(Regex("\\s+"), " ") }, (if (compact) 1400 else FTC_MAX) - head.length - tail.length, " ") { (t, b) ->
                    "Received $t from ${c.dashed}: \"$b\"." } + tail
            })
    }

    /** CSV + Markdown confirmation report into Downloads/SpamReporter. Returns the CSV uri. */
    fun exportReport(ctx: Context, store: Store): Uri? {
        val cases = cases(store).associateBy { it.digits }
        val byKey = store.messages().associateBy { it.key() }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US)
        val p = store.profile
        val rows = store.filings().sortedWith(compareBy({ it.agency }, { it.sender }, { it.filedAt }))
        val csv = StringBuilder("agency,category,sender_number,texted_number,text_dates,confirmation_number,filed_at,status\n")
        val md = StringBuilder("# Spam text report confirmations — ${p.phoneDashed()}\n\n| Agency | Category | Sender | Text date(s) | Confirmation # | Filed | Status |\n|---|---|---|---|---|---|---|\n")
        for (f in rows) {
            val c = cases[Phone.digits(f.sender)]
            val covered = if (f.events.isNotEmpty()) f.events.mapNotNull(byKey::get) else c?.msgs?.filter { it.ts <= f.filedAt } ?: emptyList()
            val dates = covered.sortedBy { it.ts }.joinToString("; ") { mt.format(Date(it.ts)) }
            val cat = c?.category?.name?.lowercase() ?: ""
            csv.append(listOf(f.agency, cat, f.sender, p.phoneDashed(), dates, f.confirmation ?: "", fmt.format(Date(f.filedAt)), f.status)
                .joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }).append('\n')
            md.append("| ${f.agency} | $cat | ${f.sender} | $dates | ${f.confirmation ?: "pending"} | ${fmt.format(Date(f.filedAt))} | ${f.status} |\n")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        fun save(name: String, mime: String, data: String): Uri? {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpamReporter")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) }
            return uri
        }
        save("spam-report-$stamp.md", "text/markdown", md.toString())
        // the app's own filing history (import it on another install so nothing is filed twice)
        save("spam-report-history-$stamp.json", "application/json", store.exportHistory())
        return save("spam-report-$stamp.csv", "text/csv", csv.toString())
    }
}
