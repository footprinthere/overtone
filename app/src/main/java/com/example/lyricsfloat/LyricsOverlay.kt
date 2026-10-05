package com.example.lyricsfloat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 다른 앱 위에 떠 있는 가사 창. 여러 줄 모드와 얇은 띠 모드를 오간다. */
class LyricsOverlay(
    private val ctx: Context,
    private val prefs: Prefs,
    private val actions: Actions,
) {
    interface Actions {
        fun onClose()
        fun onWebSearch()
        fun onOffsetDelta(deltaMs: Long)
        fun onOffsetReset()
        fun onPickOther()
        fun onClickThroughChanged()
    }

    private sealed interface Content {
        data class Message(val text: String) : Content
        data class Plain(val text: String) : Content
        data class Synced(val lines: List<LrcLine>) : Content
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val root = FrameLayout(ctx)
    private val background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }

    // 여러 줄 모드
    private val full = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val titleView = text(13f, Color.WHITE).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val controls = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
    }
    private val offsetView = button("보정 0.0초") { actions.onOffsetReset() }
    private val scrollView = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val lyricsView = text(16f, DIM).apply {
        setLineSpacing(0f, 1.25f)
        setPadding(dp(16), dp(6), dp(16), dp(24))
    }

    // 얇은 띠 모드
    private val compactView = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }
    private val currentLine = text(17f, Color.WHITE).apply {
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val nextLine = text(13f, DIM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var attached = false
    private var compact = prefs.compact
    private var picking = false
    private var content: Content = Content.Message("")
    private var index = -1

    init {
        root.background = background
        buildFull()
        buildCompact()
        root.addView(full, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(compactView, FrameLayout.LayoutParams(MATCH, WRAP))
        applySettings()
    }

    // ---------- 외부에서 부르는 것 ----------

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
    }

    fun hide() {
        if (!attached) return
        wm.removeView(root)
        attached = false
    }

    val isShowing: Boolean get() = attached

    fun setTitle(text: String) {
        titleView.text = text
    }

    fun showMessage(text: String) = setContent(Content.Message(text))

    fun showPlain(text: String) = setContent(Content.Plain(text))

    fun showSynced(lines: List<LrcLine>) = setContent(Content.Synced(lines))

    /** 지금 부르는 줄 번호. 첫 줄 전이면 -1. */
    fun highlight(i: Int) {
        if (i == index) return
        index = i
        renderSynced()
    }

    fun setOffset(ms: Long) {
        offsetView.text = if (ms == 0L) "보정 0.0초" else "보정 %+.1f초".format(ms / 1000.0)
    }

    /** 후보 목록을 보여준다. null 이면 찾는 중. 고르기 동안은 얇은 모드여도 펼쳐서 보여준다. */
    fun showPicker(cands: List<Candidate>?, durationSec: Int, onPick: (Candidate) -> Unit) {
        picking = true
        applyMode()
        body.removeAllViews()
        body.addView(text(13f, DIM).apply {
            setPadding(dp(16), dp(6), dp(16), dp(6))
            text = when {
                cands == null -> "후보를 찾는 중…"
                cands.isEmpty() -> "검색 결과가 없어요"
                else -> "맞는 가사를 골라 주세요"
            }
        })
        cands?.forEach { c -> body.addView(candidateRow(c, durationSec) { closePicker(); onPick(c) }) }
        body.addView(button("취소") { closePicker() })
        scrollView.scrollTo(0, 0)
    }

    val isPicking: Boolean get() = picking

    /** 설정 화면이나 알림에서 바뀐 값을 다시 읽어 반영한다. */
    fun applySettings() {
        background.setColor(Color.argb(prefs.opacity * 255 / 100, 18, 18, 18))
        val font = prefs.fontSp.toFloat()
        lyricsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, font)
        currentLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, font * 1.1f)
        nextLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, font * 0.85f)

        val through = prefs.clickThrough
        params.flags = if (through) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        // 안드로이드 12부터는 창 전체 투명도가 이 값보다 높으면 터치가 밑의 앱으로 전달되지 않고 막힌다.
        params.alpha = if (through && Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        } else {
            1f
        }

        compact = prefs.compact
        applyMode()
    }

    fun onConfigurationChanged() {
        clampToScreen()
        update()
    }

    // ---------- 화면 구성 ----------

    private fun buildFull() {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(2), dp(2))
            setOnTouchListener(DragListener())
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(button("얇게") { setCompact(true) })
        header.addView(button("⋯") {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        header.addView(button("✕") { actions.onClose() })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        row.addView(button("◀ 늦게") { actions.onOffsetDelta(-OFFSET_STEP_MS) })
        row.addView(offsetView)
        row.addView(button("빠르게 ▶") { actions.onOffsetDelta(OFFSET_STEP_MS) })
        row.addView(button("다른 가사") { actions.onPickOther() })
        row.addView(button("웹 검색") { actions.onWebSearch() })
        row.addView(button("터치 통과") {
            prefs.clickThrough = true
            applySettings()
            actions.onClickThroughChanged()
        })
        controls.addView(row)

        body.addView(lyricsView)
        scrollView.addView(body)

        val resize = text(14f, DIM).apply {
            text = "◢"
            setPadding(dp(10), dp(4), dp(6), dp(2))
            setOnTouchListener(ResizeListener())
        }
        val content = FrameLayout(ctx)
        content.addView(scrollView, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(resize, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))

        full.addView(header)
        full.addView(controls)
        full.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun buildCompact() {
        compactView.addView(currentLine)
        compactView.addView(nextLine)
        compactView.setOnClickListener { setCompact(false) }
        compactView.setOnTouchListener(DragListener())
    }

    private fun candidateRow(c: Candidate, durationSec: Int, onClick: () -> Unit): TextView {
        val kind = when {
            c.synced != null -> "싱크"
            c.instrumental -> "연주곡"
            else -> "일반"
        }
        val len = c.durationSec.roundToInt()
        val diff = if (durationSec > 0) " (%+d초)".format(len - durationSec) else ""
        return text(14f, Color.WHITE).apply {
            val time = "${len / 60}:${(len % 60).toString().padStart(2, '0')}"
            text = "${c.trackName} — ${c.artistName}\n${c.albumName} · $time$diff · $kind"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onClick() }
        }
    }

    fun closePicker() {
        picking = false
        body.removeAllViews()
        body.addView(lyricsView)
        applyMode()
    }

    private fun setCompact(on: Boolean) {
        prefs.compact = on
        compact = on
        applyMode()
    }

    /** 현재 모드에 맞게 보일 영역과 창 크기를 정하고 내용을 다시 그린다. */
    private fun applyMode() {
        val asCompact = compact && !picking
        full.visibility = if (asCompact) View.GONE else View.VISIBLE
        compactView.visibility = if (asCompact) View.VISIBLE else View.GONE
        background.cornerRadius = dp(if (asCompact) 12 else 14).toFloat()

        val (sw, _) = screenSize()
        val (x, y, w, h) = prefs.bounds(asCompact)
        params.width = if (w > 0) w else sw - dp(32)
        params.height = if (asCompact) WRAP else if (h > 0) h else dp(280)
        params.x = if (x >= 0) x else dp(16)
        params.y = if (y >= 0) y else dp(if (asCompact) 80 else 120)
        clampToScreen()
        render()
        update()
    }

    private fun setContent(c: Content) {
        content = c
        index = -1
        render()
        scrollView.scrollTo(0, 0)
    }

    private fun render() {
        if (picking) return
        when (val c = content) {
            is Content.Message -> {
                lyricsView.text = c.text
                currentLine.text = c.text
                nextLine.text = ""
            }
            is Content.Plain -> {
                lyricsView.text = c.text
                currentLine.text = "싱크 없는 가사예요"
                nextLine.text = "탭하면 펼쳐서 볼 수 있어요"
            }
            is Content.Synced -> renderSynced()
        }
    }

    private fun renderSynced() {
        val lines = (content as? Content.Synced)?.lines ?: return
        if (picking) return
        fun label(i: Int) = lines.getOrNull(i)?.text?.ifBlank { "♪" } ?: ""
        currentLine.text = if (index < 0) "♪" else label(index)
        nextLine.text = label(index + 1)
        if (compact) return

        val sb = SpannableStringBuilder()
        var curStart = 0
        for ((i, l) in lines.withIndex()) {
            val start = sb.length
            sb.append(l.text.ifBlank { "♪" })
            if (i == index) {
                curStart = start
                val end = sb.length
                sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("\n")
        }
        lyricsView.text = sb
        lyricsView.post {
            val layout = lyricsView.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset(curStart)) - scrollView.height / 3
            scrollView.smoothScrollTo(0, max(0, y))
        }
    }

    // ---------- 창 위치·크기 ----------

    private fun screenSize(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun clampToScreen() {
        val (sw, sh) = screenSize()
        params.width = params.width.coerceIn(dp(MIN_W_DP).coerceAtMost(sw), sw)
        if (params.height > 0) params.height = params.height.coerceIn(dp(MIN_H_DP).coerceAtMost(sh), sh)
        val h = if (params.height > 0) params.height else max(root.height, dp(56))
        params.x = params.x.coerceIn(0, max(0, sw - params.width))
        params.y = params.y.coerceIn(0, max(0, sh - h))
    }

    private fun update() {
        if (attached) wm.updateViewLayout(root, params)
    }

    private fun saveBounds() {
        val asCompact = compact && !picking
        prefs.saveBounds(asCompact, params.x, params.y, params.width, if (asCompact) -1 else params.height)
    }

    /** 끌면 창을 옮기고, 거의 움직이지 않고 떼면 그 뷰의 클릭으로 처리한다. */
    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = e.rawX; downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampToScreen()
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) saveBounds() else v.performClick()
            }
            return true
        }
    }

    private inner class ResizeListener : View.OnTouchListener {
        private var startW = 0
        private var startH = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width; startH = params.height
                    downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = startW + (e.rawX - downX).toInt()
                    params.height = startH + (e.rawY - downY).toInt()
                    clampToScreen()
                    update()
                }
                MotionEvent.ACTION_UP -> saveBounds()
            }
            return true
        }
    }

    // ---------- 작은 도우미 ----------

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun text(sp: Float, color: Int) = TextView(ctx).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun button(label: String, onClick: () -> Unit) = text(14f, Color.WHITE).apply {
        text = label
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    companion object {
        private val DIM = Color.parseColor("#99FFFFFF")
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MIN_W_DP = 160
        private const val MIN_H_DP = 120
        private const val OFFSET_STEP_MS = 500L
    }
}
