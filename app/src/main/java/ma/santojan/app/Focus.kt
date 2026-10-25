package ma.santojan.app

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallScreeningService
import android.view.View
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import java.util.Calendar

/** Mode « تركيز » : coupe internet et les appels sauf les personnes choisies, à des heures programmées. */
object Focus {
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)

    // Réglages
    fun scheduleOn(c: Context) = p(c).getBoolean("f_sched", false)
    fun startMin(c: Context) = p(c).getInt("f_start", 22 * 60)
    fun endMin(c: Context) = p(c).getInt("f_end", 7 * 60)
    fun days(c: Context): Set<Int> = (p(c).getString("f_days", "1,2,3,4,5,6,7") ?: "")
        .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    fun blockNet(c: Context) = p(c).getBoolean("f_net", true)
    fun blockCalls(c: Context) = p(c).getBoolean("f_calls", true)
    fun repeatCallers(c: Context) = p(c).getBoolean("f_repeat", true)
    fun rejectUnknown(c: Context) = p(c).getBoolean("f_unknown", false)
    fun allowedGroups(c: Context): List<String> = StrList.get(c, "f_groups", emptyList())
    fun allowedApps(c: Context): List<String> = StrList.get(c, "f_apps", emptyList())
    fun manualUntil(c: Context) = p(c).getLong("f_manual", 0L)
    fun running(c: Context) = p(c).getBoolean("f_running", false)

    fun set(c: Context, block: (android.content.SharedPreferences.Editor) -> Unit) {
        val e = p(c).edit(); block(e); e.apply()
    }

    /** Fenêtres [début, fin) autour de maintenant, selon le programme. */
    private fun windows(c: Context, now: Long): List<Pair<Long, Long>> {
        if (!scheduleOn(c)) return emptyList()
        val s = startMin(c); val e = endMin(c); val d = days(c)
        val out = ArrayList<Pair<Long, Long>>()
        for (off in -1..8) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now; add(Calendar.DAY_OF_YEAR, off)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            // 1 = lundi ... 7 = dimanche
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            if (dow !in d) continue
            val base = cal.timeInMillis
            val st = base + s * 60_000L
            val en = if (e > s) base + e * 60_000L else base + 86_400_000L + e * 60_000L
            out.add(st to en)
        }
        return out
    }

    fun activeUntil(c: Context, now: Long = System.currentTimeMillis()): Long {
        var until = 0L
        if (manualUntil(c) > now) until = manualUntil(c)
        val skip = p(c).getLong("f_skip_until", 0L)
        windows(c, now).forEach { (a, b) -> if (now in a until b && b > until && b > skip) until = b }
        return until
    }

    fun isActive(c: Context) = activeUntil(c) > System.currentTimeMillis()

    private fun nextBoundary(c: Context, now: Long): Long? {
        val pts = ArrayList<Long>()
        windows(c, now).forEach { (a, b) -> pts.add(a); pts.add(b) }
        if (manualUntil(c) > now) pts.add(manualUntil(c))
        return pts.filter { it > now + 1000 }.minOrNull()
    }

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 777, Intent(c, FocusReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Applique l'état (début/fin) et programme le prochain changement. */
    fun apply(c: Context) {
        val now = System.currentTimeMillis()
        val active = isActive(c)
        if (active && !running(c)) start(c)
        else if (!active && running(c)) stop(c)
        else if (active) refreshVpn(c)

        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c))
        val next = nextBoundary(c, now) ?: return
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(c))
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, next, pi(c))
        }
    }

    fun startNow(c: Context, minutes: Int) {
        set(c) { it.putLong("f_manual", System.currentTimeMillis() + minutes * 60_000L) }
        apply(c)
    }

    fun stopNow(c: Context) {
        set(c) { it.putLong("f_manual", 0L) }
        // si on est dans une plage programmée, on la saute jusqu'à sa fin
        val now = System.currentTimeMillis()
        val w = windows(c, now).firstOrNull { now in it.first until it.second }
        if (w != null) set(c) { it.putLong("f_skip_until", w.second) }
        if (running(c)) stop(c)
        apply(c)
    }

    private fun start(c: Context) {
        set(c) { it.putBoolean("f_running", true) }
        if (blockCalls(c)) dndOn(c)
        if (blockNet(c)) vpnStart(c)
        Notif.show(c, 778, "🛡️ وضع التركيز بدا", "حتى ${Fmt.time(activeUntil(c))}")
    }

    private fun stop(c: Context) {
        set(c) { it.putBoolean("f_running", false) }
        dndOff(c)
        vpnStop(c)
        Notif.show(c, 778, "✅ وضع التركيز سالا", "رجع الإنترنيت والمكالمات")
    }

    private fun refreshVpn(c: Context) {
        if (blockNet(c)) vpnStart(c) else vpnStop(c)
    }

    // ---- Appels : Ne pas déranger (seulement les contacts « favoris »)
    fun dndAllowed(c: Context): Boolean =
        (c.getSystemService(NotificationManager::class.java)).isNotificationPolicyAccessGranted

    private fun dndOn(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            set(c) { it.putInt("f_prev_filter", nm.currentInterruptionFilter) }
            var cats = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
            if (repeatCallers(c)) cats = cats or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
            nm.notificationPolicy = NotificationManager.Policy(cats, NotificationManager.Policy.PRIORITY_SENDERS_STARRED, 0)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (_: Exception) {}
    }

    private fun dndOff(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            val prev = p(c).getInt("f_prev_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
            nm.setInterruptionFilter(
                if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else prev
            )
        } catch (_: Exception) {}
    }

    // ---- Internet : VPN qui ne laisse rien passer
    private fun vpnStart(c: Context) {
        if (VpnService.prepare(c) != null) {
            Notif.show(c, 779, "⚠️ ما قدرتش نقطع الإنترنيت", "حل التطبيق ووافق على VPN")
            return
        }
        try { c.startForegroundService(Intent(c, BlockVpn::class.java).setAction(BlockVpn.START)) } catch (_: Exception) {}
    }

    private fun vpnStop(c: Context) {
        try { c.startService(Intent(c, BlockVpn::class.java).setAction(BlockVpn.STOP)) } catch (_: Exception) {}
    }

    /** Numéros des personnes autorisées (groupes choisis), 9 derniers chiffres. */
    fun allowedNumbers(c: Context): Set<String> {
        val g = allowedGroups(c)
        return Groups.list(c).filter { it.name in g }.flatMap { it.members }
            .map { it.number.filter { d -> d.isDigit() }.takeLast(9) }.toSet()
    }

    /** Met une étoile (favori) aux personnes autorisées, pour que « Ne pas déranger » les laisse passer. */
    fun starAllowed(c: Context): Int {
        var n = 0
        val g = allowedGroups(c)
        Groups.list(c).filter { it.name in g }.flatMap { it.members }.forEach { m ->
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(m.number))
                c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getLong(0)
                        val cv = ContentValues().apply { put(ContactsContract.Contacts.STARRED, 1) }
                        n += c.contentResolver.update(
                            ContactsContract.Contacts.CONTENT_URI, cv, ContactsContract.Contacts._ID + "=?", arrayOf(id.toString())
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return n
    }
}

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Focus.apply(c)
}

