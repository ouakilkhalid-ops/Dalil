package ma.santojan.app

import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

class WaChat(
    val name: String, val unread: Int, val sender: String, val text: String,
    val time: Long, val group: Boolean, val pkg: String
)

object WaStore {
    private const val KEY = "wa_chats"
    val PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")
    /** Lien direct vers la discussion (gardé en mémoire tant que l'app tourne). */
    val intents = HashMap<String, PendingIntent>()
    var onChange: (() -> Unit)? = null

    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): MutableMap<String, WaChat> {
        val out = LinkedHashMap<String, WaChat>()
        try {
            val o = JSONObject(p(c).getString(KEY, "{}") ?: "{}")
            o.keys().forEach { k ->
                val x = o.getJSONObject(k)
                out[k] = WaChat(k, x.optInt("u"), x.optString("s"), x.optString("t"), x.optLong("ti"),
                    x.optBoolean("g"), x.optString("p", "com.whatsapp"))
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    fun saveAll(c: Context, m: Map<String, WaChat>) {
        val o = JSONObject()
        m.values.forEach {
            o.put(it.name, JSONObject().put("u", it.unread).put("s", it.sender).put("t", it.text)
                .put("ti", it.time).put("g", it.group).put("p", it.pkg))
        }
        p(c).edit().putString(KEY, o.toString()).apply()
    }

    fun put(c: Context, chat: WaChat) {
        val m = all(c); m[chat.name] = chat; saveAll(c, m); notifyUi()
    }

    fun markRead(c: Context, name: String) {
        val m = all(c)
        val x = m[name] ?: return
        if (x.unread == 0) return
        m[name] = WaChat(x.name, 0, x.sender, x.text, x.time, x.group, x.pkg)
        saveAll(c, m); notifyUi()
    }

    fun markAllRead(c: Context) {
        saveAll(c, all(c).mapValues { (_, x) -> WaChat(x.name, 0, x.sender, x.text, x.time, x.group, x.pkg) })
        notifyUi()
    }

    fun remove(c: Context, name: String) {
        val m = all(c); m.remove(name); saveAll(c, m)
        setPinned(c, pinned(c) - name); setHidden(c, hidden(c) - name)
    }

    // Ordre choisi par l'utilisateur + discussions cachées
    fun pinned(c: Context): List<String> = StrList.get(c, "wa_pinned", emptyList())
    fun setPinned(c: Context, l: List<String>) = StrList.set(c, "wa_pinned", l)
    fun hidden(c: Context): List<String> = StrList.get(c, "wa_hidden", emptyList())
    fun setHidden(c: Context, l: List<String>) = StrList.set(c, "wa_hidden", l)

    private fun notifyUi() = Handler(Looper.getMainLooper()).post { onChange?.invoke() }

    fun listenerEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(c, WaListener::class.java).flattenToString())
    }
}

/** Lit les notifications de WhatsApp : nom du groupe, nombre de messages, dernier message. */
class WaListener : NotificationListenerService() {

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName !in WaStore.PKGS) return
            val n = sbn.notification
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            val name = chatName(n.extras) ?: return
            // WhatsApp enlève la notification quand on lit la discussion
            WaStore.markRead(applicationContext, name)
        } catch (_: Exception) {}
    }

    private fun chatName(e: Bundle): String? {
        val conv = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val raw = conv ?: title ?: return null
        var name = raw.replace(Regex("""\s*\(\d+[^)]*\)\s*$"""), "").trim()
        if (conv == null && name.contains(": ")) name = name.substringBefore(": ").trim()
        if (name.isBlank() || name.equals("WhatsApp", true)) return null
        return name
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in WaStore.PKGS) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val e = n.extras ?: return
        val name = chatName(e) ?: return

        val conv = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val isGroup = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            (conv == null && title.contains(": "))

        var count = 0
        var sender = ""
        var text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        var time = sbn.postTime
        @Suppress("DEPRECATION")
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            count = msgs.size
            (msgs.last() as? Bundle)?.let { b ->
                text = b.getCharSequence("text")?.toString() ?: text
                sender = b.getCharSequence("sender")?.toString() ?: ""
                val t = b.getLong("time"); if (t > 0) time = t
            }
        }
        if (sender.isBlank() && conv == null && title.contains(": ")) sender = title.substringAfter(": ").trim()
        if (sender == name) sender = ""
        val num = n.number
        if (num > count) count = num
        if (count == 0) count = 1

        n.contentIntent?.let { WaStore.intents[name] = it }
        WaStore.put(applicationContext, WaChat(name, count, sender, text, time, isGroup, sbn.packageName))
    }
}

