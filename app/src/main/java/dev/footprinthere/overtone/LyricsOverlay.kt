package dev.footprinthere.overtone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 다른 앱 위에 떠 있는 가사 창. 여러 줄 모드와 얇은 띠 모드를 오가고, 화면 가장자리의 아이콘으로 접을 수 있다. */
class LyricsOverlay(
    private val ctx: Context,
    private val prefs: Prefs,
    private val actions: Actions,
) {
    interface Actions {
        fun onClose()
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
    private val background = GradientDrawable().apply {
        cornerRadius = dp(RADIUS_FULL_DP).toFloat()
        setStroke(dp(1), Palette.GLASS_EDGE)
    }

    // 여러 줄 모드
    private val full = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val titleView = text(12f, Palette.GLASS_TITLE).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val controls = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(32))
        visibility = View.GONE
    }
    private val offsetView = chip("보정 0.0초") { actions.onOffsetReset() }
    private val scrollView = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(36))
    }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val lyricsView = text(16f, Palette.LYRIC_DIM).apply {
        setLineSpacing(0f, 1.25f)
        setPadding(dp(16), dp(6), dp(16), dp(24))
    }

    // 얇은 띠 모드
    private val compactView = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(4), dp(10))
    }
    private val currentLine = text(17f, Palette.ACCENT).apply {
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val nextLine = text(13f, Palette.LYRIC_DIM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    // 접었을 때의 아이콘
    private val bubble = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_tile)
        imageTintList = ColorStateList.valueOf(Palette.ACCENT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        visibility = View.GONE
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
    private var minimized = false
    private var restoreHeight = 0
    private var anim: ValueAnimator? = null

    init {
        root.background = background
        buildFull()
        buildCompact()
        bubble.setOnClickListener { restore() }
        bubble.setOnTouchListener(DragListener())
        root.addView(full, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(compactView, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(bubble, FrameLayout.LayoutParams(MATCH, MATCH))
        applySettings()
    }

    // ---------- 외부에서 부르는 것 ----------

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
    }

    fun hide() {
        anim?.end()
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
        body.addView(text(13f, Palette.LYRIC_DIM).apply {
            setPadding(dp(16), dp(6), dp(16), dp(6))
            text = when {
                cands == null -> "후보를 찾는 중…"
                cands.isEmpty() -> "검색 결과가 없어요"
                else -> "맞는 가사를 골라 주세요"
            }
        })
        cands?.forEach { c -> body.addView(candidateRow(c, durationSec) { closePicker(); onPick(c) }) }
        body.addView(chip("취소") { closePicker() }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(dp(16), dp(8), 0, dp(12))
        })
        scrollView.scrollTo(0, 0)
    }

    val isPicking: Boolean get() = picking

    /** 설정 화면이나 알림에서 바뀐 값을 다시 읽어 반영한다. */
    fun applySettings() {
        background.setColor(Color.argb(prefs.opacity * 255 / 100, Palette.GLASS_R, Palette.GLASS_G, Palette.GLASS_B))
        val font = prefs.fontSp.toFloat()
        lyricsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, font)
        currentLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, font * 1.1f)
        nextLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, font * 0.85f)

        applyTouchFlags()
        compact = prefs.compact
        applyMode()
    }

    /** 접힌 아이콘은 눌러서 펼 수 있어야 하므로 터치 통과를 적용하지 않는다. */
    private fun applyTouchFlags() {
        val through = prefs.clickThrough && !minimized
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
    }

    fun onConfigurationChanged() {
        if (minimized && anim == null) {
            val (sw, _) = screenSize()
            params.x = if (params.x > 0) sw - params.width else 0
        }
        clampToScreen()
        update()
    }

    // ---------- 접기·펴기 ----------

    /** 가사를 먼저 흐리게 한 뒤, 창을 가까운 화면 가장자리로 빨아들이듯 줄여 아이콘으로 만든다. */
    private fun minimize() {
        if (minimized || anim != null) return
        minimized = true
        applyTouchFlags()
        val (sw, sh) = screenSize()
        val size = dp(BUBBLE_DP)
        val h = if (params.height > 0) params.height else root.height
        restoreHeight = h
        params.height = h
        val toRight = params.x + params.width / 2 > sw / 2
        val target = intArrayOf(
            if (toRight) sw - size else 0,
            (params.y + h / 2 - size / 2).coerceIn(0, max(0, sh - size)),
            size, size,
        )
        val content = if (full.visibility == View.VISIBLE) full else compactView
        val startRadius = background.cornerRadius
        animateWindow(target, 320, AccelerateInterpolator(1.5f), onFrame = { t ->
            content.alpha = (1 - t * 2.5f).coerceAtLeast(0f)
            if (content.alpha == 0f) content.visibility = View.GONE
            background.cornerRadius = startRadius + (size / 2f - startRadius) * t
        }) {
            content.visibility = View.GONE
            content.alpha = 1f
            background.cornerRadius = size / 2f
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.animate().alpha(1f).setDuration(120).start()
        }
    }

    /** 아이콘을 원래 자리와 크기로 다시 펼친다. */
    private fun restore() {
        if (!minimized || anim != null) return
        minimized = false
        bubble.visibility = View.GONE
        val asCompact = compact && !picking
        val target = targetBounds(asCompact)
        if (target[3] == WRAP) target[3] = restoreHeight
        val startRadius = background.cornerRadius
        val endRadius = dp(if (asCompact) RADIUS_COMPACT_DP else RADIUS_FULL_DP).toFloat()
        animateWindow(target, 260, DecelerateInterpolator(), onFrame = { t ->
            background.cornerRadius = startRadius + (endRadius - startRadius) * t
        }) {
            applyTouchFlags()
            applyMode()
            val content = if (asCompact) compactView else full
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(150).start()
        }
    }

    /** 아이콘을 끌다 놓으면 가까운 쪽 가장자리에 붙인다. */
    private fun snapToEdge() {
        val (sw, _) = screenSize()
        val size = params.width
        val x = if (params.x + size / 2 > sw / 2) sw - size else 0
        animateWindow(intArrayOf(x, params.y, size, size), 180, DecelerateInterpolator(), onFrame = {}) {}
    }

    private fun animateWindow(
        to: IntArray,
        durationMs: Long,
        easing: TimeInterpolator,
        onFrame: (Float) -> Unit,
        onEnd: () -> Unit,
    ) {
        val from = intArrayOf(params.x, params.y, params.width, params.height)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = easing
            addUpdateListener {
                val t = it.animatedValue as Float
                params.x = from[0] + ((to[0] - from[0]) * t).toInt()
                params.y = from[1] + ((to[1] - from[1]) * t).toInt()
                params.width = from[2] + ((to[2] - from[2]) * t).toInt()
                params.height = from[3] + ((to[3] - from[3]) * t).toInt()
                onFrame(t)
                update()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    anim = null
                    onEnd()
                }
            })
            start()
        }
    }

    // ---------- 화면 구성 ----------

    private fun buildFull() {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(4), dp(0))
            setOnTouchListener(DragListener())
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(iconButton(R.drawable.ic_compact, "얇게") { setCompact(true) })
        header.addView(iconButton(R.drawable.ic_minimize, "접기") { minimize() })
        header.addView(iconButton(R.drawable.ic_more, "더 보기") {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        header.addView(iconButton(R.drawable.ic_close, "닫기") { actions.onClose() })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(8), dp(8))
        }
        row.addView(chip("◀ 늦게") { actions.onOffsetDelta(-OFFSET_STEP_MS) })
        row.addView(offsetView)
        row.addView(chip("빠르게 ▶") { actions.onOffsetDelta(OFFSET_STEP_MS) })
        row.addView(chip("다른 가사") { actions.onPickOther() })
        row.addView(chip("터치 통과") {
            prefs.clickThrough = true
            applySettings()
            actions.onClickThroughChanged()
        })
        controls.addView(row)

        body.addView(lyricsView)
        scrollView.addView(body)

        val resize = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_resize)
            imageTintList = ColorStateList.valueOf(Palette.LYRIC_DIM)
            setPadding(dp(12), dp(12), dp(4), dp(4))
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
        val lines = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lines.addView(currentLine)
        lines.addView(nextLine)
        compactView.addView(lines, LinearLayout.LayoutParams(0, WRAP, 1f))
        compactView.addView(iconButton(R.drawable.ic_minimize, "접기") { minimize() })
        compactView.setOnClickListener { setCompact(false) }
        compactView.setOnTouchListener(DragListener())
    }

    private fun candidateRow(c: Candidate, durationSec: Int, onClick: () -> Unit): TextView {
        val details = mutableListOf(c.source)
        when {
            c.synced != null -> details += "싱크"
            c.instrumental -> details += "연주곡"
            c.plain != null -> details += "일반"
        }
        if (c.durationSec >= 0) {
            val len = c.durationSec.roundToInt()
            val diff = if (durationSec > 0) " (%+d초)".format(len - durationSec) else ""
            details += "${len / 60}:${(len % 60).toString().padStart(2, '0')}$diff"
        }
        if (c.albumName.isNotBlank()) details += c.albumName
        return text(14f, Color.WHITE).apply {
            text = SpannableStringBuilder("${c.trackName} — ${c.artistName}\n").apply {
                val start = length
                append(details.joinToString(" · "))
                setSpan(ForegroundColorSpan(Palette.LYRIC_DIM), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Palette.CHIP, 12)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(3), dp(10), dp(3)) }
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
        if (minimized) {
            render()
            return
        }
        val asCompact = compact && !picking
        full.visibility = if (asCompact) View.GONE else View.VISIBLE
        compactView.visibility = if (asCompact) View.VISIBLE else View.GONE
        background.cornerRadius = dp(if (asCompact) RADIUS_COMPACT_DP else RADIUS_FULL_DP).toFloat()

        val (x, y, w, h) = targetBounds(asCompact)
        params.x = x
        params.y = y
        params.width = w
        params.height = h
        clampToScreen()
        render()
        update()
    }

    /** 저장된 위치·크기. 저장된 적 없으면 기본값. 얇은 모드의 높이는 내용에 맞춘다(WRAP). */
    private fun targetBounds(asCompact: Boolean): IntArray {
        val (sw, _) = screenSize()
        val (x, y, w, h) = prefs.bounds(asCompact)
        return intArrayOf(
            if (x >= 0) x else dp(16),
            if (y >= 0) y else dp(if (asCompact) 80 else 120),
            if (w > 0) w else sw - dp(32),
            if (asCompact) WRAP else if (h > 0) h else dp(280),
        )
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
                currentLine.setTextColor(Color.WHITE)
                currentLine.text = c.text
                nextLine.text = ""
            }
            is Content.Plain -> {
                lyricsView.text = c.text
                currentLine.setTextColor(Color.WHITE)
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
        currentLine.setTextColor(Palette.ACCENT)
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
                sb.setSpan(ForegroundColorSpan(Palette.ACCENT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        if (!minimized) {
            params.width = params.width.coerceIn(dp(MIN_W_DP).coerceAtMost(sw), sw)
            if (params.height > 0) params.height = params.height.coerceIn(dp(MIN_H_DP).coerceAtMost(sh), sh)
        }
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
                MotionEvent.ACTION_UP -> when {
                    !moved -> v.performClick()
                    minimized -> snapToEdge()
                    else -> saveBounds()
                }
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

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconButton(res: Int, label: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Palette.GLASS_TITLE)
        contentDescription = label
        setPadding(dp(9), dp(9), dp(9), dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, onClick: () -> Unit) = text(13f, Color.WHITE).apply {
        text = label
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(Palette.CHIP, 99)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MIN_W_DP = 160
        private const val MIN_H_DP = 120
        private const val OFFSET_STEP_MS = 500L
        private const val BUBBLE_DP = 48
        private const val RADIUS_FULL_DP = 18
        private const val RADIUS_COMPACT_DP = 22
    }
}
