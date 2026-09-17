package com.simpledrawbot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * v3.1 单元测试
 * - DrawMode 枚举
 * - 长笔画拆分阈值
 * - 采样间距
 * - 笔画长度
 */
class DrawingServiceTest {

    // ================================================================
    //  DrawMode 枚举
    // ================================================================

    @Test
    fun drawMode_auto_overlayCount_2() {
        assertEquals(2, DrawingAccessibilityService.DrawMode.AUTO.overlayCount)
    }

    @Test
    fun drawMode_precise_overlayCount_1() {
        assertEquals(1, DrawingAccessibilityService.DrawMode.PRECISE.overlayCount)
    }

    @Test
    fun drawMode_auto_speed_06() {
        assertEquals(0.6f, DrawingAccessibilityService.DrawMode.AUTO.defaultSpeed)
    }

    @Test
    fun drawMode_precise_speed_10() {
        assertEquals(1.0f, DrawingAccessibilityService.DrawMode.PRECISE.defaultSpeed)
    }

    @Test
    fun drawMode_auto_label() {
        assertEquals("自动加粗", DrawingAccessibilityService.DrawMode.AUTO.label)
    }

    @Test
    fun drawMode_precise_label() {
        assertEquals("单线精准", DrawingAccessibilityService.DrawMode.PRECISE.label)
    }

    // ================================================================
    //  长笔画拆分
    // ================================================================

    @Test
    fun splitThreshold_below300px_noSplit() {
        val len = 250f
        val segments = if (len > 300f) {
            (len / (300f * 0.8f)).toInt().coerceIn(2, 3)
        } else 1
        assertEquals(1, segments)
    }

    @Test
    fun splitThreshold_above300px_splits() {
        val len = 500f
        val segments = (len / (300f * 0.8f)).toInt().coerceIn(2, 3)
        assertEquals(2, segments)
    }

    @Test
    fun splitThreshold_veryLong_max3() {
        val len = 3000f
        val segments = (len / (300f * 0.8f)).toInt().coerceIn(2, 3)
        assertEquals(3, segments)
    }

    // ================================================================
    //  采样
    // ================================================================

    @Test
    fun sample_straightLine_consistentSpacing() {
        val points = sampleLine(0f, 0f, 100f, 0f, step = 2f)
        assertTrue("Expected >= 50, got ${points.size}", points.size >= 50)
        for (i in 0 until points.size - 1) {
            val (x1, y1) = points[i]; val (x2, y2) = points[i + 1]
            val d = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
            assertTrue("Distance $d > 3", d <= 3.0)
        }
    }

    @Test
    fun sample_empty_returnsEmpty() {
        assertEquals(0, sampleLine(0f, 0f, 0f, 0f, step = 2f).size)
    }

    @Test
    fun sample_short_returnsAtLeastTwo() {
        val points = sampleLine(0f, 0f, 3f, 0f, step = 2f)
        assertTrue("Expected >= 2, got ${points.size}", points.size >= 2)
    }

    // ================================================================
    //  笔画长度
    // ================================================================

    @Test
    fun strokeLength_horizontal() {
        val points = listOf(0f to 0f, 100f to 0f, 200f to 0f)
        assertEquals(200f, strokeLength(points), 0.1f)
    }

    @Test
    fun strokeLength_diagonal() {
        val points = listOf(0f to 0f, 100f to 100f)
        assertEquals(141.42f, strokeLength(points), 0.1f)
    }

    // ================================================================
    //  自适应粗细（保留算法验证）
    // ================================================================

    @Test
    fun adaptiveWidth_isolated_full() {
        val strokes = listOf(
            listOf(100f to 100f, 200f to 200f),
            listOf(500f to 500f, 600f to 600f)
        )
        val widths = computeAdaptiveWidths(strokes, rectSize = 1000f, baseWidth = 4f)
        assertEquals(4f, widths[0], 0.01f)
        assertEquals(4f, widths[1], 0.01f)
    }

    @Test
    fun adaptiveWidth_dense_reduced() {
        val strokes = listOf(
            listOf(100f to 100f, 150f to 100f),
            listOf(105f to 105f, 155f to 105f),
            listOf(110f to 110f, 160f to 110f)
        )
        val widths = computeAdaptiveWidths(strokes, rectSize = 1000f, baseWidth = 4f)
        widths.forEach { assertTrue("Expected ~1.96, got $it", it in 1.9f..2.0f) }
    }

    // ================================================================
    //  辅助
    // ================================================================

    private data class Meta(val cx: Float, val cy: Float, val len: Float)

    private fun computeAdaptiveWidths(
        strokes: List<List<Pair<Float, Float>>>, rectSize: Float, baseWidth: Float
    ): List<Float> {
        if (baseWidth <= 1f || strokes.size <= 1) return strokes.map { baseWidth }
        val metas = strokes.map { pts ->
            val xs = pts.map { it.first }; val ys = pts.map { it.second }
            val cx = (xs.min() + xs.max()) / 2f
            val cy = (ys.min() + ys.max()) / 2f
            val len = strokeLength(pts)
            Meta(cx, cy, len)
        }
        val sr = rectSize * 0.08f
        return strokes.indices.map { i ->
            val m = metas[i]
            val nearby = metas.indices.count { j ->
                j != i && hypot((m.cx - metas[j].cx).toDouble(), (m.cy - metas[j].cy).toDouble()) < sr
            }
            val df = when { nearby <= 1 -> 1.0f; nearby <= 3 -> 0.7f; else -> 0.5f }
            val lf = if (m.len < 80f) 0.7f else 1.0f
            (baseWidth * df * lf).coerceAtLeast(1f)
        }
    }

    private fun strokeLength(pts: List<Pair<Float, Float>>): Float =
        pts.zipWithNext { a, b ->
            hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble())
        }.sum().toFloat()

    private fun sampleLine(
        x1: Float, y1: Float, x2: Float, y2: Float, step: Float
    ): List<Pair<Float, Float>> {
        val totalLen = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        if (totalLen <= 0) return emptyList()
        val result = mutableListOf<Pair<Float, Float>>()
        var dist = 0f
        while (dist <= totalLen) {
            val t = (dist / totalLen).coerceIn(0f, 1f)
            result.add(x1 + (x2 - x1) * t to y1 + (y2 - y1) * t)
            dist += step
        }
        return result
    }
}
