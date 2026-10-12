// v0.6 – 2026-10-12: çoklu dosya, sayfa düzeni (Tek/2'li/4'lü/6'lı/Kimlik), önizleme, PDF olarak kaydetme
package com.onur.tvgonder

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
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
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** Yazdırılacak tek bir kaynak: PDF (çok sayfa) ya da resim (tek sayfa). */
sealed class PrintItem(val name: String) {
    class Pdf(name: String, val file: File, val pages: Int) : PrintItem(name)
    class Img(name: String, val uri: Uri) : PrintItem(name)

    val count: Int get() = if (this is Pdf) pages else 1
}

/** Bir kağıda kaç sayfa/resim konacağı. Kimlik: ön + arka yüz gerçek kart boyutunda. */
enum class Layout(val perSheet: Int, val label: String) {
    One(1, "Tek"), Two(2, "2'li"), Four(4, "4'lü"), Six(6, "6'lı"), Id(2, "Kimlik")
}

/** Seçilen tüm dosyalar; her PDF sayfası ve her resim bir "parça". */
class PrintJob(val items: List<PrintItem>) {
    val tiles: List<Pair<PrintItem, Int>> = items.flatMap { item -> List(item.count) { p -> item to p } }
    fun sheets(layout: Layout): Int = max(1, (tiles.size + layout.perSheet - 1) / layout.perSheet)
}

object Printing {

    private fun mm(v: Float) = v * 72f / 25.4f   // milimetre → nokta (1/72 inç)

    // ------------------------------------------------------------ yükleme

    private fun nameOf(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "Belge")

    /** Seçilen dosyaları okur. Desteklenmeyenleri atlar; hiçbiri okunamazsa mesajlı hata fırlatır. */
    fun load(ctx: Context, uris: List<Uri>): List<PrintItem> {
        val dir = File(ctx.cacheDir, "print").apply { mkdirs() }
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L
        dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }

