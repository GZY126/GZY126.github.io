package com.simpledrawbot.ai

import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import com.simpledrawbot.model.Stroke
import java.util.Stack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * SVG path 解析器
 * 将 SVG path 的 d 属性解析为笔画坐标（归一化 0~1）
 *
 * 支持 M/m L/l H/h V/v C/c S/s Q/q T/t A/a Z 命令
 */
object SvgPathParser {

    private const val TAG = "SvgPathParser"
    private const val SAMPLE_DENSITY = 0.02f // 每 0.02 长度采样一个点

    /**
     * 解析 SVG path 字符串，返回笔画列表（坐标归一化到 0~1）
     * @param d SVG path 的 d 属性，如 "M 50 20 C 60 20 70 30 70 40 Z"
     * @param scaleX x 坐标缩放因子，默认 0.01 表示 0~100 范围缩放到 0~1
     * @param scaleY y 坐标缩放因子
     */
    fun parse(d: String, scaleX: Float = 0.01f, scaleY: Float = 0.01f): List<Stroke> {
        val strokes = mutableListOf<Stroke>()
        try {
            val tokens = tokenize(d)
            if (tokens.isEmpty()) return strokes

            var currentX = 0f
            var currentY = 0f
            var startX = 0f
            var startY = 0f
            var lastControlX = 0f
            var lastControlY = 0f
            var lastCmd = ""
            var currentPoints = mutableListOf<PointF>()
            val hasContent = { currentPoints.isNotEmpty() }

            fun flush(closePath: Boolean) {
                if (currentPoints.isNotEmpty()) {
                    strokes.add(Stroke(currentPoints.toList(), closePath))
                    currentPoints = mutableListOf()
                }
            }

            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]

                if (token.isCommand()) {
                    when (token.uppercase()) {
                        "M" -> {
                            // 起始点：先 flush 前一笔
                            flush(false)
                            lastCmd = token.uppercase()
                            i++
                            if (i + 1 < tokens.size) {
                                currentX = tokens[i].toFloat() * scaleX
                                currentY = tokens[i + 1].toFloat() * scaleY
                                startX = currentX
                                startY = currentY
                                currentPoints.add(PointF(currentX, currentY))
                                i += 2
                                // 隐含的 L
                                while (i + 1 < tokens.size && !tokens[i].isCommand()) {
                                    currentX = tokens[i].toFloat() * scaleX
                                    currentY = tokens[i + 1].toFloat() * scaleY
                                    currentPoints.add(PointF(currentX, currentY))
                                    i += 2
                                }
                            }
                        }
                        "L" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i + 1 < tokens.size && !tokens[i].isCommand()) {
                                if (token.uppercase() == "L") {
                                    currentX = tokens[i].toFloat() * scaleX
                                    currentY = tokens[i + 1].toFloat() * scaleY
                                } else {
                                    currentX += tokens[i].toFloat() * scaleX
                                    currentY += tokens[i + 1].toFloat() * scaleY
                                }
                                currentPoints.add(PointF(currentX, currentY))
                                i += 2
                            }
                        }
                        "H" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i < tokens.size && !tokens[i].isCommand()) {
                                currentX = if (token.uppercase() == "H") {
                                    tokens[i].toFloat() * scaleX
                                } else {
                                    currentX + tokens[i].toFloat() * scaleX
                                }
                                currentPoints.add(PointF(currentX, currentY))
                                i++
                            }
                        }
                        "V" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i < tokens.size && !tokens[i].isCommand()) {
                                currentY = if (token.uppercase() == "V") {
                                    tokens[i].toFloat() * scaleX
                                } else {
                                    currentY + tokens[i].toFloat() * scaleX
                                }
                                currentPoints.add(PointF(currentX, currentY))
                                i++
                            }
                        }
                        "C" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i + 5 < tokens.size && !tokens[i].isCommand()) {
                                val (x1, y1, x2, y2, ex, ey) = if (token.uppercase() == "C") {
                                    Six(
                                        tokens[i].toFloat() * scaleX,
                                        tokens[i + 1].toFloat() * scaleY,
                                        tokens[i + 2].toFloat() * scaleX,
                                        tokens[i + 3].toFloat() * scaleY,
                                        tokens[i + 4].toFloat() * scaleX,
                                        tokens[i + 5].toFloat() * scaleY
                                    )
                                } else {
                                    Six(
                                        currentX + tokens[i].toFloat() * scaleX,
                                        currentY + tokens[i + 1].toFloat() * scaleY,
                                        currentX + tokens[i + 2].toFloat() * scaleX,
                                        currentY + tokens[i + 3].toFloat() * scaleY,
                                        currentX + tokens[i + 4].toFloat() * scaleX,
                                        currentY + tokens[i + 5].toFloat() * scaleY
                                    )
                                }
                                lastControlX = x2
                                lastControlY = y2
                                sampleCubicBezier(currentX, currentY, x1, y1, x2, y2, ex, ey, currentPoints)
                                currentX = ex
                                currentY = ey
                                i += 6
                            }
                        }
                        "S" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i + 3 < tokens.size && !tokens[i].isCommand()) {
                                val reflectX = if (lastCmd == "C" || lastCmd == "S") {
                                    2 * currentX - lastControlX
                                } else currentX
                                val reflectY = if (lastCmd == "C" || lastCmd == "S") {
                                    2 * currentY - lastControlY
                                } else currentY
                                val (x2, y2, ex, ey) = if (token.uppercase() == "S") {
                                    Quad(
                                        tokens[i].toFloat() * scaleX,
                                        tokens[i + 1].toFloat() * scaleY,
                                        tokens[i + 2].toFloat() * scaleX,
                                        tokens[i + 3].toFloat() * scaleY
                                    )
                                } else {
                                    Quad(
                                        currentX + tokens[i].toFloat() * scaleX,
                                        currentY + tokens[i + 1].toFloat() * scaleY,
                                        currentX + tokens[i + 2].toFloat() * scaleX,
                                        currentY + tokens[i + 3].toFloat() * scaleY
                                    )
                                }
                                lastControlX = x2
                                lastControlY = y2
                                sampleCubicBezier(currentX, currentY, reflectX, reflectY, x2, y2, ex, ey, currentPoints)
                                currentX = ex
                                currentY = ey
                                lastCmd = "S"
                                i += 4
                            }
                        }
                        "Q" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i + 3 < tokens.size && !tokens[i].isCommand()) {
                                val (cx, cy, ex, ey) = if (token.uppercase() == "Q") {
                                    Quad(
                                        tokens[i].toFloat() * scaleX,
                                        tokens[i + 1].toFloat() * scaleY,
                                        tokens[i + 2].toFloat() * scaleX,
                                        tokens[i + 3].toFloat() * scaleY
                                    )
                                } else {
                                    Quad(
                                        currentX + tokens[i].toFloat() * scaleX,
                                        currentY + tokens[i + 1].toFloat() * scaleY,
                                        currentX + tokens[i + 2].toFloat() * scaleX,
                                        currentY + tokens[i + 3].toFloat() * scaleY
                                    )
                                }
                                lastControlX = cx
                                lastControlY = cy
                                sampleQuadBezier(currentX, currentY, cx, cy, ex, ey, currentPoints)
                                currentX = ex
                                currentY = ey
                                i += 4
                            }
                        }
                        "T" -> {
                            lastCmd = token.uppercase()
                            i++
                            while (i + 1 < tokens.size && !tokens[i].isCommand()) {
                                val reflectX = if (lastCmd == "Q" || lastCmd == "T") {
                                    2 * currentX - lastControlX
                                } else currentX
                                val reflectY = if (lastCmd == "Q" || lastCmd == "T") {
                                    2 * currentY - lastControlY
                                } else currentY
                                val ex = if (token.uppercase() == "T") tokens[i].toFloat() * scaleX else currentX + tokens[i].toFloat() * scaleX
                                val ey = if (token.uppercase() == "T") tokens[i + 1].toFloat() * scaleY else currentY + tokens[i + 1].toFloat() * scaleY
                                lastControlX = reflectX
                                lastControlY = reflectY
                                sampleQuadBezier(currentX, currentY, reflectX, reflectY, ex, ey, currentPoints)
                                currentX = ex
                                currentY = ey
                                lastCmd = "T"
                                i += 2
                            }
                        }
                        "A" -> {
                            lastCmd = token.uppercase()
                            i++
                            // A rx ry x-rotation large-arc-flag sweep-flag ex ey
                            while (i + 6 < tokens.size && !tokens[i].isCommand()) {
                                val rx = tokens[i].toFloat() * scaleX
                                val ry = tokens[i + 1].toFloat() * scaleY
                                val ex = if (token.uppercase() == "A") tokens[i + 5].toFloat() * scaleY else currentX + tokens[i + 5].toFloat() * scaleY
                                val ey = if (token.uppercase() == "A") tokens[i + 6].toFloat() * scaleY else currentY + tokens[i + 6].toFloat() * scaleY
                                // 简化：用直线近似弧线（多点采样）
                                val steps = maxOf(4, (rx + ry).toInt())
                                for (s in 1..steps) {
                                    val t = s.toFloat() / steps
                                    currentPoints.add(PointF(
                                        currentX + (ex - currentX) * t,
                                        currentY + (ey - currentY) * t
                                    ))
                                }
                                currentX = ex
                                currentY = ey
                                i += 7
                            }
                        }
                        "Z" -> {
                            flush(true)
                            currentX = startX
                            currentY = startY
                            lastCmd = "Z"
                            i++
                        }
                    }
                } else {
                    i++
                }
            }
            flush(false)
        } catch (e: Exception) {
            Log.e(TAG, "SVG path 解析失败: ${e.message}", e)
        }
        return strokes
    }

    private data class Six(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float)
    private data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private fun sampleCubicBezier(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        out: MutableList<PointF>
    ) {
        val length = approxBezierLength(x0, y0, x1, y1, x2, y2, x3, y3)
        val steps = maxOf(4, (length / SAMPLE_DENSITY).toInt())
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val mt = 1 - t
            val x = mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3
            val y = mt * mt * mt * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y3
            out.add(PointF(x, y))
        }
    }

    private fun sampleQuadBezier(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        out: MutableList<PointF>
    ) {
        val length = approxQuadLength(x0, y0, x1, y1, x2, y2)
        val steps = maxOf(4, (length / SAMPLE_DENSITY).toInt())
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val mt = 1 - t
            val x = mt * mt * x0 + 2 * mt * t * x1 + t * t * x2
            val y = mt * mt * y0 + 2 * mt * t * y1 + t * t * y2
            out.add(PointF(x, y))
        }
    }

    private fun approxBezierLength(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float
    ): Float {
        val dx1 = x1 - x0
        val dy1 = y1 - y0
        val dx2 = x2 - x1
        val dy2 = y2 - y1
        val dx3 = x3 - x2
        val dy3 = y3 - y2
        val chord = abs(x3 - x0) + abs(y3 - y0)
        val poly = abs(dx1) + abs(dy1) + abs(dx2) + abs(dy2) + abs(dx3) + abs(dy3)
        return (chord + poly) / 2
    }

    private fun approxQuadLength(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val chord = abs(x2 - x0) + abs(y2 - y0)
        val poly = abs(x1 - x0) + abs(y1 - y0) + abs(x2 - x1) + abs(y2 - y1)
        return (chord + poly) / 2
    }

    private fun tokenize(d: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        val chars = d.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                }
                c.isLetter() -> {
                    if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                    result.add(c.toString())
                }
                c == ',' -> {
                    if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                }
                c == '-' || c == '+' -> {
                    if (sb.isNotEmpty() && sb.last() != 'e' && sb.last() != 'E') {
                        result.add(sb.toString()); sb.clear()
                    }
                    sb.append(c)
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    private fun String.isCommand(): Boolean {
        return length == 1 && first().isLetter()
    }
}
