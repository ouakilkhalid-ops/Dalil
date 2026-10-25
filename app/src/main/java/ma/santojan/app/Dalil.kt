package ma.santojan.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

// ------------------------------------------------------------------ Effet 3D

object Fx {
    fun shade(color: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (f > 1f) {
            hsv[1] = (hsv[1] * 0.8f).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        } else {
            hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        }
        return Color.HSVToColor(hsv)
    }

    /** Carré arrondi avec dégradé + reflet brillant en haut = effet 3D. */
    fun icon3d(c: Context, color: Int, sizeDp: Int, radiusDp: Int = 16): LayerDrawable {
        val r = Ui.dp(c, radiusDp).toFloat()
        val px = Ui.dp(c, sizeDp)
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(shade(color, 1.35f), color, shade(color, 0.6f))
        ).apply {
            cornerRadius = r
            setStroke(Ui.dp(c, 1), shade(color, 1.5f))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = r * 0.8f }
        return LayerDrawable(arrayOf(base, gloss)).apply {
            setLayerInset(1, Ui.dp(c, 4), Ui.dp(c, 3), Ui.dp(c, 4), px / 2)
        }
    }

    /** Fond du carré de l'accueil (léger dégradé). */
    fun tileBg(c: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#27364D"), Color.parseColor("#182234"))
    ).apply {
        cornerRadius = Ui.dp(c, 18).toFloat()
        setStroke(Ui.dp(c, 1), Color.parseColor("#334155"))
    }
}

// ------------------------------------------------------------------ Les carrés de l'accueil

class TileDef(val id: String, val tab: Int, val icon: String, val label: String, val color: Int)

object Tiles {
    val ALL = listOf(
        TileDef("calls", 0, "📞", "المكالمات", Color.parseColor("#16A34A")),
        TileDef("wa", TAB_WA, "💬", "واتساب", Color.parseColor("#22C55E")),
        TileDef("groups", 1, "👪", "المجموعات", Color.parseColor("#3B82F6")),
        TileDef("meet", TAB_MEET, "🎙️", "الاجتماعات", Color.parseColor("#EF4444")),
        TileDef("focus", TAB_FOCUS, "🛡️", "التركيز", Color.parseColor("#8B5CF6")),
        TileDef("rem", 6, "⏰", "التذكير", Color.parseColor("#F59E0B")),
        TileDef("spend", 7, "💰", "المصاريف", Color.parseColor("#0EA5E9")),
        TileDef("news", TAB_NEWS, "📰", "الأخبار", Color.parseColor("#DC2626")),
        TileDef("foot", TAB_FOOT, "⚽", "الكورة", Color.parseColor("#15803D")),
        TileDef("money", TAB_MONEY, "💱", "العملة والذهب", Color.parseColor("#CA8A04")),
        TileDef("fuel", TAB_FUEL, "⛽", "الغازوال", Color.parseColor("#EA580C")),
        TileDef("pop", TAB_POP, "👥", "السكان", Color.parseColor("#0D9488")),
        TileDef("apps", 5, "📱", "التطبيقات", Color.parseColor("#64748B")),
        TileDef("set", TAB_MORE, "⚙️", "الإعدادات", Color.parseColor("#475569"))
    )

    val ICONS = listOf(
        "📞", "☎️", "💬", "📲", "👪", "👥", "👨‍👩‍👧", "🎙️", "🎤", "🛡️", "🔒", "⏰",
        "⏳", "🗓️", "💰", "💵", "💳", "📊", "📰", "🗞️", "📢", "⚽", "🏆", "🥅",
        "💱", "🪙", "🥇", "⛽", "🚗", "🌍", "🇲🇦", "📱", "⚙️", "🔧", "🏠", "⭐",
        "❤️", "🔔", "📍", "📷", "🎵", "🍽️", "✈️", "📚"
    )
    val COLORS = listOf(
        "#16A34A", "#22C55E", "#3B82F6", "#0EA5E9", "#8B5CF6", "#EC4899",
        "#EF4444", "#DC2626", "#F59E0B", "#EA580C", "#CA8A04", "#0D9488",
        "#64748B", "#475569", "#1D4ED8", "#7C3AED"
    )

    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)
    fun icon(c: Context, d: TileDef): String = p(c).getString("ti_icon_${d.id}", null) ?: d.icon
    fun label(c: Context, d: TileDef): String = p(c).getString("ti_label_${d.id}", null) ?: d.label
    fun color(c: Context, d: TileDef): Int = p(c).getInt("ti_color_${d.id}", d.color)
    fun visible(c: Context, d: TileDef): Boolean = d.tab == TAB_MORE || p(c).getBoolean("ti_vis_${d.id}", true)

    fun setIcon(c: Context, d: TileDef, v: String) = p(c).edit().putString("ti_icon_${d.id}", v).apply()
    fun setLabel(c: Context, d: TileDef, v: String) = p(c).edit().putString("ti_label_${d.id}", v).apply()
    fun setColor(c: Context, d: TileDef, v: Int) = p(c).edit().putInt("ti_color_${d.id}", v).apply()
    fun setVisible(c: Context, d: TileDef, v: Boolean) = p(c).edit().putBoolean("ti_vis_${d.id}", v).apply()
    fun reset(c: Context, d: TileDef) = p(c).edit()
        .remove("ti_icon_${d.id}").remove("ti_label_${d.id}").remove("ti_color_${d.id}").remove("ti_vis_${d.id}").apply()
}

