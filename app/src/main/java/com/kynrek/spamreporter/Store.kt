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
                  val coversUntil: Long = 0) {
    val cutoff get() = if (coversUntil > 0) coversUntil else filedAt
    /** Stable id: a retry of the same events overwrites the failed attempt instead of adding a row. */
    val id get() = "${agency.name}|${Phone.digits(sender)}|${events.minOrNull() ?: ""}"
    val ok get() = status == "submitted" || status == "confirmed"
    fun toJson() = JSONObject().put("sender", sender).put("agency", agency.name).put("status", status)
        .put("confirmation", confirmation ?: JSONObject.NULL).put("filedAt", filedAt).put("note", note)
        .put("events", JSONArray(events)).put("coversUntil", coversUntil)
    companion object {
        fun from(o: JSONObject) = Filing(o.getString("sender"), Agency.valueOf(o.getString("agency")),
            o.getString("status"), if (o.isNull("confirmation")) null else o.getString("confirmation"),
            o.getLong("filedAt"), o.optString("note"),
            o.optJSONArray("events")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList(), o.optLong("coversUntil"))
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
    @Synchronized fun addSpamSenders(s: Collection<String>) { val all = spamSenders(); all.addAll(s); write("spam_senders.json", JSONArray(all.toList()).toString()) }

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
        (m.key() in f.events || (Phone.digits(f.sender) == Phone.digits(m.sender) && m.ts <= f.cutoff)) }

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
            if (conf != null && have.any { it.agency == agency && it.confirmation == conf }) { known++; continue }
            val texts = o.optJSONArray("texts")?.let { t -> (0 until t.length()).map { t.getJSONObject(it) } } ?: emptyList()
            val events = texts.map { Msg(sender, it.getLong("ts"), it.optString("body"), Source.SMS, true).key() }
            val f = Filing(sender, agency, if (conf != null) "confirmed" else "submitted", conf, o.getLong("filedAt"),
                "imported", events, o.optLong("coversUntil"))
            if (have.any { it.id == f.id && it.ok }) { known++; continue }
            upsertFiling(f); added++
        }
        return added to known
    }

    fun exportHistory(): String {
        val byKey = messages().associateBy { it.key() }
        return JSONObject().put("version", 1).put("filings", JSONArray(filings().filter { it.ok }.map { f ->
            JSONObject().put("agency", f.agency.name).put("sender", f.sender).put("confirmation", f.confirmation ?: JSONObject.NULL)
                .put("filedAt", f.filedAt).put("coversUntil", f.cutoff)
                .put("texts", JSONArray(f.events.mapNotNull(byKey::get).map { JSONObject().put("ts", it.ts).put("body", it.body) }))
        })).toString(1)
    }

    @Synchronized fun upsertFiling(fl: Filing) {
        val all = filings(); all.removeAll { it.id == fl.id }; all.add(fl)
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
