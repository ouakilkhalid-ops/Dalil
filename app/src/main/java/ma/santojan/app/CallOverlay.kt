package ma.santojan.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** Petite bulle qui affiche la durée de l'appel en cours. */
object CallOverlay {
    private var view: TextView? = null
    private var start = 0L
    private val h = Handler(Looper.getMainLooper())

    @SuppressLint("ClickableViewAccessibility")
    fun show(c: Context) {
        if (view != null || !Settings.canDrawOverlays(c)) return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(c).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            background = Ui.round(Color.parseColor("#E6166534"), Ui.dp(c, 22).toFloat())
            setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 18), Ui.dp(c, 8))
            text = "⏱ 00:00"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Ui.dp(c, 90)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
            }
            true
        }
        try {
            wm.addView(tv, lp)
        } catch (e: Exception) {
            return
        }
        view = tv
        start = SystemClock.elapsedRealtime()
        tick()
    }

    private fun tick() {
        val v = view ?: return
        val sec = (SystemClock.elapsedRealtime() - start) / 1000
        v.text = "⏱ ${Fmt.chrono(sec)}"
        h.postDelayed({ tick() }, 1000)
    }

    fun hide(c: Context) {
        h.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        try { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }
}

/** Garde l'app en vie pendant l'appel pour que la bulle reste affichée. */
class CallOverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.channels(this)
        val n = Notification.Builder(this, Notif.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Dalil")
            .setContentText("كنحسب مدة المكالمة")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(7, n)
            }
        } catch (e: Exception) {
            stopSelf()
        }
        CallOverlay.show(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CallOverlay.hide(applicationContext)
        super.onDestroy()
    }
}

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = c.applicationContext
        when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (!Store.getB(app, "overlay", true) || !Settings.canDrawOverlays(app)) return
                // On affiche la bulle d'abord (Android 14 l'exige avant de lancer le service)
                CallOverlay.show(app)
                try { app.startForegroundService(Intent(app, CallOverlayService::class.java)) } catch (_: Exception) {}
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallOverlay.hide(app)
                try { app.stopService(Intent(app, CallOverlayService::class.java)) } catch (_: Exception) {}
            }
        }
    }
}
