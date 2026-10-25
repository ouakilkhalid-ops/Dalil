package ma.santojan.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Taux de change et or (source gratuite : fawazahmed0 currency-api, données quotidiennes). */
object Money {
    private const val OZ = 31.1035
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)

    class Rates(val date: String, val eur: Double, val usd: Double, val gold24: Double)

    private fun url(date: String) = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$date/v1/currencies/mad.json"
    private fun fallback(date: String) = if (date == "latest") "https://latest.currency-api.pages.dev/v1/currencies/mad.json"
        else "https://$date.currency-api.pages.dev/v1/currencies/mad.json"

    private fun load(date: String): Rates {
        val txt = try { Net.get(url(date)) } catch (e: Exception) { Net.get(fallback(date)) }
        val o = JSONObject(txt)
        val m = o.getJSONObject("mad")
        // 1 MAD = x EUR  →  1 EUR = 1/x MAD
        val eur = 1 / m.getDouble("eur")
        val usd = 1 / m.getDouble("usd")
        val ozMad = 1 / m.getDouble("xau")
        return Rates(o.optString("date", date), eur, usd, ozMad / OZ)
    }

    private fun day(offset: Int) = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.time)

    /** (aujourd'hui, hier, il y a 7 jours, il y a 30 jours) */
    fun fetchAll(): List<Rates?> {
        val dates = listOf("latest", day(-1), day(-7), day(-30))
        val res = arrayOfNulls<Rates>(4)
        dates.mapIndexed { i, d -> Thread { res[i] = try { load(d) } catch (_: Exception) { null } }.apply { start() } }
            .forEach { it.join(20000) }
        return res.toList()
    }

    fun save(c: Context, l: List<Rates?>) {
        val a = JSONArray()
        l.forEach { r -> a.put(if (r == null) JSONObject.NULL else JSONObject().put("d", r.date).put("e", r.eur).put("u", r.usd).put("g", r.gold24)) }
        p(c).edit().putString("money", a.toString()).putLong("money_at", System.currentTimeMillis()).apply()
    }

    fun cached(c: Context): List<Rates?> = try {
        val a = JSONArray(p(c).getString("money", "[]"))
        (0 until a.length()).map { i -> a.optJSONObject(i)?.let { Rates(it.getString("d"), it.getDouble("e"), it.getDouble("u"), it.getDouble("g")) } }
    } catch (e: Exception) { emptyList() }

    fun cachedAt(c: Context) = p(c).getLong("money_at", 0L)
}

private var moneyLoading = false

private fun MainActivity.trend(now: Double, before: Double?, label: String): View {
    val ctx = this
    if (before == null || before <= 0) return Ui.text(ctx, "$label: —", 12f, Ui.MUTED)
    val pct = (now - before) / before * 100
    val (arrow, color) = when {
        pct > 0.05 -> "▲ طلع" to Ui.ACCENT
        pct < -0.05 -> "▼ نزل" to Ui.RED
        else -> "● ثابت" to Ui.MUTED
    }
    return Ui.text(ctx, "$label: $arrow ${String.format(Locale.US, "%.2f", kotlin.math.abs(pct))}%", 12f, color)
}

private fun MainActivity.rateCard(icon: String, title: String, value: Double, unit: String, digits: Int,
                                 y: Double?, w: Double?, m: Double?, note: String? = null): View {
    val ctx = this
    val c = Ui.card(ctx)
    c.addView(Ui.text(ctx, "$icon $title", 16f, bold = true))
    c.addView(Ui.text(ctx, String.format(Locale.US, "%,.${digits}f", value) + " $unit", 28f, Ui.ACCENT, true))
    val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    r.addView(trend(value, y, "من البارح"))
    r.addView(trend(value, w, "من سيمانة"))
    r.addView(trend(value, m, "من شهر"))
    c.addView(r)
    if (note != null) c.addView(Ui.text(ctx, note, 11f, Ui.MUTED))
    return c
}