// ------------------------------------------------------------------ Écran

private var waFilter = 0      // 0 = tout, 1 = groupes, 2 = personnes
private var waShowHidden = false

fun MainActivity.openWaChat(c: WaChat) {
    val pi = WaStore.intents[c.name]
    var ok = false
    if (pi != null) {
        ok = try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val opts = android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            true
        } catch (e: Exception) { false }
    }
    if (!ok) {
        val i = packageManager.getLaunchIntentForPackage(c.pkg)
        if (i != null) openSafe(i) else toast("WhatsApp ما كاينش")
        toast("حليت WhatsApp — قلّب على «${c.name}»")
    }
    WaStore.markRead(this, c.name)
}

private fun MainActivity.chatMenu(c: WaChat) {
    val ctx = this
    val pinned = WaStore.pinned(ctx)
    val hidden = WaStore.hidden(ctx)
    val isPinned = c.name in pinned
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    if (isPinned) {
        val i = pinned.indexOf(c.name)
        if (i > 0) opts.add("⬆️ طلّعو" to {
            WaStore.setPinned(ctx, pinned.toMutableList().apply { removeAt(i); add(i - 1, c.name) })
        })
        if (i < pinned.size - 1) opts.add("⬇️ هبّطو" to {
            WaStore.setPinned(ctx, pinned.toMutableList().apply { removeAt(i); add(i + 1, c.name) })
        })
        opts.add("⏫ حطّو الأول" to { WaStore.setPinned(ctx, listOf(c.name) + (pinned - c.name)) })
        opts.add("📍 حيّد من المهمين" to { WaStore.setPinned(ctx, pinned - c.name) })
    } else {
        opts.add("📌 زيدو للمهمين (الفوق)" to { WaStore.setPinned(ctx, pinned + c.name) })
    }
    if (c.name in hidden) opts.add("👁️ بيّنو" to { WaStore.setHidden(ctx, hidden - c.name) })
    else opts.add("🙈 خبّيه" to { WaStore.setHidden(ctx, hidden + c.name); WaStore.setPinned(ctx, pinned - c.name) })
    opts.add("✔️ ردّو مقري" to { WaStore.markRead(ctx, c.name) })
    opts.add("🗑️ مسحو من اللائحة" to { WaStore.remove(ctx, c.name) })

    AlertDialog.Builder(ctx)
        .setTitle(c.name)
        .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second(); refresh() }
        .show()
}

private fun MainActivity.chatRow(c: WaChat, pinned: Boolean): View {
    val ctx = this
    val card = Ui.card(ctx)
    val r = Ui.row(ctx)
    val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val icon = (if (pinned) "📌 " else "") + (if (c.group) "👥 " else "👤 ")
    info.addView(Ui.text(ctx, icon + c.name, 16f, bold = true))
    val last = (if (c.sender.isNotBlank()) "${c.sender}: " else "") + c.text
    info.addView(Ui.text(ctx, last.take(90), 13f, Ui.MUTED).apply { maxLines = 2 })
    info.addView(Ui.text(ctx, Fmt.ago(c.time), 11f, Ui.MUTED))
    r.addView(info, Ui.lp(ctx, 1f, 0))
    if (c.unread > 0) {
        r.addView(Ui.text(ctx, if (c.unread > 99) "99+" else "${c.unread}", 14f, android.graphics.Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Ui.ACCENT, Ui.dp(ctx, 14).toFloat())
            minWidth = Ui.dp(ctx, 30)
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 3), Ui.dp(ctx, 8), Ui.dp(ctx, 3))
        })
    }
    card.addView(r)
    card.setOnClickListener { openWaChat(c) }
    card.setOnLongClickListener { chatMenu(c); true }
    return card
}

