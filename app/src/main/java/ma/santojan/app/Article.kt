package ma.santojan.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/** Lecture d'un article dans l'app : texte propre + résumé IA + PDF. */
object Article {
    var current: NewsItem? = null
    val texts = HashMap<String, String>()
    val briefs = HashMap<String, String>()
    val loading = HashSet<String>()
    val tried = HashSet<String>()

    fun extract(html: String): String {
        var h = html.replace(Regex("(?is)<(script|style|noscript|nav|footer|header|aside|form)[^>]*>.*?</\\1>"), " ")
        val desc = Regex("(?is)<meta[^>]+(?:property|name)=[\"'](?:og:description|description)[\"'][^>]+content=[\"']([^\"']+)").find(h)?.groupValues?.get(1)
        val paras = Regex("(?is)<p[^>]*>(.*?)</p>").findAll(h).map {
            android.text.Html.fromHtml(it.groupValues[1].replace(Regex("(?is)<[^>]+>"), " "), 0).toString()
                .replace(Regex("\\s+"), " ").trim()
        }.filter { p ->
            p.length >= 50 && listOf("©", "cookie", "جميع الحقوق", "اشترك", "suscr", "abonn", "newsletter", "javascript").none { p.lowercase().contains(it) }
        }.distinct().take(30).toList()
        var body = paras.joinToString("\n\n")
        if (body.length < 300) {
            // articles sans <p> (ex. Nadorcity) : on prend le bloc de texte principal
            Regex("(?is)<div[^>]+class=\"[^\"]*(?:entry-content|texte|article-body|post-content|article-content)[^\"]*\"[^>]*>").find(h)?.let { m ->
                val seg = h.substring(m.range.last + 1, minOf(h.length, m.range.last + 20000))
                    .replace(Regex("(?is)<br\\s*/?>|</p>|</div>"), "\n")
                val clean = android.text.Html.fromHtml(seg.replace(Regex("(?is)<[^>]+>"), " ").replace("\n", "<br>"), 0).toString()
                val lines = clean.split("\n").map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.length >= 40 }.distinct().take(30)
                if (lines.joinToString("").length > body.length) body = lines.joinToString("\n\n")
            }
        }
        return if (body.length > 200) body else listOfNotNull(desc?.let { android.text.Html.fromHtml(it, 0).toString() }, body)
            .joinToString("\n\n").trim()
    }
}

fun MainActivity.openArticle(n: NewsItem) {
    Article.current = n
    show(TAB_ARTICLE)
}

private fun MainActivity.loadArticle(n: NewsItem) {
    if (n.link in Article.loading) return
    Article.loading.add(n.link)
    Article.tried.add(n.link)
    Thread {
        // 1) le texte d'abord (rapide), on l'affiche tout de suite
        val t = Article.texts[n.link] ?: try { Article.extract(Net.get(n.link, 15000)) } catch (e: Exception) { "" }
        Article.texts[n.link] = t
        runOnUiThread { if (currentTab() == TAB_ARTICLE && Article.current?.link == n.link) refresh() }
        // 2) ensuite le résumé
        if (t.length > 150 && CallAi.key(this).isNotBlank() && Article.briefs[n.link] == null) {
            try {
                Article.briefs[n.link] = CallAi.ask(this, "لخّص هاد المقال فـ 3 حتى 5 نقط قصيرة بالعربية البسيطة. ما تزيدش معلومات ما كايناش فالمقال.\n" +
                    "العنوان: ${n.title}\n\n${t.take(9000)}").first
            } catch (_: Exception) {}
        }
        runOnUiThread {
            Article.loading.remove(n.link)
            if (currentTab() == TAB_ARTICLE && Article.current?.link == n.link) refresh()
        }
    }.start()
}

/** Prépare les premiers articles en arrière-plan pour qu'ils s'ouvrent vite. */
fun prefetchArticles(items: List<NewsItem>) {
    val todo = items.take(10).filter { it.link !in Article.texts }
    if (todo.isEmpty()) return
    Thread {
        todo.forEach { n ->
            if (n.link !in Article.texts) try { Article.texts[n.link] = Article.extract(Net.get(n.link, 12000)) } catch (_: Exception) {}
        }
    }.start()
}

