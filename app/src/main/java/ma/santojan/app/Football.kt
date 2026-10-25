package ma.santojan.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class Match(val league: String, val home: String, val away: String, val hs: String, val aws: String,
            val state: String, val detail: String, val time: Long)
class Row(val rank: Int, val team: String, val played: Int, val gd: Int, val pts: Int)

/** Football : classements et matchs importants (ESPN, gratuit, sans clé). */
object Foot {
    const val ES = "esp.1"; const val EN = "eng.1"; const val MA = "botola"
    val NAMES = mapOf(ES to "🇪🇸 الليغا", EN to "🏴 البريميرليغ", MA to "🇲🇦 البطولة")
    private val BIG = listOf("Real Madrid", "Barcelona", "Atlético", "Athletic Club", "Villarreal", "Real Betis", "Sevilla", "Real Sociedad",
        "Manchester City", "Arsenal", "Liverpool", "Chelsea", "Manchester United", "Tottenham", "Newcastle", "Aston Villa",
        "Bayern", "Paris Saint-Germain", "Inter Milan", "Juventus", "AC Milan", "Borussia Dortmund")

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun day(off: Int) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, off) }.time)

    private fun parseEvents(league: String, txt: String): List<Match> {
        val out = ArrayList<Match>()
        val ev = JSONObject(txt).optJSONArray("events") ?: return out
        for (i in 0 until ev.length()) try {
            val e = ev.getJSONObject(i)
            val st = e.getJSONObject("status").getJSONObject("type")
            val comp = e.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors")
            var h = ""; var a = ""; var hs = ""; var aws = ""
            for (j in 0 until comp.length()) {
                val c = comp.getJSONObject(j)
                val name = c.getJSONObject("team").optString("shortDisplayName", c.getJSONObject("team").optString("displayName"))
                if (c.optString("homeAway") == "home") { h = name; hs = c.optString("score") } else { a = name; aws = c.optString("score") }
            }
            val clock = e.getJSONObject("status").optString("displayClock")
            val state = st.optString("state")
            out.add(Match(league, h, a, hs, aws, state, if (state == "in") clock else st.optString("shortDetail"),
                try { iso.parse(e.getString("date"))?.time ?: 0L } catch (_: Exception) { 0L }))
        } catch (_: Exception) {}
        return out
    }

    fun matches(league: String): List<Match> {
        val res = java.util.Collections.synchronizedList(ArrayList<Match>())
        (-1..4).map { off -> Thread {
            try { res.addAll(parseEvents(league, Net.get("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/scoreboard?dates=${day(off)}"))) } catch (_: Exception) {}
        }.apply { start() } }.forEach { it.join(20000) }
        return res.distinctBy { it.home + it.away + it.time }.sortedBy { it.time }
    }

    /** Matchs de l'équipe du Maroc et grosses affiches européennes. */
    fun special(): List<Match> {
        val res = java.util.Collections.synchronizedList(ArrayList<Match>())
        listOf("caf.nations", "fifa.worldq.caf", "fifa.friendly", "caf.nations_qual", "fifa.world", "uefa.champions").map { lg -> Thread {
            try {
                val l = parseEvents(lg, Net.get("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/scoreboard"))
                res.addAll(if (lg == "uefa.champions") l.filter { isBig(it) } else l.filter { it.home.contains("Morocco") || it.away.contains("Morocco") })
            } catch (_: Exception) {}
        }.apply { start() } }.forEach { it.join(20000) }
        val now = System.currentTimeMillis()
        return res.filter { it.time in (now - 2 * 86_400_000L)..(now + 10 * 86_400_000L) }.sortedBy { it.time }
    }

    fun isBig(m: Match) = BIG.any { m.home.contains(it) || m.away.contains(it) }

    fun table(league: String): List<Row> {
        val o = JSONObject(Net.get("https://site.api.espn.com/apis/v2/sports/soccer/$league/standings"))
        val e = o.getJSONArray("children").getJSONObject(0).getJSONObject("standings").getJSONArray("entries")
        val out = ArrayList<Row>()
        for (i in 0 until e.length()) {
            val x = e.getJSONObject(i)
            val st = HashMap<String, Double>()
            val a = x.getJSONArray("stats")
            for (j in 0 until a.length()) a.getJSONObject(j).let { s -> if (!s.isNull("value")) st[s.getString("name")] = s.getDouble("value") }
            out.add(Row((st["rank"] ?: (i + 1.0)).toInt(), x.getJSONObject("team").optString("shortDisplayName"),
                (st["gamesPlayed"] ?: 0.0).toInt(), (st["pointDifferential"] ?: 0.0).toInt(), (st["points"] ?: 0.0).toInt()))
        }
        return out.sortedBy { it.rank }
    }

    // cache simple
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)
    fun saveMatches(c: Context, key: String, l: List<Match>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("l", it.league).put("h", it.home).put("a", it.away).put("hs", it.hs)
            .put("as", it.aws).put("s", it.state).put("d", it.detail).put("t", it.time)) }
        p(c).edit().putString("fm_$key", a.toString()).putLong("fm_at_$key", System.currentTimeMillis()).apply()
    }
    fun loadMatches(c: Context, key: String): List<Match> = try {
        val a = JSONArray(p(c).getString("fm_$key", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            Match(o.getString("l"), o.getString("h"), o.getString("a"), o.getString("hs"), o.getString("as"), o.getString("s"), o.getString("d"), o.getLong("t")) }
    } catch (e: Exception) { emptyList() }
    fun saveTable(c: Context, key: String, l: List<Row>) {
        val a = JSONArray(); l.forEach { a.put(JSONArray().put(it.rank).put(it.team).put(it.played).put(it.gd).put(it.pts)) }
        p(c).edit().putString("ft_$key", a.toString()).apply()
    }
    fun loadTable(c: Context, key: String): List<Row> = try {
        val a = JSONArray(p(c).getString("ft_$key", "[]"))
        (0 until a.length()).map { val x = a.getJSONArray(it); Row(x.getInt(0), x.getString(1), x.getInt(2), x.getInt(3), x.getInt(4)) }
    } catch (e: Exception) { emptyList() }
    fun at(c: Context, key: String) = p(c).getLong("fm_at_$key", 0L)
}

