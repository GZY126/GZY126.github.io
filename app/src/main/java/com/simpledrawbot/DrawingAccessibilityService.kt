package com.simpledrawbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.simpledrawbot.model.DrawTemplate
import kotlin.math.*

/**
 * v3.1: 纯中心线 + 叠画模拟粗线
 *
 * 认知修正：
 *   - dispatchGesture 不带压感，慢速不会让笔触变粗
 *   - getFillPath 膨胀 = 闭合轮廓 = 多余闭合的万恶之源
 *
 * 两个模式全部基于原始中心线，零膨胀、零闭合：
 *   AUTO:   同一条中心线叠画 2 遍，横向偏移 1px，0.6x 速度
 *   PRECISE: 只画 1 遍中心线，1.0x 全速，粗细交给目标应用
 */
class DrawingAccessibilityService : AccessibilityService() {

    companion object {
        var instance: DrawingAccessibilityService? = null
            private set

        private const val DEFAULT_DURATION_SECONDS = 30
        private const val MIN_STROKE_DURATION_MS = 150L
        private const val MAX_STROKE_DURATION_MS = 4000L
        private const val BASE_INTER_STROKE_DELAY_MS = 100L
        private const val MAX_INTER_STROKE_DELAY_MS = 500L

        /** 长笔画拆分阈值 */
        private const val LONG_SPLIT_THRESHOLD = 300f

        /** AUTO 模式叠画偏移量 (px) */
        private const val AUTO_OVERLAY_OFFSET_X = 1f
        private const val AUTO_OVERLAY_OFFSET_Y = 0f

        @Volatile var speedMultiplier: Float = 1.0f
        @Volatile var drawMode: DrawMode = DrawMode.AUTO
    }

    enum class DrawMode(
        val label: String,
        val description: String,
        val defaultSpeed: Float,
        val overlayCount: Int  // 叠画遍数
    ) {
        AUTO("自动加粗", "叠画2遍，线条饱满", 0.6f, 2),
        PRECISE("单线精准", "单线全速，配合目标画笔", 1.0f, 1)
    }

    interface DrawProgressCallback {
        fun onProgress(current: Int, total: Int)
        fun onStateChanged(isPaused: Boolean)
    }

    enum class SortMode { TSP, TOP_TO_BOTTOM, NONE }

    // ---- 状态 ----
    private var isCanceled = false
    @Volatile private var isPaused = false
    private var currentCallback: ((Boolean) -> Unit)? = null
    private var progressCallback: DrawProgressCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pausedIndex = 0
    private var pausedPaths: List<Path>? = null
    private var pausedDurations: List<Long>? = null
    private var pausedInterStrokeDelays: List<Long>? = null
    private var pausedRandom: java.util.Random? = null
    private var pausedModeSpeed: Float = 1.0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    // ================================================================
    //  startDrawing
    // ================================================================

