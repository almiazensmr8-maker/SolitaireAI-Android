package com.ammar.solitaireai

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var board: TextView
    private lateinit var moves: TextView
    private lateinit var stats: TextView
    private var state = CardEngine.newGame()
    private val history = mutableListOf<GameState>()
    private var autoPlaying = false
    private var solverRunning = false
    private val autoHandler = Handler(Looper.getMainLooper())
    private val bg = Color.rgb(9, 16, 30)
    private val panel = Color.rgb(18, 30, 49)
    private val mint = Color.rgb(94, 234, 212)
    private val text = Color.rgb(234, 241, 255)
    private val muted = Color.rgb(148, 163, 184)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        render()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        setContentView(scroll)

        label("SOLITAIRE AI", 25, mint, true)
        label("محرك سوليتير تجريبي • يعمل محلياً", 14, muted)
        status = label("جاهز", 13, text, true)
        val row1 = row()
        button(row1, "لعبة جديدة") { autoPlaying = false; state = CardEngine.newGame(drawCount = state.drawCount); history.clear(); render() }
        button(row1, "أفضل حركة") { showSuggestion() }
        val row2 = row()
        button(row2, "تنفيذ حركة") { playBest() }
        button(row2, "لعب تلقائي") { toggleAuto() }
        button(row2, "تراجع") { autoPlaying = false; if (history.isNotEmpty()) { state = history.removeAt(history.lastIndex); render() } }
        val row3 = row()
        button(row3, "سحب 1") { state.drawCount = 1; draw() }
        button(row3, "سحب 3") { state.drawCount = 3; draw() }
        button(row3, "اختبار 2000 توزيع") { runStress() }
        label("لوحة الأوراق", 18, text, true)
        board = label("", 13, text)
        label("الحركات المقترحة", 18, text, true)
        moves = label("", 13, mint)
        label("الإحصائيات", 18, text, true)
        stats = label("", 13, muted)
        val overlayRow = row()
        button(overlayRow, "فتح اللوحة العائمة") { openFloatingPanel() }
        label("هذه نسخة مستقلة للاختبار. اللوحة العائمة لا تقرأ شاشة جواكر ولا تنفذ حركات داخلها.", 12, muted)
    }

    private fun openFloatingPanel() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "اسمح بالظهور فوق التطبيقات، ثم ارجع واضغط الزر مرة ثانية", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, FloatingControlService::class.java))
        Toast.makeText(this, "تم طلب فتح اللوحة العائمة", Toast.LENGTH_SHORT).show()
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.rgb(6, 32, 30))
            setBackgroundColor(mint)
            setOnClickListener { action() }
        }
        parent.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(7), dp(2), dp(7))
        }
        root.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }
    private fun render() {
        status.text = if (Suit.values().all { state.foundations.getValue(it).size == 13 }) "اكتمل الفوز!" else "الحالة: قيد اللعب"
        val b = StringBuilder()
        b.append("السحب: ${state.stock.size} | المكشوف: ${state.waste.lastOrNull()?.label ?: "—"}\n")
        b.append("الأساسات: ")
        Suit.values().forEach { b.append("${state.foundations.getValue(it).lastOrNull()?.label ?: it.symbol}  ") }
        b.append("\n\n")
        state.tableau.forEachIndexed { i, col ->
            b.append("${i + 1}. ")
            b.append(if (col.isEmpty()) "[مكان فارغ — الملك فقط]" else col.joinToString("  ") { if (it.faceUp) it.label else "▧" })
            b.append("\n")
        }
        board.text = b.toString()
        moves.text = CardEngine.legalMoves(state).sortedByDescending { CardEngine.score(state, it) }.take(8)
            .mapIndexed { i, m -> "${i + 1}. ${m}" }.joinToString("\n").ifBlank { "لا توجد حركة قانونية حالياً" }
        stats.text = "الحركات: ${state.movesMade}\nالنقاط: ${state.score}\nالأوراق المكشوفة: ${state.tableau.flatten().count { it.faceUp }}\nالأساسات: ${state.foundations.values.sumOf { it.size }}/52\nتراجع متاح: ${history.size}"
    }
    private fun showSuggestion() {
        runSolver { result ->
            val m = result.move
            val message = if (m == null) "لا توجد حركة قانونية" else
                "${m}\nتعمّق البحث: ${result.depthReached}\nالعُقد المفحوصة: ${result.expandedNodes}\nالوقت: ${result.elapsedMs} ms" +
                if (result.timedOut) "\nوصل البحث إلى حد الوقت المحدد." else ""
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun playBest() {
        runSolver { result ->
            val m = result.move ?: run { Toast.makeText(this, "لا توجد حركة قانونية", Toast.LENGTH_SHORT).show(); return@runSolver }
            history += state.copyDeep()
            state = CardEngine.apply(state, m)
            render()
        }
    }

    private fun toggleAuto() {
        autoPlaying = !autoPlaying
        if (autoPlaying) {
            status.text = "اللعب التلقائي يعمل داخل هذه النسخة المستقلة"
            autoStep()
        } else {
            status.text = "تم إيقاف اللعب التلقائي"
        }
    }

    private fun autoStep() {
        if (!autoPlaying) return
        if (Suit.values().all { state.foundations.getValue(it).size == 13 }) {
            autoPlaying = false
            status.text = "اكتمل الفوز!"
            return
        }
        runSolver { result ->
            if (!autoPlaying) return@runSolver
            val m = result.move
            if (m == null) {
                autoPlaying = false
                status.text = "توقف: لا توجد حركات قانونية"
                return@runSolver
            }
            history += state.copyDeep()
            state = CardEngine.apply(state, m)
            render()
            if (autoPlaying) autoHandler.postDelayed({ autoStep() }, 180L)
        }
    }

    private fun runSolver(onResult: (SolverResult) -> Unit) {
        if (solverRunning) {
            Toast.makeText(this, "المحرك يحسب الحركة الحالية…", Toast.LENGTH_SHORT).show()
            return
        }
        solverRunning = true
        val snapshot = state.copyDeep()
        val snapshotKey = AdvancedSolver.signature(snapshot)
        Thread {
            val result = try {
                AdvancedSolver.suggest(snapshot, EngineSettings.config())
            } catch (e: Exception) {
                SolverResult(null, Double.NEGATIVE_INFINITY, 0, 0, 0, false, false)
            }
            runOnUiThread {
                solverRunning = false
                if (AdvancedSolver.signature(state) != snapshotKey) {
                    status.text = "تغيّرت حالة اللعبة؛ أعد طلب الحركة"
                    return@runOnUiThread
                }
                onResult(result)
            }
        }.start()
    }

    private fun runStress() {
        status.text = "يجري اختبار سلامة 2000 توزيع في الخلفية…"
        Thread {
            val result = try { CardEngine.stressTest(2_000, 150).toString() }
            catch (e: Exception) { "فشل اختبار السلامة: ${e.message}" }
            runOnUiThread {
                stats.text = "اختبار سلامة المحرك:\n$result\n\nهذا الاختبار يفحص بقاء الأوراق الـ52 فريدة وصحة الانتقالات، وليس تدريباً آلياً أو مقياساً لضمان الفوز."
                status.text = "انتهى اختبار السلامة"
                Toast.makeText(this, "انتهى اختبار السلامة", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
