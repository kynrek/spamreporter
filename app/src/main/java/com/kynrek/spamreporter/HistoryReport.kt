package com.kynrek.spamreporter

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One filed report with the texts it covered — shared by the History screen and the PDF. */
data class HistoryEntry(val filing: Filing, val category: Category?, val texts: List<Pair<Long, String>>) {
    val agencyName get() = if (filing.agency == Agency.FCC) "FCC consumer complaint" else "FTC Do Not Call report"
    val numberLabel get() = if (filing.agency == Agency.FCC) "Ticket" else "Report"
}

object HistoryReport {
    val when_ = SimpleDateFormat("MMM d, yyyy h:mm a z", Locale.US)

    fun entries(store: Store): List<HistoryEntry> {
        val ms = store.messages(); val fs = store.filings(); val cats = store.categories()
        return fs.filter { it.ok }.sortedByDescending { it.filedAt }
            .map { f -> HistoryEntry(f, cats[Phone.digits(f.sender)], store.coveredTexts(f, ms, fs)) }
    }

    /** Letter-size PDF: who it was reported to, report number and date, sender, and each text's time and content. */
    fun pdf(ctx: Context, store: Store): Uri? {
        val p = store.profile; val list = entries(store)
        val doc = PdfDocument()
        val W = 612; val H = 792; val M = 54f; val width = (W - 2 * M).toInt()
        fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint().apply {
            isAntiAlias = true; textSize = size; this.color = color; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL) }
        val title = paint(18f, true); val h2 = paint(12.5f, true); val body = paint(10f); val meta = paint(9f, color = Color.DKGRAY)
        val quote = paint(10f, color = Color.rgb(40, 40, 40))
        var pageNo = 0; lateinit var page: PdfDocument.Page; var y = 0f
        fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); y = M
            page.canvas.drawText("Spam text complaint history · ${p.first} ${p.last} · ${p.phoneDashed()} · page $pageNo", M, H - 28f, meta)
        }
        fun para(text: String, tp: TextPaint, before: Float = 0f, indent: Float = 0f, keep: Float = 0f) {
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, width - indent.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            y += before
            if (y + minOf(lay.height.toFloat(), 60f) + keep > H - M) newPage()
            for (i in 0 until lay.lineCount) {
                val top = lay.getLineTop(i); val h = lay.getLineBottom(i) - top
                if (y + h > H - M) newPage()
                val c = page.canvas; c.save(); c.translate(M + indent, y - top); c.clipRect(0, top, width, top + h); lay.draw(c); c.restore()
                y += h
            }
        }
        newPage()
        para("Spam text complaint history", title)
        para("Prepared for ${p.first} ${p.last} · texts received on ${p.phoneDashed()}" +
            (p.dncRegisteredOn.ifBlank { null }?.let { " (on the National Do Not Call Registry since $it)" } ?: "") +
            "\nGenerated ${when_.format(Date())} by Spam Reporter", meta, 4f)
        val fcc = list.count { it.filing.agency == Agency.FCC }; val ftc = list.size - fcc
        para("${list.size} reports: $fcc FCC complaints, $ftc FTC Do Not Call reports, about ${list.map { Phone.digits(it.filing.sender) }.distinct().size} senders.", body, 8f)
        for (e in list) {
            val f = e.filing
            para("${e.agencyName} — ${e.numberLabel} #${f.confirmation ?: "(number pending)"}", h2, 16f, keep = 40f)
            para("Filed ${when_.format(Date(f.filedAt))} · Sender ${f.sender}" + (e.category?.let { " · ${it.name.lowercase()}" } ?: ""), meta, 2f)
            if (e.texts.isEmpty()) para("(text not available on this phone)", meta, 4f, 12f)
            for ((ts, b) in e.texts) {
                para("Received ${when_.format(Date(ts))} from ${f.sender}:", meta, 6f, 12f)
                para(b.trim(), quote, 1f, 12f)
            }
        }
        doc.finishPage(page)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "spam-report-history-$stamp.pdf"); put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpamReporter")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null.also { doc.close() }
        ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        return uri
    }
}
