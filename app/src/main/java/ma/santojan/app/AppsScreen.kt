package ma.santojan.app

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

private const val DAY = 86_400_000L

class AppRow(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val last: Long,
    val weekMs: Long,
    val system: Boolean
)

fun MainActivity.appsScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "📱 التطبيقات"))

    if (!hasUsageAccess()) {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, "باش نعرف آخر مرة حلّيتي كل تطبيق، خاصك تفعّل «Accès aux données d'utilisation» لـ Dalil.", 14f, Ui.MUTED))
        c.addView(Ui.button(ctx, "فعّل") { openSafe(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        col.addView(c)
        return Ui.scroll(ctx, col)
    }

    val status = Ui.text(ctx, "كنجمع التطبيقات...", color = Ui.MUTED)
    col.addView(status)
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(box)

    // Le chargement se fait en arrière-plan : c'est ça qui faisait bloquer/fermer l'ancien « nettoyage »
    Thread {
        val rows: List<AppRow>? = try { loadApps() } catch (e: Exception) { null }
        runOnUiThread {
            if (rows == null) {
                status.text = "وقع مشكل فالتحميل، عاود جرب."
                return@runOnUiThread
            }
            val now = System.currentTimeMillis()
            val unused = rows.count { !it.system && (it.last == 0L || now - it.last > 30 * DAY) }
            status.text = "${rows.size} تطبيق  •  $unused ما تحلّوش من أكثر من 30 يوم\n(اللي ما مستعملينش كيبانو اللولين)"
            rows.forEach { box.addView(appRow(it)) }
        }
    }.start()

    return Ui.scroll(ctx, col)
}

private fun MainActivity.loadApps(): List<AppRow> {
    val pm = packageManager
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val ris = pm.queryIntentActivities(launch, 0)

    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val last = HashMap<String, Long>()
    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60 * DAY, now)?.forEach { s ->
        if (s.lastTimeUsed > (last[s.packageName] ?: 0L)) last[s.packageName] = s.lastTimeUsed
    }
    usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, now - 365 * DAY, now)?.forEach { s ->
        if (s.lastTimeUsed > (last[s.packageName] ?: 0L)) last[s.packageName] = s.lastTimeUsed
    }
    val week = usm.queryAndAggregateUsageStats(now - 7 * DAY, now)

    val seen = HashSet<String>()
    val out = ArrayList<AppRow>()
    for (ri in ris) {
        val pkg = ri.activityInfo.packageName
        if (pkg == packageName || !seen.add(pkg)) continue
        val ai = ri.activityInfo.applicationInfo
        val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
        val lastUsed = last[pkg] ?: 0L
        out.add(AppRow(pkg, ri.loadLabel(pm).toString(), icon, if (lastUsed > now) 0L else lastUsed, week[pkg]?.totalTimeInForeground ?: 0L, sys))
    }
    out.sortBy { it.last }
    return out
}

private fun MainActivity.appRow(a: AppRow): View {
    val ctx = this
    val now = System.currentTimeMillis()
    val card = Ui.card(ctx)
    val r = Ui.row(ctx)

    val iv = ImageView(ctx).apply { setImageDrawable(a.icon) }
    r.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)))

    val info = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), 0)
    }
    val old = a.last == 0L || now - a.last > 30 * DAY
    info.addView(Ui.text(ctx, a.label, 15f, bold = true))
    info.addView(Ui.text(ctx, Fmt.ago(a.last), 13f, if (old) Ui.ORANGE else Ui.ACCENT))
    if (a.last > 0L) info.addView(Ui.text(ctx, "آخر مرة: ${Fmt.date(a.last)}", 12f, Ui.MUTED))
    if (a.weekMs > 60_000L) info.addView(Ui.text(ctx, "هاد السيمانة: ${Fmt.dur(a.weekMs / 1000)}", 12f, Ui.MUTED))
    r.addView(info, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

    if (!a.system) {
        r.addView(Ui.button(ctx, "حذف", Ui.RED) {
            openSafe(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
        })
    } else {
        r.addView(Ui.text(ctx, "ديال\nالنظام", 11f, Ui.MUTED))
    }
    card.addView(r)
    card.setOnClickListener {
        openSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
    }
    return card
}
