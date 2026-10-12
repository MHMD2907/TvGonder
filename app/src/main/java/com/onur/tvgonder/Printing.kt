package com.onur.tvgonder

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Yazdırılacak belge: PDF (sayfa sayfa) ya da tek sayfalık resim. */
class PrintDoc(
    val name: String,
    val file: File?,          // PDF ise önbellekteki kopya
    val pageCount: Int,
    val bitmap: Bitmap?,      // resim ise
    val thumb: Bitmap,        // önizleme
)

object Printing {

    /** PDF veya resmi okur. Hata olursa mesajlı istisna fırlatır. */
    fun load(ctx: Context, uri: Uri): PrintDoc {
        val cr = ctx.contentResolver
        val type = cr.getType(uri) ?: ""
        var name = "Belge"
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i) ?: name
            }
        }
        return when {
            type == "application/pdf" -> {
                val f = File(ctx.cacheDir, "print.pdf")
                cr.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    ?: error("Dosya açılamadı.")
                PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                    val count = r.pageCount
                    if (count == 0) error("PDF boş.")
                    val p = r.openPage(0)
                    val w = 600
                    val h = (p.height * w / p.width.toFloat()).toInt()
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close()
                    PrintDoc(name, f, count, null, b)
                }
            }
            type.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var s = 1
                while (bounds.outWidth / s > 3000 || bounds.outHeight / s > 3000) s *= 2
                val bmp = cr.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
                } ?: error("Resim açılamadı.")
                PrintDoc(name, null, 1, bmp, bmp)
            }
            else -> error("Şimdilik sadece PDF ve resim yazdırılabilir.")
        }
    }

    /** Android'in yazdırma ekranını seçilen ayarlarla açar. Sayfa aralığı, kopya ve yazıcı o ekranda seçilir. */
    fun print(ctx: Context, doc: PrintDoc, a5: Boolean, landscape: Boolean, color: Boolean) {
        val activity = findActivity(ctx) ?: error("Yazdırma ekranı açılamadı.")
        val base = if (a5) PrintAttributes.MediaSize.ISO_A5 else PrintAttributes.MediaSize.ISO_A4
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (landscape) base.asLandscape() else base.asPortrait())
            .setColorMode(if (color) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(doc.name, DocAdapter(activity, doc), attrs)
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private class DocAdapter(val ctx: Context, val doc: PrintDoc) : PrintDocumentAdapter() {
        private var attrs: PrintAttributes? = null
        private var renderer: PdfRenderer? = null

        override fun onStart() {
            doc.file?.let {
                renderer = PdfRenderer(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY))
            }
        }

        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal?,
            cb: LayoutResultCallback, extras: Bundle?,
        ) {
            attrs = new
            if (signal?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(doc.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(doc.pageCount)
                .build()
            cb.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, dest: ParcelFileDescriptor,
            signal: CancellationSignal?, cb: WriteResultCallback,
        ) {
            val a = attrs
            if (a == null) { cb.onWriteFailed("Yazdırma ayarı alınamadı."); return }
            val out = PrintedPdfDocument(ctx, a)
            try {
                var n = 0
                for (i in indexes(pages)) {
                    if (signal?.isCanceled == true) { cb.onWriteCancelled(); return }
                    val page = out.startPage(n++)
                    drawPage(i, page.canvas, page.info.contentRect)
                    out.finishPage(page)
                }
                out.writeTo(FileOutputStream(dest.fileDescriptor))
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            } finally {
                out.close()
            }
        }

        override fun onFinish() {
            renderer?.close()
            renderer = null
        }

        private fun indexes(pages: Array<out PageRange>?): List<Int> {
            val last = doc.pageCount - 1
            if (pages == null || pages.isEmpty() || pages.any { it == PageRange.ALL_PAGES }) return (0..last).toList()
            val set = sortedSetOf<Int>()
            for (r in pages) for (p in maxOf(r.start, 0)..minOf(r.end, last)) set.add(p)
            return set.toList()
        }

        private fun drawPage(i: Int, canvas: android.graphics.Canvas, rect: android.graphics.Rect) {
            val r = renderer
            val bmp: Bitmap = if (r != null) {
                val p = r.openPage(i)
                val scale = 150f / 72f
                val b = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                p.close()
                b
            } else doc.bitmap!!

            val s = minOf(rect.width().toFloat() / bmp.width, rect.height().toFloat() / bmp.height)
            val dw = bmp.width * s
            val dh = bmp.height * s
            val left = rect.left + (rect.width() - dw) / 2
            val top = rect.top + (rect.height() - dh) / 2
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)
            if (r != null) bmp.recycle()
        }
    }
}
