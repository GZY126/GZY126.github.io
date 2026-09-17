package com.simpledrawbot.ai

import android.graphics.PointF
import com.simpledrawbot.model.Stroke
import kotlin.math.*

/**
 * 把 AI 返回的结构化绘图指令（draw_step）转换为笔画坐标
 * 支持的 shape 类型：
 * - circle：圆形，params: "centerX,centerY,radius"
 * - oval：椭圆，params: "centerX,centerY,rx,ry"
 * - arc：圆弧，params: "centerX,centerY,radius,startAngle,sweepAngle"
 * - line：直线，params: "x1,y1,x2,y2"
 * - curve：曲线，params: "x1,y1,cx1,cy1,cx2,cy2,x2,y2" (三次贝塞尔)
 * - rect：矩形，params: "left,top,right,bottom"
 * - triangle：三角形，params: "x1,y1,x2,y2,x3,y3"
 * - path：自由路径，params: "x1,y1,x2,y2,x3,y3,..."
 */
object ShapeToStrokeConverter {

    private const val SAMPLES_PER_CIRCLE = 40
    private const val SAMPLES_PER_ARC = 20
    private const val SAMPLES_PER_CURVE = 20

    fun convert(steps: List<AiDrawStep>): List<Stroke> {
        val strokes = mutableListOf<Stroke>()
        for (step in steps) {
            val stroke = convertStep(step)
            if (stroke != null && stroke.points.size >= 2) {
                strokes.add(stroke)
            }
        }
        return strokes
    }

    private fun convertStep(step: AiDrawStep): Stroke? {
        val params = parseParams(step.params)
        if (params.isEmpty()) return null

        return try {
            when (step.type.lowercase()) {
                "circle" -> makeCircle(params)
                "oval" -> makeOval(params)
                "arc" -> makeArc(params)
                "line" -> makeLine(params)
                "curve" -> makeCurve(params)
                "rect" -> makeRect(params)
                "triangle" -> makeTriangle(params)
                "path" -> makePath(params)
                else -> {
                    // 未知类型，尝试当作 path 处理
                    if (params.size >= 4) makePath(params) else null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ShapeConverter", "转换失败 step=${step.step} type=${step.type}", e)
            null
        }
    }

    /**
     * 解析参数字符串：支持 "0.5,0.3,0.15" 或 "centerX:0.5,centerY:0.3,radius:0.15"
     */
    private fun parseParams(raw: String): List<Float> {
        val cleaned = raw.replace(Regex("[a-zA-Z]+:"), "") // 去掉 "centerX:" 等前缀
        return cleaned.split(",", "，").mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun makeCircle(p: List<Float>): Stroke? {
        if (p.size < 3) return null
        val cx = p[0]; val cy = p[1]; val r = p[2]
        val pts = mutableListOf<PointF>()
        for (i in 0..SAMPLES_PER_CIRCLE) {
            val angle = 2.0 * PI * i / SAMPLES_PER_CIRCLE
            pts.add(PointF((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat()))
        }
        return Stroke(pts, true)
    }

    private fun makeOval(p: List<Float>): Stroke? {
        if (p.size < 4) return null
        val cx = p[0]; val cy = p[1]; val rx = p[2]; val ry = p[3]
        val pts = mutableListOf<PointF>()
        for (i in 0..SAMPLES_PER_CIRCLE) {
            val angle = 2.0 * PI * i / SAMPLES_PER_CIRCLE
            pts.add(PointF((cx + rx * cos(angle)).toFloat(), (cy + ry * sin(angle)).toFloat()))
        }
        return Stroke(pts, true)
    }

    private fun makeArc(p: List<Float>): Stroke? {
        if (p.size < 5) return null
        val cx = p[0]; val cy = p[1]; val r = p[2]
        val startDeg = p[3]; val sweepDeg = p[4]
        val startRad = Math.toRadians(startDeg.toDouble())
        val sweepRad = Math.toRadians(sweepDeg.toDouble())
        val pts = mutableListOf<PointF>()
        for (i in 0..SAMPLES_PER_ARC) {
            val angle = startRad + sweepRad * i / SAMPLES_PER_ARC
            pts.add(PointF((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat()))
        }
        return Stroke(pts, false)
    }

    private fun makeLine(p: List<Float>): Stroke? {
        if (p.size < 4) return null
        return Stroke(listOf(PointF(p[0], p[1]), PointF(p[2], p[3])), false)
    }

    private fun makeCurve(p: List<Float>): Stroke? {
        if (p.size < 8) return makeLine(p) // fallback to line
        val x0 = p[0]; val y0 = p[1]
        val cx1 = p[2]; val cy1 = p[3]
        val cx2 = p[4]; val cy2 = p[5]
        val x3 = p[6]; val y3 = p[7]
        val pts = mutableListOf<PointF>()
        for (i in 0..SAMPLES_PER_CURVE) {
            val t = i.toFloat() / SAMPLES_PER_CURVE
            val mt = 1f - t
            val x = mt * mt * mt * x0 + 3 * mt * mt * t * cx1 + 3 * mt * t * t * cx2 + t * t * t * x3
            val y = mt * mt * mt * y0 + 3 * mt * mt * t * cy1 + 3 * mt * t * t * cy2 + t * t * t * y3
            pts.add(PointF(x, y))
        }
        return Stroke(pts, false)
    }

    private fun makeRect(p: List<Float>): Stroke? {
        if (p.size < 4) return null
        val l = p[0]; val t = p[1]; val r = p[2]; val b = p[3]
        return Stroke(
            listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b)),
            true
        )
    }

    private fun makeTriangle(p: List<Float>): Stroke? {
        if (p.size < 6) return null
        return Stroke(
            listOf(PointF(p[0], p[1]), PointF(p[2], p[3]), PointF(p[4], p[5])),
            true
        )
    }

    private fun makePath(p: List<Float>): Stroke? {
        if (p.size < 4) return null
        val pts = mutableListOf<PointF>()
        for (i in 0 until p.size - 1 step 2) {
            pts.add(PointF(p[i], p[i + 1]))
        }
        return Stroke(pts, false)
    }
}
