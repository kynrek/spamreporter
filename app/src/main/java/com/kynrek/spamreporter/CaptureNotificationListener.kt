package com.kynrek.spamreporter

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * RCS access method 2 (new messages going forward).
 * Every incoming text — RCS or SMS — produces a Google Messages notification whose MessagingStyle
 * extras carry sender, text and timestamp. We save those, so RCS spam that arrives later is captured
 * even though RCS never reaches the SMS provider.
 *
 * Also: FCC complaint confirmations come by email ("…has been assigned Ticket No. 1234567"), and the
 * subject line we file includes the sender number, so a Gmail notification lets us attach the ticket
 * to the right filing automatically.
 */
class CaptureNotificationListener : NotificationListenerService() {
    private val ticketRe = Regex("""Ticket No\.?\s*(\d{5,})""")
    private val numRe = Regex("""(\d{3})-(\d{3})-(\d{4})""")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ex = sbn.notification.extras ?: return
        when (sbn.packageName) {
            MessagesAccessibilityService.PKG -> captureMessages(ex)
            "com.google.android.gm" -> captureFccTicket(ex)
        }
    }

    private fun captureMessages(ex: Bundle) {
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return
        val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr).mapNotNull { m ->
            val sender = m.senderPerson?.uri?.removePrefix("tel:") ?: m.senderPerson?.name?.toString() ?: conv ?: return@mapNotNull null
            if (m.senderPerson == null) return@mapNotNull null  // null sender = our own outgoing message
            Msg(sender, m.timestamp, m.text?.toString() ?: "", Source.NOTIFICATION, false)
        }
        if (msgs.isNotEmpty()) Store(this).addMessages(msgs)
    }

    private fun captureFccTicket(ex: Bundle) {
        val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
            .joinToString(" ") { ex.getCharSequence(it)?.toString() ?: "" }
        val ticket = ticketRe.find(text)?.groupValues?.get(1) ?: return
        if (!text.contains("Request received", true) && !text.contains("FCC", true)) return
        // our subject format: "... from 555-010-0001 to 555-010-0002" — first number is the sender
        val sender = numRe.find(text)?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] } ?: return
        Store(this).attachFccTicket(sender, ticket)
        SpamReply.autoSend(this, sender)
    }
}
