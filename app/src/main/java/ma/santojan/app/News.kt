package ma.santojan.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import android.view.View
import android.widget.CheckBox
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

object Net {
    fun get(url: String, timeout: Int = 15000): String {
        var u = url
        repeat(4) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeout; c.readTimeout = timeout
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Jassim/1.0")
                val code = c.responseCode
                if (code in 300..399) { u = c.getHeaderField("Location") ?: throw Exception("redirect"); return@repeat }
                if (code !in 200..299) throw Exception("HTTP $code")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }
        throw Exception("redirects")
    }
}

class NewsItem(val title: String, val link: String, val source: String, val time: Long)

class Source(val name: String, val url: String)

object News {
    const val MA = "ma"; const val ES = "es"; const val EU = "eu"; const val NA = "na"
    val REGIONS = listOf(NA, MA, ES, EU)

    val SOURCES = mapOf(
        NA to listOf(
            Source("ناظورسيتي", "https://www.nadorcity.com/xml/syndication.rss"),
            Source("ناظور بريس", "https://nadorpress.com/feed")
        ),
        MA to listOf(
            Source("هسبريس", "https://www.hespress.com/feed"),
            Source("اليوم 24", "https://www.alyaoum24.com/feed/"),
            Source("Hespress FR", "https://fr.hespress.com/feed"),
            Source("MAP", "https://www.mapexpress.ma/feed/"),
            Source("Yabiladi", "https://www.yabiladi.com/rss/news.xml")
        ),
        ES to listOf(
            Source("El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada"),
            Source("Europa Press", "https://www.europapress.es/rss/rss.aspx")
        ),
        EU to listOf(
            Source("يورونيوز", "https://arabic.euronews.com/rss"),
            Source("DW عربية", "https://rss.dw.com/rdf/rss-ar-all"),
            Source("BBC Europe", "https://feeds.bbci.co.uk/news/world/europe/rss.xml"),
            Source("Euronews", "https://www.euronews.com/rss?level=theme&name=news")
        )
    )
    val NAMES = mapOf(NA to "📍 الناظور", MA to "🇲🇦 المغرب", ES to "🇪🇸 إسبانيا", EU to "🇪🇺 أوروبا")

    private val LOCAL = listOf("الناظور", "ناظور", "nador", "زغنغن", "بني انصار", "بني أنصار", "سلوان", "العروي", "الدريوش", "driouch",
        "الحسيمة", "hoceima", "بركان", "berkane", "وجدة", "oujda", "الجهة الشرقية", "جهة الشرق", "l'oriental", "مليلية", "melilla",
        "مارتشيكا", "marchica", "تاوريرت", "جرادة", "فكيك", "الريف")
    fun isLocal(t: String): Boolean { val l = t.lowercase(); return LOCAL.any { l.contains(it) } }

    private val FUEL = listOf("الغازوال", "الكازوال", "المحروقات", "البنزين", "الوقود", "gasoil", "gazole", "carburant", "essence", "prix à la pompe")
    fun isFuel(t: String): Boolean { val l = t.lowercase(); return FUEL.any { l.contains(it) } }

    private val URGENT = listOf("عاجل", "urgent", "breaking", "última hora", "ultima hora", "زلزال", "séisme", "terremoto",
        "earthquake", "فيضان", "inondation", "inundaci", "حريق", "incendie", "incendio", "إنذار", "alerte", "alerta")

    fun isUrgent(t: String): Boolean { val l = t.lowercase(); return URGENT.any { l.contains(it) } }

