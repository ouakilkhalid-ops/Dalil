package ma.santojan.app

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Enregistrement des réunions (micro) en arrière-plan. */
class RecService : Service() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        var file: File? = null
        var startedAt = 0L
        private var rec: MediaRecorder? = null
        val recording get() = rec != null

        fun dir(c: Context) = File(c.filesDir, "meetings").apply { mkdirs() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRec(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (rec != null) return START_NOT_STICKY
        Notif.channels(this)
        val stopPi = PendingIntent.getService(this, 11,
            Intent(this, RecService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 12,
            Intent(this, MainActivity::class.java).putExtra("tab", TAB_MEET), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Notif.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🎙️ كنسجّل الاجتماع")
            .setContentText("ورك هنا باش تشوف")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "⏹️ وقّف", stopPi).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(10, n)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        val title = (intent?.getStringExtra("title") ?: "").replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val f = File(dir(this), (if (title.isNotEmpty()) "${stamp}_$title" else stamp) + ".m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            f.delete(); rec = null
            Notif.show(this, 13, "⚠️ ما قدرتش نسجّل", e.message ?: "")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopRec() {
        try { rec?.stop() } catch (_: Exception) { file?.delete() }
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }

    override fun onDestroy() {
        stopRec(); super.onDestroy()
    }
}

private var player: MediaPlayer? = null
private var playing: String? = null

private fun reportFile(f: File) = File(f.parentFile, f.nameWithoutExtension + ".txt")

private fun durationOf(f: File): Long = try {
    val m = MediaMetadataRetriever()
    m.setDataSource(f.absolutePath)
    val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    m.release(); d / 1000
} catch (e: Exception) { 0L }

fun MainActivity.makeReport(f: File, prompt: String = CallAi.MEETING_PROMPT) {
    val ctx = this
    if (CallAi.key(ctx).isBlank()) { toast("حط المفتاح ديال Gemini فـ «الإعدادات» الأول"); show(TAB_MORE); return }
    val wait = AlertDialog.Builder(ctx).setMessage("🤖 كنسمع للتسجيل ونكتب التقرير...\n(اجتماع طويل يقدر ياخد شي دقايق)").setCancelable(false).show()
    Thread {
        val res: Result<String> = try {
            Result.success(CallAi.summarize(ctx, f.readBytes(), "audio/mp4", prompt))
        } catch (e: Exception) { Result.failure(e) }
        runOnUiThread {
            try { wait.dismiss() } catch (_: Exception) {}
            res.onSuccess { t ->
                reportFile(f).writeText(t)
                showSummary(f.nameWithoutExtension, t)
                refresh()
            }.onFailure { e ->
                AlertDialog.Builder(ctx).setTitle("ما قدرتش نكتب التقرير").setMessage(e.message ?: "خطأ").setPositiveButton("OK", null).show()
            }
        }
    }.start()
}

fun MainActivity.meetingsScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "🎙️ الاجتماعات"))
    col.addView(Ui.text(ctx, "سجّل اجتماع ولا أي هضرة، ومن بعد الذكاء الاصطناعي يعطيك تقرير: الملخص، القرارات، المهام والمواعيد.", 13f, Ui.MUTED))
    col.addView(Ui.gap(ctx, 8))

    val card = Ui.card(ctx)
    if (RecService.recording) {
        val timer = Ui.text(ctx, "🔴 00:00", 32f, Ui.RED, true).apply { gravity = android.view.Gravity.CENTER }
        card.addView(timer)
        card.addView(Ui.text(ctx, RecService.file?.nameWithoutExtension ?: "", 12f, Ui.MUTED).apply { gravity = android.view.Gravity.CENTER })
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                timer.text = "🔴 " + Fmt.chrono((SystemClock.elapsedRealtime() - RecService.startedAt) / 1000)
                if (RecService.recording && timer.isAttachedToWindow) h.postDelayed(this, 1000)
            }
        }
        timer.post(tick)
        card.addView(Ui.button(ctx, "⏹️ وقّف التسجيل", Ui.RED) {
            startService(Intent(ctx, RecService::class.java).setAction(RecService.STOP))
            h.postDelayed({ refresh() }, 500)
        })
        card.addView(Ui.text(ctx, "تقدر تسد التطبيق، التسجيل كيبقى خدام (كاين إشعار فوق).", 11f, Ui.MUTED))
    } else {
        val name = Ui.input(ctx, "سمية الاجتماع (اختياري) — مثلا: اجتماع الشركة")
        card.addView(name)
        card.addView(Ui.button(ctx, "🎙️ بدا التسجيل", Ui.ACCENT) {
            if (!has(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9)
            } else {
                startForegroundService(Intent(ctx, RecService::class.java).setAction(RecService.START)
                    .putExtra("title", name.text.toString()))
                Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 600)
            }
        })
    }
    card.addView(Ui.button(ctx, "📂 تقرير على تسجيل من التيليفون", Ui.BLUE) { pendingMeetingPick = true; pickAudio() })
    col.addView(card)

    val files = RecService.dir(ctx).listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    val current = RecService.file
    col.addView(Ui.text(ctx, if (files.isEmpty()) "مازال ما كاين حتى تسجيل." else "التسجيلات:", 15f, Ui.MUTED, true))
    files.filter { !(RecService.recording && it == current) }.forEach { f ->
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, f.nameWithoutExtension.replace('_', ' '), 15f, bold = true))
        val dur = durationOf(f)
        c.addView(Ui.text(ctx, "${Fmt.date(f.lastModified())}  •  ${Fmt.dur(dur)}  •  ${f.length() / 1024} ك.ب", 12f, Ui.MUTED))
        val rep = reportFile(f)
        val r = Ui.row(ctx)
        if (rep.exists()) {
            c.addView(Ui.text(ctx, "✅ عندو تقرير", 12f, Ui.ACCENT))
            r.addView(Ui.button(ctx, "📋 التقرير", Ui.ACCENT) { showSummary(f.nameWithoutExtension, rep.readText()) }, Ui.lp(ctx, 1f))
        } else {
            r.addView(Ui.button(ctx, "📝 دير تقرير", Ui.ACCENT) { makeReport(f) }, Ui.lp(ctx, 1f))
        }
        val isPlaying = playing == f.absolutePath
        r.addView(Ui.button(ctx, if (isPlaying) "⏸️" else "▶️", Ui.BLUE) {
            try {
                player?.release(); player = null
                if (isPlaying) { playing = null } else {
                    player = MediaPlayer().apply {
                        setDataSource(f.absolutePath); prepare(); start()
                        setOnCompletionListener { playing = null; refresh() }
                    }
                    playing = f.absolutePath
                }
            } catch (e: Exception) { toast("ما قدرتش نشغّل") }
            refresh()
        }, Ui.lp(ctx))
        r.addView(Ui.button(ctx, "🗑️", Ui.RED) {
            confirm("نمسح هاد التسجيل؟") {
                if (playing == f.absolutePath) { player?.release(); player = null; playing = null }
                f.delete(); rep.delete(); refresh()
            }
        }, Ui.lp(ctx))
        c.addView(r)
        if (rep.exists()) {
            c.setOnLongClickListener { confirm("نعاود نكتب التقرير؟") { makeReport(f) }; true }
        }
        col.addView(c)
    }
    return Ui.scroll(ctx, col)
}