// ------------------------------------------------------------------ Écran

private var footSel = "imp"
private var footLoading = false
private val footHandler = Handler(Looper.getMainLooper())
private var footLive = false

private fun MainActivity.loadFoot(force: Boolean) {
    if (footLoading) return
    footLoading = true
    val sel = footSel
    Thread {
        try {
            if (sel == "imp") {
                val all = ArrayList<Match>()
                listOf(Foot.ES, Foot.EN).forEach { lg ->
                    val m = Foot.matches(lg); Foot.saveMatches(this, lg, m)
                    val tb = try { Foot.table(lg).also { Foot.saveTable(this, lg, it) } } catch (_: Exception) { Foot.loadTable(this, lg) }
                    val top = tb.take(6).map { it.team }.toSet()
                    all.addAll(m.filter { it.state == "in" || Foot.isBig(it) || (it.home in top && it.away in top) })
                }
                all.addAll(Foot.special())
                Foot.saveMatches(this, "imp", all.sortedBy { it.time })
            } else if (sel != Foot.MA) {
                Foot.saveMatches(this, sel, Foot.matches(sel))
                try { Foot.saveTable(this, sel, Foot.table(sel)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        runOnUiThread { footLoading = false; if (currentTab() == TAB_FOOT) refresh() }
    }.start()
    if (force) refresh()
}

private fun MainActivity.matchCard(m: Match): View {
    val ctx = this
    val c = Ui.card(ctx)
    val live = m.state == "in"
    val head = when (m.state) {
        "in" -> "🔴 مباشر ${m.detail}"
        "post" -> "✅ سالات"
        else -> {
            val cal = Calendar.getInstance(); val today = cal.get(Calendar.DAY_OF_YEAR)
            val d = Calendar.getInstance().apply { timeInMillis = m.time }
            val dd = d.get(Calendar.DAY_OF_YEAR) - today
            (when (dd) { 0 -> "اليوم"; 1 -> "غدا"; -1 -> "البارح"; else -> SimpleDateFormat("dd/MM", Locale.US).format(d.time) }) + " " + Fmt.time(m.time)
        }
    }
    val lg = when (m.league) {
        Foot.ES -> "🇪🇸"; Foot.EN -> "🏴"; "uefa.champions" -> "⭐ أبطال أوروبا"; else -> if (m.league.startsWith("caf") || m.league.startsWith("fifa")) "🇲🇦 المنتخب" else ""
    }
    c.addView(Ui.text(ctx, "$lg  $head", 12f, if (live) Ui.RED else Ui.MUTED, live))
    val r = Ui.row(ctx)
    r.addView(Ui.text(ctx, m.home, 15f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
    val score = if (m.state == "pre") "—" else "${m.hs} - ${m.aws}"
    r.addView(Ui.text(ctx, score, 20f, if (live) Ui.RED else Ui.ACCENT, true).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR },
        LinearLayout.LayoutParams(Ui.dp(ctx, 80), Ui.WRAP))
    r.addView(Ui.text(ctx, m.away, 15f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
    c.addView(r)
    return c
}

private fun MainActivity.tableView(rows: List<Row>): View {
    val ctx = this
    val c = Ui.card(ctx)
    c.addView(Ui.text(ctx, "📊 الترتيب", 16f, bold = true))
    fun line(a: String, b: String, p: String, g: String, pts: String, color: Int, bold: Boolean) {
        val r = Ui.row(ctx).apply { setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3)) }
        r.addView(Ui.text(ctx, a, 13f, color, bold), LinearLayout.LayoutParams(Ui.dp(ctx, 28), Ui.WRAP))
        r.addView(Ui.text(ctx, b, 14f, Ui.TEXT, bold), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        listOf(p, g, pts).forEachIndexed { i, s ->
            r.addView(Ui.text(ctx, s, 13f, if (i == 2) Ui.ACCENT else Ui.MUTED, bold || i == 2).apply {
                gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR
            }, LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.WRAP))
        }
        c.addView(r)
    }
    line("#", "الفريق", "لعب", "+/-", "نقط", Ui.MUTED, true)
    rows.forEach { x ->
        val col = when { x.rank <= 4 -> Ui.ACCENT; x.rank > rows.size - 3 -> Ui.RED; else -> Ui.MUTED }
        line("${x.rank}", x.team, "${x.played}", (if (x.gd > 0) "+" else "") + x.gd, "${x.pts}", col, false)
    }
    c.addView(Ui.text(ctx, "🟢 الأوائل  •  🔴 مهددين بالنزول", 11f, Ui.MUTED))
    return c
}

fun MainActivity.footballScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "⚽ الكورة"))

    val chips = Ui.row(ctx)
    listOf("imp" to "⭐ المهم", Foot.ES to Foot.NAMES[Foot.ES]!!, Foot.EN to Foot.NAMES[Foot.EN]!!, Foot.MA to Foot.NAMES[Foot.MA]!!).forEach { (k, t) ->
        chips.addView(Ui.button(ctx, t, if (footSel == k) Ui.ACCENT else Ui.CHIP) { footSel = k; refresh() }.apply { textSize = 14f }, Ui.lp(ctx))
    }
    col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

    if (footSel == Foot.MA) return botolaScreen(col)

    val ms = Foot.loadMatches(ctx, footSel)
    val age = System.currentTimeMillis() - Foot.at(ctx, footSel)
    val anyLive = ms.any { it.state == "in" }
    if (ms.isEmpty() || age > (if (anyLive) 60_000 else 10 * 60_000)) loadFoot(false)
    if (footLoading && ms.isEmpty()) col.addView(Ui.text(ctx, "⏳ كنجيب الماتشات...", 14f, Ui.MUTED))

    // rafraîchissement automatique pendant les matchs en direct
    if (anyLive && !footLive) {
        footLive = true
        footHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || currentTab() != TAB_FOOT) { footLive = false; return }
                loadFoot(false); footHandler.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    val now = System.currentTimeMillis()
    val live = ms.filter { it.state == "in" }
    val next = ms.filter { it.state == "pre" && it.time > now - 3_600_000 }.let { l -> if (footSel == "imp") l else l.filter { Foot.isBig(it) }.ifEmpty { l } }.take(8)
    val done = ms.filter { it.state == "post" && it.time > now - 2 * 86_400_000L }.let { l -> if (footSel == "imp") l else l.filter { Foot.isBig(it) }.ifEmpty { l } }.takeLast(6).reversed()

    if (live.isNotEmpty()) { col.addView(Ui.text(ctx, "🔴 كيتلعبو دابا", 16f, Ui.RED, true)); live.forEach { col.addView(matchCard(it)) } }
    if (next.isNotEmpty()) { col.addView(Ui.text(ctx, "📅 الماتشات المهمة الجاية", 16f, Ui.TEXT, true)); next.forEach { col.addView(matchCard(it)) } }
    if (done.isNotEmpty()) { col.addView(Ui.text(ctx, "✅ آخر النتائج", 16f, Ui.TEXT, true)); done.forEach { col.addView(matchCard(it)) } }
    if (!footLoading && ms.isEmpty()) col.addView(Ui.text(ctx, "ما كاين حتى ماتش مهم هاد الأيام.", 14f, Ui.MUTED))

    if (footSel != "imp") {
        val tb = Foot.loadTable(ctx, footSel)
        if (tb.isNotEmpty()) col.addView(tableView(tb))
    }
    col.addView(Ui.text(ctx, "آخر تحديث: ${Fmt.ago(Foot.at(ctx, footSel))}" + if (anyLive) "  •  🔴 كيتحدّث كل دقيقة" else "", 11f, Ui.MUTED))
    col.addView(Ui.button(ctx, "🔄 حدّث", Ui.CHIP) { loadFoot(true) })
    return Ui.scroll(ctx, col)
}

/** Botola : pas de source gratuite fiable → recherche Google via Gemini. */
private fun MainActivity.botolaScreen(col: LinearLayout): View {
    val ctx = this
    val pr = getSharedPreferences("santojan", Context.MODE_PRIVATE)
    val txt = pr.getString("botola_txt", null)
    val at = pr.getLong("botola_at", 0L)
    col.addView(Ui.button(ctx, if (txt == null) "🔍 جيب الترتيب والماتشات" else "🔄 حدّث", Ui.ACCENT) { loadBotola() })
    if (txt != null) {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, txt, 14f).apply { setTextIsSelectable(true); typeface = Typeface.DEFAULT })
        col.addView(c)
        col.addView(Ui.text(ctx, "آخر تحديث: ${Fmt.ago(at)}", 11f, Ui.MUTED))
    } else {
        col.addView(Ui.text(ctx, "البطولة الاحترافية ما عندهاش مصدر مجاني مباشر، داكشي علاش كنقلبو عليها فـ Google (خاص مفتاح Gemini).", 12f, Ui.MUTED))
    }
    return Ui.scroll(ctx, col)
}

