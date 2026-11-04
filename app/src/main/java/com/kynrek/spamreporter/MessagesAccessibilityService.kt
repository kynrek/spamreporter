package com.kynrek.spamreporter

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RCS access method 1 (existing messages).
 * Google Messages keeps RCS chats and its spam flags in private storage that no third-party app can read,
 * so this service reads what Messages shows on screen in its "Spam & blocked" folder: every conversation
 * there is spam-flagged by the user (or by Google), and each message bubble's accessibility description
 * contains "<sender> said <text> <time> ." which gives us sender, text and timestamp — RCS included.
 *
 * It only acts when the user has pressed "Import spam folder" in the app ([armed]) and then opened
 * Messages > profile menu > Spam & blocked.
 */
class MessagesAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var armed = false
        @Volatile var running = false
        @Volatile var lastStatus = "idle"
        /** True only while Android actually has the service running (it can stay "enabled" but unbound after an app update). */
        @Volatile var connected = false
        const val PKG = "com.google.android.apps.messaging"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!armed || running || e.packageName != PKG) return
        val root = rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("Spam & blocked").isEmpty()) return
        if (findAll(root) { it.viewIdResourceName?.endsWith("conversation_name") == true }.isEmpty()) return
        running = true; armed = false
        scope.launch { try { importFolder() } catch (t: Throwable) { lastStatus = "error: $t" } finally { running = false } }
    }

    override fun onServiceConnected() { super.onServiceConnected(); connected = true }
    override fun onUnbind(intent: android.content.Intent?): Boolean { connected = false; return super.onUnbind(intent) }
    override fun onInterrupt() {}
    override fun onDestroy() { connected = false; scope.cancel(); super.onDestroy() }

    /**
     * One pass down the Spam & blocked list. Each row already shows the sender, the latest text (snippet) and
     * its day, so a conversation is only opened when that latest text is new to us: not already stored
     * (same sender, same day, same text) and not older than what an earlier report for that sender covered.
     * Rows are handled in place as the list scrolls (no scroll-to-top-and-search per conversation).
     */
    private suspend fun importFolder() {
        val store = Store(this)
        val zone = ZoneId.systemDefault()
        val known = store.messages().groupBy { Phone.digits(it.sender) }
        val covered = store.filings().filter { it.ok }.groupBy { Phone.digits(it.sender) }
            .mapValues { (_, fs) -> Instant.ofEpochMilli(fs.maxOf { it.cutoff }).atZone(zone).toLocalDate() }
        fun norm(x: String) = x.replace(Regex("\\s+"), " ").trim().lowercase()
        fun isKnown(name: String, stamp: String, snippet: String): Boolean {
            val d = Phone.digits(name)
            val day = rowDate(stamp) ?: return false          // can't tell -> open it
            covered[d]?.let { if (day.isBefore(it)) return true }  // newest text predates what we already reported
            val snip = norm(snippet).removeSuffix("…").take(40)
            return known[d].orEmpty().any { m ->
                Instant.ofEpochMilli(m.ts).atZone(zone).toLocalDate() == day && (snip.isEmpty() || norm(m.body).startsWith(snip)) }
        }

        val seen = HashSet<String>(); val senders = LinkedHashSet<String>()
        var opened = 0; var skipped = 0; var idle = 0
        for (page in 0 until 60) {
            val root = rootInActiveWindow ?: break
            var fresh = 0
            for (nameNode in findAll(root) { it.viewIdResourceName?.endsWith("conversation_name") == true }) {
                val name = nameNode.text?.toString() ?: continue
                if (!seen.add(name)) continue
                fresh++; senders += name
                val row = nameNode.parentClickable() ?: nameNode
                if (isKnown(name, child(row, "conversation_timestamp"), child(row, "conversation_snippet"))) {
                    skipped++; lastStatus = "scanning: $opened opened, $skipped already known"; continue }
                row.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(1200)
                val descs = LinkedHashSet<String>()
                for (i in 0 until 8) {
                    val r = rootInActiveWindow ?: break
                    val before = descs.size
                    findAll(r) { (it.contentDescription?.length ?: 0) > 15 && it.contentDescription!!.contains(" said ") }
                        .forEach { descs.add(it.contentDescription.toString()) }
                    if (i > 0 && descs.size == before) break
                    scrollList(r, forward = false); delay(500)
                }
                store.addMessages(descs.mapNotNull { parse(it, name) })
                opened++; lastStatus = "scanning: $opened opened, $skipped already known"
                performGlobalAction(GLOBAL_ACTION_BACK); delay(900)
            }
            if (fresh == 0) { if (++idle >= 2) break } else idle = 0
            rootInActiveWindow?.let { scrollList(it, forward = true) }; delay(600)
        }
        store.addSpamSenders(senders.filter { Phone.isTenDigit(it) })
        lastStatus = "last scan: ${senders.size} conversations, $opened opened, $skipped already known"
        getSharedPreferences("scan", MODE_PRIVATE).edit().putString("last", lastStatus).apply()
    }

    private fun child(row: AccessibilityNodeInfo, id: String) =
        findAll(row) { it.viewIdResourceName?.endsWith(id) == true }.firstOrNull()?.text?.toString().orEmpty()

    /** Date shown on a list row: "3:38 PM" (today), "Yesterday", "Tue" (past week), "Oct 30" or "Oct 30, 2025". */
    private fun rowDate(stamp: String): LocalDate? {
        val s = stamp.trim(); val today = LocalDate.now()
        if (s.isEmpty()) return null
        if (s.contains(':') || s.equals("Now", true) || s.endsWith("min", true)) return today
        if (s.equals("Yesterday", true)) return today.minusDays(1)
        if (s.none { it.isDigit() }) DayOfWeek.values().firstOrNull { it.name.startsWith(s.uppercase().take(3)) }
            ?.let { dow -> var x = today.minusDays(1); while (x.dayOfWeek != dow) x = x.minusDays(1); return x }
        return runCatching {
            val parts = s.replace(",", "").split(" ")
            val mon = Month.values().first { it.name.startsWith(parts[0].uppercase()) }
            LocalDate.of(parts.getOrNull(2)?.toInt() ?: today.year, mon, parts[1].toInt()).let { if (it.isAfter(today)) it.minusYears(1) else it }
        }.getOrNull()
    }

    // ---- parsing "(555) 123-4567 said  Hello there Monday 3:38 PM ., Not end-to-end encrypted"
    private val timeRe = Regex("""\s((?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|[A-Z][a-z]{2} \d{1,2},(?: \d{4},)?)\s)?(\d{1,2}:\d{2}\s?[AP]M)\s\.""")
    fun parse(desc: String, sender: String): Msg? {
        val d = desc.replace(' ', ' ')
        val idx = d.indexOf(" said "); if (idx < 0) return null
        val rest = d.substring(idx + 6).trim()
        val m = timeRe.findAll(rest).lastOrNull() ?: return null
        val body = rest.substring(0, m.range.first).trim()
        return Msg(sender, toEpoch(m.groupValues[1].trim().trimEnd(','), m.groupValues[2]), body, Source.RCS_SPAM_FOLDER, true)
    }

    private fun toEpoch(dayPart: String, time: String): Long {
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val t = LocalTime.parse(time.replace(" ", "").uppercase(), DateTimeFormatter.ofPattern("h:mma", Locale.US))
        val date = when {
            dayPart.isEmpty() -> today
            dayPart in DayOfWeek.values().map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.US) } -> {
                val dow = DayOfWeek.valueOf(dayPart.uppercase()); var x = today.minusDays(1)
                while (x.dayOfWeek != dow) x = x.minusDays(1); x
            }
            else -> runCatching {
                val parts = dayPart.replace(",", "").split(" ")
                val mon = Month.values().first { it.name.startsWith(parts[0].uppercase()) }
                val year = parts.getOrNull(2)?.toInt() ?: today.year
                LocalDate.of(year, mon, parts[1].toInt()).let { if (it.isAfter(today)) it.minusYears(1) else it }
            }.getOrDefault(today)
        }
        return date.atTime(t).atZone(zone).toInstant().toEpochMilli()
    }

    // ---- node helpers
    private fun findAll(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>(); val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        while (stack.isNotEmpty()) { val n = stack.removeLast(); if (pred(n)) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add) }
        return out
    }
    private fun scrollList(root: AccessibilityNodeInfo, forward: Boolean) {
        findAll(root) { it.isScrollable }.maxByOrNull { n -> android.graphics.Rect().also(n::getBoundsInScreen).height() }
            ?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
    private fun AccessibilityNodeInfo.parentClickable(): AccessibilityNodeInfo? { var p = parent
        while (p != null && !p.isClickable) p = p.parent; return p }
}