    private val fmts = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        for (f in fmts) try { return SimpleDateFormat(f, Locale.US).parse(s.trim())?.time ?: 0L } catch (_: Exception) {}
        return 0L
    }

    fun parse(xml: String, source: String): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(xml.reader())
            var inItem = false
            var title = ""; var link = ""; var date = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item", "entry" -> { inItem = true; title = ""; link = ""; date = "" }
                        "title" -> if (inItem) title = p.nextText()
                        "link" -> if (inItem) { val h = p.getAttributeValue(null, "href"); link = h ?: p.nextText() }
                        "pubDate", "dc:date", "published", "updated" -> if (inItem && date.isEmpty()) date = p.nextText()
                    }
                } else if (ev == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry")) {
                    inItem = false
                    val t = android.text.Html.fromHtml(title, 0).toString().trim()
                    if (t.isNotEmpty() && link.isNotBlank()) out.add(NewsItem(t, link.trim(), source, parseDate(date)))
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
        return out.take(25)
    }

    /** Charge toutes les sources d'une région en parallèle. */
    fun fetch(region: String): List<NewsItem> {
        val res = java.util.Collections.synchronizedList(ArrayList<NewsItem>())
        val threads = SOURCES[region].orEmpty().map { s ->
            Thread { try { res.addAll(parse(Net.get(s.url), s.name)) } catch (_: Exception) {} }.apply { start() }
        } + (if (region == NA) SOURCES[MA].orEmpty().map { s ->
            // les journaux nationaux : seulement ce qui parle de Nador et de la région
            Thread { try { res.addAll(parse(Net.get(s.url), s.name).filter { isLocal(it.title) }) } catch (_: Exception) {} }.apply { start() }
        } else emptyList())
        threads.forEach { it.join(20000) }
        val now = System.currentTimeMillis()
        return res.toList().filter { it.time <= now + 3_600_000 }.distinctBy { it.title }.sortedByDescending { it.time }
    }

    // cache
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)
    fun save(c: Context, region: String, l: List<NewsItem>) {
        val a = JSONArray()
        l.take(80).forEach { a.put(JSONObject().put("t", it.title).put("l", it.link).put("s", it.source).put("ti", it.time)) }
        p(c).edit().putString("news_$region", a.toString()).putLong("news_at_$region", System.currentTimeMillis()).apply()
    }
    fun cached(c: Context, region: String): List<NewsItem> = try {
        val a = JSONArray(p(c).getString("news_$region", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); NewsItem(o.getString("t"), o.getString("l"), o.getString("s"), o.getLong("ti")) }
    } catch (e: Exception) { emptyList() }
    fun cachedAt(c: Context, region: String) = p(c).getLong("news_at_$region", 0L)

    fun notifyOn(c: Context, region: String) = p(c).getBoolean("news_notif_$region", region == MA || region == NA)
    fun setNotify(c: Context, region: String, v: Boolean) {
        p(c).edit().putBoolean("news_notif_$region", v).apply()
        try { schedule(c) } catch (_: Exception) {}
    }

    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        val any = REGIONS.any { notifyOn(c, it) }
        if (!any) { js.cancel(55); return }
        if (js.getPendingJob(55) != null) return
        js.schedule(JobInfo.Builder(55, ComponentName(c, NewsJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(30 * 60 * 1000L)
            .setPersisted(true)
            .build())
    }

    /** Cherche les nouvelles infos urgentes et envoie une notification. */
    fun checkUrgent(c: Context) {
        val seen = StrList.get(c, "news_seen", emptyList()).toMutableList()
        val first = seen.isEmpty()
        var sent = 0
        REGIONS.filter { notifyOn(c, it) }.forEach { r ->
            val items = fetch(r)
            if (items.isNotEmpty()) save(c, r, items)
            items.filter { isUrgent(it.title) && it.link !in seen && System.currentTimeMillis() - it.time < 6 * 3_600_000 }
                .take(3).forEach { n ->
                    seen.add(n.link)
                    if (!first && sent < 3) { show(c, n, r); sent++ }
                }
        }
        StrList.set(c, "news_seen", seen.takeLast(300))
    }

    private fun show(c: Context, n: NewsItem, region: String) {
        Notif.channels(c)
        val open = PendingIntent.getActivity(c, n.link.hashCode(), Intent(c, MainActivity::class.java)
            .putExtra("a_link", n.link).putExtra("a_title", n.title).putExtra("a_src", n.source).putExtra("a_time", n.time)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = Notification.Builder(c, Notif.CH_REM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⚡ ${NAMES[region]} — ${n.source}")
            .setContentText(n.title)
            .setStyle(Notification.BigTextStyle().bigText(n.title))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { c.getSystemService(NotificationManager::class.java).notify(n.link.hashCode(), notif) } catch (_: Exception) {}
    }
}

class NewsJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { News.checkUrgent(applicationContext) } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}

// ------------------------------------------------------------------ Écran

private var newsRegion: String? = null
private var newsLoading = false

fun MainActivity.newsScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "📰 الأخبار"))
    try { News.schedule(ctx) } catch (_: Exception) {}

    News.REGIONS.chunked(2).forEach { pair ->
        val rr = Ui.row(ctx)
        pair.forEach { r ->
            rr.addView(Ui.button(ctx, News.NAMES[r]!!, if (newsRegion == r) Ui.ACCENT else Ui.CHIP) {
                newsRegion = r; refresh()
            }.apply { textSize = 15f; minimumHeight = Ui.dp(ctx, 54) }, Ui.lp(ctx, 1f))
        }
        col.addView(rr)
    }

    val region = newsRegion
    if (region == null) {
        col.addView(Ui.text(ctx, "اختار شنو بغيتي تشوف 👆", 15f, Ui.MUTED).apply { setPadding(0, Ui.dp(ctx, 16), 0, 0) })
        col.addView(Ui.gap(ctx, 12))
        col.addView(Ui.text(ctx, "المصادر: ناظورسيتي، ناظور بريس، هسبريس، اليوم 24، لاماب (MAP)، Yabiladi، El País، Europa Press، يورونيوز، DW، BBC.\n" +
            "فيسبوك ما كيخليش التطبيقات تقرا المنشورات، وزايد فيه بزاف ديال الأخبار الكاذبة، داكشي علاش كناخدو غير من المواقع المعروفة.", 12f, Ui.MUTED))
        return Ui.scroll(ctx, col)
    }

    // Notifications
    col.addView(CheckBox(ctx).apply {
        text = "🔔 نبهني ملي يكون شي خبر عاجل (${News.NAMES[region]})"
        setTextColor(Ui.TEXT); isChecked = News.notifyOn(ctx, region)
        setOnCheckedChangeListener { _, v -> News.setNotify(ctx, region, v) }
    })

    val items = News.cached(ctx, region)
    val age = System.currentTimeMillis() - News.cachedAt(ctx, region)
    val bar = Ui.row(ctx)
    bar.addView(Ui.button(ctx, "🤖 أهم الأخبار باختصار", Ui.ACCENT) { briefNews(region, items) }, Ui.lp(ctx, 1f))
    bar.addView(Ui.button(ctx, "🔄", Ui.BLUE) { loadNews(region, true) }, Ui.lp(ctx))
    col.addView(bar)

    if (newsLoading) col.addView(Ui.text(ctx, "⏳ كنجيب الأخبار...", 14f, Ui.MUTED))
    else if (items.isEmpty() || age > 3 * 60_000) loadNews(region, false)
    startLiveNews()

    if (items.isNotEmpty()) col.addView(Ui.text(ctx, "🔴 مباشر — كيتحدّث بوحدو كل 3 دقايق  •  آخر تحديث: ${Fmt.ago(News.cachedAt(ctx, region))}", 11f, Ui.RED))
    items.take(50).forEach { n ->
        val c = Ui.card(ctx)
        val urgent = News.isUrgent(n.title)
        c.addView(Ui.text(ctx, (if (urgent) "⚡ " else "") + n.title, 15f, if (urgent) Ui.ORANGE else Ui.TEXT, true))
        c.addView(Ui.text(ctx, "${n.source}  •  ${if (n.time > 0) Fmt.ago(n.time) else ""}", 11f, Ui.MUTED))
        c.setOnClickListener { openArticle(n) }
        col.addView(c)
    }
    return Ui.scroll(ctx, col)
}

