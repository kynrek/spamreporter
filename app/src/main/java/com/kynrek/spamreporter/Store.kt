package com.kynrek.spamreporter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Source { SMS, MMS, RCS_SPAM_FOLDER, NOTIFICATION }
enum class Category { COMMERCIAL, POLITICAL, SURVEY, IGNORE }
enum class Agency { FCC, FTC }

/** One received message. [ts] is epoch millis; 0 when only the date is known. */
data class Msg(val sender: String, val ts: Long, val body: String, val source: Source, val spamFlagged: Boolean) {
    /**
     * Identity of the text *event*: sender + minute received + normalized text. The same text seen through
     * several routes (SMS inbox, Spam & blocked folder, notification) maps to one event, so it's never reported twice.
     */
    fun key() = "${Phone.digits(sender)}|${ts / 60_000}|${body.trim().replace(Regex("\\s+"), " ").hashCode()}"
    fun toJson() = JSONObject().put("sender", sender).put("ts", ts).put("body", body)
        .put("source", source.name).put("spam", spamFlagged)
    companion object {
        fun from(o: JSONObject) = Msg(o.getString("sender"), o.getLong("ts"), o.getString("body"),
            Source.valueOf(o.getString("source")), o.optBoolean("spam"))
    }
}

/**
 * One report to one agency, covering specific text events ([events] = Msg.key()s).
 * A later text from the same sender is a new event and gets its own report.
 */
