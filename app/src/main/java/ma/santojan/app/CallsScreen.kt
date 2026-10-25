package ma.santojan.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import java.util.Calendar

fun MainActivity.callsScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "📞 المكالمات"))

    if (!has(Manifest.permission.READ_CALL_LOG)) {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, "خاصني الإذن باش نقرا سجل المكالمات.", color = Ui.MUTED))
        c.addView(Ui.button(ctx, "عطي الإذن") { askPermissions() })
        col.addView(c)
        return Ui.scroll(ctx, col)
    }

    col.addView(Ui.text(ctx, "ورك على مكالمة باش تعيّط • ضغط طويل باش تاخد تقرير 🤖", 12f, Ui.MUTED))
    col.addView(Ui.gap(ctx, 6))

    val startOfDay = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    var todaySec = 0L
    var todayCount = 0
    val rows = mutableListOf<View>()
    try {
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            var n = 0
            var more = c.moveToFirst()
            while (more) {
                val number = c.getString(0) ?: ""
                val name = c.getString(1)
                val type = c.getInt(2)
                val date = c.getLong(3)
                val dur = c.getLong(4)
                if (date >= startOfDay && dur > 0) { todaySec += dur; todayCount++ }
                if (n < 150) { rows.add(callRow(number, name, type, date, dur)); n++ }
                more = if (n >= 150 && date < startOfDay) false else c.moveToNext()
            }
        }
    } catch (e: Exception) {
        col.addView(Ui.text(ctx, "ما قدرتش نقرا السجل: ${e.message}", color = Ui.RED))
    }

    val sum = Ui.card(ctx)
    sum.addView(Ui.text(ctx, "اليوم: $todayCount مكالمة", 16f, bold = true))
    sum.addView(Ui.text(ctx, "مجموع الوقت لي هضرتي: ${Fmt.dur(todaySec)}", 14f, Ui.ACCENT))
    col.addView(sum)

    if (rows.isEmpty()) col.addView(Ui.text(ctx, "ما كاين حتى مكالمة.", color = Ui.MUTED))
    rows.forEach { col.addView(it) }
    return Ui.scroll(ctx, col)
}

private fun MainActivity.callRow(number: String, name: String?, type: Int, date: Long, dur: Long): View {
    val ctx = this
    val card = Ui.card(ctx)
    val saved = !name.isNullOrBlank()
    val (label, color) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "↙ واردة" to Ui.ACCENT
        CallLog.Calls.OUTGOING_TYPE -> "↗ صادرة" to Ui.BLUE
        CallLog.Calls.MISSED_TYPE -> "✖ فائتة" to Ui.RED
        CallLog.Calls.REJECTED_TYPE -> "⛔ مرفوضة" to Ui.ORANGE
        else -> "• مكالمة" to Ui.MUTED
    }
    val top = Ui.row(ctx)
    val info = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
    info.addView(Ui.text(ctx, if (saved) name!! else number.ifBlank { "نمرة مخبية" }, 16f, bold = true))
    if (saved) info.addView(Ui.text(ctx, number, 12f, Ui.MUTED))
    val durTxt = if (dur > 0) Fmt.dur(dur) else "ما تهضرتوش"
    info.addView(Ui.text(ctx, "$label  •  ${Fmt.date(date)}  •  $durTxt", 13f, color))
    top.addView(info, Ui.lp(ctx, 1f, 0))
    if (number.isNotBlank()) {
        top.addView(Ui.button(ctx, "📞", Ui.ACCENT) { callNow(number) }, Ui.lp(ctx))
        top.addView(Ui.button(ctx, "💬", Ui.CHIP) { whatsapp(number) }, Ui.lp(ctx))
        // Toucher la carte = rappeler direct
        card.setOnClickListener { callNow(number) }
        // Appui long = rapport IA de la conversation
        card.setOnLongClickListener { callReport(number, if (saved) name!! else number, date, dur); true }
    }
    card.addView(top)

    if (!saved && number.isNotBlank()) {
        card.addView(Ui.text(ctx, "⚠️ نمرة ما مسجّلاش", 13f, Ui.ORANGE))
        val r = Ui.row(ctx)
        r.addView(Ui.button(ctx, "سجّل", Ui.ACCENT) {
            openSafe(
                Intent(Intent.ACTION_INSERT)
                    .setType(ContactsContract.Contacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number)
            )
        }, Ui.lp(ctx, 1f))
        r.addView(Ui.button(ctx, "فكّرني 20د", Ui.ORANGE) {
            Reminders.add(ctx, "عيّط على $number", 20)
            toast("غادي نفكرك من 20 دقيقة")
        }, Ui.lp(ctx, 1f))
        card.addView(r)
    }
    return card
}

/** Réglage du chrono affiché pendant les appels (dans « المزيد »). */
fun MainActivity.overlayCard(): View {
    val ctx = this
    val ov = Ui.card(ctx)
    ov.addView(Ui.text(ctx, "⏱️ المدة فوق الشاشة ملي تكون كتهضر", bold = true))
    if (!Settings.canDrawOverlays(ctx)) {
        ov.addView(Ui.text(ctx, "فعّل «Afficher par-dessus les autres apps» باش يبان ليك الشرونو أثناء المكالمة.", 13f, Ui.MUTED))
        ov.addView(Ui.button(ctx, "فعّل") {
            openSafe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
    } else {
        val on = Store.getB(ctx, "overlay", true)
        ov.addView(Ui.text(ctx, if (on) "مفعّل ✅ (تقدر تجرّه بصبعك)" else "متوقف", 13f, Ui.MUTED))
        ov.addView(Ui.button(ctx, if (on) "وقّف" else "شغّل", if (on) Ui.RED else Ui.ACCENT) {
            Store.putB(ctx, "overlay", !on); refresh()
        })
    }
    return ov
}