// ------------------------------------------------------------------ Réglages de l'info sous les icônes

object Info {
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)
    fun newsOn(c: Context) = p(c).getBoolean("hi_news_on", true)
    fun footOn(c: Context) = p(c).getBoolean("hi_foot_on", true)
    fun newsCount(c: Context) = p(c).getInt("hi_news_n", 2).coerceIn(1, 3)
    fun footCount(c: Context) = p(c).getInt("hi_foot_n", 2).coerceIn(1, 3)
    fun region(c: Context) = p(c).getString("hi_region", News.NA) ?: News.NA
    fun setNewsOn(c: Context, v: Boolean) = p(c).edit().putBoolean("hi_news_on", v).apply()
    fun setFootOn(c: Context, v: Boolean) = p(c).edit().putBoolean("hi_foot_on", v).apply()
    fun setNewsCount(c: Context, v: Int) = p(c).edit().putInt("hi_news_n", v).apply()
    fun setFootCount(c: Context, v: Int) = p(c).edit().putInt("hi_foot_n", v).apply()
    fun setRegion(c: Context, v: String) = p(c).edit().putString("hi_region", v).apply()
}

private var homeNewsLoading = false
private var homeNewsTry = 0L
private var homeFootLoading = false
private var homeFootTry = 0L

private fun MainActivity.homeNewsLoad(region: String) {
    val now = System.currentTimeMillis()
    if (homeNewsLoading || now - homeNewsTry < 120_000) return
    homeNewsLoading = true
    homeNewsTry = now
    Thread {
        val l = try { News.fetch(region) } catch (_: Exception) { emptyList() }
        if (l.isNotEmpty()) News.save(this, region, l)
        runOnUiThread {
            homeNewsLoading = false
            if (l.isNotEmpty() && currentTab() == TAB_HOME) refresh()
        }
    }.start()
}

private fun MainActivity.homeFootLoad() {
    val now = System.currentTimeMillis()
    if (homeFootLoading || now - homeFootTry < 120_000) return
    homeFootLoading = true
    homeFootTry = now
    Thread {
        var ok = false
        try {
            val all = ArrayList<Match>()
            listOf(Foot.ES, Foot.EN).forEach { lg ->
                all.addAll(Foot.matches(lg).filter { it.state == "in" || Foot.isBig(it) })
            }
            all.addAll(Foot.special())
            Foot.saveMatches(this, "home", all.distinctBy { it.home + it.away + it.time }.sortedBy { it.time })
            ok = true
        } catch (_: Exception) {}
        runOnUiThread {
            homeFootLoading = false
            if (ok && currentTab() == TAB_HOME) refresh()
        }
    }.start()
}

private fun MainActivity.infoCard(title: String, onClick: () -> Unit): LinearLayout {
    val card = Ui.card(this)
    card.background = Fx.tileBg(this)
    card.isClickable = true
    card.setOnClickListener { onClick() }
    card.addView(Ui.text(this, title, 15f, Ui.ACCENT, true).apply { setPadding(0, 0, 0, Ui.dp(this@infoCard, 4)) })
    return card
}