fun MainActivity.moneyScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "💱 العملة والذهب"))

    val l = Money.cached(ctx)
    val today = l.getOrNull(0)
    val stale = System.currentTimeMillis() - Money.cachedAt(ctx) > 3 * 3_600_000
    if ((today == null || stale) && !moneyLoading) {
        moneyLoading = true
        Thread {
            val r = Money.fetchAll()
            if (r[0] != null) Money.save(ctx, r)
            runOnUiThread {
                moneyLoading = false
                if (r[0] == null) toast("ما قدرتش نجيب الأسعار، شوف الإنترنيت")
                if (currentTab() == TAB_MONEY) refresh()
            }
        }.start()
    }
    if (today == null) {
        col.addView(Ui.text(ctx, "⏳ كنجيب الأسعار...", 15f, Ui.MUTED))
        return Ui.scroll(ctx, col)
    }
    val y = l.getOrNull(1); val w = l.getOrNull(2); val m = l.getOrNull(3)

    col.addView(rateCard("🇪🇺", "1 أورو", today.eur, "درهم", 3, y?.eur, w?.eur, m?.eur))
    col.addView(rateCard("🇺🇸", "1 دولار", today.usd, "درهم", 3, y?.usd, w?.usd, m?.usd))
    col.addView(rateCard("🥇", "الذهب 24 قيراط (غرام)", today.gold24, "درهم", 0, y?.gold24, w?.gold24, m?.gold24,
        "18 قيراط ≈ ${String.format(Locale.US, "%,.0f", today.gold24 * 0.75)} درهم  •  21 قيراط ≈ ${String.format(Locale.US, "%,.0f", today.gold24 * 0.875)} درهم\n" +
        "هادا ثمن الذهب العالمي. عند الصايغ كيكون الثمن كثر شوية (الصنعة)."))

    // Convertisseur
    val cv = Ui.card(ctx)
    cv.addView(Ui.text(ctx, "🔁 حوّل", 16f, bold = true))
    val inp = Ui.input(ctx, "المبلغ", number = true)
    val out = Ui.text(ctx, "", 15f, Ui.ACCENT, true)
    cv.addView(inp)
    val rr = Ui.row(ctx)
    fun conv(f: (Double) -> String) {
        val v = inp.text.toString().replace(',', '.').toDoubleOrNull()
        out.text = if (v == null) "كتب المبلغ" else f(v)
    }
    rr.addView(Ui.button(ctx, "€ → DH", Ui.BLUE) { conv { String.format(Locale.US, "%,.2f درهم", it * today.eur) } }, Ui.lp(ctx, 1f))
    rr.addView(Ui.button(ctx, "$ → DH", Ui.BLUE) { conv { String.format(Locale.US, "%,.2f درهم", it * today.usd) } }, Ui.lp(ctx, 1f))
    rr.addView(Ui.button(ctx, "DH → €", Ui.CHIP) { conv { String.format(Locale.US, "%,.2f €", it / today.eur) } }, Ui.lp(ctx, 1f))
    cv.addView(rr)
    cv.addView(out)
    col.addView(cv)

    col.addView(Ui.text(ctx, "تاريخ الأسعار: ${today.date}  •  آخر تحديث ${Fmt.ago(Money.cachedAt(ctx))}\n" +
        "الأسعار مرجعية (سوق عالمي). فالبنك ولا عند الصراف كيكون فرق صغير.", 11f, Ui.MUTED))
    col.addView(Ui.button(ctx, "🔄 حدّث", Ui.CHIP) {
        if (!moneyLoading) {
            moneyLoading = true
            Thread {
                val r = Money.fetchAll(); if (r[0] != null) Money.save(ctx, r)
                runOnUiThread { moneyLoading = false; refresh() }
            }.start()
        }
    })
    return Ui.scroll(ctx, col)
}

// ------------------------------------------------------------------ Gasoil

/** Prix du gasoil tiré des articles de presse marocains (Hespress), sans IA. */
object Fuel {
    class Price(val value: Double, val snippet: String, val title: String, val source: String, val link: String, val time: Long)

