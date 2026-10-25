package ma.santojan.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Résumé des appels par IA (Google Gemini) à partir des enregistrements Xiaomi. */
object CallAi {
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)
    const val DEFAULT_MODEL = "gemini-flash-latest"
    const val FALLBACK_MODEL = "gemini-3.8-flash"

    fun key(c: Context) = p(c).getString("ai_key", "") ?: ""
    fun setKey(c: Context, k: String) = p(c).edit().putString("ai_key", k.trim()).apply()
    fun model(c: Context) = (p(c).getString("ai_model", "") ?: "").ifBlank { DEFAULT_MODEL }
    fun setModel(c: Context, m: String) = p(c).edit().putString("ai_model", m.trim()).apply()

    const val PROMPT = """هادا تسجيل ديال مكالمة تيليفونية. الهضرة يمكن تكون بالدارجة المغربية، العربية، الفرنسية، الإسبانية، ولا بالأمازيغية (الريفية أو قريبة منها).
كتب ملخص قصير بالدارجة المغربية بالحروف العربية، بهاد الشكل بالضبط:
📝 الملخص: (جوج ولا ثلاث جمل)
📌 النقط المهمة: (لائحة قصيرة: اتفاقات، تواريخ، مبالغ، أرقام، أسماء)
✅ شنو خاصك دير: (المهام لي خاص تديرها، ولا «والو»)
إلا كان شي جزء ما مفهومش (مثلا بالأمازيغية)، قول هادشي بصراحة وما تخترعش."""

    const val MEETING_PROMPT = """هادا تسجيل ديال اجتماع ولا هضرة بين بزاف ديال الناس. الهضرة يمكن تكون بالدارجة المغربية، العربية، الفرنسية، الإسبانية، ولا بالأمازيغية.
كتب تقرير منظم بالعربية (فصحى بسيطة) بهاد الشكل:
📋 تقرير الاجتماع
🗓️ الموضوع: (جملة وحدة)
👥 المتدخلون: (إلا بانو أسماء ولا صفات، وإلا «غير محدد»)
📝 ملخص النقاش: (فقرة قصيرة)
📌 النقط الرئيسية: (لائحة)
✅ القرارات المتخذة: (لائحة، ولا «لا شيء»)
📅 المهام والمواعيد: (شكون غادي يدير شنو وفوقاش، ولا «لا شيء»)
🔢 أرقام ومبالغ مهمة: (إلا كانو)
إلا كان شي جزء ما مفهومش، قول هادشي بصراحة وما تخترعش."""

    private fun callGemini(k: String, model: String, body: String): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 300000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", k)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return code to txt
        } finally {
            conn.disconnect()
        }
    }

    /** Envoie un gros fichier à Gemini (Files API) et renvoie son URI. */
    private fun upload(k: String, audio: ByteArray, mime: String): String {
        val start = URL("https://generativelanguage.googleapis.com/upload/v1beta/files").openConnection() as HttpURLConnection
        val uploadUrl: String
        try {
            start.requestMethod = "POST"
            start.doOutput = true
            start.setRequestProperty("x-goog-api-key", k)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", audio.size.toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            start.setRequestProperty("Content-Type", "application/json")
            start.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", "audio")).toString().toByteArray()) }
            if (start.responseCode !in 200..299) throw Exception("ما قدرتش نصيفط الملف (${start.responseCode})")
            uploadUrl = start.getHeaderField("X-Goog-Upload-URL") ?: throw Exception("ما قدرتش نصيفط الملف")
        } finally { start.disconnect() }

        val up = URL(uploadUrl).openConnection() as HttpURLConnection
        val info: JSONObject
        try {
            up.requestMethod = "POST"
            up.doOutput = true
            up.connectTimeout = 30000
            up.readTimeout = 300000
            up.setFixedLengthStreamingMode(audio.size)
            up.setRequestProperty("X-Goog-Upload-Offset", "0")
            up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            up.outputStream.use { it.write(audio) }
            val txt = (if (up.responseCode in 200..299) up.inputStream else up.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (up.responseCode !in 200..299) throw Exception("ما قدرتش نصيفط الملف: $txt")
            info = JSONObject(txt).getJSONObject("file")
        } finally { up.disconnect() }

        // On attend que le fichier soit prêt
        var state = info.optString("state", "ACTIVE")
        val name = info.getString("name")
        var tries = 0
        while (state == "PROCESSING" && tries < 30) {
            Thread.sleep(2000); tries++
            val g = URL("https://generativelanguage.googleapis.com/v1beta/$name").openConnection() as HttpURLConnection
            try {
                g.setRequestProperty("x-goog-api-key", k)
                state = JSONObject(g.inputStream.bufferedReader().use { it.readText() }).optString("state", "ACTIVE")
            } finally { g.disconnect() }
        }
        if (state == "FAILED") throw Exception("Gemini ما قدرش يقرا الملف")
        return info.getString("uri")
    }

    /** Appelle Gemini. Renvoie le texte ou lance une exception avec un message lisible. */
    fun summarize(c: Context, audio: ByteArray, mime: String, prompt: String = PROMPT): String {
        val k = key(c)
        if (k.isBlank()) throw Exception("خاصك تحط المفتاح ديال Gemini فـ «الإعدادات».")
        val media = if (audio.size > 14 * 1024 * 1024) {
            JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", upload(k, audio, mime)))
        } else {
            JSONObject().put("inline_data", JSONObject().put("mime_type", mime)
                .put("data", Base64.encodeToString(audio, Base64.NO_WRAP)))
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(media)
        ))).toString()

        var lastErr = ""
        for (m in listOf(model(c), FALLBACK_MODEL).distinct()) {
            val (code, txt) = callGemini(k, m, body)
            if (code in 200..299) {
                val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                return sb.toString().replace("**", "").replace(Regex("(?m)^#+\\s*"), "").trim().ifBlank { "ما تفهمات حتى حاجة فالتسجيل." }
            }
            lastErr = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (e: Exception) { "خطأ $code" }
            if (code == 400 && lastErr.contains("API key", true)) throw Exception("المفتاح ماشي صحيح: $lastErr")
            if (code != 404) break
        }
        throw Exception(lastErr)
    }

    /** Question texte à Gemini. [search] = chercher sur Google (infos du jour). Renvoie (texte, sources). */
    fun ask(c: Context, prompt: String, search: Boolean = false): Pair<String, List<Pair<String, String>>> {
        val k = key(c)
        if (k.isBlank()) throw Exception("خاصك تحط المفتاح ديال Gemini فـ «الإعدادات».")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        var lastErr = ""
        for (m in listOf(model(c), FALLBACK_MODEL).distinct()) {
            val (code, txt) = callGemini(k, m, body.toString())
            if (code in 200..299) {
                val cand = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                val parts = cand.getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                val src = ArrayList<Pair<String, String>>()
                cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { ch ->
                    for (i in 0 until ch.length()) ch.getJSONObject(i).optJSONObject("web")?.let { w ->
                        src.add(w.optString("title") to w.optString("uri"))
                    }
                }
                val clean = sb.toString().replace("**", "").replace(Regex("(?m)^#+\\s*"), "").trim()
                return clean to src.distinctBy { it.first }
            }
            lastErr = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (e: Exception) { "خطأ $code" }
            if (code != 404) break
        }
        throw Exception(lastErr)
    }

    fun mimeFor(name: String, given: String?): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".mp3") -> "audio/mp3"
            n.endsWith(".m4a") || n.endsWith(".mp4") -> "audio/mp4"
            n.endsWith(".aac") -> "audio/aac"
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".ogg") || n.endsWith(".opus") -> "audio/ogg"
            n.endsWith(".flac") -> "audio/flac"
            n.endsWith(".amr") -> "audio/amr"
            !given.isNullOrBlank() -> given
            else -> "audio/mp4"
        }
    }

    // ---- Résumés enregistrés
    class Summary(val id: Long, val title: String, val text: String, val date: Long)

    fun summaries(c: Context): List<Summary> {
        val a = Store.arr(c, "summaries")
        return (0 until a.length()).mapNotNull {
            try {
                val o = a.getJSONObject(it)
                Summary(o.getLong("id"), o.getString("title"), o.getString("text"), o.getLong("date"))
            } catch (e: Exception) { null }
        }
    }

    fun saveSummary(c: Context, title: String, text: String) {
        val now = System.currentTimeMillis()
        val a = JSONArray()
        (listOf(Summary(now, title, text, now)) + summaries(c)).take(100).forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text).put("date", it.date))
        }
        Store.saveArr(c, "summaries", a)
    }

    fun removeSummary(c: Context, id: Long) {
        val a = JSONArray()
        summaries(c).filter { it.id != id }.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text).put("date", it.date))
        }
        Store.saveArr(c, "summaries", a)
    }

    // ---- Enregistrements d'appels trouvés sur le téléphone
    class Rec(val uri: Uri, val name: String, val date: Long, val size: Long, val mime: String?)

    fun findRecordings(c: Context, limit: Int = 30): List<Rec> {
        val out = ArrayList<Rec>()
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA
        )
        try {
            c.contentResolver.query(col, proj, null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < limit) {
                    val path = (cur.getString(5) ?: "").lowercase()
                    val name = cur.getString(1) ?: ""
                    val isCall = path.contains("call") || path.contains("appel") || path.contains("rec_call") ||
                        name.lowercase().contains("call")
                    if (!isCall) continue
                    out.add(Rec(ContentUris.withAppendedId(col, cur.getLong(0)), name, cur.getLong(2) * 1000, cur.getLong(3), cur.getString(4)))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Tous les fichiers audio créés/modifiés entre [from] et [to] (n'importe quel dossier). */
    fun findAround(c: Context, from: Long, to: Long): List<Rec> {
        val out = ArrayList<Rec>()
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE)
        val sel = "(" + MediaStore.Audio.Media.DATE_MODIFIED + " BETWEEN ? AND ?) OR (" +
            MediaStore.Audio.Media.DATE_ADDED + " BETWEEN ? AND ?)"
        val a = arrayOf((from / 1000).toString(), (to / 1000).toString(), (from / 1000).toString(), (to / 1000).toString())
        try {
            c.contentResolver.query(col, proj, sel, a, null)?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(Rec(ContentUris.withAppendedId(col, cur.getLong(0)), cur.getString(1) ?: "audio",
                        cur.getLong(2) * 1000, cur.getLong(3), cur.getString(4)))
                }
            }
        } catch (_: Exception) {}
        // Dossier choisi par l'utilisateur (marche même si le dossier est caché)
        folder(c)?.let { out.addAll(findInFolder(c, it, from, to)) }
        return out
    }

    fun folder(c: Context): Uri? = p(c).getString("rec_folder", null)?.let { Uri.parse(it) }
    fun setFolder(c: Context, u: Uri) = p(c).edit().putString("rec_folder", u.toString()).apply()

    private fun findInFolder(c: Context, tree: Uri, from: Long, to: Long): List<Rec> {
        val out = ArrayList<Rec>()
        fun walk(docId: String, depth: Int) {
            val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                c.contentResolver.query(kids, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0); val mime = cur.getString(3) ?: ""
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 3) walk(id, depth + 1)
                        } else {
                            val t = cur.getLong(2)
                            if (t in from..to) out.add(Rec(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                cur.getString(1) ?: "audio", t, cur.getLong(4), mime))
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        try { walk(android.provider.DocumentsContract.getTreeDocumentId(tree), 0) } catch (_: Exception) {}
        return out
    }

    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
}

