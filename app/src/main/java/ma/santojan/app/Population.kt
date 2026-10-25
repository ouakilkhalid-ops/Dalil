package ma.santojan.app

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Démographie du Maroc : recensement HCP 2024 + séries Banque mondiale. */
object Pop {
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)
    val IND = linkedMapOf(
        "SP.POP.GROW" to "📈 النمو السنوي ديال السكان",
        "SP.DYN.TFRT.IN" to "👶 عدد الولاد لكل مرا",
        "SP.DYN.LE00.IN" to "❤️ متوسط العمر",
        "SP.URB.TOTL.IN.ZS" to "🏙️ ساكنين فالمدن",
        "SP.POP.0014.TO.ZS" to "🧒 أقل من 15 عام",
        "SP.POP.65UP.TO.ZS" to "👴 كثر من 65 عام"
    )

    private fun series(code: String): List<Pair<Int, Double>> {
        val a = JSONArray(Net.get("https://api.worldbank.org/v2/country/MA/indicator/$code?format=json&per_page=100"))
        val d = a.getJSONArray(1)
        return (0 until d.length()).mapNotNull {
            val o = d.getJSONObject(it)
            if (o.isNull("value")) null else o.getString("date").toInt() to o.getDouble("value")
        }.sortedBy { it.first }
    }

    fun fetch(c: Context): Boolean {
        val o = JSONObject()
        val codes = listOf("SP.POP.TOTL", "SP.DYN.CBRT.IN", "SP.DYN.CDRT.IN") + IND.keys
        val th = codes.map { code -> Thread {
            try {
                val s = series(code)
                val a = JSONArray(); s.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
                synchronized(o) { o.put(code, a) }
            } catch (_: Exception) {}
        }.apply { start() } }
        th.forEach { it.join(25000) }
        if (!o.has("SP.POP.TOTL")) return false
        p(c).edit().putString("pop", o.toString()).putLong("pop_at", System.currentTimeMillis()).apply()
        return true
    }

    fun get(c: Context, code: String): List<Pair<Int, Double>> = try {
        val a = JSONObject(p(c).getString("pop", "{}")).getJSONArray(code)
        (0 until a.length()).map { val x = a.getJSONArray(it); x.getInt(0) to x.getDouble(1) }
    } catch (e: Exception) { emptyList() }

    fun at(c: Context) = p(c).getLong("pop_at", 0L)
}

private var popLoading = false

private fun fmtM(v: Double) = String.format(Locale.US, "%.1f مليون", v / 1_000_000)