/** Les infos (أخبار / كورة) تحت الأيقونات، حسب ما اختار المستعمل فـ الإعدادات. */
fun MainActivity.homeInfo(col: LinearLayout) {
    val ctx = this
    val now = System.currentTimeMillis()

    if (Info.newsOn(ctx)) {
        val region = Info.region(ctx)
        val items = News.cached(ctx, region)
        if (items.isEmpty() || now - News.cachedAt(ctx, region) > 10 * 60_000L) homeNewsLoad(region)
        val card = infoCard("📰 ${Tiles.label(ctx, Tiles.ALL.first { it.id == "news" })} · ${News.NAMES[region] ?: ""}") { show(TAB_NEWS) }
        if (items.isEmpty()) card.addView(Ui.text(ctx, "⏳ كنجيب الأخبار...", 13f, Ui.MUTED))
        items.take(Info.newsCount(ctx)).forEach { n ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
                isClickable = true
                setOnClickListener { openArticle(n) }
            }
            line.addView(Ui.text(ctx, n.title, 14f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            line.addView(Ui.text(ctx, "${n.source} · ${Fmt.ago(n.time)}", 11f, Ui.MUTED))
            card.addView(line)
        }
        col.addView(card)
    }

    if (Info.footOn(ctx)) {
        val ms = Foot.loadMatches(ctx, "home")
        val anyLive = ms.any { it.state == "in" }
        val limit = if (anyLive) 60_000L else 10 * 60_000L
        if (ms.isEmpty() || now - Foot.at(ctx, "home") > limit) homeFootLoad()
        val live = ms.filter { it.state == "in" }
        val next = ms.filter { it.state == "pre" && it.time >= now }.sortedBy { it.time }
        val done = ms.filter { it.state == "post" }.sortedByDescending { it.time }
        val pick = (live + next + done).take(Info.footCount(ctx))
        val card = infoCard("⚽ ${Tiles.label(ctx, Tiles.ALL.first { it.id == "foot" })}") { show(TAB_FOOT) }
        if (pick.isEmpty()) card.addView(Ui.text(ctx, "⏳ كنجيب الماتشات...", 13f, Ui.MUTED))
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        pick.forEach { m ->
            val s = when (m.state) {
                "in" -> "🔴 ${m.home} ${m.hs} - ${m.aws} ${m.away}  (${m.detail})"
                "post" -> "✅ ${m.home} ${m.hs} - ${m.aws} ${m.away}"
                else -> "🕒 ${m.home} - ${m.away}  ${fmt.format(java.util.Date(m.time))}"
            }
            card.addView(Ui.text(ctx, s, 14f).apply { setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3)) })
        }
        col.addView(card)
    }
}

// ------------------------------------------------------------------ Cartes de réglages

private fun MainActivity.toggleBtn(label: String, on: Boolean, action: () -> Unit) =
    Ui.button(this, (if (on) "✅ " else "⬜ ") + label, if (on) Ui.ACCENT else Ui.CHIP) { action() }

fun MainActivity.homeSettingsCard(): View {
    val ctx = this
    val card = Ui.card(ctx)
    card.addView(Ui.text(ctx, "🏠 شنو يبان تحت الأيقونات", 16f, bold = true))
    card.addView(Ui.text(ctx, "ختار الأخبار ولا الكورة ولا جوج، وشحال بغيتي تبان (1 حتى 3).", 13f, Ui.MUTED))

    val r1 = Ui.row(ctx)
    r1.addView(toggleBtn("الأخبار", Info.newsOn(ctx)) { Info.setNewsOn(ctx, !Info.newsOn(ctx)); refresh() }, Ui.lp(ctx, 1f))
    (1..3).forEach { n ->
        r1.addView(Ui.button(ctx, "$n", if (Info.newsCount(ctx) == n) Ui.ACCENT else Ui.CHIP) {
            Info.setNewsCount(ctx, n); refresh()
        })
    }
    card.addView(r1)

    val regions = Ui.row(ctx)
    News.REGIONS.forEach { r ->
        regions.addView(Ui.button(ctx, News.NAMES[r] ?: r, if (Info.region(ctx) == r) Ui.BLUE else Ui.CHIP) {
            Info.setRegion(ctx, r); refresh()
        }, Ui.lp(ctx))
    }
    card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(regions) })

    val r2 = Ui.row(ctx)
    r2.addView(toggleBtn("الكورة", Info.footOn(ctx)) { Info.setFootOn(ctx, !Info.footOn(ctx)); refresh() }, Ui.lp(ctx, 1f))
    (1..3).forEach { n ->
        r2.addView(Ui.button(ctx, "$n", if (Info.footCount(ctx) == n) Ui.ACCENT else Ui.CHIP) {
            Info.setFootCount(ctx, n); refresh()
        })
    }
    card.addView(r2)
    return card
}