private const val MAX_BYTES = 150 * 1024 * 1024

fun MainActivity.summarizeUri(uri: Uri, name: String, mime: String?, prompt: String = CallAi.PROMPT) {
    val ctx = this
    if (CallAi.key(ctx).isBlank()) {
        toast("حط المفتاح ديال Gemini فـ «الإعدادات» الأول")
        show(TAB_MORE)
        return
    }
    val wait = AlertDialog.Builder(ctx).setMessage("🤖 كنسمع للمكالمة ونلخصها... (تقدر تاخد دقيقة)").setCancelable(false).show()
    Thread {
        val result: Result<String> = try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("ما قدرتش نقرا الملف")
            if (bytes.size > MAX_BYTES) throw Exception("التسجيل كبير بزاف (أكثر من 150 ميغا).")
            Result.success(CallAi.summarize(ctx, bytes, CallAi.mimeFor(name, mime), prompt))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            try { wait.dismiss() } catch (_: Exception) {}
            result.onSuccess { text ->
                CallAi.saveSummary(ctx, name, text)
                showSummary(name, text)
                refresh()
            }.onFailure { e ->
                AlertDialog.Builder(ctx).setTitle("ما قدرتش نلخص").setMessage(e.message ?: "خطأ").setPositiveButton("OK", null).show()
            }
        }
    }.start()
}