class BlockVpn : VpnService() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        private var tun: ParcelFileDescriptor? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            close(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        Notif.channels(this)
        val n = Notification.Builder(this, Notif.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🛡️ الإنترنيت مقطوع")
            .setContentText("وضع التركيز حتى ${Fmt.time(Focus.activeUntil(this))}")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(9, n)
        } catch (_: Exception) {}
        close()
        try {
            val b = Builder()
                .setSession("Jassim — تركيز")
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd11:1:1:1::1", 128)
                .addRoute("::", 0)
            try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
            Focus.allowedApps(this).forEach { pkg -> try { b.addDisallowedApplication(pkg) } catch (_: Exception) {} }
            tun = b.establish()
        } catch (_: Exception) {}
        return START_STICKY
    }

    private fun close() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onRevoke() {
        close(); stopSelf()
    }

    override fun onDestroy() {
        close(); super.onDestroy()
    }
}

/** Rejette les numéros inconnus (pas dans les contacts) pendant le mode تركيز. */
class FocusScreen : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val r = CallResponse.Builder()
        val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        val num = details.handle?.schemeSpecificPart?.filter { it.isDigit() }?.takeLast(9) ?: ""
        val block = incoming && Focus.isActive(this) && Focus.blockCalls(this) && Focus.rejectUnknown(this) &&
            num !in Focus.allowedNumbers(this)
        if (block) {
            r.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(false)
        }
        respondToCall(details, r.build())
    }
}

// ------------------------------------------------------------------ Écran

private fun hhmm(m: Int) = String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)

