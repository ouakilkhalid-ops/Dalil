package ma.santojan.app

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View

fun MainActivity.moreScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "⚙️ الإعدادات"))

    // ---- Autorisations
    val perms = Ui.card(ctx)
    perms.addView(Ui.text(ctx, "🔑 الأذونات", 16f, bold = true))
    fun line(label: String, ok: Boolean, action: () -> Unit) {
        val r = Ui.row(ctx)
        r.addView(Ui.text(ctx, (if (ok) "✅ " else "❌ ") + label, 14f), Ui.lp(ctx, 1f, 2))
        if (!ok) r.addView(Ui.button(ctx, "فعّل", Ui.BLUE, action))
        perms.addView(r)
    }
    line("سجل المكالمات", has(Manifest.permission.READ_CALL_LOG)) { askPermissions() }
    line("حالة الهاتف (للشرونو)", has(Manifest.permission.READ_PHONE_STATE)) { askPermissions() }
    line("جهات الاتصال", has(Manifest.permission.READ_CONTACTS)) { askPermissions() }
    if (Build.VERSION.SDK_INT >= 33) line("الإشعارات", has(Manifest.permission.POST_NOTIFICATIONS)) { askPermissions() }
    line("العرض فوق التطبيقات", Settings.canDrawOverlays(ctx)) {
        openSafe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
    line("استعمال التطبيقات", hasUsageAccess()) { openSafe(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    if (Build.VERSION.SDK_INT >= 31) {
        val am = getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
        line("المنبهات الدقيقة", am.canScheduleExactAlarms()) {
            openSafe(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }
    col.addView(perms)

    col.addView(homeSettingsCard())
    col.addView(iconsSettingsCard())
    col.addView(aiSettingsCard())
    col.addView(aiCallsCard())
    col.addView(overlayCard())

    // ---- Xiaomi
    val xi = Ui.card(ctx)
    xi.addView(Ui.text(ctx, "📱 مهم فـ Xiaomi", 16f, bold = true))
    xi.addView(Ui.text(ctx,
        "باش يخدمو التذكيرات والشرونو مزيان: فعّل «Démarrage automatique» (Autostart) وحط البطارية على «Pas de restrictions» لـ Dalil.",
        14f, Ui.MUTED))
    val xr = Ui.row(ctx)
    xr.addView(Ui.button(ctx, "Autostart", Ui.ORANGE) {
        val i = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        try { startActivity(i) } catch (e: Exception) {
            openSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }, Ui.lp(ctx, 1f))
    xr.addView(Ui.button(ctx, "إعدادات التطبيق", Ui.BLUE) {
        openSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }, Ui.lp(ctx, 1f))
    xi.addView(xr)
    col.addView(xi)

    // ---- Mode nuit
    val night = Ui.card(ctx)
    night.addView(Ui.text(ctx, "🌙 السكوت فالليل", 16f, bold = true))
    night.addView(Ui.text(ctx,
        "استعمل «Ne pas déranger» ديال التيليفون: برمجو من 23:00 حتى 7:00، وفعّل «Appels répétés».\n" +
        "ملاحظة: النظام كيخلي التيليفون يصوني إلا عيّط نفس الشخص جوج مرات فـ 15 دقيقة (ماشي 3 مرات).",
        14f, Ui.MUTED))
    night.addView(Ui.button(ctx, "حل «Ne pas déranger»", Ui.BLUE) {
        if (!openSafeQuiet(Intent("android.settings.ZEN_MODE_SETTINGS"))) openSafe(Intent(Settings.ACTION_SOUND_SETTINGS))
    })
    col.addView(night)

    // ---- Bientôt
    val soon = Ui.card(ctx)
    soon.addView(Ui.text(ctx, "🔜 جاي فالنسخة الجاية", 16f, bold = true))
    soon.addView(Ui.text(ctx,
        "• تذكير بالإيمايلات المهمة ديال Gmail",
        14f, Ui.MUTED))
    col.addView(soon)

    col.addView(Ui.text(ctx, "Dalil v6.4", 12f, Ui.MUTED))
    return Ui.scroll(ctx, col)
}

private fun MainActivity.openSafeQuiet(i: Intent): Boolean = try {
    startActivity(i); true
} catch (e: Exception) { false }