fun MainActivity.showSummary(title: String, text: String) {
    val tv = Ui.text(this, text, 15f, android.graphics.Color.BLACK).apply {
        setPadding(Ui.dp(this@showSummary, 20), Ui.dp(this@showSummary, 10), Ui.dp(this@showSummary, 20), 0)
        setTextIsSelectable(true)
    }
    AlertDialog.Builder(this)
        .setTitle("🤖 $title")
        .setView(ScrollView(this).apply { addView(tv) })
        .setPositiveButton("مزيان", null)
        .setNeutralButton("⏰ فكّرني") { _, _ ->
            Reminders.add(this, "من المكالمة: " + text.lines().firstOrNull { it.contains("✅") }.orEmpty().ifBlank { title }, 60)
            toast("غادي نفكرك من ساعة")
        }
        .setNegativeButton("📤 بارطاجي") { _, _ ->
            openSafe(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "بارطاجي"))
        }
        .show()
}

fun MainActivity.pickAudio() {
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 4)
    } catch (e: Exception) {
        toast("ما قدرتش نحل الملفات")
    }
}

fun MainActivity.onAudioPicked(uri: Uri?) {
    if (uri == null) return
    var name = "تسجيل"
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
    } catch (_: Exception) {}
    if (pendingMeetingPick) {
        pendingMeetingPick = false
        summarizeUri(uri, name, contentResolver.getType(uri), CallAi.MEETING_PROMPT)
    } else {
        summarizeUri(uri, name, contentResolver.getType(uri))
    }
}