fun MainActivity.populationScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "👥 السكان فالمغرب"))

    // ---- Compteur en direct (estimation à partir du recensement HCP 2024)
    val birth = Pop.get(ctx, "SP.DYN.CBRT.IN").lastOrNull()?.second ?: 16.0   // naissances pour 1000 hab./an
    val death = Pop.get(ctx, "SP.DYN.CDRT.IN").lastOrNull()?.second ?: 5.3
    val live = Ui.card(ctx)
    live.addView(Ui.text(ctx, "🔴 مباشر — عدد المغاربة دابا (تقدير)", 15f, Ui.RED, true))
    val big = Ui.text(ctx, "", 30f, Ui.ACCENT, true).apply { textDirection = View.TEXT_DIRECTION_LTR; gravity = android.view.Gravity.CENTER }
    live.addView(big, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    val bRow = Ui.text(ctx, "", 14f); val dRow = Ui.text(ctx, "", 14f); val gRow = Ui.text(ctx, "", 14f, Ui.ACCENT, true)
    val yRow = Ui.text(ctx, "", 13f, Ui.MUTED)
    live.addView(Ui.gap(ctx, 6)); live.addView(bRow); live.addView(dRow); live.addView(gRow); live.addView(yRow)
    live.addView(Ui.text(ctx, "الحساب: الإحصاء الرسمي 36,828,330 (1 شتنبر 2024) + نمو 0.85% فالعام. الولادات والوفيات حسب معدلات البنك الدولي.", 10f, Ui.MUTED))
    col.addView(live)
    val base = 36_828_330.0
    val baseT = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Africa/Casablanca")).apply {
        set(2024, java.util.Calendar.SEPTEMBER, 1, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    val yearMs = 365.25 * 86_400_000.0
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val nf = java.text.NumberFormat.getIntegerInstance(Locale.US)
    var attached = false
    val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val popNow = base * Math.exp(0.0085 * (now - baseT) / yearMs)
            val day0 = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val y0 = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
            val perMs = popNow / yearMs / 1000.0
            val bToday = birth * perMs * (now - day0); val dToday = death * perMs * (now - day0)
            val bYear = birth * perMs * (now - y0); val dYear = death * perMs * (now - y0)
            big.text = nf.format(popNow.toLong())
            bRow.text = "👶 تزادو اليوم: ${nf.format(bToday.toLong())}"
            dRow.text = "🕊️ توفاو اليوم: ${nf.format(dToday.toLong())}"
            gRow.text = "📈 زادو اليوم (صافي): +${nf.format((bToday - dToday).toLong())}"
            yRow.text = "هاد العام: 👶 ${nf.format(bYear.toLong())}  •  🕊️ ${nf.format(dYear.toLong())}"
            if (!attached || big.isAttachedToWindow) handler.postDelayed(this, 1000)
        }
    }
    tick.run()
    big.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { attached = true }
        override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(tick) }
    })

    // Recensement officiel
    val h = Ui.card(ctx)
    h.addView(Ui.text(ctx, "🇲🇦 الإحصاء الرسمي 2024 (HCP)", 16f, bold = true))
    h.addView(Ui.text(ctx, "36,828,330 نسمة", 28f, Ui.ACCENT, true))
    h.addView(Ui.text(ctx,
        "• المغاربة: 36,680,178  •  الأجانب: 148,152\n" +
        "• النمو: 0.85% فالعام بين 2014 و 2024 (كان 1.25% بين 2004 و 2014)\n" +
        "• فالمدن: 62.8% (كانو 60.4% ف 2014)\n" +
        "• الأسر: 9,275,038 — متوسط الأسرة 3.9 أفراد (كان 4.6)",
        13f))
    h.addView(Ui.text(ctx, "الإحصاء دار ف 1 شتنبر 2024.", 11f, Ui.MUTED))
    col.addView(h)

    val pop = Pop.get(ctx, "SP.POP.TOTL")
    if ((pop.isEmpty() || System.currentTimeMillis() - Pop.at(ctx) > 7 * 86_400_000L) && !popLoading) {
        popLoading = true
        Thread {
            val ok = Pop.fetch(ctx)
            runOnUiThread {
                popLoading = false
                if (!ok && pop.isEmpty()) toast("ما قدرتش نجيب المعطيات، شوف الإنترنيت")
                if (currentTab() == TAB_POP) refresh()
            }
        }.start()
    }
    if (pop.isEmpty()) {
        col.addView(Ui.text(ctx, "⏳ كنجيب المعطيات ديال البنك الدولي...", 14f, Ui.MUTED))
        return Ui.scroll(ctx, col)
    }

    // Courbe : population tous les 5 ans
    val ch = Ui.card(ctx)
    ch.addView(Ui.text(ctx, "📊 تطور عدد السكان", 16f, bold = true))
    val pts = pop.filter { it.first % 5 == 0 || it == pop.last() }.distinctBy { it.first }
    val max = pts.maxOf { it.second }
    pts.forEach { (y, v) ->
        val r = Ui.row(ctx)
        r.addView(Ui.text(ctx, "$y", 12f, Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.WRAP))
        val pct = (v / max).toFloat()
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(View(ctx).apply { background = Ui.round(Ui.ACCENT, Ui.dp(ctx, 4).toFloat()) },
            LinearLayout.LayoutParams(0, Ui.dp(ctx, 14), pct))
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, Ui.dp(ctx, 14), 1f - pct))
        r.addView(bar, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        r.addView(Ui.text(ctx, fmtM(v), 12f), LinearLayout.LayoutParams(Ui.dp(ctx, 82), Ui.WRAP).apply { marginStart = Ui.dp(ctx, 6) })
        ch.addView(r, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(ctx, 3) })
    }
    val first = pop.first(); val last = pop.last()
    ch.addView(Ui.text(ctx, "من ${first.first} حتى ${last.first}: السكان تضاعفو ${String.format(Locale.US, "%.1f", last.second / first.second)} مرات.", 12f, Ui.MUTED))
    col.addView(ch)

    // Indicateurs
    val ic = Ui.card(ctx)
    ic.addView(Ui.text(ctx, "📌 أرقام مهمة (البنك الدولي)", 16f, bold = true))
    Pop.IND.forEach { (code, label) ->
        val s = Pop.get(ctx, code)
        val l = s.lastOrNull() ?: return@forEach
        val old = s.firstOrNull { it.first == l.first - 20 } ?: s.firstOrNull()
        val unit = when (code) { "SP.DYN.TFRT.IN" -> ""; "SP.DYN.LE00.IN" -> " عام"; else -> "%" }
        val v = String.format(Locale.US, if (code == "SP.DYN.LE00.IN") "%.1f" else "%.2f", l.second) + unit
        val was = if (old != null && old != l) "  (كان ${String.format(Locale.US, "%.2f", old.second)}$unit ف ${old.first})" else ""
        val r = Ui.row(ctx)
        r.addView(Ui.text(ctx, label, 14f), Ui.lp(ctx, 1f, 2))
        r.addView(Ui.text(ctx, v, 15f, Ui.ACCENT, true))
        ic.addView(r)
        ic.addView(Ui.text(ctx, "${l.first}$was", 11f, Ui.MUTED))
    }
    col.addView(ic)

    col.addView(Ui.text(ctx,
        "ملاحظة: البنك الدولي كيعطي تقدير (${fmtM(last.second)} ف ${last.first}) كثر من الإحصاء الرسمي ديال HCP، حيت كيحسب بطريقة أخرى. الرقم الرسمي هو ديال HCP.",
        11f, Ui.MUTED))
    return Ui.scroll(ctx, col)
}