    fun startDrawing(
        template: DrawTemplate,
        rect: RectF,
        durationSeconds: Int = DEFAULT_DURATION_SECONDS,
        callback: (Boolean) -> Unit,
        progressCb: DrawProgressCallback? = null,
        sortMode: SortMode = SortMode.TSP
    ) {
        isCanceled = false; isPaused = false
        currentCallback = callback; progressCallback = progressCb

        val drawWidth = rect.width()
        val drawHeight = rect.height()
        if (drawWidth <= 0 || drawHeight <= 0) { currentCallback?.invoke(false); return }

        val random = java.util.Random()

        // 归一化 → 屏幕坐标
        val rawStrokes = template.strokes.map { s ->
            s.points.map { PointF(rect.left + it.x * drawWidth, rect.top + it.y * drawHeight) }
        }.filter { it.size >= 2 }
        if (rawStrokes.isEmpty()) { currentCallback?.invoke(false); return }

        // 排序
        val orderedStrokes = when (sortMode) {
            SortMode.TSP -> greedyTspSort(rawStrokes)
            SortMode.TOP_TO_BOTTOM -> topToBottomSort(rawStrokes)
            SortMode.NONE -> rawStrokes
        }

        // 生成绘制 Path 列表
        // AUTO: 每条中心线生成 2 个 Path（原线 + 偏移 1px）
        // PRECISE: 每条中心线生成 1 个 Path
        val drawPaths = mutableListOf<Path>()
        for (stroke in orderedStrokes) {
            val centerPaths = strokeToSplitPaths(stroke)
            if (drawMode == DrawMode.AUTO) {
                // 叠画 2 遍：原线 + 横向偏移 1px
                for (cp in centerPaths) {
                    drawPaths.add(cp)  // 第 1 遍：原始
                    drawPaths.add(offsetPath(cp, AUTO_OVERLAY_OFFSET_X, AUTO_OVERLAY_OFFSET_Y))  // 第 2 遍：偏移
                }
            } else {
                // PRECISE: 只画 1 遍
                drawPaths.addAll(centerPaths)
            }
        }

        if (drawPaths.isEmpty()) { currentCallback?.invoke(false); return }

        // 时间分配
        val pathLengths = drawPaths.map { PathMeasure(it, false).length }
        val interStrokeDelays = calculateInterStrokeDelays(drawPaths)
        val strokeDurations = calculateStrokeDurations(
            pathLengths, durationSeconds * 1000L, interStrokeDelays.sum()
        )

        val modeSpeed = drawMode.defaultSpeed

        progressCallback?.onProgress(0, drawPaths.size)
        executePath(0, drawPaths, strokeDurations, interStrokeDelays, random, modeSpeed)
    }