fun MainActivity.loadNews(region: String, force: Boolean) {
    if (newsLoading) return
    newsLoading = true
    Thread {
        val l = try { News.fetch(region) } catch (_: Exception) { emptyList() }
        if (l.isNotEmpty()) { News.save(this, region, l); prefetchArticles(l) }
        runOnUiThread {
            newsLoading = false
            if (l.isEmpty() && force) toast("ما قدرتش نجيب الأخبار، شوف الإنترنيت")
            if (currentTab() == TAB_NEWS) refresh()
        }
    }.start()
    if (force) refresh()
}

private fun MainActivity.briefNews(region: String, items: List<NewsItem>) {
    if (items.isEmpty()) { toast("ما كاين حتى خبر، ورك 🔄"); return }
    if (CallAi.key(this).isBlank()) { toast("حط المفتاح ديال Gemini فـ «الإعدادات»"); show(TAB_MORE); return }
    val list = items.take(60).joinToString("\n") { "- [${it.source}] ${it.title}" }
    val prompt = """هادي عناوين الأخبار ديال اليوم (${News.NAMES[region]}) من مواقع معروفة:
$list

اختار أهم 7 أخبار حسب الأولوية (الأخبار لي كتهم الناس بزاف، الأمن، الاقتصاد، الأسعار، القرارات الرسمية).
لكل خبر كتب سطر واحد بالعربية البسيطة، وزيد بين قوسين المصادر لي نشراتو. الخبر لي نشراتو بزاف ديال المصادر حطو الأول لأنه أوثق.
ما تزيدش معلومات ما كايناش فالعناوين. الشكل:
1. 🔴/🟡 الخبر (المصادر)"""
    val wait = AlertDialog.Builder(this).setMessage("🤖 كنختار أهم الأخبار...").setCancelable(false).show()
    Thread {
        val r = try { Result.success(CallAi.ask(this, prompt).first) } catch (e: Exception) { Result.failure(e) }
        runOnUiThread {
            try { wait.dismiss() } catch (_: Exception) {}
            r.onSuccess { showSummary("أهم الأخبار — ${News.NAMES[region]}", it) }
                .onFailure { toast(it.message ?: "خطأ") }
        }
    }.start()
}


private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
private var liveRunning = false

private fun MainActivity.startLiveNews() {
    if (liveRunning) return
    liveRunning = true
    val act = this
    liveHandler.postDelayed(object : Runnable {
        override fun run() {
            val r = newsRegion
            if (act.isFinishing || act.currentTab() != TAB_NEWS || r == null) { liveRunning = false; return }
            act.loadNews(r, false)
            liveHandler.postDelayed(this, 3 * 60_000L)
        }
    }, 3 * 60_000L)
}