// ------------------------------------------------------------------ Rapport d'un appel (appui long)

var pendingMeetingPick = false

fun MainActivity.callReport(number: String, who: String, date: Long, dur: Long) {
    val ctx = this
    if (dur <= 0) { toast("هاد المكالمة ما تهضرتوش فيها"); return }
    if (!has(CallAi.audioPermission())) {
        toast("عطي الإذن باش نلقى التسجيلات")
        requestPermissions(arrayOf(CallAi.audioPermission()), 5)
        return
    }
    val end = date + dur * 1000
    val from = date - 60_000
    val to = end + 300_000
    val candidates = (CallAi.findAround(ctx, from, to) +
        CallAi.findRecordings(ctx, 400).filter { it.date in from..to })
        .filter { it.size > 2000 && !it.uri.toString().contains("/meetings/") }
    val digits = number.filter { it.isDigit() }.takeLast(9)
    // priorité : nom du fichier qui contient le numéro ou le nom, sinon le plus proche de la fin de l'appel
    val rec = candidates.firstOrNull { (digits.isNotEmpty() && it.name.filter { d -> d.isDigit() }.contains(digits)) || it.name.contains(who, true) }
        ?: candidates.minByOrNull { kotlin.math.abs(it.date - end) }
    val title = "مكالمة مع $who — ${Fmt.date(date)}"
    val old = CallAi.summaries(ctx).find { it.title == title }
    AlertDialog.Builder(ctx)
        .setTitle("📞 $who")
        .setMessage(
            when {
                old != null -> "عندك تقرير على هاد المكالمة. بغيتي تشوفو؟"
                rec != null -> "بغيتي تقرير على هاد المكالمة؟ (${Fmt.dur(dur)})"
                else -> "ما لقيتش التسجيل ديال هاد المكالمة.\n\n• إلا كان التسجيل كاين، ورك «📁 الدوسي» واختار الدوسي فين كيتسجلو المكالمات (مرة وحدة).\n• ولا ورك «📂» واختار التسجيل بيدك."
            }
        )
        .setPositiveButton(
            when { old != null -> "📋 شوفو"; rec != null -> "📝 إيه، دير تقرير"; else -> "📂 اختار التسجيل بيدي" }
        ) { _, _ ->
            when {
                old != null -> showSummary(old.title, old.text)
                rec != null -> summarizeUri(rec.uri, title, rec.mime)
                else -> pickAudio()
            }
        }
        .setNegativeButton("لا", null)
        .apply { if (old == null && rec == null) setNeutralButton("📁 الدوسي") { _, _ -> pickRecFolder() } }
        .apply { if (old != null && rec != null) setNeutralButton("🔄 عاود") { _, _ -> summarizeUri(rec.uri, title, rec.mime) } }
        .show()
}
