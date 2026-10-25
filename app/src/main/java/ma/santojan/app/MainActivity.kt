package ma.santojan.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

const val TAB_HOME = -1
const val TAB_WA = 2
const val TAB_MEET = 3
const val TAB_FOCUS = 4
const val TAB_MORE = 8
const val TAB_NEWS = 9
const val TAB_MONEY = 10
const val TAB_FUEL = 11
const val TAB_POP = 12
const val TAB_ARTICLE = 13
const val TAB_FOOT = 14

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var homeBtn: Button
    private var current = TAB_HOME
    fun currentTab() = current
    var pendingGroup: String? = null
    var pendingCall: String? = null
    val openGroups = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.channels(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 4))
        }
        homeBtn = Ui.button(this, "🏠 الرئيسية", Ui.CHIP) { show(TAB_HOME) }
        header.addView(homeBtn, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        header.addView(Ui.text(this, "دليل", 22f, Ui.ACCENT, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        header.addView(View(this), LinearLayout.LayoutParams(Ui.dp(this, 1), 1))
        root.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        setContentView(root)

        current = savedInstanceState?.getInt("tab") ?: intent?.getIntExtra("tab", TAB_HOME) ?: TAB_HOME
        Focus.apply(this)
        try { News.schedule(this) } catch (_: Exception) {}
        if (savedInstanceState == null) {
            if (!handleShare(intent)) askPermissions()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleShare(intent)
    }

    /** Un enregistrement partagé depuis une autre app (ex. Téléphone de Google) → rapport IA. */
    private fun handleShare(i: Intent?): Boolean {
        val link = i?.getStringExtra("a_link")
        if (link != null) {
            openArticle(NewsItem(i.getStringExtra("a_title") ?: "", link, i.getStringExtra("a_src") ?: "", i.getLongExtra("a_time", 0L)))
            return true
        }
        if (i?.action != Intent.ACTION_SEND) return false
        @Suppress("DEPRECATION")
        val uri = i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return false
        var name = "تسجيل"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (_: Exception) {}
        val type = contentResolver.getType(uri) ?: i.type
        android.app.AlertDialog.Builder(this)
            .setTitle("🤖 شنو هاد التسجيل؟")
            .setItems(arrayOf("📞 مكالمة — ملخص", "🎙️ اجتماع — تقرير كامل")) { _, w ->
                if (w == 0) summarizeUri(uri, name, type) else summarizeUri(uri, name, type, CallAi.MEETING_PROMPT)
            }
            .setNegativeButton("لغي", null)
            .show()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onResume() {
        super.onResume()
        show(current)
    }

    override fun onDestroy() {
        WaStore.onChange = null
        super.onDestroy()
    }

    @Deprecated("")
    override fun onBackPressed() {
        if (current == TAB_ARTICLE) show(TAB_NEWS)
        else if (current != TAB_HOME) show(TAB_HOME)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    fun show(i: Int) {
        val keepY = if (i == current) (content.getChildAt(0) as? android.widget.ScrollView)?.scrollY ?: 0 else 0
        current = i
        homeBtn.visibility = if (i == TAB_HOME) View.INVISIBLE else View.VISIBLE
        content.removeAllViews()
        val v: View = try {
            when (i) {
                TAB_HOME -> homeScreen()
                0 -> callsScreen()
                1 -> groupsScreen()
                TAB_WA -> whatsappScreen()
                TAB_MEET -> meetingsScreen()
                TAB_FOCUS -> focusScreen()
                5 -> appsScreen()
                6 -> remindersScreen()
                7 -> chargesScreen()
                TAB_NEWS -> newsScreen()
                TAB_MONEY -> moneyScreen()
                TAB_FUEL -> fuelScreen()
                TAB_POP -> populationScreen()
                TAB_ARTICLE -> articleScreen()
                TAB_FOOT -> footballScreen()
                else -> moreScreen()
            }
        } catch (e: Exception) {
            val col = Ui.column(this)
            col.addView(Ui.text(this, "وقع مشكل: ${e.message}", color = Ui.RED))
            Ui.scroll(this, col)
        }
        content.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        if (keepY > 0 && v is android.widget.ScrollView) v.post { v.scrollTo(0, keepY) }
    }

    fun refresh() = show(current)

    fun has(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = list.filter { !has(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && has(Manifest.permission.WRITE_CONTACTS)) {
            toast("⭐ تزادت نجمة لـ ${Focus.starAllowed(this)} جهة اتصال")
        }
        if (requestCode == 2) {
            val n = pendingCall
            pendingCall = null
            if (n != null) {
                if (has(Manifest.permission.CALL_PHONE)) openSafe(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$n")))
                else openSafe(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$n")))
            }
            return
        }
        refresh()
    }

    @Deprecated("")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3) {
            if (resultCode == RESULT_OK) onContactPicked(data?.data) else pendingGroup = null
        }
        if (requestCode == 4) { if (resultCode == RESULT_OK) onAudioPicked(data?.data) else pendingMeetingPick = false }
        if (requestCode == 6 || requestCode == 8) refresh()
        if (requestCode == 10 && resultCode == RESULT_OK) onRecFolderPicked(data?.data)
    }

    fun openSafe(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: Exception) {
        toast("ما قدرتش نحل هاد الصفحة")
        false
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
