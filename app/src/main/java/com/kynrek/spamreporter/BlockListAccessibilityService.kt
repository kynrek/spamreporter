package com.kynrek.spamreporter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/** Numbers Spam Reporter added to the phone's Blocked numbers list (so it can offer Undo), plus the pending job. */
object TextBlock {
    private fun f(c: Context) = File(c.filesDir, "text_blocked.json")
    fun added(c: Context): MutableSet<String> = runCatching { JSONArray(f(c).readText()).let { a -> (0 until a.length()).map(a::getString) } }
        .getOrDefault(emptyList()).toMutableSet()
    @Synchronized fun mark(c: Context, d: String, on: Boolean) { val s = added(c); if (if (on) s.add(d) else s.remove(d)) f(c).writeText(JSONArray(s.toList()).toString()) }
    fun wipe(c: Context) = f(c).delete()

    /** Job for the service: numbers to add, numbers to remove. Set by BlockActivity right before opening the screen. */
    @Volatile var toAdd: List<String> = emptyList()
    @Volatile var toRemove: List<String> = emptyList()
    @Volatile var status = ""
    @Volatile var running = false
    fun pending() = toAdd.isNotEmpty() || toRemove.isNotEmpty()
}

/**
 * Fills in the phone's own Blocked numbers screen (Telecom settings) for a job the user started in Spam Reporter:
 * "Add a number" → type → Block, or the row's Unblock (X) → confirm. Then goes back to the app.
 * Limited by its config to the Telecom settings packages; does nothing unless a job is pending.
 */
class BlockListAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!TextBlock.pending() || TextBlock.running) return
        val root = rootInActiveWindow ?: return
        if (byId(root, "add_blocked") == null) return
        TextBlock.running = true
        scope.launch { try { runJob() } catch (t: Throwable) { TextBlock.status = "Stopped: ${t.message}" } finally {
            TextBlock.toAdd = emptyList(); TextBlock.toRemove = emptyList(); TextBlock.running = false
            performGlobalAction(GLOBAL_ACTION_BACK) } }
    }
    override fun onInterrupt() {}
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun byId(root: AccessibilityNodeInfo, id: String) = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id").firstOrNull()
    private fun button1(root: AccessibilityNodeInfo) = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
    private suspend fun waitFor(ms: Long = 4000, f: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { rootInActiveWindow?.let(f)?.let { return it }; delay(150) }
        return null
    }

    /** Numbers currently on the list (scrolls through it once). */
    private suspend fun listed(): Set<String> {
        val out = HashSet<String>()
        repeat(60) {
            val root = rootInActiveWindow ?: return out
            val before = out.size
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/blocked_number").forEach { out += Phone.digits(it.text?.toString() ?: "") }
            val list = root.findAccessibilityNodeInfosByViewId("android:id/list").lastOrNull() ?: return out
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) && out.size == before) return out
            delay(250)
        }
        return out
    }

    private suspend fun runJob() {
        val adds = TextBlock.toAdd; val removes = TextBlock.toRemove
        TextBlock.status = "Checking the phone's Blocked numbers list…"
        val already = listed()
        var added = 0; var removed = 0; var skipped = 0
        for (d in adds) {
            if (d in already) { TextBlock.mark(this, d, true); skipped++; continue }
            TextBlock.status = "Blocking ${Phone.dashed(d)} (${added + skipped + 1} of ${adds.size})"
            val add = waitFor { byId(it, "add_blocked") } ?: error("couldn't find Add a number")
            add.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val field = waitFor { byId(it, "add_blocked_number") } ?: error("no number field")
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, d) })
            delay(200)
            (waitFor { button1(it) } ?: error("no Block button")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitFor { r -> r.takeIf { byId(it, "add_blocked_number") == null } }
            TextBlock.mark(this, d, true); added++
            delay(300)
        }
        for (d in removes) {
            TextBlock.status = "Unblocking ${Phone.dashed(d)}"
            var done = false
            // scroll back to the top, then down until the row shows up
            repeat(30) { rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/list")?.lastOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            repeat(60) {
                if (done) return@repeat
                val root = rootInActiveWindow ?: return@repeat
                val row = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/blocked_number").firstOrNull { Phone.digits(it.text?.toString() ?: "") == d }
                if (row != null) {
                    val x = row.parent?.let { p -> (0 until p.childCount).mapNotNull(p::getChild).firstOrNull { it.viewIdResourceName?.endsWith("delete_blocked_number") == true } }
                    x?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    waitFor(2000) { button1(it) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(400); done = true
                } else if (root.findAccessibilityNodeInfosByViewId("android:id/list").lastOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return@repeat
                else delay(250)
            }
            TextBlock.mark(this, d, false); if (done) removed++
        }
        TextBlock.status = listOfNotNull("Blocked $added".takeIf { adds.isNotEmpty() }, "$skipped were already blocked".takeIf { skipped > 0 },
            "Unblocked $removed".takeIf { removes.isNotEmpty() }).joinToString(" · ")
    }
}
