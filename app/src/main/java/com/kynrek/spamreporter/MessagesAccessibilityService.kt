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

    override fun onInterrupt() {}
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun importFolder() {
        val store = Store(this)
        val names = LinkedHashSet<String>()
        // 1) collect conversation names (sender numbers) by scrolling the list
        for (i in 0 until 40) {
            val root = rootInActiveWindow ?: break
            val before = names.size
            findAll(root) { it.viewIdResourceName?.endsWith("conversation_name") == true }.forEach { n -> n.text?.toString()?.let(names::add) }
            if (i > 0 && names.size == before) break
            scrollList(root, forward = true); delay(700)
        }
        lastStatus = "found ${names.size} spam conversations"
        store.addSpamSenders(names.filter { Phone.isTenDigit(it) })
        // back to top
        repeat(15) { rootInActiveWindow?.let { scrollList(it, forward = false) }; delay(150) }

        // 2) open each conversation and read message descriptions
        var done = 0
        for (name in names) {
            val node = findConversation(name) ?: continue
            (node.parentClickable() ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(1800)
            val descs = LinkedHashSet<String>()
            for (i in 0 until 8) {
                val root = rootInActiveWindow ?: break
                val before = descs.size
                findAll(root) { (it.contentDescription?.length ?: 0) > 15 && it.contentDescription!!.contains(" said ") }
                    .forEach { descs.add(it.contentDescription.toString()) }
                if (i > 0 && descs.size == before) break
                scrollList(root, forward = false); delay(700)
            }
            val msgs = descs.mapNotNull { parse(it, name) }
            store.addMessages(msgs)
            done++; lastStatus = "imported $done/${names.size}"
            performGlobalAction(GLOBAL_ACTION_BACK); delay(1200)
        }
        lastStatus = "done: ${names.size} conversations"
    }

    private suspend fun findConversation(name: String): AccessibilityNodeInfo? {
        repeat(15) { rootInActiveWindow?.let { scrollList(it, forward = false) }; delay(120) }
        for (i in 0 until 30) {
            val root = rootInActiveWindow ?: return null
            findAll(root) { it.viewIdResourceName?.endsWith("conversation_name") == true && it.text?.toString() == name }
                .firstOrNull()?.let { return it }
            scrollList(root, forward = true); delay(600)
        }
        return null
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