    fun stopDrawing() {
        isCanceled = true; isPaused = false; pausedPaths = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun pauseDrawing() { isPaused = true; progressCallback?.onStateChanged(true) }

    fun resumeDrawing() {
        if (!isPaused) return
        isPaused = false; progressCallback?.onStateChanged(false)
        val idx = pausedIndex
        val paths = pausedPaths
        val durations = pausedDurations
        val delays = pausedInterStrokeDelays
        val random = pausedRandom
        val speed = pausedModeSpeed
        if (paths != null && durations != null && delays != null && random != null) {
            executePath(idx, paths, durations, delays, random, speed)
        }
    }

    val isDrawingPaused: Boolean get() = isPaused

    // ================================================================
    //  排序
    // ================================================================

    private fun greedyTspSort(strokes: List<List<PointF>>): List<List<PointF>> {
        if (strokes.size <= 2) return strokes
        val remaining = strokes.toMutableList()
        val ordered = mutableListOf<List<PointF>>()
        var cur = remaining.minByOrNull { s -> s.first().x + s.first().y } ?: strokes.first()
        remaining.remove(cur); ordered.add(cur)
        while (remaining.isNotEmpty()) {
            val end = cur.last()
            cur = remaining.minByOrNull { s ->
                val st = s.first(); hypot(st.x - end.x, st.y - end.y)
            } ?: remaining.first()
            remaining.remove(cur); ordered.add(cur)
        }
        return ordered
    }

    private fun topToBottomSort(strokes: List<List<PointF>>): List<List<PointF>> =
        strokes.sortedWith(compareBy({ it.first().y }, { it.first().x }))

    // ================================================================
    //  中心线 → Path（含长笔画拆分）
    // ================================================================

    private fun strokeToSplitPaths(points: List<PointF>): List<Path> {
        val fullPath = pointsToPath(points)
        val pm = PathMeasure(fullPath, false)
        val totalLen = pm.length
        if (totalLen <= LONG_SPLIT_THRESHOLD) return listOf(fullPath)

        val segments = (totalLen / (LONG_SPLIT_THRESHOLD * 0.8f)).toInt().coerceIn(2, 3)
        val segLen = totalLen / segments
        val result = mutableListOf<Path>()
        for (s in 0 until segments) {
            val segPath = Path()
            pm.getSegment(s * segLen, ((s + 1) * segLen).coerceAtMost(totalLen), segPath, true)
            result.add(segPath)
        }
        return result
    }

    private fun pointsToPath(points: List<PointF>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        if (points.size > 3) {
            for (i in 1 until points.size - 1) {
                val mx = (points[i].x + points[i + 1].x) / 2f
                val my = (points[i].y + points[i + 1].y) / 2f
                path.quadTo(points[i].x, points[i].y, mx, my)
            }
            path.lineTo(points.last().x, points.last().y)
        } else {
            for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        }
        return path
    }

    /** 对 Path 做整体平移（返回新 Path） */
    private fun offsetPath(src: Path, dx: Float, dy: Float): Path {
        val dst = Path()
        val m = Matrix().apply { postTranslate(dx, dy) }
        src.transform(m, dst)
        return dst
    }

    // ================================================================
    //  时间计算
    // ================================================================

    private fun calculateInterStrokeDelays(paths: List<Path>): List<Long> {
        val delays = mutableListOf<Long>()
        for (i in 0 until paths.size - 1) {
            val pm1 = PathMeasure(paths[i], false)
            val pm2 = PathMeasure(paths[i + 1], false)
            val end = FloatArray(2); val start = FloatArray(2)
            pm1.getPosTan(pm1.length, end, null)
            pm2.getPosTan(0f, start, null)
            val d = hypot((start[0] - end[0]).toDouble(), (start[1] - end[1]).toDouble())
            delays.add((BASE_INTER_STROKE_DELAY_MS + d * 0.5f).toLong()
                .coerceIn(BASE_INTER_STROKE_DELAY_MS, MAX_INTER_STROKE_DELAY_MS))
        }
        delays.add(0L); return delays
    }

    private fun calculateStrokeDurations(
        pathLengths: List<Float>, totalMs: Long, delayBudget: Long
    ): List<Long> {
        if (pathLengths.isEmpty()) return emptyList()
        val available = max(totalMs - delayBudget, pathLengths.size * MIN_STROKE_DURATION_MS)
        val weights = pathLengths.map { sqrt(it.coerceAtLeast(1f).toDouble()) }
        val tw = weights.sum()
        if (tw <= 0) return pathLengths.map { MIN_STROKE_DURATION_MS }
        return weights.map { w ->
            ((available * w / tw).toLong()).coerceIn(MIN_STROKE_DURATION_MS, MAX_STROKE_DURATION_MS)
        }
    }

    // ================================================================
    //  核心：Path 直接传 dispatchGesture
    // ================================================================

    private fun executePath(
        index: Int,
        paths: List<Path>,
        durations: List<Long>,
        interStrokeDelays: List<Long>,
        random: java.util.Random,
        modeSpeed: Float
    ) {
        if (isCanceled || index >= paths.size) {
            if (!isCanceled) currentCallback?.invoke(true)
            progressCallback?.onProgress(paths.size, paths.size); return
        }
        if (isPaused) {
            pausedIndex = index; pausedPaths = paths; pausedDurations = durations
            pausedInterStrokeDelays = interStrokeDelays; pausedRandom = random
            pausedModeSpeed = modeSpeed; return
        }

        val path = paths[index]
        val userSpeed = 1.0f / speedMultiplier.coerceIn(0.25f, 4.0f)
        val combined = (modeSpeed * userSpeed).coerceIn(0.15f, 3.0f)

        val totalDuration = durations.getOrElse(index) { MIN_STROKE_DURATION_MS }
            .let { (it / combined * (0.85 + random.nextFloat() * 0.3)).toLong() }
            .coerceIn(MIN_STROKE_DURATION_MS / 2, MAX_STROKE_DURATION_MS * 2)

        val gb = GestureDescription.Builder()
        gb.addStroke(GestureDescription.StrokeDescription(path, 0, totalDuration))

        val cb = object : GestureResultCallback() {
            override fun onCompleted(desc: GestureDescription?) {
                super.onCompleted(desc)
                if (isCanceled) return
                progressCallback?.onProgress(index + 1, paths.size)
                val delay = interStrokeDelays.getOrElse(index) { BASE_INTER_STROKE_DELAY_MS }
                    .let { (it / combined).toLong() + random.nextInt(80).toLong() }
                mainHandler.postDelayed({
                    if (!isCanceled) executePath(index + 1, paths, durations, interStrokeDelays, random, modeSpeed)
                }, delay)
            }
            override fun onCancelled(desc: GestureDescription?) {
                super.onCancelled(desc)
                if (!isCanceled) currentCallback?.invoke(false)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dispatchGesture(gb.build(), cb, null)
        }
    }
}
