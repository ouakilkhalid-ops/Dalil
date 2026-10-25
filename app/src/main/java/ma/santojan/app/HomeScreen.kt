package ma.santojan.app

import android.Manifest
import android.graphics.Color
import android.provider.CallLog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.Calendar

private class Tile(val tab: Int, val icon: String, val label: String, val color: Int, val badge: String?)

private fun MainActivity.callsToday(): Pair<Int, Int> {
    if (!has(Manifest.permission.READ_CALL_LOG)) return 0 to 0
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    var all = 0; var missed = 0
    try {
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.TYPE),
            CallLog.Calls.DATE + ">=?", arrayOf(start.toString()), null)?.use { c ->
            while (c.moveToNext()) { all++; if (c.getInt(0) == CallLog.Calls.MISSED_TYPE) missed++ }
        }
    } catch (_: Exception) {}
    return all to missed
}

fun MainActivity.homeScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    WaStore.onChange = { if (currentTab() == TAB_WA || currentTab() == TAB_HOME) refresh() }

    // Petites infos pour chaque carré
    val (calls, missed) = callsToday()
    val waUnread = try {
        val hidden = WaStore.hidden(ctx)
        WaStore.all(ctx).values.filter { it.name !in hidden }.sumOf { it.unread }
    } catch (_: Exception) { 0 }
    val rems = Reminders.list(ctx).count { it.at > System.currentTimeMillis() }
    val focusUntil = Focus.activeUntil(ctx)
    val month = Calendar.getInstance()
    val spent = Charges.list(ctx).filter {
        val d = Calendar.getInstance().apply { timeInMillis = it.date }
        d.get(Calendar.YEAR) == month.get(Calendar.YEAR) && d.get(Calendar.MONTH) == month.get(Calendar.MONTH)
    }.sumOf { it.amount }

    val badges = mapOf<String, String?>(
        "calls" to (if (missed > 0) "$missed فائتة" else if (calls > 0) "$calls اليوم" else null),
        "wa" to (if (waUnread > 0) "$waUnread ما مقريين" else null),
        "meet" to (if (RecService.recording) "🔴 كيسجّل" else null),
        "focus" to (if (focusUntil > System.currentTimeMillis()) "🟢 حتى ${Fmt.time(focusUntil)}" else null),
        "rem" to (if (rems > 0) "$rems جايين" else null),
        "spend" to (if (spent > 0) Fmt.money(spent) else null),
        "money" to moneyBadge()
    )
    val tiles = Tiles.ALL.filter { Tiles.visible(ctx, it) }.map {
        Tile(it.tab, Tiles.icon(ctx, it), Tiles.label(ctx, it), Tiles.color(ctx, it), badges[it.id])
    }

    tiles.chunked(3).forEach { rowTiles ->
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        rowTiles.forEach { t -> row.addView(tileView(t), LinearLayout.LayoutParams(0, Ui.dp(ctx, 128), 1f).apply {
            setMargins(Ui.dp(ctx, 5), Ui.dp(ctx, 5), Ui.dp(ctx, 5), Ui.dp(ctx, 5))
        }) }
        repeat(3 - rowTiles.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
        col.addView(row)
    }
    homeInfo(col)
    return Ui.scroll(ctx, col)
}

private fun MainActivity.tileView(t: Tile): View {
    val ctx = this
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Fx.tileBg(ctx)
        elevation = Ui.dp(ctx, 4).toFloat()
        setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        isClickable = true
        setOnClickListener { show(t.tab) }
    }
    val icon = Ui.text(ctx, t.icon, 31f).apply {
        gravity = Gravity.CENTER
        background = Fx.icon3d(ctx, t.color, 62, 17)
        elevation = Ui.dp(ctx, 6).toFloat()
        setShadowLayer(7f, 0f, 4f, Color.argb(140, 0, 0, 0))
    }
    box.addView(icon, LinearLayout.LayoutParams(Ui.dp(ctx, 62), Ui.dp(ctx, 62)))
    box.addView(Ui.text(ctx, t.label, 15f, Ui.TEXT, true).apply {
        gravity = Gravity.CENTER; setPadding(0, Ui.dp(ctx, 6), 0, 0)
    }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    box.addView(Ui.text(ctx, t.badge ?: " ", 11f, if (t.badge != null) Ui.ACCENT else Ui.MUTED).apply {
        gravity = Gravity.CENTER; maxLines = 1
    }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    return box
}

private fun MainActivity.moneyBadge(): String? {
    val l = Money.cached(this)
    val t = l.getOrNull(0) ?: return null
    val y = l.getOrNull(1)
    val arrow = if (y == null) "" else if (t.eur > y.eur) " ▲" else if (t.eur < y.eur) " ▼" else ""
    return String.format(java.util.Locale.US, "€ %.2f$arrow", t.eur)
}