data class Filing(val sender: String, val agency: Agency, var status: String, var confirmation: String?,
                  var filedAt: Long, var note: String = "", val events: List<String> = emptyList(),
                  /** Every text from this sender received at or before this time is covered by this report. 0 = use [filedAt]. */
                  val coversUntil: Long = 0,
                  /** Copy of the texts this report was about (received time, body), for the history page and PDF. */
                  val texts: List<Pair<Long, String>> = emptyList()) {
    val cutoff get() = if (coversUntil > 0) coversUntil else filedAt
    /** Stable id: a retry of the same events overwrites the failed attempt instead of adding a row. */
    val id get() = "${agency.name}|${Phone.digits(sender)}|${events.minOrNull() ?: ""}"
    val ok get() = status == "submitted" || status == "confirmed"
    fun toJson() = JSONObject().put("sender", sender).put("agency", agency.name).put("status", status)
        .put("confirmation", confirmation ?: JSONObject.NULL).put("filedAt", filedAt).put("note", note)
        .put("events", JSONArray(events)).put("coversUntil", coversUntil)
        .put("texts", JSONArray(texts.map { (ts, b) -> JSONObject().put("ts", ts).put("body", b) }))
    companion object {
        fun from(o: JSONObject) = Filing(o.getString("sender"), Agency.valueOf(o.getString("agency")),
            o.getString("status"), if (o.isNull("confirmation")) null else o.getString("confirmation"),
            o.getLong("filedAt"), o.optString("note"),
            o.optJSONArray("events")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList(), o.optLong("coversUntil"),
            o.optJSONArray("texts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { t -> t.getLong("ts") to t.optString("body") } } } ?: emptyList())
    }
}

data class Profile(
    var first: String = "", var last: String = "", var address1: String = "", var address2: String = "",
    var city: String = "", var state: String = "", var zip: String = "", var email: String = "",
    var phone: String = "", var dncRegisteredOn: String = "",
    var attested: Boolean = false, var attestedAt: Long = 0,
) {
    fun complete() = listOf(first, last, address1, city, state, zip, email, phone).all { it.isNotBlank() }
    fun phoneDigits() = phone.filter(Char::isDigit).takeLast(10)
    fun phoneDashed() = phoneDigits().let { if (it.length == 10) "${it.substring(0,3)}-${it.substring(3,6)}-${it.substring(6)}" else phone }
}

/** Tiny JSON-file store in app-private storage. */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val dir = ctx.filesDir
    private fun f(name: String) = File(dir, name)
    private fun readArr(name: String) = Crypto.read(f(name))?.let(::JSONArray) ?: JSONArray()
    private fun readObj(name: String) = Crypto.read(f(name))?.let(::JSONObject)
    private fun write(name: String, s: String) = Crypto.write(f(name), s)
    fun wipeAll() = Crypto.wipe(dir)

    var profile: Profile
        get() {
            val o = readObj("profile.json") ?: return Profile()
            return Profile(o.optString("first"), o.optString("last"), o.optString("address1"), o.optString("address2"),
                o.optString("city"), o.optString("state"), o.optString("zip"), o.optString("email"), o.optString("phone"),
                o.optString("dnc"), o.optBoolean("attested"), o.optLong("attestedAt"))
        }
        set(p) {
            write("profile.json", JSONObject().put("first", p.first).put("last", p.last).put("address1", p.address1)
                .put("address2", p.address2).put("city", p.city).put("state", p.state).put("zip", p.zip)
                .put("email", p.email).put("phone", p.phone).put("dnc", p.dncRegisteredOn)
                .put("attested", p.attested).put("attestedAt", p.attestedAt).toString())
        }

    @Synchronized fun messages(): List<Msg> = readArr("messages.json").let { a -> (0 until a.length()).map { Msg.from(a.getJSONObject(it)) } }

    /** Merge new messages, de-duplicating; a message seen as spam-flagged anywhere stays flagged. */
    @Synchronized fun addMessages(new: List<Msg>): Int {
        val map = LinkedHashMap<String, Msg>()
        messages().forEach { map[it.key()] = it }
        var added = 0
        for (m in new) {
            val old = map[m.key()]
            if (old == null) { map[m.key()] = m; added++ }
            else if (m.spamFlagged && !old.spamFlagged) map[m.key()] = old.copy(spamFlagged = true)
            else if (old.source == Source.RCS_SPAM_FOLDER && m.source != Source.RCS_SPAM_FOLDER) map[m.key()] = m.copy(spamFlagged = true) // keep the exact timestamp
        }
        write("messages.json", JSONArray(map.values.map { it.toJson() }).toString())
        return added
    }

    @Synchronized fun spamSenders(): MutableSet<String> = readArr("spam_senders.json").let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
    @Synchronized fun removeSpamSenders(digits: String) { val all = spamSenders().filterNot { Phone.digits(it) == digits }
        write("spam_senders.json", JSONArray(all).toString()) }
    @Synchronized fun addSpamSenders(s: Collection<String>) { val all = spamSenders(); all.addAll(s); write("spam_senders.json", JSONArray(all.toList()).toString()) }

    /**
     * "Known numbers": senders the user said aren't spam (swiped right on "Choose spam senders", or added in Settings).
     * Their texts, now and in future, are never suggested or reported. digits -> when it was added.
     */
    @Synchronized fun knownNumbers(): MutableMap<String, Long> {
        val o = readObj("known.json") ?: JSONObject()
        return o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }
    @Synchronized fun addKnown(digits: String) { val m = knownNumbers(); m.putIfAbsent(digits, System.currentTimeMillis())
        write("known.json", JSONObject(m as Map<*, *>).toString()); removeSpamSenders(digits) }
    @Synchronized fun removeKnown(digits: String) { val m = knownNumbers(); if (m.remove(digits) != null) write("known.json", JSONObject(m as Map<*, *>).toString()) }

    /**
     * Numbers that are never spam: known numbers; contacts (setting on + access granted); and numbers you replied to
     * (setting on) unless you explicitly marked them spam afterwards ([spamOverrides]).
     */
    fun notSpam(): Set<String> = knownNumbers().keys + Contacts.numbers(app) + (Replies.numbers(app) - spamOverrides())

    /**
     * When each spam sender was marked: digits -> newest text covered by the mark. With "Remember spam senders" off,
     * texts after this aren't treated as spam until the sender is ticked again. Created from the current spam list
     * the first time (so existing marks cover everything received so far).
     */
    @Synchronized fun spamMarks(): MutableMap<String, Long> {
        readObj("spam_marks.json")?.let { o -> return o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap() }
        val newest = messages().groupBy { Phone.digits(it.sender) }.mapValues { (_, ms) -> ms.maxOf { it.ts } }
        val m = spamSenders().map(Phone::digits).associateWith { newest[it] ?: 0L }.toMutableMap()
        write("spam_marks.json", JSONObject(m as Map<*, *>).toString()); return m
    }
    @Synchronized fun setSpamMark(digits: String, upTo: Long?) { val m = spamMarks(); if (upTo == null) m.remove(digits) else m[digits] = upTo
        write("spam_marks.json", JSONObject(m as Map<*, *>).toString()) }
    /** "Remember spam senders" (default on): new texts from a number you marked spam are spam too. */
    fun rememberSpam() = app.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("remember_spam", true)

    /** Numbers you explicitly marked spam (tick / swipe left) — this beats "you replied, so not spam". */
    @Synchronized fun spamOverrides(): MutableSet<String> = readArr("spam_overrides.json").let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
    @Synchronized fun setSpamOverride(digits: String, on: Boolean) { val s = spamOverrides(); if (if (on) s.add(digits) else s.remove(digits))
        write("spam_overrides.json", JSONArray(s.toList()).toString()) }

    /** Newest text (ts) of each sender already shown on "Choose spam senders"; later texts make it show again. */
    @Synchronized fun seen(): MutableMap<String, Long> {
        val o = readObj("seen.json") ?: JSONObject()
        return o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }
    @Synchronized fun markSeen(shown: Map<String, Long>) { if (shown.isEmpty()) return
        val m = seen(); for ((d, ts) in shown) if (ts > (m[d] ?: 0)) m[d] = ts
        write("seen.json", JSONObject(m as Map<*, *>).toString()) }

    /** User's category decision per sender (overrides the classifier). */
    @Synchronized fun categories(): MutableMap<String, Category> {
        val o = readObj("categories.json") ?: JSONObject()
        return o.keys().asSequence().associateWith { Category.valueOf(o.getString(it)) }.toMutableMap()
    }
    @Synchronized fun setCategory(sender: String, c: Category) { val m = categories(); m[sender] = c
        write("categories.json", JSONObject(m.mapValues { it.value.name }).toString()) }

    @Synchronized fun filings(): MutableList<Filing> = readArr("filings.json").let { a -> (0 until a.length()).map { Filing.from(a.getJSONObject(it)) }.toMutableList() }
    /**
     * Has this text already been reported to [a]? A report covers the exact texts it listed and every other text from
     * that sender received up to its [Filing.cutoff] (the newest text it included). Anything the sender sends after
     * that is a new event and gets its own report. Matching on time as well as text identity means small differences
     * in how a text was captured (SMS inbox vs. spam folder vs. notification) can't cause a duplicate filing.
     */
    fun reported(m: Msg, a: Agency, fs: List<Filing> = filings()) = fs.any { f -> f.ok && f.agency == a &&
        (m.key() in f.events || (Phone.digits(f.sender) == Phone.digits(m.sender) && m.ts / 60_000 <= f.cutoff / 60_000)) }  // minute precision: agency/ledger times are to the minute

    /**
     * Merge reports filed elsewhere (or exported from another install). Returns (added, alreadyKnown).
     * Format: {"version":1,"filings":[{agency,sender,confirmation,filedAt,coversUntil,texts:[{ts,body}]}]}
     */
    @Synchronized fun importHistory(json: String): Pair<Int, Int> {
        val arr = JSONObject(json).getJSONArray("filings")
        val have = filings(); var added = 0; var known = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val agency = Agency.valueOf(o.getString("agency")); val sender = Phone.dashed(o.getString("sender"))
            val conf = if (o.isNull("confirmation")) null else o.optString("confirmation").ifBlank { null }
            val texts = o.optJSONArray("texts")?.let { t -> (0 until t.length()).map { t.getJSONObject(it) } } ?: emptyList()
            val copy = texts.map { it.getLong("ts") to it.optString("body") }
            val existing = have.firstOrNull { conf != null && it.agency == agency && it.confirmation == conf }
            if (existing != null) {
                if (existing.texts.isEmpty() && copy.isNotEmpty()) upsertFiling(existing.copy(texts = copy))   // backfill the text copy
                known++; continue }
            val events = texts.map { Msg(sender, it.getLong("ts"), it.optString("body"), Source.SMS, true).key() }
            val f = Filing(sender, agency, if (conf != null) "confirmed" else "submitted", conf, o.getLong("filedAt"),
                "imported", events, o.optLong("coversUntil"), copy)
            if (have.any { it.id == f.id && it.ok && (conf == null || it.confirmation == null) }) { known++; continue }
            upsertFiling(f); added++
        }
        return added to known
    }

    /**
     * The texts a report covered: its stored copy, else the matching messages on this phone (by event key, else
     * same sender received after the previous report's cutoff and up to this one's, to the minute).
     */
    fun coveredTexts(f: Filing, msgs: List<Msg> = messages(), fs: List<Filing> = filings()): List<Pair<Long, String>> {
        if (f.texts.isNotEmpty()) return f.texts.sortedBy { it.first }
        val d = Phone.digits(f.sender)
        val mine = msgs.filter { Phone.digits(it.sender) == d }
        mine.filter { it.key() in f.events }.takeIf { it.isNotEmpty() }?.let { return it.sortedBy { m -> m.ts }.map { m -> m.ts to m.body } }
        val prev = fs.filter { it.ok && it.agency == f.agency && Phone.digits(it.sender) == d && it.cutoff < f.cutoff }.maxOfOrNull { it.cutoff / 60_000 } ?: -1
        return mine.filter { it.ts / 60_000 <= f.cutoff / 60_000 && it.ts / 60_000 > prev }.sortedBy { it.ts }
            .distinctBy { it.ts / 60_000 to it.body.trim() }.map { it.ts to it.body }
    }

    fun exportHistory(): String {
        val ms = messages(); val fs = filings()
        return JSONObject().put("version", 1).put("filings", JSONArray(fs.filter { it.ok }.map { f ->
            JSONObject().put("agency", f.agency.name).put("sender", f.sender).put("confirmation", f.confirmation ?: JSONObject.NULL)
                .put("filedAt", f.filedAt).put("coversUntil", f.cutoff)
                .put("texts", JSONArray(coveredTexts(f, ms, fs).map { (ts, b) -> JSONObject().put("ts", ts).put("body", b) }))
        })).toString(1)
    }

    @Synchronized fun upsertFiling(fl: Filing) {
        // replaces a failed/unnumbered attempt at the same events, or the same numbered report; two reports with
        // different agency numbers are both kept (e.g. an accidental duplicate is still a real report on record)
        val all = filings(); all.removeAll { it.id == fl.id && (!it.ok || it.confirmation == null || fl.confirmation == null || it.confirmation == fl.confirmation) }; all.add(fl)
        write("filings.json", JSONArray(all.map { it.toJson() }).toString())
    }
    /** Called by the notification listener when an FCC ticket email arrives. */
    @Synchronized fun attachFccTicket(senderDigits: String, ticket: String) {
        val mine = filings().filter { it.agency == Agency.FCC && Phone.digits(it.sender) == senderDigits }
        if (mine.any { it.confirmation == ticket }) return
        // tickets arrive in filing order: give it to the oldest submitted report for this sender still waiting on one
        val fl = mine.filter { it.status == "submitted" && it.confirmation == null }.minByOrNull { it.filedAt } ?: return
        fl.confirmation = ticket; fl.status = "confirmed"; upsertFiling(fl)
    }
}

object Phone {
    fun digits(s: String) = s.filter(Char::isDigit).let { if (it.length == 11 && it.startsWith("1")) it.drop(1) else it }
    fun dashed(s: String) = digits(s).let { if (it.length == 10) "${it.substring(0,3)}-${it.substring(3,6)}-${it.substring(6)}" else s }
    /** Short codes (5-6 digits) and alphanumeric senders can't be filed as phone numbers. */
    fun isTenDigit(s: String) = digits(s).length == 10
}