fun MainActivity.focusScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "🛡️ وضع التركيز"))
    col.addView(Ui.text(ctx, "كيقطع الإنترنيت والمكالمات على الكل، غير الناس لي بغيتي، فالوقت لي حددتي، ومن بعد كيرجع كلشي بوحدو.", 13f, Ui.MUTED))
    col.addView(Ui.gap(ctx, 8))

    // ---- État
    val st = Ui.card(ctx)
    val until = Focus.activeUntil(ctx)
    if (until > System.currentTimeMillis()) {
        st.addView(Ui.text(ctx, "🟢 خدام دابا حتى ${Fmt.time(until)}", 18f, Ui.ACCENT, true))
        st.addView(Ui.button(ctx, "⏹️ وقّف دابا", Ui.RED) { Focus.stopNow(ctx); refresh() })
    } else {
        st.addView(Ui.text(ctx, "⚪ متوقف", 18f, Ui.MUTED, true))
        st.addView(Ui.text(ctx, "بدا دابا لمدة:", 13f, Ui.MUTED))
        val r = Ui.row(ctx)
        listOf(30, 60, 120, 180).forEach { m ->
            r.addView(Ui.button(ctx, Fmt.minutes(m), Ui.ACCENT) { Focus.startNow(ctx, m); refresh() }, Ui.lp(ctx))
        }
        st.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(r) })
    }
    col.addView(st)

    // ---- Programme
    val sc = Ui.card(ctx)
    sc.addView(Ui.text(ctx, "⏰ البرنامج", 16f, bold = true))
    val on = CheckBox(ctx).apply {
        text = "يخدم بوحدو كل نهار فهاد الوقت"; setTextColor(Ui.TEXT); isChecked = Focus.scheduleOn(ctx)
        setOnCheckedChangeListener { _, v -> Focus.set(ctx) { it.putBoolean("f_sched", v) }; Focus.apply(ctx) }
    }
    sc.addView(on)
    val tr = Ui.row(ctx)
    tr.addView(Ui.button(ctx, "من ${hhmm(Focus.startMin(ctx))}", Ui.BLUE) {
        android.app.TimePickerDialog(ctx, { _, h, m ->
            Focus.set(ctx) { it.putInt("f_start", h * 60 + m) }; Focus.apply(ctx); refresh()
        }, Focus.startMin(ctx) / 60, Focus.startMin(ctx) % 60, true).show()
    }, Ui.lp(ctx, 1f))
    tr.addView(Ui.button(ctx, "حتى ${hhmm(Focus.endMin(ctx))}", Ui.BLUE) {
        android.app.TimePickerDialog(ctx, { _, h, m ->
            Focus.set(ctx) { it.putInt("f_end", h * 60 + m) }; Focus.apply(ctx); refresh()
        }, Focus.endMin(ctx) / 60, Focus.endMin(ctx) % 60, true).show()
    }, Ui.lp(ctx, 1f))
    sc.addView(tr)
    val names = listOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")
    val d = Focus.days(ctx)
    val dr = Ui.row(ctx)
    names.forEachIndexed { i, n ->
        val day = i + 1
        dr.addView(Ui.button(ctx, n, if (day in d) Ui.ACCENT else Ui.CHIP) {
            val nd = if (day in d) d - day else d + day
            Focus.set(ctx) { it.putString("f_days", nd.sorted().joinToString(",")) }; Focus.apply(ctx); refresh()
        }, Ui.lp(ctx))
    }
    sc.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(dr) })
    col.addView(sc)

    // ---- Appels
    val cc = Ui.card(ctx)
    cc.addView(Ui.text(ctx, "📞 المكالمات", 16f, bold = true))
    cc.addView(CheckBox(ctx).apply {
        text = "سكّت المكالمات (غير الناس لي اخترت)"; setTextColor(Ui.TEXT); isChecked = Focus.blockCalls(ctx)
        setOnCheckedChangeListener { _, v -> Focus.set(ctx) { it.putBoolean("f_calls", v) } }
    })
    cc.addView(CheckBox(ctx).apply {
        text = "خلي لي عيّط جوج مرات فـ 15 دقيقة يدوز (حالة مستعجلة)"; setTextColor(Ui.TEXT); isChecked = Focus.repeatCallers(ctx)
        setOnCheckedChangeListener { _, v -> Focus.set(ctx) { it.putBoolean("f_repeat", v) } }
    })
    cc.addView(CheckBox(ctx).apply {
        text = "ارفض النمر لي ما مسجلينش"; setTextColor(Ui.TEXT); isChecked = Focus.rejectUnknown(ctx)
        setOnCheckedChangeListener { _, v ->
            Focus.set(ctx) { it.putBoolean("f_unknown", v) }
            if (v && Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (!rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    @Suppress("DEPRECATION")
                    try { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 6) } catch (_: Exception) {}
                }
            }
        }
    })
    cc.addView(Ui.text(ctx, "الناس لي يدوزو (من المجموعات):", 13f, Ui.MUTED, true))
    val allowed = Focus.allowedGroups(ctx)
    Groups.list(ctx).forEach { g ->
        cc.addView(CheckBox(ctx).apply {
            text = "${g.name} (${g.members.size})"; setTextColor(Ui.TEXT); isChecked = g.name in allowed
            setOnCheckedChangeListener { _, v ->
                val l = Focus.allowedGroups(ctx)
                StrList.set(ctx, "f_groups", if (v) l + g.name else l - g.name)
            }
        })
    }
    cc.addView(Ui.button(ctx, "⭐ طبّق على الناس المختارين", Ui.ACCENT) {
        if (!has(android.Manifest.permission.WRITE_CONTACTS)) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_CONTACTS), 7)
        } else {
            val n = Focus.starAllowed(ctx)
            toast("⭐ تزادت نجمة لـ $n جهة اتصال — هوما لي غادي يدوزو")
        }
    })
    cc.addView(Ui.text(ctx, "«Ne pas déranger» كيخلي غير الناس لي عندهم ⭐ فجهات الاتصال. هاد الزر كيزيد النجمة للناس ديال المجموعات لي اخترتي.", 11f, Ui.MUTED))
    if (!Focus.dndAllowed(ctx)) {
        cc.addView(Ui.button(ctx, "🔓 عطي الإذن ديال «Ne pas déranger»", Ui.ORANGE) {
            openSafe(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
    }
    col.addView(cc)

    // ---- Internet
    val ic = Ui.card(ctx)
    ic.addView(Ui.text(ctx, "🌐 الإنترنيت", 16f, bold = true))
    ic.addView(CheckBox(ctx).apply {
        text = "قطع الإنترنيت (Wi-Fi و 4G)"; setTextColor(Ui.TEXT); isChecked = Focus.blockNet(ctx)
        setOnCheckedChangeListener { _, v ->
            Focus.set(ctx) { it.putBoolean("f_net", v) }
            if (v) {
                VpnService.prepare(ctx)?.let {
                    @Suppress("DEPRECATION")
                    try { startActivityForResult(it, 8) } catch (_: Exception) {}
                }
            }
            if (Focus.isActive(ctx)) Focus.apply(ctx)
        }
    })
    if (VpnService.prepare(ctx) != null) {
        ic.addView(Ui.button(ctx, "🔓 وافق على VPN (مرة وحدة)", Ui.ORANGE) {
            VpnService.prepare(ctx)?.let {
                @Suppress("DEPRECATION")
                try { startActivityForResult(it, 8) } catch (_: Exception) {}
            }
        })
        ic.addView(Ui.text(ctx, "التطبيق كيستعمل VPN داخلي غير باش يسد الإنترنيت. ما كيصيفط والو لحتى شي بلاصة.", 11f, Ui.MUTED))
    }
    val apps = Focus.allowedApps(ctx)
    ic.addView(Ui.text(ctx, "تطبيقات يبقى عندها الإنترنيت: " +
        (if (apps.isEmpty()) "والو" else apps.joinToString("، ") { appLabel(it) }), 13f, Ui.MUTED))
    ic.addView(Ui.button(ctx, "📱 اختار التطبيقات", Ui.BLUE) { pickAllowedApps() })
    ic.addView(Ui.text(ctx, "مثلا إلا خليتي WhatsApp، غادي يبقى خدام مع الكل (ما يمكنش نختار شخص فواتساب).", 11f, Ui.MUTED))
    col.addView(ic)

    return Ui.scroll(ctx, col)
}

private fun MainActivity.appLabel(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { pkg }

private fun MainActivity.pickAllowedApps() {
    val ctx = this
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val list = packageManager.queryIntentActivities(launch, 0)
        .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        .filter { it.first != packageName }
        .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    val cur = Focus.allowedApps(ctx).toMutableSet()
    val checked = list.map { it.first in cur }.toBooleanArray()
    AlertDialog.Builder(ctx)
        .setTitle("شنو يبقى عندو الإنترنيت؟")
        .setMultiChoiceItems(list.map { it.second }.toTypedArray(), checked) { _, w, v ->
            if (v) cur.add(list[w].first) else cur.remove(list[w].first)
        }
        .setPositiveButton("حفظ") { _, _ ->
            StrList.set(ctx, "f_apps", cur.toList())
            if (Focus.isActive(ctx)) Focus.apply(ctx)
            refresh()
        }
        .setNegativeButton("لغي", null)
        .show()
}