fun MainActivity.whatsappScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "💬 واتساب"))
    WaStore.onChange = { if (currentTab() == TAB_WA || currentTab() == TAB_HOME) refresh() }

    if (!WaStore.listenerEnabled(ctx)) {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, "باش نشوف المجموعات وشحال من ميساج، خاص تعطي لـ Dalil «Accès aux notifications».", 14f))
        c.addView(Ui.button(ctx, "1️⃣ فعّل «Accès aux notifications»", Ui.ACCENT) {
            openSafe(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        c.addView(Ui.gap(ctx, 6))
        c.addView(Ui.text(ctx,
            "إلا كان الزر مسدود (رمادي): سير لـ إعدادات التطبيق → ⋮ (الفوق) → «Autoriser les paramètres restreints»، ومن بعد رجع فعّل.",
            12f, Ui.MUTED))
        c.addView(Ui.button(ctx, "2️⃣ إعدادات التطبيق", Ui.BLUE) {
            openSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(c)
        return Ui.scroll(ctx, col)
    }

    val all = WaStore.all(ctx).values.toList()
    val pinned = WaStore.pinned(ctx)
    val hidden = WaStore.hidden(ctx)
    val visible = all.filter { it.name !in hidden }
    val totalUnread = visible.sumOf { it.unread }
    val chatsUnread = visible.count { it.unread > 0 }

    val head = Ui.card(ctx)
    head.addView(Ui.text(ctx, if (totalUnread == 0) "✅ كلشي مقري" else "📩 $totalUnread ميساج ما مقريش فـ $chatsUnread", 17f, Ui.ACCENT, true))
    head.addView(Ui.text(ctx, "ورك على شي وحدة باش تحلها فواتساب. ضغط مطوّل باش ترتّب، تثبّت ولا تخبّي.", 12f, Ui.MUTED))
    val hr = Ui.row(ctx)
    hr.addView(Ui.button(ctx, "✔️ كلشي مقري", Ui.CHIP) { WaStore.markAllRead(ctx); refresh() }, Ui.lp(ctx, 1f))
    hr.addView(Ui.button(ctx, "💬 حل واتساب", Ui.ACCENT) {
        packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { openSafe(it) }
    }, Ui.lp(ctx, 1f))
    head.addView(hr)
    col.addView(head)

    // Filtres
    val fr = Ui.row(ctx)
    listOf("الكل", "👥 المجموعات", "👤 الأشخاص").forEachIndexed { i, t ->
        fr.addView(Ui.button(ctx, t, if (waFilter == i) Ui.ACCENT else Ui.CHIP) { waFilter = i; refresh() }, Ui.lp(ctx))
    }
    col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(fr) })

    fun pass(c: WaChat) = when (waFilter) { 1 -> c.group; 2 -> !c.group; else -> true }

    val pinnedChats = pinned.mapNotNull { n -> visible.find { it.name == n } }.filter { pass(it) }
    val others = visible.filter { it.name !in pinned && pass(it) }
        .sortedWith(compareByDescending<WaChat> { it.unread > 0 }.thenByDescending { it.time })

    if (pinnedChats.isEmpty() && others.isEmpty()) {
        col.addView(Ui.text(ctx, "مازال ما جا حتى ميساج من بعد ما فعّلتي. ملي يجي شي ميساج فواتساب غادي يبان هنا.", 14f, Ui.MUTED))
    }
    if (pinnedChats.isNotEmpty()) {
        col.addView(Ui.text(ctx, "📌 المهمين", 14f, Ui.MUTED, true))
        pinnedChats.forEach { col.addView(chatRow(it, true)) }
    }
    if (others.isNotEmpty()) {
        if (pinnedChats.isNotEmpty()) col.addView(Ui.text(ctx, "الباقي", 14f, Ui.MUTED, true))
        others.forEach { col.addView(chatRow(it, false)) }
    }

    val hiddenChats = all.filter { it.name in hidden }
    if (hiddenChats.isNotEmpty()) {
        col.addView(Ui.button(ctx, (if (waShowHidden) "▾ " else "▸ ") + "🙈 المخبيين (${hiddenChats.size})", Ui.CHIP) {
            waShowHidden = !waShowHidden; refresh()
        })
        if (waShowHidden) hiddenChats.forEach { col.addView(chatRow(it, false)) }
    }

    col.addView(Ui.gap(ctx, 8))
    col.addView(Ui.text(ctx,
        "ملاحظة: المجموعات لي «Muet» فواتساب ما كيصيفطوش notification، وما غاديش يبانو هنا.",
        11f, Ui.MUTED))
    return Ui.scroll(ctx, col)
}
