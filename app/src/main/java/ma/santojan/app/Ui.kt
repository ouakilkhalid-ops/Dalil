package ma.santojan.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    val BG = Color.parseColor("#0F172A")
    val CARD = Color.parseColor("#1E293B")
    val CHIP = Color.parseColor("#334155")
    val TEXT = Color.parseColor("#F1F5F9")
    val MUTED = Color.parseColor("#94A3B8")
    val ACCENT = Color.parseColor("#22C55E")
    val RED = Color.parseColor("#EF4444")
    val BLUE = Color.parseColor("#3B82F6")
    val ORANGE = Color.parseColor("#F59E0B")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun text(c: Context, s: CharSequence, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            textDirection = View.TEXT_DIRECTION_ANY_RTL
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun title(c: Context, s: String): TextView =
        text(c, s, 22f, TEXT, true).apply { setPadding(0, 0, 0, dp(c, 10)) }

    fun button(c: Context, s: String, color: Int = BLUE, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = round(color, dp(c, 10).toFloat())
            setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4))
            minHeight = 0
            minimumHeight = dp(c, 40)
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 24))
    }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(CARD, dp(c, 14).toFloat())
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(c, 10) }
    }

    fun lp(c: Context, weight: Float = 0f, m: Int = 3): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (weight > 0f) 0 else WRAP, WRAP, weight).apply {
            setMargins(dp(c, m), dp(c, m), dp(c, m), dp(c, m))
        }

    fun scroll(c: Context, inner: View): ScrollView = ScrollView(c).apply {
        isFillViewport = true
        addView(inner)
    }

    fun input(c: Context, hint: String, number: Boolean = false): EditText = EditText(c).apply {
        this.hint = hint
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textDirection = View.TEXT_DIRECTION_ANY_RTL
        background = round(CHIP, dp(c, 10).toFloat())
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(c, 4), 0, dp(c, 4)) }
    }

    fun gap(c: Context, h: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
    }
}

object Fmt {
    fun dur(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}س ${m}د"
            m > 0 -> "${m}د ${s}ث"
            else -> "${s}ث"
        }
    }

    fun chrono(sec: Long): String =
        if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        else String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    fun minutes(m: Int): String = when {
        m < 60 -> "$m دقيقة"
        m % 60 == 0 -> "${m / 60} ساعة"
        else -> "${m / 60}س ${m % 60}د"
    }

    fun ago(t: Long): String {
        if (t <= 0L) return "ما تحلّش هاد العام"
        val min = (System.currentTimeMillis() - t) / 60000
        return when {
            min < 1 -> "دابا"
            min < 60 -> "هادي $min دقيقة"
            min < 1440 -> "هادي ${min / 60} ساعة"
            else -> "هادي ${min / 1440} يوم"
        }
    }

    fun date(t: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(t))
    fun time(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
    fun money(v: Double): String = String.format(Locale.US, "%,.0f درهم", v)
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("santojan", Context.MODE_PRIVATE)

    fun arr(c: Context, key: String): JSONArray =
        try { JSONArray(p(c).getString(key, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    fun saveArr(c: Context, key: String, a: JSONArray) = p(c).edit().putString(key, a.toString()).apply()
    fun getB(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    fun putB(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()
    fun getD(c: Context, key: String): Double = p(c).getFloat(key, 0f).toDouble()
    fun putD(c: Context, key: String, v: Double) = p(c).edit().putFloat(key, v.toFloat()).apply()
}