/** Carte « Résumé IA » affichée dans l'écran des appels. */
fun MainActivity.aiCallsCard(): View {
    val ctx = this
    val card = Ui.card(ctx)
    card.addView(Ui.text(ctx, "🤖 ملخص المكالمات بالذكاء الاصطناعي", 16f, bold = true))

    if (CallAi.key(ctx).isBlank()) {
        card.addView(Ui.text(ctx, "باش يخدم، خاصك مفتاح Gemini (فابور). حطو فـ «الإعدادات».", 13f, Ui.MUTED))
        card.addView(Ui.button(ctx, "سير لـ «الإعدادات»", Ui.BLUE) { show(TAB_MORE) })
        return card
    }

    card.addView(Ui.text(ctx, "خاص يكون التسجيل ديال المكالمات مفعّل فالتيليفون (Téléphone → Paramètres → Enregistrement des appels).", 12f, Ui.MUTED))
    card.addView(Ui.button(ctx, "📂 اختار تسجيل", Ui.BLUE) { pickAudio() })
    card.addView(Ui.button(ctx, if (CallAi.folder(ctx) == null) "📁 اختار الدوسي ديال التسجيلات" else "📁 الدوسي: ✅ (بدّلو)", Ui.CHIP) { pickRecFolder() })

    if (!has(CallAi.audioPermission())) {
        card.addView(Ui.button(ctx, "🔓 خليني نلقى التسجيلات بوحدي", Ui.CHIP) {
            requestPermissions(arrayOf(CallAi.audioPermission()), 5)
        })
    } else {
        val recs = CallAi.findRecordings(ctx).take(8)
        if (recs.isEmpty()) {
            card.addView(Ui.text(ctx, "ما لقيت حتى تسجيل ديال مكالمة. استعمل «اختار تسجيل».", 12f, Ui.MUTED))
        } else {
            card.addView(Ui.text(ctx, "آخر التسجيلات:", 13f, Ui.MUTED, true))
            recs.forEach { r ->
                val row = Ui.row(ctx)
                val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                info.addView(Ui.text(ctx, r.name, 13f))
                info.addView(Ui.text(ctx, "${Fmt.date(r.date)}  •  ${r.size / 1024} ك.ب", 11f, Ui.MUTED))
                row.addView(info, Ui.lp(ctx, 1f, 0))
                row.addView(Ui.button(ctx, "لخّص", Ui.ACCENT) { summarizeUri(r.uri, r.name, r.mime) })
                card.addView(row)
            }
        }
    }

    val saved = CallAi.summaries(ctx)
    if (saved.isNotEmpty()) {
        card.addView(Ui.gap(ctx, 8))
        card.addView(Ui.text(ctx, "الملخصات لي تحفظو:", 13f, Ui.MUTED, true))
        saved.take(10).forEach { s ->
            val row = Ui.row(ctx)
            val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            info.addView(Ui.text(ctx, s.title, 13f, bold = true))
            info.addView(Ui.text(ctx, s.text.lines().map { it.replace("📝", "").replace("الملخص:", "").trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(80), 12f, Ui.MUTED))
            info.addView(Ui.text(ctx, Fmt.date(s.date), 11f, Ui.MUTED))
            row.addView(info, Ui.lp(ctx, 1f, 0))
            row.setOnClickListener { showSummary(s.title, s.text) }
            row.setOnLongClickListener {
                confirm("نمسح هاد الملخص؟") { CallAi.removeSummary(ctx, s.id); refresh() }
                true
            }
            card.addView(row)
        }
    }
    return card
}

/** Réglages de l'IA dans « المزيد ». */
fun MainActivity.aiSettingsCard(): View {
    val ctx = this
    val card = Ui.card(ctx)
    card.addView(Ui.text(ctx, "🤖 الذكاء الاصطناعي (Gemini)", 16f, bold = true))
    card.addView(Ui.text(ctx,
        "1. حل aistudio.google.com/apikey ودخل بحساب Gmail ديالك\n2. ورك على «Create API key» وكوبي المفتاح\n3. لصقو هنا وورك «حفظ»\n" +
        "ملاحظة: التسجيلات كيتصيفطو لـ Google باش يتلخصو.",
        13f, Ui.MUTED))
    card.addView(Ui.button(ctx, "🔗 حل صفحة المفتاح", Ui.BLUE) {
        openSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
    })
    val k = Ui.input(ctx, "المفتاح (كيبدا بـ AIza...)").apply { setText(CallAi.key(ctx)) }
    card.addView(k)
    val m = Ui.input(ctx, "الموديل").apply { setText(CallAi.model(ctx)) }
    card.addView(Ui.text(ctx, "الموديل (خليه كيف هو):", 12f, Ui.MUTED))
    card.addView(m)
    card.addView(Ui.button(ctx, "💾 حفظ", Ui.ACCENT) {
        CallAi.setKey(ctx, k.text.toString())
        CallAi.setModel(ctx, m.text.toString())
        toast(if (CallAi.key(ctx).isBlank()) "تمسح المفتاح" else "تحفظ ✅")
        refresh()
    })
    return card
}

fun MainActivity.pickRecFolder() {
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 10)
        toast("دخل للدوسي فين كيتسجلو المكالمات وورك «Utiliser ce dossier»")
    } catch (e: Exception) { toast("ما قدرتش نحل الملفات") }
}

fun MainActivity.onRecFolderPicked(uri: Uri?) {
    if (uri == null) return
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        CallAi.setFolder(this, uri)
        toast("✅ تحفظ الدوسي")
    } catch (e: Exception) { toast("ما قدرتش نحفظ الدوسي") }
    refresh()
}
