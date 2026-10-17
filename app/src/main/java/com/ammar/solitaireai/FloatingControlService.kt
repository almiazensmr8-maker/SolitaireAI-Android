package com.ammar.solitaireai

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Floating controls for the standalone engine. This service does not inspect or control other apps. */
class FloatingControlService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var running = false
    private var mode = 1 // 0 quick, 1 balanced, 2 deep
    private lateinit var status: TextView
    private lateinit var strength: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel == null) showPanel()
        return START_NOT_STICKY
    }

    private fun showPanel() {
        mode = when (EngineSettings.mode) {
            EngineSettings.Mode.QUICK -> 0
            EngineSettings.Mode.BALANCED -> 1
            EngineSettings.Mode.DEEP -> 2
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(17, 27, 44))
        }
        val header = TextView(this).apply {
            text = "SOLITAIRE AI  ⋮"
            textSize = 15f
            setTextColor(Color.rgb(94, 234, 212))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
            setOnTouchListener(DragTouchListener(wm) { panel })
        }
        root.addView(header)
        status = TextView(this).apply {
            text = "الحالة: متوقف"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(status)
        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runRow.addView(button("تشغيل") {
            running = true
            status.text = "الحالة: يعمل — وضع ${modeName()}"
            Toast.makeText(this@FloatingControlService, "اللوحة فعّالة؛ لا تتصل بلعبة جواكر", Toast.LENGTH_SHORT).show()
        }, weightParams())
        runRow.addView(button("إيقاف") {
            running = false
            status.text = "الحالة: متوقف"
        }, weightParams())
        root.addView(runRow)
        strength = TextView(this).apply {
            text = "القوة: ${modeName()}"
            textSize = 12f
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(0, dp(7), 0, dp(4))
        }
        root.addView(strength)
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(button("سريع") { setMode(0) }, weightParams())
        modeRow.addView(button("متوازن") { setMode(1) }, weightParams())
        modeRow.addView(button("عميق") { setMode(2) }, weightParams())
        root.addView(modeRow)
        root.addView(button("إخفاء اللوحة  ×") { closePanel() }, LinearLayout.LayoutParams(-1, dp(38)))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            dp(245), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8); y = dp(110)
        }
        panel = root
        try { wm.addView(root, params) } catch (_: Exception) {
            panel = null
            Toast.makeText(this, "تعذّر إظهار اللوحة. تحقق من صلاحية الظهور فوق التطبيقات.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun setMode(value: Int) {
        mode = value
        EngineSettings.setMode(when (value) {
            0 -> EngineSettings.Mode.QUICK
            1 -> EngineSettings.Mode.BALANCED
            else -> EngineSettings.Mode.DEEP
        })
        strength.text = "القوة: ${modeName()}"
        if (running) status.text = "الحالة: يعمل — وضع ${modeName()}"
    }

    private fun modeName() = when (mode) { 0 -> "سريع"; 1 -> "متوازن"; else -> "عميق" }

    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.rgb(8, 30, 35))
        setBackgroundColor(Color.rgb(94, 234, 212))
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        super.onDestroy()
    }
}

/** Enables dragging the floating panel by its title bar. */
private class DragTouchListener(
    private val wm: WindowManager,
    private val panelProvider: () -> android.view.View?
) : android.view.View.OnTouchListener {
    private var startX = 0f
    private var startY = 0f
    private var initialX = 0
    private var initialY = 0
    override fun onTouch(view: android.view.View, event: android.view.MotionEvent): Boolean {
        val target = panelProvider() ?: return false
        val params = target.layoutParams as? WindowManager.LayoutParams ?: return false
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                initialX = params.x; initialY = params.y
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                params.x = initialX - (event.rawX - startX).toInt()
                params.y = initialY + (event.rawY - startY).toInt()
                try { wm.updateViewLayout(target, params) } catch (_: Exception) {}
                return true
            }
        }
        return false
    }
}
