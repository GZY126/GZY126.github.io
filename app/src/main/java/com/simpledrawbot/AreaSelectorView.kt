package com.simpledrawbot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.simpledrawbot.util.PreferenceHelper
import kotlin.math.*

class AreaSelectorView(context: Context) : View(context) {

    private val prefs = PreferenceHelper(context)

    enum class AspectRatio(val label: String, val ratio: Float?) {
        FREE("自由", null), SQUARE("1:1", 1.0f),
        FOUR_THREE("4:3", 4f / 3f), SIXTEEN_NINE("16:9", 16f / 9f)
    }
    private var lockedRatio: AspectRatio = AspectRatio.FREE

    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B35"); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20FF6B35"); style = Paint.Style.FILL
    }
    private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B35"); style = Paint.Style.FILL
    }
    private val paintHandleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val paintEdgeHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF6B35"); style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val paintTextBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF6B35"); style = Paint.Style.FILL
    }
    private val paintInfoBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000"); style = Paint.Style.FILL
    }
    private val paintSnapLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF6B35"); style = Paint.Style.STROKE; strokeWidth = 1f
    }

    private var screenWidth = 1080; private var screenHeight = 1920
    private var rect = RectF(200f, 400f, 700f, 800f)
    private var animProgress = 0f
    private var onConfirmListener: ((RectF) -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null
    private var handleRadius = 20f; private var edgeHandleSize = 14f
    private var activeHandle = -1 // -1=移动, 0~3=四角, 4=上, 5=右, 6=下, 7=左
    private var lastX = 0f; private var lastY = 0f
    private var isDragging = false

    // 双指缩放
    private var initialSpan = 0f
    private var initialRect = RectF()
    private var isPinching = false
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f

    private val snapThreshold = 30f
    private val areaPresets: Map<String, RectF> = prefs.getAreaPresets()
    private var showPresetMenu = false

    // 四角把手
    private val cornerHandles: List<RectF> get() {
        val r = handleRadius
        return listOf(
            RectF(rect.left - r, rect.top - r, rect.left + r, rect.top + r),
            RectF(rect.right - r, rect.top - r, rect.right + r, rect.top + r),
            RectF(rect.left - r, rect.bottom - r, rect.left + r, rect.bottom + r),
            RectF(rect.right - r, rect.bottom - r, rect.right + r, rect.bottom + r)
        )
    }
    // 四边中间把手
    private val edgeHandles: List<RectF> get() {
        val s = edgeHandleSize; val midX = (rect.left + rect.right) / 2f; val midY = (rect.top + rect.bottom) / 2f
        return listOf(
            RectF(midX - s, rect.top - s, midX + s, rect.top + s),      // 上
            RectF(rect.right - s, midY - s, rect.right + s, midY + s),   // 右
            RectF(midX - s, rect.bottom - s, midX + s, rect.bottom + s), // 下
            RectF(rect.left - s, midY - s, rect.left + s, midY + s)      // 左
        )
    }

    init {
        readScreenResolution()
        initDynamicSizes()
        initDefaultRect()
        startPulseAnimation()
    }

    private fun initDynamicSizes() {
        handleRadius = context.resources.getDimension(R.dimen.area_selector_handle_size) / 2f
        edgeHandleSize = handleRadius * 0.7f
        paintBorder.strokeWidth = context.resources.getDimension(R.dimen.area_selector_stroke_width)
        paintText.textSize = context.resources.getDimension(R.dimen.area_selector_handle_size)
    }

    private fun readScreenResolution() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            screenWidth = metrics.bounds.width(); screenHeight = metrics.bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels; screenHeight = dm.heightPixels
        }
    }

    private fun initDefaultRect() {
        val margin = context.resources.getDimension(R.dimen.area_selector_default_margin)
        val minSize = context.resources.getDimension(R.dimen.area_selector_min_size)
        rect = RectF(margin, margin,
            (screenWidth - margin).coerceAtLeast(margin + minSize),
            (screenHeight - margin).coerceAtLeast(margin + minSize))
    }

    fun setOnConfirm(listener: (RectF) -> Unit) { onConfirmListener = listener }
    fun setOnCancel(listener: () -> Unit) { onCancelListener = listener }
    fun setInitialRect(r: RectF) { rect = RectF(r); clampRectToScreen(); invalidate() }

    private fun startPulseAnimation() {
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 2000; anim.repeatCount = ValueAnimator.INFINITE; anim.repeatMode = ValueAnimator.REVERSE
        anim.addUpdateListener { animProgress = it.animatedFraction; invalidate() }
        anim.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 双指缩放
        if (event.pointerCount == 2) {
            return handlePinch(event)
        }
        return handleTouch(event)
    }

    private fun handlePinch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                initialSpan = getSpan(event)
                initialRect = RectF(rect)
                // 记录双指中心点
                pinchCenterX = (event.getX(0) + event.getX(1)) / 2f
                pinchCenterY = (event.getY(0) + event.getY(1)) / 2f
                isPinching = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPinching) return true
                val currentSpan = getSpan(event)
                val scale = (currentSpan / initialSpan).coerceIn(0.2f, 5f)
                val minSize = context.resources.getDimension(R.dimen.area_selector_min_size)
                val newW = (initialRect.width() * scale).coerceAtLeast(minSize)
                val newH = (initialRect.height() * scale).coerceAtLeast(minSize)

                // 比例锁定时保持宽高比
                val ratio = lockedRatio.ratio
                val (finalW, finalH) = if (ratio != null) {
                    if (newW / newH > ratio) Pair(newH * ratio, newH) else Pair(newW, newW / ratio)
                } else Pair(newW, newH)

                // 以双指中心点为基准缩放（更符合直觉）
                val cx = pinchCenterX; val cy = pinchCenterY
                // 计算初始中心到双指中心的偏移，缩放后保持相对位置
                val initCx = initialRect.centerX(); val initCy = initialRect.centerY()
                val offsetX = cx - initCx; val offsetY = cy - initCy
                val newCx = cx - offsetX * scale; val newCy = cy - offsetY * scale

                rect.left = newCx - finalW / 2; rect.right = newCx + finalW / 2
                rect.top = newCy - finalH / 2; rect.bottom = newCy + finalH / 2
                clampRectToScreen(); invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                isPinching = false; return true
            }
        }
        return false
    }

    private fun getSpan(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val btnBarY = height * 0.93f
                if (y > btnBarY) {
                    if (x < width * 0.08f) { showPresetMenu = !showPresetMenu; invalidate(); return true }
                    else if (x in width * 0.10f..width * 0.20f) { cycleRatio(); invalidate(); return true }
                    else if (x in width * 0.22f..width * 0.32f) { snapToCenter(); invalidate(); return true }
                    else if (x in width * 0.34f..width * 0.44f) { snapToEdge(); invalidate(); return true }
                    else if (x in width * 0.46f..width * 0.56f) { saveCurrentPreset(); invalidate(); return true }
                    else if (x < width * 0.70f) { onCancelListener?.invoke(); return true }
                    else { onConfirmListener?.invoke(RectF(rect)); return true }
                    return false
                }

                if (showPresetMenu) {
                    val presets = prefs.getAreaPresets()
                    val entryH = 42f; val menuBottom = height * 0.93f
                    val menuTop = menuBottom - presets.size * entryH - 8f
                    if (y in menuTop..menuBottom) {
                        val idx = ((y - menuTop) / entryH).toInt()
                        val name = presets.keys.toList().getOrNull(idx)
                        if (name != null) { presets[name]?.let { rect = RectF(it); clampRectToScreen(); showPresetMenu = false; invalidate() }; return true }
                    }
                    showPresetMenu = false; invalidate()
                }

                // 检测四角把手 (0-3)
                activeHandle = cornerHandles.indexOfFirst { it.contains(x, y) }
                if (activeHandle != -1) { lastX = x; lastY = y; isDragging = true; return true }
                // 检测四边把手 (4-7)
                val ehIdx = edgeHandles.indexOfFirst { it.contains(x, y) }
                if (ehIdx != -1) { activeHandle = ehIdx + 4; lastX = x; lastY = y; isDragging = true; return true }
                // 检测选区内部
                if (rect.contains(x, y)) { activeHandle = -1; lastX = x; lastY = y; isDragging = true; return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val dx = x - lastX; val dy = y - lastY
                when (activeHandle) {
                    0 -> { rect.left += dx; rect.top += dy }
                    1 -> { rect.right += dx; rect.top += dy }
                    2 -> { rect.left += dx; rect.bottom += dy }
                    3 -> { rect.right += dx; rect.bottom += dy }
                    4 -> { rect.top += dy }       // 上边把手：只调高度
                    5 -> { rect.right += dx }     // 右边把手：只调宽度
                    6 -> { rect.bottom += dy }    // 下边把手：只调高度
                    7 -> { rect.left += dx }      // 左边把手：只调宽度
                    -1 -> rect.offset(dx, dy)
                }
                applyRatioLock(activeHandle)
                applySnap()
                clampRectToScreen()
                lastX = x; lastY = y; invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                activeHandle = -1; isDragging = false; invalidate(); return true
            }
        }
        return false
    }

    private fun applyRatioLock(handle: Int) {
        val ratio = lockedRatio.ratio ?: return
        if (handle in 0..3) {
            when (handle) {
                0, 1 -> { rect.top = rect.bottom - rect.width() / ratio }
                2, 3 -> { rect.bottom = rect.top + rect.width() / ratio }
            }
        }
    }

    private fun applySnap() {
        val cx = rect.centerX(); val cy = rect.centerY()
        val scx = screenWidth / 2f; val scy = screenHeight / 2f
        if (abs(cx - scx) < snapThreshold) rect.offset(scx - cx, 0f)
        if (abs(cy - scy) < snapThreshold) rect.offset(0f, scy - cy)
        if (rect.left < snapThreshold) rect.offset(-rect.left, 0f)
        if (rect.right > screenWidth - snapThreshold) rect.offset(screenWidth - rect.right, 0f)
        if (rect.top < snapThreshold) rect.offset(0f, -rect.top)
        if (rect.bottom > screenHeight - snapThreshold) rect.offset(0f, screenHeight - rect.bottom)
    }

    private fun cycleRatio() {
        lockedRatio = when (lockedRatio) {
            AspectRatio.FREE -> AspectRatio.SQUARE
            AspectRatio.SQUARE -> AspectRatio.FOUR_THREE
            AspectRatio.FOUR_THREE -> AspectRatio.SIXTEEN_NINE
            AspectRatio.SIXTEEN_NINE -> AspectRatio.FREE
        }
        lockedRatio.ratio?.let { ratio ->
            rect.bottom = rect.top + rect.width() / ratio; clampRectToScreen()
        }
    }

    private fun snapToCenter() {
        val cx = screenWidth / 2f; val cy = screenHeight / 2f
        val hw = rect.width() / 2f; val hh = rect.height() / 2f
        rect = RectF(cx - hw, cy - hh, cx + hw, cy + hh); clampRectToScreen()
    }

    private fun snapToEdge() {
        val hw = rect.width() / 2f; val hh = rect.height() / 2f
        when {
            abs(rect.centerX() - hw) < 50 && abs(rect.centerY() - hh) < 50 -> rect.offset(screenWidth - rect.right - rect.left, 0f)
            rect.right > screenWidth - 50 && rect.top < 50 -> rect.offset(0f, screenHeight - rect.bottom)
            rect.right > screenWidth - 50 && rect.bottom > screenHeight - 50 -> snapToCenter()
            else -> rect = RectF(0f, 0f, hw * 2, hh * 2)
        }
        clampRectToScreen()
    }

    private fun saveCurrentPreset() {
        prefs.saveAreaPreset("预设_${System.currentTimeMillis() % 100000}", rect); invalidate()
    }

    private fun clampRectToScreen() {
        val minSize = context.resources.getDimension(R.dimen.area_selector_min_size)
        if (rect.left > rect.right - minSize) rect.left = rect.right - minSize
        if (rect.top > rect.bottom - minSize) rect.top = rect.bottom - minSize
        val maxW = screenWidth.toFloat(); val maxH = screenHeight.toFloat()
        val topMargin = 50f; val bottomMargin = maxH * 0.07f
        rect.left = rect.left.coerceIn(0f, maxW - minSize)
        rect.right = rect.right.coerceIn(minSize, maxW)
        rect.top = rect.top.coerceIn(topMargin, maxH - bottomMargin - minSize)
        rect.bottom = rect.bottom.coerceIn(topMargin + minSize, maxH - bottomMargin)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val baseSize = handleRadius

        canvas.drawColor(Color.parseColor("#40000000"))
        canvas.drawRect(rect, paintFill)

        if (isDragging || isPinching) {
            val scx = screenWidth / 2f; val scy = screenHeight / 2f
            if (abs(rect.centerX() - scx) < snapThreshold) canvas.drawLine(scx, 0f, scx, h, paintSnapLine)
            if (abs(rect.centerY() - scy) < snapThreshold) canvas.drawLine(0f, scy, w, scy, paintSnapLine)
        }

        paintBorder.alpha = (150 + (105 * animProgress)).toInt()
        canvas.drawRect(rect, paintBorder)

        // 四角把手
        cornerHandles.forEach { hdl ->
            canvas.drawCircle(hdl.centerX(), hdl.centerY(), handleRadius, paintHandle)
            canvas.drawCircle(hdl.centerX(), hdl.centerY(), handleRadius, paintHandleStroke)
        }
        // 四边中间把手
        edgeHandles.forEach { hdl ->
            canvas.drawRoundRect(hdl, 4f, 4f, paintEdgeHandle)
        }

        val cx = rect.centerX(); val cy = rect.centerY()
        paintText.textSize = baseSize * 0.8f; paintText.color = Color.WHITE
        canvas.drawText("╋", cx, cy + baseSize * 0.25f, paintText)

        val infoText = "选区 ${rect.width().toInt()}×${rect.height().toInt()} | 屏幕 ${screenWidth}×${screenHeight} | 比例 ${lockedRatio.label} | 双指缩放"
        paintText.textSize = baseSize * 0.6f
        val textW = paintText.measureText(infoText)
        val infoY = cy + baseSize * 1.5f
        canvas.drawRoundRect(cx - textW / 2f - baseSize, infoY - baseSize,
            cx + textW / 2f + baseSize, infoY + baseSize * 0.4f, baseSize * 0.6f, baseSize * 0.6f, paintInfoBg)
        canvas.drawText(infoText, cx, infoY, paintText)

        if (showPresetMenu) {
            val presets = prefs.getAreaPresets()
            val entryH = 36f; val menuW = 120f; val menuX = 10f
            val menuBottom = h * 0.93f; val menuTop = menuBottom - presets.size * entryH - 4f
            canvas.drawRoundRect(menuX - 4, menuTop - 4, menuX + menuW + 4, menuBottom + 4, 8f, 8f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EE333333"); style = Paint.Style.FILL })
            presets.entries.forEachIndexed { idx, entry ->
                val entryY = menuTop + idx * entryH
                paintText.textSize = 11f
                canvas.drawText(entry.key, menuX + menuW / 2, entryY + entryH * 0.65f, paintText)
            }
        }

        val btnBarY = h * 0.93f; val btnH = baseSize * 2.0f; val btnY = btnBarY
        paintTextBg.color = Color.parseColor("#CC333333")
        canvas.drawRoundRect(0f, h * 0.93f, w, h, 0f, 0f, paintTextBg)

        val btnLabels = listOf("预设", "比例", "居中", "贴边", "保存", "取消", "确定")
        val btnColors = listOf("#CC666666", "#CC666666", "#CC666666", "#CC666666", "#CC666666", "#CCFF4444", "#CC4CAF50")
        btnLabels.forEachIndexed { idx, label ->
            val btnX = w * (0.06f + idx * 0.125f); val bw = w * 0.10f
            paintTextBg.color = Color.parseColor(btnColors[idx])
            canvas.drawRoundRect(btnX - bw/2, btnY - btnH/2, btnX + bw/2, btnY + btnH/2, baseSize*0.6f, baseSize*0.6f, paintTextBg)
            paintText.textSize = baseSize * 0.55f; paintText.color = Color.WHITE
            canvas.drawText(label, btnX, btnY + baseSize * 0.2f, paintText)
        }

        paintTextBg.color = Color.parseColor("#CC000000")
        canvas.drawRoundRect(w*0.1f, baseSize, w*0.9f, baseSize*2.4f, baseSize*0.8f, baseSize*0.8f, paintTextBg)
        paintText.textSize = baseSize * 0.7f; paintText.color = Color.WHITE
        canvas.drawText("拖动调整 | 四边把手微调 | 双指缩放 | 比例锁/吸附", w/2, baseSize*1.9f, paintText)
    }
}