        val out = mutableListOf<PrintItem>()
        var lastError: String? = null
        for (uri in uris) {
            val r = runCatching {
                val cr = ctx.contentResolver
                val name = nameOf(ctx, uri)
                val type = cr.getType(uri) ?: ""
                val isPdf = type == "application/pdf" || name.lowercase().endsWith(".pdf")
                val isImg = type.startsWith("image/") ||
                    Regex(""".*\.(jpe?g|png|webp|heic|heif|bmp|gif)$""", RegexOption.IGNORE_CASE).matches(name)
                when {
                    isPdf -> {
                        val f = File(dir, "p_${System.nanoTime()}.pdf")
                        cr.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                            ?: error("$name açılamadı.")
                        val pages = PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
                        if (pages == 0) error("$name boş.")
                        PrintItem.Pdf(name, f, pages)
                    }
                    isImg -> PrintItem.Img(name, uri)
                    else -> error("$name: sadece PDF ve resim yazdırılabilir.")
                }
            }
            r.onSuccess { out += it }.onFailure { lastError = it.message }
        }
        if (out.isEmpty()) error(lastError ?: "Dosya açılamadı.")
        return out
    }

    // ------------------------------------------------------------ bitmap üretimi

    private fun decodeImage(ctx: Context, uri: Uri, maxPx: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder fotoğrafın yönünü (EXIF) kendisi düzeltir.
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val s = min(1f, maxPx.toFloat() / max(w, h))
                dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (max(b.outWidth, b.outHeight) / s > maxPx) s *= 2
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: error("Resim açılamadı.")
    }

    private fun renderPdfPage(file: File, page: Int, maxPx: Int): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(page).use { p ->
                val s = maxPx.toFloat() / max(p.width, p.height)
                val b = Bitmap.createBitmap(max(1, (p.width * s).toInt()), max(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                b
            }
        }

    fun tileBitmap(ctx: Context, item: PrintItem, page: Int, maxPx: Int): Bitmap = when (item) {
        is PrintItem.Pdf -> renderPdfPage(item.file, page, maxPx)
        is PrintItem.Img -> decodeImage(ctx, item.uri, maxPx)
    }

    fun thumb(ctx: Context, item: PrintItem): Bitmap? = runCatching { tileBitmap(ctx, item, 0, 240) }.getOrNull()

    // ------------------------------------------------------------ kağıda yerleştirme

    private fun paint(color: Boolean) = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        if (!color) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    /** Bitmap'i kutuya oranını bozmadan sığdırır; autoRotate ise daha iyi sığıyorsa 90° çevirir. */
    private fun drawFit(c: Canvas, bmp: Bitmap, box: RectF, p: Paint, autoRotate: Boolean) {
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        val rotate = autoRotate && (bw > bh) != (box.width() > box.height()) && bw != bh
        c.save()
        c.translate(box.centerX(), box.centerY())
        val s: Float
        if (rotate) {
            c.rotate(90f)
            s = min(box.width() / bh, box.height() / bw)
        } else {
            s = min(box.width() / bw, box.height() / bh)
        }
        c.drawBitmap(bmp, null, RectF(-bw * s / 2, -bh * s / 2, bw * s / 2, bh * s / 2), p)
        c.restore()
    }

    /** Bir kağıdı çizer. area: kağıdın tamamı (nokta biriminde). */
    fun drawSheet(ctx: Context, c: Canvas, area: RectF, job: PrintJob, layout: Layout, sheet: Int, color: Boolean, maxPx: Int) {
        val m = mm(8f)
        val r = RectF(area.left + m, area.top + m, area.right - m, area.bottom - m)
        val start = sheet * layout.perSheet
        if (start >= job.tiles.size) return
        val tiles = job.tiles.subList(start, min(start + layout.perSheet, job.tiles.size))
        val p = paint(color)

        if (layout == Layout.Id) {
            // Kimlik/kart: 85,6 × 54 mm, üst üste, arada boşluk; kesim için ince çerçeve.
            var cw = mm(85.6f); var ch = mm(54f)
            val gap = mm(12f)
            val fit = min(1f, min(r.width() / cw, (r.height() - gap) / (2 * ch)))
            cw *= fit; ch *= fit
            val top = r.top + mm(6f)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; this.color = Color.LTGRAY }
            tiles.forEachIndexed { k, (item, page) ->
                val box = RectF(r.centerX() - cw / 2, top + k * (ch + gap), r.centerX() + cw / 2, top + k * (ch + gap) + ch)
                val bmp = tileBitmap(ctx, item, page, maxPx)
                drawFit(c, bmp, box, p, autoRotate = true)
                bmp.recycle()
                c.drawRect(box, frame)
            }
            return
        }

        val land = r.width() > r.height()
        val (cols, rows) = when (layout) {
            Layout.One -> 1 to 1
            Layout.Two -> if (land) 2 to 1 else 1 to 2
            Layout.Four -> 2 to 2
            Layout.Six -> if (land) 3 to 2 else 2 to 3
            Layout.Id -> 1 to 2
        }
        val gap = if (layout == Layout.One) 0f else mm(4f)
        val cw = (r.width() - gap * (cols - 1)) / cols
        val chh = (r.height() - gap * (rows - 1)) / rows
        tiles.forEachIndexed { k, (item, page) ->
            val col = k % cols; val row = k / cols
            val box = RectF(r.left + col * (cw + gap), r.top + row * (chh + gap), r.left + col * (cw + gap) + cw, r.top + row * (chh + gap) + chh)
            val bmp = tileBitmap(ctx, item, page, maxPx)
            drawFit(c, bmp, box, p, autoRotate = layout != Layout.One)
            bmp.recycle()
        }
    }

    private fun maxPxFor(layout: Layout) = when (layout) {
        Layout.One -> 2200
        Layout.Two, Layout.Id -> 1600
        else -> 1200
    }

    /** Kağıt boyutu (nokta): A4 595×842, A5 420×595. */
    private fun pageSize(a5: Boolean, landscape: Boolean): Pair<Int, Int> {
        val (w, h) = if (a5) 420 to 595 else 595 to 842
        return if (landscape) h to w else w to h
    }

    /** Önizleme: bir kağıdın küçük resmi. */
    fun preview(ctx: Context, job: PrintJob, layout: Layout, sheet: Int, a5: Boolean, landscape: Boolean, color: Boolean, widthPx: Int = 700): Bitmap {
        val (pw, ph) = pageSize(a5, landscape)
        val s = widthPx.toFloat() / pw
        val bmp = Bitmap.createBitmap(widthPx, (ph * s).toInt(), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        c.scale(s, s)
        drawSheet(ctx, c, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), job, layout, sheet, color, 900)
        return bmp
    }

    /** Tüm kağıtları tek PDF olarak yazar (kaydetmek veya paylaşmak için). */
    fun writePdf(ctx: Context, job: PrintJob, layout: Layout, a5: Boolean, landscape: Boolean, color: Boolean, out: OutputStream) {
        val (pw, ph) = pageSize(a5, landscape)
        val doc = PdfDocument()
        try {
            for (i in 0 until job.sheets(layout)) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                drawSheet(ctx, page.canvas, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), job, layout, i, color, maxPxFor(layout))
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Android'in yazdırma ekranını açar. Yazıcı, sayfa aralığı ve kopya o ekranda seçilir. */
    fun print(ctx: Context, job: PrintJob, layout: Layout, a5: Boolean, landscape: Boolean, color: Boolean) {
        val activity = findActivity(ctx) ?: error("Yazdırma ekranı açılamadı.")
        val base = if (a5) PrintAttributes.MediaSize.ISO_A5 else PrintAttributes.MediaSize.ISO_A4
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (landscape) base.asLandscape() else base.asPortrait())
            .setColorMode(if (color) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val name = if (job.items.size == 1) job.items[0].name else "TV Gönder – ${job.items.size} dosya"
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, DocAdapter(activity, job, layout, color, name), attrs)
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private class DocAdapter(
        val ctx: Context, val job: PrintJob, val layout: Layout, val color: Boolean, val name: String,
    ) : PrintDocumentAdapter() {
        private var attrs: PrintAttributes? = null

        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal?,
            cb: LayoutResultCallback, extras: Bundle?,
        ) {
            attrs = new
            if (signal?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(job.sheets(layout))
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
                    val info = page.info
                    drawSheet(
                        ctx, page.canvas, RectF(0f, 0f, info.pageWidth.toFloat(), info.pageHeight.toFloat()),
                        job, layout, i, color, maxPxFor(layout),
                    )
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

        private fun indexes(pages: Array<out PageRange>?): List<Int> {
            val last = job.sheets(layout) - 1
            if (pages == null || pages.isEmpty() || pages.any { it == PageRange.ALL_PAGES }) return (0..last).toList()
            val set = sortedSetOf<Int>()
            for (r in pages) for (p in max(r.start, 0)..min(r.end, last)) set.add(p)
            return set.toList()
        }
    }
}