fun MainActivity.articleScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    val n = Article.current ?: run { col.addView(Ui.text(ctx, "ما كاين حتى مقال", 14f, Ui.MUTED)); return Ui.scroll(ctx, col) }

    col.addView(Ui.button(ctx, "← رجع للأخبار", Ui.CHIP) { show(TAB_NEWS) })
    col.addView(Ui.gap(ctx, 6))
    col.addView(Ui.text(ctx, n.title, 20f, Ui.TEXT, true))
    col.addView(Ui.text(ctx, "${n.source}  •  ${if (n.time > 0) Fmt.date(n.time) else ""}", 12f, Ui.MUTED))
    col.addView(Ui.gap(ctx, 8))

    val text = Article.texts[n.link]
    if (text == null) {
        col.addView(Ui.text(ctx, "⏳ كنجيب المقال...", 15f, Ui.MUTED))
        loadArticle(n)
        return Ui.scroll(ctx, col)
    }

    val brief = Article.briefs[n.link]
    if (brief == null && n.link !in Article.tried && CallAi.key(ctx).isNotBlank() && text.length > 150) loadArticle(n)
    if (brief != null) {
        val b = Ui.card(ctx)
        b.addView(Ui.text(ctx, "🤖 باختصار", 15f, Ui.ACCENT, true))
        b.addView(Ui.text(ctx, brief, 15f).apply { setTextIsSelectable(true) })
        col.addView(b)
    } else if (n.link in Article.loading) {
        col.addView(Ui.text(ctx, "🤖 كنكتب الملخص... (تقدر تقرا المقال لتحت)", 13f, Ui.MUTED))
    } else if (CallAi.key(ctx).isBlank()) {
        col.addView(Ui.text(ctx, "باش يبان ليك ملخص قصير، حط مفتاح Gemini فـ «الإعدادات».", 12f, Ui.MUTED))
    }

    val r = Ui.row(ctx)
    r.addView(Ui.button(ctx, "📄 PDF", Ui.BLUE) { savePdf(n, brief, text) }, Ui.lp(ctx, 1f))
    r.addView(Ui.button(ctx, "📤 بارطاجي المقال", Ui.CHIP) {
        val body = n.title + "\n(" + n.source + ")\n\n" + (if (brief != null) "باختصار:\n$brief\n\n" else "") + text
        openSafe(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, body.take(60000)), "بارطاجي"))
    }, Ui.lp(ctx, 1f))
    col.addView(r)

    val c = Ui.card(ctx)
    if (text.isBlank()) {
        c.addView(Ui.text(ctx, "ما قدرتش نجيب هاد المقال. شوف الإنترنيت وعاود.", 14f, Ui.MUTED))
        c.addView(Ui.button(ctx, "🔄 عاود", Ui.BLUE) { Article.texts.remove(n.link); refresh() })
    } else {
        c.addView(Ui.text(ctx, text, 16f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.25f) })
    }
    col.addView(c)
    return Ui.scroll(ctx, col)
}

/** Enregistre l'article en PDF dans Téléchargements/Jassim. */
private fun MainActivity.savePdf(n: NewsItem, brief: String?, text: String) {
    try {
        val doc = PdfDocument()
        val w = 595; val h = 842; val m = 40
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val full = n.title + "\n" + n.source + (if (n.time > 0) " — " + Fmt.date(n.time) else "") + "\n\n" +
            (if (brief != null) "باختصار:\n$brief\n\n" else "") + text
        val layout = StaticLayout.Builder.obtain(full, 0, full.length, tp, w - 2 * m)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).build()
        var line = 0; var page = 1
        while (line < layout.lineCount) {
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, page).create())
            val top = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - top <= h - 2 * m) last++
            val cv = pg.canvas
            cv.save()
            cv.translate(m.toFloat(), (m - top).toFloat())
            cv.clipRect(0, top, w - 2 * m, layout.getLineBottom(last - 1))
            layout.draw(cv)
            cv.restore()
            doc.finishPage(pg)
            line = last; page++
        }
        val name = "Jassim_" + System.currentTimeMillis() + ".pdf"
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 29) {
            val cvs = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Jassim")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cvs)
        } else null
        if (uri == null) { toast("ما قدرتش نحفظ PDF"); doc.close(); return }
        contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        toast("✅ تحفظ فـ Download/Jassim")
        openSafe(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        toast("ما قدرتش نصاوب PDF: ${e.message}")
    }
}