    private val FEEDS = listOf(
        "Hespress FR" to "https://fr.hespress.com/?s=gasoil&feed=rss2",
        "هسبريس" to "https://www.hespress.com/?s=%D8%A7%D9%84%D8%BA%D8%A7%D8%B2%D9%88%D8%A7%D9%84&feed=rss2",
        "هسبريس" to "https://www.hespress.com/?s=%D8%A7%D9%84%D9%85%D8%AD%D8%B1%D9%88%D9%82%D8%A7%D8%AA&feed=rss2"
    )
    private const val KW = "(?:gasoil|gazole|diesel|الغازوال|الكازوال|الديزل|السولار)"
    private const val NUM = "(\\d{1,2}[,.]\\d{1,2})"
    private const val CUR = "(?:dh|dhs|dirhams?|درهم|دراهم)"
    private val after = Regex("(?i)$KW[^.؛\\n]{0,120}?$NUM\\s*$CUR")
    private val before = Regex("(?i)$NUM\\s*$CUR[^.؛\\n]{0,60}?$KW")

    fun news(): List<NewsItem> {
        val res = java.util.Collections.synchronizedList(ArrayList<NewsItem>())
        FEEDS.map { (n, u) -> Thread { try { res.addAll(News.parse(Net.get(u), n)) } catch (_: Exception) {} }.apply { start() } }
            .forEach { it.join(20000) }
        return res.filter { News.isFuel(it.title) || it.title.lowercase().contains("carburant") }
            .distinctBy { it.link }.sortedByDescending { it.time }
    }

    fun find(items: List<NewsItem>): Price? {
        for (n in items.take(8)) {
            val html = try { Net.get(n.link, 15000) } catch (_: Exception) { continue }
            val txt = android.text.Html.fromHtml(html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?is)<[^>]+>"), " "), 0).toString().replace(Regex("\\s+"), " ")
            val m = (after.findAll(txt) + before.findAll(txt)).firstOrNull { r ->
                val v = r.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0; v in 7.0..25.0
            } ?: continue
            val v = m.groupValues[1].replace(',', '.').toDouble()
            val a = maxOf(0, m.range.first - 120); val b = minOf(txt.length, m.range.last + 120)
            return Price(v, "…" + txt.substring(a, b).trim() + "…", n.title, n.source, n.link, n.time)
        }
        return null
    }
}

private var fuelLoading = false

fun MainActivity.fuelScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "⛽ ثمن الغازوال"))
    val pr = getSharedPreferences("santojan", Context.MODE_PRIVATE)
    val at = pr.getLong("fuel2_at", 0L)
    if ((at == 0L || System.currentTimeMillis() - at > 6 * 3_600_000) && !fuelLoading) loadFuel()

    val v = pr.getFloat("fuel2_v", 0f)
    val card = Ui.card(ctx)
    if (v > 0f) {
        card.addView(Ui.text(ctx, "⛽ الغازوال فالمغرب (تقريبا)", 15f, Ui.MUTED, true))
        card.addView(Ui.text(ctx, String.format(Locale.US, "%.2f درهم / لتر", v), 30f, Ui.ACCENT, true))
        card.addView(Ui.text(ctx, "حسب ${pr.getString("fuel2_src", "")} — ${Fmt.date(pr.getLong("fuel2_t", 0L)).take(10)}", 12f, Ui.MUTED))
        card.addView(Ui.gap(ctx, 6))
        card.addView(Ui.text(ctx, pr.getString("fuel2_snip", "") ?: "", 13f).apply { setTextIsSelectable(true) })
        val title = pr.getString("fuel2_title", "") ?: ""; val link = pr.getString("fuel2_link", "") ?: ""
        if (link.isNotEmpty()) card.addView(Ui.button(ctx, "📰 اقرا المقال", Ui.CHIP) {
            openArticle(NewsItem(title, link, pr.getString("fuel2_src", "") ?: "", pr.getLong("fuel2_t", 0L)))
        })
    } else if (fuelLoading) {
        card.addView(Ui.text(ctx, "⏳ كنقلب فالأخبار على الثمن...", 15f, Ui.MUTED))
    } else {
        card.addView(Ui.text(ctx, "ما لقيتش الثمن فالأخبار الجديدة. عاود من بعد.", 14f, Ui.MUTED))
    }
    col.addView(card)
    col.addView(Ui.text(ctx, "الثمن كيتقارب بين Afriquia و Shell و TotalEnergies و Winxo… كيتبدل شوية من محطة لمحطة.", 11f, Ui.MUTED))
    col.addView(Ui.button(ctx, "🔄 حدّث", Ui.BLUE) { loadFuel(); refresh() })

    // Liste des articles
    val list = try {
        val a = JSONArray(pr.getString("fuel2_news", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); NewsItem(o.getString("t"), o.getString("l"), o.getString("s"), o.getLong("ti")) }
    } catch (_: Exception) { emptyList() }
    if (list.isNotEmpty()) {
        col.addView(Ui.gap(ctx, 8))
        col.addView(Ui.text(ctx, "📰 آخر أخبار المحروقات", 15f, Ui.TEXT, true))
        list.take(8).forEach { n ->
            val c = Ui.card(ctx)
            c.addView(Ui.text(ctx, n.title, 14f, bold = true))
            c.addView(Ui.text(ctx, "${n.source}  •  ${if (n.time > 0) Fmt.ago(n.time) else ""}", 11f, Ui.MUTED))
            c.setOnClickListener { openArticle(n) }
            col.addView(c)
        }
    }

    // Comparaison avec d'autres pays (optionnel, IA)
    col.addView(Ui.gap(ctx, 8))
    val wtxt = pr.getString("fuel_txt", null)
    col.addView(Ui.button(ctx, "🌍 قارن مع الدول (أرخص وأغلى)", Ui.CHIP) { loadWorld() })
    if (wtxt != null) {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, wtxt, 14f).apply { setTextIsSelectable(true) })
        col.addView(c)
    }
    return Ui.scroll(ctx, col)
}