fun MainActivity.iconsSettingsCard(): View {
    val ctx = this
    val card = Ui.card(ctx)
    card.addView(Ui.text(ctx, "🎨 الأيقونات ديال الرئيسية", 16f, bold = true))
    card.addView(Ui.text(ctx, "بدّل السمية، الأيقونة، اللون، ولا خبّي اللي ما بغيتيش.", 13f, Ui.MUTED))

    Tiles.ALL.forEach { d ->
        val shown = Tiles.visible(ctx, d)
        val row = Ui.row(ctx).apply { setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6)) }
        row.addView(Ui.text(ctx, Tiles.icon(ctx, d), 22f).apply {
            gravity = Gravity.CENTER
            background = Fx.icon3d(ctx, Tiles.color(ctx, d), 42, 12)
            alpha = if (shown) 1f else 0.4f
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 42), Ui.dp(ctx, 42)))
        row.addView(Ui.text(ctx, Tiles.label(ctx, d), 14f, if (shown) Ui.TEXT else Ui.MUTED).apply {
            setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), 0)
        }, Ui.lp(ctx, 1f, 0))
        row.addView(Ui.button(ctx, "✏️", Ui.CHIP) {
            askText("✏️ سمية جديدة", "السمية", Tiles.label(ctx, d)) { Tiles.setLabel(ctx, d, it); refresh() }
        }, Ui.lp(ctx, 0f, 2))
        row.addView(Ui.button(ctx, "🎨", Ui.CHIP) { pickIcon(d) }, Ui.lp(ctx, 0f, 2))
        if (d.tab != TAB_MORE) {
            row.addView(Ui.button(ctx, if (shown) "👁" else "🚫", if (shown) Ui.CHIP else Ui.RED) {
                Tiles.setVisible(ctx, d, !shown); refresh()
            }, Ui.lp(ctx, 0f, 2))
        }
        card.addView(row)
    }
    card.addView(Ui.button(ctx, "↩️ رجّع كلشي لأصلو", Ui.ORANGE) {
        confirm("نرجّع الأيقونات والسميات كاملين لأصلهم؟") {
            Tiles.ALL.forEach { Tiles.reset(ctx, it) }
            refresh()
        }
    })
    return card
}

private fun MainActivity.pickIcon(d: TileDef) {
    val ctx = this
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
    }
    val preview = Ui.text(ctx, Tiles.icon(ctx, d), 34f).apply { gravity = Gravity.CENTER }
    fun paint() {
        preview.text = Tiles.icon(ctx, d)
        preview.background = Fx.icon3d(ctx, Tiles.color(ctx, d), 64, 18)
    }
    paint()
    box.addView(preview, LinearLayout.LayoutParams(Ui.dp(ctx, 64), Ui.dp(ctx, 64)).apply {
        gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(ctx, 10)
    })

    box.addView(Ui.text(ctx, "الأيقونة", 13f, Ui.MUTED))
    Tiles.ICONS.chunked(6).forEach { chunk ->
        val r = Ui.row(ctx)
        chunk.forEach { e ->
            r.addView(Ui.text(ctx, e, 26f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { Tiles.setIcon(ctx, d, e); paint() }
            }, LinearLayout.LayoutParams(0, Ui.dp(ctx, 46), 1f))
        }
        repeat(6 - chunk.size) { r.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
        box.addView(r)
    }

    box.addView(Ui.text(ctx, "اللون", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(ctx, 10), 0, 0) })
    Tiles.COLORS.chunked(8).forEach { chunk ->
        val r = Ui.row(ctx)
        chunk.forEach { hex ->
            val col = Color.parseColor(hex)
            r.addView(View(ctx).apply {
                background = Fx.icon3d(ctx, col, 30, 9)
                setOnClickListener { Tiles.setColor(ctx, d, col); paint() }
            }, LinearLayout.LayoutParams(0, Ui.dp(ctx, 34), 1f).apply { setMargins(Ui.dp(ctx, 2), Ui.dp(ctx, 3), Ui.dp(ctx, 2), Ui.dp(ctx, 3)) })
        }
        box.addView(r)
    }

    AlertDialog.Builder(ctx)
        .setTitle(Tiles.label(ctx, d))
        .setView(ScrollView(ctx).apply { addView(box) })
        .setPositiveButton("تم") { _, _ -> refresh() }
        .setNeutralButton("↩️ الأصل") { _, _ -> Tiles.reset(ctx, d); refresh() }
        .setOnCancelListener { refresh() }
        .show()
}