private fun MainActivity.loadBotola() {
    if (CallAi.key(this).isBlank()) { toast("حط المفتاح ديال Gemini فـ «الإعدادات»"); show(TAB_MORE); return }
    val prompt = """قلّب فالإنترنيت على البطولة الاحترافية المغربية (Botola Pro Inwi) ديال هاد الموسم، اليوم ${Fmt.date(System.currentTimeMillis()).take(10)}.
جاوب بالعربية، مختصر، بهاد الشكل:
📊 الترتيب: (جدول نصي: الرتبة. الفريق — النقط — عدد الماتشات) لكل الفرق إلا لقيتيهم
✅ آخر نتائج مهمة: (3 حتى 5)
📅 الماتشات المهمة الجاية: (الفريقين — النهار والساعة بتوقيت المغرب)
ما تخترعش. إلا ما لقيتيش معلومة أكيدة قول «ما لقيتش»."""
    val wait = AlertDialog.Builder(this).setMessage("🔍 كنقلب على البطولة...").setCancelable(false).show()
    Thread {
        val r = try { Result.success(CallAi.ask(this, prompt, search = true).first) } catch (e: Exception) { Result.failure(e) }
        runOnUiThread {
            try { wait.dismiss() } catch (_: Exception) {}
            r.onSuccess {
                getSharedPreferences("santojan", Context.MODE_PRIVATE).edit().putString("botola_txt", it).putLong("botola_at", System.currentTimeMillis()).apply()
                refresh()
            }.onFailure { toast(it.message ?: "خطأ") }
        }
    }.start()
}