private fun MainActivity.loadFuel() {
    if (fuelLoading) return
    fuelLoading = true
    Thread {
        val items = try { Fuel.news() } catch (_: Exception) { emptyList() }
        val p = if (items.isNotEmpty()) try { Fuel.find(items) } catch (_: Exception) { null } else null
        val e = getSharedPreferences("santojan", Context.MODE_PRIVATE).edit()
        val a = JSONArray(); items.take(10).forEach { a.put(JSONObject().put("t", it.title).put("l", it.link).put("s", it.source).put("ti", it.time)) }
        e.putString("fuel2_news", a.toString()).putLong("fuel2_at", System.currentTimeMillis())
        if (p != null) e.putFloat("fuel2_v", p.value.toFloat()).putString("fuel2_snip", p.snippet).putString("fuel2_title", p.title)
            .putString("fuel2_src", p.source).putString("fuel2_link", p.link).putLong("fuel2_t", p.time)
        e.apply()
        runOnUiThread { fuelLoading = false; if (currentTab() == TAB_FUEL) refresh() }
    }.start()
}

private fun MainActivity.loadWorld() {
    if (CallAi.key(this).isBlank()) { toast("حط المفتاح ديال Gemini فـ «الإعدادات»"); show(TAB_MORE); return }
    val prompt = """قلّب فالإنترنيت وجاوب بالعربية، مختصر:
📉 أرخص 5 دول فالعالم فالغازوال: (الدولة — الثمن باللتر بالدرهم المغربي تقريبا)
📈 أغلى 5 دول: (الدولة — الثمن بالدرهم)
🌍 قريب منا: إسبانيا، فرنسا، الجزائر، تونس، مصر (الثمن بالدرهم)
ما تخترعش الأرقام."""
    val wait = AlertDialog.Builder(this).setMessage("🔍 كنقلب...").setCancelable(false).show()
    Thread {
        val r = try { Result.success(CallAi.ask(this, prompt, search = true).first) } catch (e: Exception) { Result.failure(e) }
        runOnUiThread {
            try { wait.dismiss() } catch (_: Exception) {}
            r.onSuccess { getSharedPreferences("santojan", Context.MODE_PRIVATE).edit().putString("fuel_txt", it).apply(); refresh() }
                .onFailure { toast(it.message ?: "خطأ") }
        }
    }.start()
}
