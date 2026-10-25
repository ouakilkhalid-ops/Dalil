package ma.santojan.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.HorizontalScrollView
import org.json.JSONArray
import org.json.JSONObject

class Rem(val id: Int, val text: String, val at: Long)

object Reminders {
    private const val KEY = "rems"

    fun list(c: Context): List<Rem> {
        val a = Store.arr(c, KEY)
        return (0 until a.length()).mapNotNull {
            try {
                val o = a.getJSONObject(it)
                Rem(o.getInt("id"), o.getString("text"), o.getLong("at"))
            } catch (e: Exception) { null }
        }
    }

    private fun save(c: Context, l: List<Rem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        Store.saveArr(c, KEY, a)
    }

    fun add(c: Context, text: String, minutes: Int) =
        addAt(c, text, System.currentTimeMillis() + minutes * 60_000L)

    fun addAt(c: Context, text: String, at: Long) {
        val now = System.currentTimeMillis()
        val r = Rem((now % 1_000_000_000L).toInt(), text, at)
        save(c, list(c) + r)
        schedule(c, r)
    }

    private fun pi(c: Context, r: Rem): PendingIntent = PendingIntent.getBroadcast(
        c, r.id,
        Intent(c, ReminderReceiver::class.java).putExtra("id", r.id).putExtra("text", r.text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, r: Rem) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(r.at, System.currentTimeMillis() + 3000)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, r))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, r))
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi(c, r))
        }
    }

    fun cancel(c: Context, id: Int) {
        list(c).find { it.id == id }?.let {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, it))
        }
        remove(c, id)
    }

    fun remove(c: Context, id: Int) = save(c, list(c).filter { it.id != id })

    fun rescheduleAll(c: Context) = list(c).forEach { schedule(c, it) }
}

object Notif {
    const val CH_REM = "rem"
    const val CH_CALL = "call"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REM, "التذكيرات", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "مدة المكالمة", NotificationManager.IMPORTANCE_LOW))
    }

    fun show(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channels(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CH_REM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        val text = i.getStringExtra("text") ?: "تذكير"
        Notif.show(c, id, "⏰ تذكير", text)
        Reminders.remove(c, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { Reminders.rescheduleAll(c); Focus.apply(c) }
    }
}

fun MainActivity.remindersScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "⏰ فكّرني من بعد"))

    val card = Ui.card(ctx)
    val what = Ui.input(ctx, "شنو نفكرك بيه؟ (مثلا: عيّط على أحمد)")
    val mins = Ui.input(ctx, "شحال من دقيقة؟", number = true).apply { setText("20") }
    card.addView(what)
    card.addView(Ui.text(ctx, "من بعد شحال؟ (كتب أي رقم بالدقايق)", 13f, Ui.MUTED))
    card.addView(mins)

    val quick = Ui.row(ctx)
    listOf(5, 10, 20, 30, 45, 60, 90, 120, 180).forEach { m ->
        quick.addView(Ui.button(ctx, Fmt.minutes(m), Ui.CHIP) { mins.setText(m.toString()) }, Ui.lp(ctx))
    }
    card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(quick) })

    card.addView(Ui.button(ctx, "🔔 فكّرني من بعد هاد الدقايق", Ui.ACCENT) {
        val m = mins.text.toString().replace(',', '.').toDoubleOrNull()?.toInt()
        if (m == null || m < 1) {
            toast("كتب شحال من دقيقة")
        } else {
            val t = what.text.toString().trim().ifBlank { "تذكير" }
            Reminders.add(ctx, t, m)
            toast("غادي نفكرك من ${Fmt.minutes(m)}")
            refresh()
        }
    })

    // ---- Calendrier : un jour et une heure précis
    card.addView(Ui.gap(ctx, 10))
    card.addView(Ui.text(ctx, "📅 ولا اختار نهار وساعة", 15f, bold = true))
    fun saveAt(at: Long) {
        if (at <= System.currentTimeMillis()) { toast("هاد الوقت داز"); return }
        val t = what.text.toString().trim().ifBlank { "تذكير" }
        Reminders.addAt(ctx, t, at)
        toast("غادي نفكرك نهار ${Fmt.date(at)}")
        refresh()
    }
    fun pickTime(cal: java.util.Calendar) {
        android.app.TimePickerDialog(ctx, { _, h, m ->
            cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, m)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            saveAt(cal.timeInMillis)
        }, 9, 0, true).show()
    }
    fun dayCal(addDays: Int): java.util.Calendar =
        java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, addDays) }

    val days = Ui.row(ctx)
    days.addView(Ui.button(ctx, "غدا", Ui.CHIP) { pickTime(dayCal(1)) }, Ui.lp(ctx))
    days.addView(Ui.button(ctx, "بعد غدا", Ui.CHIP) { pickTime(dayCal(2)) }, Ui.lp(ctx))
    days.addView(Ui.button(ctx, "السيمانة الجاية", Ui.CHIP) { pickTime(dayCal(7)) }, Ui.lp(ctx))
    days.addView(Ui.button(ctx, "الشهر الجاي", Ui.CHIP) {
        pickTime(java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, 1) })
    }, Ui.lp(ctx))
    card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(days) })
    card.addView(Ui.button(ctx, "📅 حل الكالندرييه", Ui.BLUE) {
        val now = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            pickTime(java.util.Calendar.getInstance().apply { set(y, mo, d) })
        }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH))
            .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }
            .show()
    })
    col.addView(card)

    if (Build.VERSION.SDK_INT >= 31) {
        val am = getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) {
            val w = Ui.card(ctx)
            w.addView(Ui.text(ctx, "باش يجي التذكير فالدقيقة بالضبط، فعّل «Alarmes et rappels».", 13f, Ui.ORANGE))
            w.addView(Ui.button(ctx, "فعّل") {
                openSafe(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
            col.addView(w)
        }
    }

    val list = Reminders.list(ctx).sortedBy { it.at }
    col.addView(Ui.text(ctx, if (list.isEmpty()) "ما كاين حتى تذكير." else "التذكيرات الجاية:", 15f, Ui.MUTED, true))
    val now = System.currentTimeMillis()
    list.forEach { r ->
        val c = Ui.card(ctx)
        val row = Ui.row(ctx)
        val info = Ui.column(ctx).apply { setPadding(0, 0, 0, 0) }
        info.addView(Ui.text(ctx, r.text, 15f, bold = true))
        val left = ((r.at - now) / 60000).toInt().coerceAtLeast(0)
        val whenTxt = if (left < 1440) "على ${Fmt.time(r.at)}  •  باقي ${Fmt.minutes(left)}"
                      else "${Fmt.date(r.at)}  •  باقي ${left / 1440} يوم"
        info.addView(Ui.text(ctx, whenTxt, 13f, Ui.ACCENT))
        row.addView(info, Ui.lp(ctx, 1f))
        row.addView(Ui.button(ctx, "لغي", Ui.RED) { Reminders.cancel(ctx, r.id); refresh() })
        c.addView(row)
        col.addView(c)
    }
    return Ui.scroll(ctx, col)
}
