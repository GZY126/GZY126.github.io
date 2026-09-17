package com.simpledrawbot.drawing

import android.graphics.PointF
import com.simpledrawbot.model.DrawTemplate
import com.simpledrawbot.model.Stroke
import kotlin.math.*

/**
 * 简笔画模板库：所有归一化坐标(0~1)
 * 每个模板由多笔笔画组成，每笔笔画由一系列点连接而成
 */
object TemplateLibrary {

    /** 在圆周上等距取点 */
    private fun circlePoints(
        cx: Float, cy: Float, r: Float, count: Int = 40,
        startAngle: Double = 0.0, endAngle: Double = 2 * PI
    ): List<PointF> {
        return (0..count).map { i ->
            val a = startAngle + (endAngle - startAngle) * i / count
            PointF(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
    }

    /** 两点连线 */
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float): Stroke =
        Stroke(listOf(PointF(x1, y1), PointF(x2, y2)))

    /** 圆弧 */
    private fun arcStroke(
        cx: Float, cy: Float, r: Float, count: Int = 25,
        startAngle: Double = 0.0, endAngle: Double = 2 * PI,
        closePath: Boolean = false
    ): Stroke = Stroke(circlePoints(cx, cy, r, count, startAngle, endAngle), closePath)

    /** 完整圆 */
    private fun circle(cx: Float, cy: Float, r: Float): Stroke =
        arcStroke(cx, cy, r, closePath = true)

    private fun circle(
        cx: Float, cy: Float, r: Float, closePath: Boolean
    ): Stroke = arcStroke(cx, cy, r, closePath = closePath)

    private fun bezier2Points(
        p0: PointF, p1: PointF, p2: PointF, count: Int = 20
    ): List<PointF> {
        return (0..count).map { i ->
            val t = i.toFloat() / count
            val u = 1 - t
            PointF(
                u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x,
                u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y
            )
        }
    }

    private fun polygonStroke(points: List<Pair<Float, Float>>, close: Boolean = true): Stroke =
        Stroke(points.map { PointF(it.first, it.second) }, close)

    // ================================================================
    //  水果类
    // ================================================================

    val 西瓜 = DrawTemplate("西瓜", listOf("西瓜", "watermelon"), listOf(
        // 外皮大圆
        arcStroke(0.5f, 0.55f, 0.42f, endAngle = PI, closePath = false),
        line(0.08f, 0.55f, 0.92f, 0.55f),
        // 瓜皮条纹 - 几条竖弧线
        *bezierStrokesForWatermelon(),
        // 瓜蒂
        line(0.5f, 0.08f, 0.5f, 0.16f),
        // 小叶子
        Stroke(listOf(PointF(0.5f, 0.16f), PointF(0.58f, 0.10f), PointF(0.62f, 0.14f), PointF(0.56f, 0.18f)), closePath = true)
    ))

    private fun bezierStrokesForWatermelon(): Array<Stroke> {
        // 西瓜的深色条纹
        val stripes = listOf(
            Triple(0.22f, 0.42f, -0.12f),
            Triple(0.38f, 0.36f, -0.05f),
            Triple(0.62f, 0.36f, 0.05f),
            Triple(0.78f, 0.42f, 0.12f)
        )
        return stripes.map { (cx, topY, curve) ->
            Stroke((0..15).map { i ->
                val t = i / 15f
                val x = cx + curve * sin(t * PI).toFloat()
                val y = topY + (0.95f - topY) * t
                PointF(x, y)
            })
        }.toTypedArray()
    }

    val 苹果 = DrawTemplate("苹果", listOf("苹果", "apple"), listOf(
        circle(0.5f, 0.55f, 0.35f, closePath = true),
        line(0.5f, 0.20f, 0.5f, 0.26f),
        Stroke(listOf(PointF(0.5f, 0.26f), PointF(0.58f, 0.20f), PointF(0.63f, 0.24f), PointF(0.56f, 0.29f)), closePath = true),
        arcStroke(0.5f, 0.32f, 0.08f, count = 12, startAngle = 0.0, endAngle = PI, closePath = false)
    ))

    val 草莓 = DrawTemplate("草莓", listOf("草莓", "strawberry"), listOf(
        polygonStroke(listOf(0.5f to 0.12f, 0.8f to 0.6f, 0.75f to 0.98f, 0.25f to 0.98f, 0.2f to 0.6f)),
        // 叶子
        Stroke(listOf(PointF(0.5f, 0.12f), PointF(0.35f, 0.08f), PointF(0.42f, 0.02f), PointF(0.5f, 0.12f)), closePath = true),
        Stroke(listOf(PointF(0.5f, 0.12f), PointF(0.65f, 0.08f), PointF(0.58f, 0.02f), PointF(0.5f, 0.12f)), closePath = true),
        // 点点装饰
        Stroke((0..5).map { PointF(0.3f + (kotlin.random.Random.nextFloat() * 0.35f), 0.3f + (kotlin.random.Random.nextFloat() * 0.55f)) })
    ))

    val 香蕉 = DrawTemplate("香蕉", listOf("香蕉", "banana"), listOf(
        Stroke(listOf(PointF(0.15f, 0.8f), PointF(0.25f, 0.25f), PointF(0.55f, 0.10f), PointF(0.88f, 0.30f))),
        Stroke(listOf(PointF(0.15f, 0.8f), PointF(0.30f, 0.35f), PointF(0.58f, 0.22f), PointF(0.85f, 0.35f))),
        line(0.88f, 0.30f, 0.92f, 0.25f)
    ))

    val 樱桃 = DrawTemplate("樱桃", listOf("樱桃", "cherry"), listOf(
        circle(0.35f, 0.45f, 0.22f, closePath = true),
        circle(0.65f, 0.45f, 0.22f, closePath = true),
        line(0.35f, 0.23f, 0.5f, 0.05f),
        line(0.65f, 0.23f, 0.5f, 0.05f),
        line(0.5f, 0.05f, 0.5f, 0.02f)
    ))

    val 葡萄 = DrawTemplate("葡萄", listOf("葡萄", "grape"), listOf(
        circle(0.5f, 0.18f, 0.08f, closePath = true),
        circle(0.35f, 0.32f, 0.08f, closePath = true),
        circle(0.5f, 0.32f, 0.08f, closePath = true),
        circle(0.65f, 0.32f, 0.08f, closePath = true),
        circle(0.28f, 0.46f, 0.08f, closePath = true),
        circle(0.42f, 0.46f, 0.08f, closePath = true),
        circle(0.58f, 0.46f, 0.08f, closePath = true),
        circle(0.72f, 0.46f, 0.08f, closePath = true),
        circle(0.35f, 0.60f, 0.08f, closePath = true),
        circle(0.50f, 0.60f, 0.08f, closePath = true),
        circle(0.65f, 0.60f, 0.08f, closePath = true),
        line(0.5f, 0.10f, 0.5f, 0.02f),
        line(0.4f, 0.06f, 0.6f, 0.06f)
    ))

    val 菠萝 = DrawTemplate("菠萝", listOf("菠萝", "pineapple"), listOf(
        polygonStroke(listOf(0.5f to 0.12f, 0.82f to 0.55f, 0.75f to 0.95f, 0.25f to 0.95f, 0.18f to 0.55f)),
        line(0.25f, 0.30f, 0.75f, 0.30f),
        line(0.22f, 0.45f, 0.78f, 0.45f),
        line(0.28f, 0.60f, 0.72f, 0.60f),
        line(0.30f, 0.75f, 0.70f, 0.75f),
        Stroke(listOf(PointF(0.5f, 0.12f), PointF(0.38f, 0.05f), PointF(0.42f, 0.0f), PointF(0.5f, 0.12f)), closePath = true),
        Stroke(listOf(PointF(0.5f, 0.12f), PointF(0.58f, 0.02f), PointF(0.62f, 0.06f), PointF(0.5f, 0.12f)), closePath = true)
    ))

    val 梨 = DrawTemplate("梨", listOf("梨", "pear", "梨子"), listOf(
        polygonStroke(listOf(0.5f to 0.2f, 0.8f to 0.45f, 0.75f to 0.9f, 0.25f to 0.9f, 0.2f to 0.45f)),
        line(0.5f, 0.2f, 0.5f, 0.1f),
        Stroke(listOf(PointF(0.5f, 0.14f), PointF(0.58f, 0.08f), PointF(0.62f, 0.13f), PointF(0.55f, 0.18f)), closePath = true)
    ))

    val 橙子 = DrawTemplate("橙子", listOf("橙子", "orange", "橘子"), listOf(
        circle(0.5f, 0.52f, 0.4f, closePath = true),
        line(0.5f, 0.12f, 0.5f, 0.18f),
        Stroke(listOf(PointF(0.5f, 0.18f), PointF(0.58f, 0.12f), PointF(0.62f, 0.16f), PointF(0.55f, 0.22f)), closePath = true),
        arcStroke(0.5f, 0.35f, 0.1f, startAngle = 0.0, endAngle = PI)
    ))

    // ================================================================
    //  自然天体类
    // ================================================================

    val 太阳 = DrawTemplate("太阳", listOf("太阳", "sun"), listOf(
        circle(0.5f, 0.5f, 0.25f, closePath = true),
        // 光芒
        *(0..7).flatMap { i ->
            val angle = (i * PI / 4).toFloat()
            val x1 = 0.5f + 0.30f * cos(angle)
            val y1 = 0.5f + 0.30f * sin(angle)
            val x2 = 0.5f + 0.42f * cos(angle)
            val y2 = 0.5f + 0.42f * sin(angle)
            listOf(line(x1, y1, x2, y2))
        }.toTypedArray(),
        // 笑脸
        Stroke(listOf(PointF(0.38f, 0.42f), PointF(0.42f, 0.40f))),
        Stroke(listOf(PointF(0.58f, 0.42f), PointF(0.62f, 0.40f))),
        arcStroke(0.5f, 0.48f, 0.12f, count = 12, startAngle = 0.15 * PI, endAngle = 0.85 * PI)
    ))

    val 月亮 = DrawTemplate("月亮", listOf("月亮", "moon"), listOf(
        arcStroke(0.45f, 0.5f, 0.38f, count = 30, startAngle = -0.35 * PI, endAngle = 1.25 * PI),
        arcStroke(0.65f, 0.38f, 0.28f, count = 25, startAngle = -0.3 * PI, endAngle = 1.1 * PI)
    ))

    val 星星 = DrawTemplate("星星", listOf("星星", "star", "五角星"), listOf(
        polygonStroke(listOf(
            0.5f to 0.05f, 0.62f to 0.32f, 0.92f to 0.36f,
            0.68f to 0.57f, 0.75f to 0.88f, 0.5f to 0.72f,
            0.25f to 0.88f, 0.32f to 0.57f, 0.08f to 0.36f,
            0.38f to 0.32f
        ))
    ))

    val 云 = DrawTemplate("云", listOf("云", "cloud", "云朵"), listOf(
        arcStroke(0.25f, 0.55f, 0.18f, count = 20, startAngle = 0.0, endAngle = PI),
        arcStroke(0.45f, 0.38f, 0.22f, count = 20, startAngle = 0.0, endAngle = PI),
        arcStroke(0.68f, 0.45f, 0.18f, count = 20, startAngle = 0.0, endAngle = PI),
        line(0.07f, 0.55f, 0.07f, 0.62f),
        line(0.07f, 0.62f, 0.86f, 0.62f),
        line(0.86f, 0.62f, 0.86f, 0.55f)
    ))

    val 彩虹 = DrawTemplate("彩虹", listOf("彩虹", "rainbow"), listOf(
        arcStroke(0.5f, 0.85f, 0.7f, count = 30, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.5f, 0.85f, 0.6f, count = 30, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.5f, 0.85f, 0.5f, count = 30, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.5f, 0.85f, 0.4f, count = 30, startAngle = PI, endAngle = 2 * PI)
    ))

    val 闪电 = DrawTemplate("闪电", listOf("闪电", "lightning", "雷电"), listOf(
        polygonStroke(listOf(
            0.55f to 0.02f, 0.30f to 0.48f, 0.48f to 0.48f,
            0.35f to 0.98f, 0.65f to 0.40f, 0.50f to 0.40f
        ))
    ))

    // ================================================================
    //  植物类
    // ================================================================

    val 树 = DrawTemplate("树", listOf("树", "tree", "大树"), listOf(
        line(0.5f, 0.98f, 0.5f, 0.45f),
        line(0.5f, 0.65f, 0.35f, 0.80f),
        line(0.5f, 0.60f, 0.65f, 0.78f),
        arcStroke(0.5f, 0.45f, 0.35f, count = 35, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.38f, 0.28f, 0.22f, count = 20, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.62f, 0.28f, 0.22f, count = 20, startAngle = PI, endAngle = 2 * PI)
    ))

    val 花 = DrawTemplate("花", listOf("花", "flower", "花朵"), listOf(
        // 花瓣
        arcStroke(0.5f, 0.2f, 0.22f, count = 15, startAngle = 0.0, endAngle = PI),
        arcStroke(0.72f, 0.38f, 0.22f, count = 15, startAngle = PI / 2, endAngle = 3 * PI / 2),
        arcStroke(0.28f, 0.38f, 0.22f, count = 15, startAngle = -PI / 2, endAngle = PI / 2),
        circle(0.5f, 0.5f, 0.12f, closePath = true),
        line(0.5f, 0.62f, 0.5f, 0.95f),
        line(0.5f, 0.78f, 0.62f, 0.72f),
        line(0.5f, 0.72f, 0.38f, 0.82f)
    ))

    val 叶子 = DrawTemplate("叶子", listOf("叶子", "leaf", "树叶"), listOf(
        Stroke(listOf(PointF(0.5f, 0.95f), PointF(0.1f, 0.15f), PointF(0.5f, 0.2f), PointF(0.9f, 0.15f), PointF(0.5f, 0.95f)), closePath = true),
        line(0.5f, 0.95f, 0.5f, 0.15f),
        line(0.5f, 0.4f, 0.28f, 0.35f),
        line(0.5f, 0.55f, 0.72f, 0.50f)
    ))

    val 蘑菇 = DrawTemplate("蘑菇", listOf("蘑菇", "mushroom"), listOf(
        arcStroke(0.5f, 0.45f, 0.4f, count = 30, startAngle = PI, endAngle = 2 * PI),
        line(0.1f, 0.45f, 0.9f, 0.45f),
        polygonStroke(listOf(0.38f to 0.45f, 0.38f to 0.85f, 0.62f to 0.85f, 0.62f to 0.45f)),
        circle(0.28f, 0.28f, 0.06f, closePath = true),
        circle(0.45f, 0.18f, 0.05f, closePath = true),
        circle(0.65f, 0.22f, 0.05f, closePath = true)
    ))

    val 仙人掌 = DrawTemplate("仙人掌", listOf("仙人掌", "cactus"), listOf(
        polygonStroke(listOf(0.35f to 0.95f, 0.35f to 0.25f, 0.65f to 0.25f, 0.65f to 0.95f)),
        line(0.35f, 0.25f, 0.45f, 0.15f),
        line(0.45f, 0.15f, 0.5f, 0.05f),
        line(0.5f, 0.05f, 0.55f, 0.15f),
        line(0.55f, 0.15f, 0.65f, 0.25f),
        line(0.65f, 0.4f, 0.78f, 0.35f),
        line(0.78f, 0.35f, 0.82f, 0.25f),
        line(0.82f, 0.25f, 0.78f, 0.5f),
        line(0.78f, 0.5f, 0.65f, 0.5f),
        line(0.65f, 0.65f, 0.72f, 0.58f),
        line(0.72f, 0.58f, 0.68f, 0.75f),
        line(0.68f, 0.75f, 0.65f, 0.7f),
        line(0.35f, 0.50f, 0.22f, 0.45f),
        line(0.22f, 0.45f, 0.18f, 0.35f),
        line(0.18f, 0.35f, 0.25f, 0.55f),
        line(0.25f, 0.55f, 0.35f, 0.55f)
    ))

    // ================================================================
    //  动物类
    // ================================================================

    val 猫 = DrawTemplate("猫", listOf("猫", "cat", "猫咪", "小猫"), listOf(
        // 耳朵
        polygonStroke(listOf(0.28f to 0.30f, 0.18f to 0.05f, 0.42f to 0.20f)),
        polygonStroke(listOf(0.72f to 0.30f, 0.82f to 0.05f, 0.58f to 0.20f)),
        // 头
        circle(0.5f, 0.48f, 0.3f, closePath = true),
        // 眼睛
        circle(0.38f, 0.44f, 0.06f, closePath = true),
        circle(0.62f, 0.44f, 0.06f, closePath = true),
        // 鼻子
        polygonStroke(listOf(0.5f to 0.52f, 0.47f to 0.55f, 0.53f to 0.55f)),
        // 嘴
        arcStroke(0.5f, 0.55f, 0.08f, count = 8, startAngle = 0.15 * PI, endAngle = 0.85 * PI),
        // 胡须
        line(0.22f, 0.50f, 0.36f, 0.52f),
        line(0.22f, 0.55f, 0.36f, 0.55f),
        line(0.64f, 0.52f, 0.78f, 0.50f),
        line(0.64f, 0.55f, 0.78f, 0.55f)
    ))

    val 狗 = DrawTemplate("狗", listOf("狗", "dog", "小狗"), listOf(
        polygonStroke(listOf(0.2f to 0.3f, 0.1f to 0.05f, 0.35f to 0.2f)),
        polygonStroke(listOf(0.8f to 0.3f, 0.9f to 0.05f, 0.65f to 0.2f)),
        circle(0.5f, 0.48f, 0.3f, closePath = true),
        circle(0.36f, 0.44f, 0.06f, closePath = true),
        circle(0.64f, 0.44f, 0.06f, closePath = true),
        arcStroke(0.5f, 0.49f, 0.14f, count = 15, startAngle = 0.0, endAngle = 0.65 * PI),
        arcStroke(0.5f, 0.49f, 0.14f, count = 15, startAngle = 0.35 * PI, endAngle = PI),
        line(0.5f, 0.78f, 0.5f, 0.85f),
        arcStroke(0.5f, 0.78f, 0.15f, count = 15, startAngle = 0.0, endAngle = PI)
    ))

    val 鱼 = DrawTemplate("鱼", listOf("鱼", "fish", "小鱼"), listOf(
        arcStroke(0.4f, 0.48f, 0.28f, count = 30, startAngle = -0.4 * PI, endAngle = 1.4 * PI),
        polygonStroke(listOf(0.68f to 0.48f, 0.95f to 0.2f, 0.95f to 0.76f)),
        circle(0.28f, 0.42f, 0.05f, closePath = true),
        arcStroke(0.28f, 0.42f, 0.12f, count = 10, startAngle = 0.3 * PI, endAngle = 0.85 * PI)
    ))

    val 兔子 = DrawTemplate("兔子", listOf("兔子", "rabbit", "bunny", "小兔子"), listOf(
        line(0.35f, 0.22f, 0.22f, 0.02f),
        line(0.35f, 0.22f, 0.38f, 0.08f),
        line(0.65f, 0.22f, 0.78f, 0.02f),
        line(0.65f, 0.22f, 0.62f, 0.08f),
        circle(0.5f, 0.48f, 0.28f, closePath = true),
        circle(0.38f, 0.44f, 0.05f, closePath = true),
        circle(0.62f, 0.44f, 0.05f, closePath = true),
        polygonStroke(listOf(0.5f to 0.50f, 0.47f to 0.53f, 0.53f to 0.53f)),
        line(0.5f, 0.53f, 0.5f, 0.58f),
        arcStroke(0.5f, 0.56f, 0.06f, count = 8, startAngle = 0.1 * PI, endAngle = 0.9 * PI)
    ))

    val 乌龟 = DrawTemplate("乌龟", listOf("乌龟", "turtle"), listOf(
        arcStroke(0.5f, 0.58f, 0.35f, count = 30, startAngle = PI, endAngle = 2 * PI),
        line(0.15f, 0.58f, 0.85f, 0.58f),
        circle(0.15f, 0.5f, 0.1f, closePath = true),
        line(0.22f, 0.58f, 0.12f, 0.72f),
        line(0.3f, 0.58f, 0.22f, 0.72f),
        line(0.85f, 0.58f, 0.88f, 0.68f),
        line(0.78f, 0.58f, 0.82f, 0.68f),
        line(0.15f, 0.5f, 0.08f, 0.48f),
        circle(0.1f, 0.48f, 0.03f, closePath = true)
    ))

    val 蝴蝶 = DrawTemplate("蝴蝶", listOf("蝴蝶", "butterfly"), listOf(
        line(0.5f, 0.95f, 0.5f, 0.55f),
        circle(0.5f, 0.55f, 0.05f, closePath = true),
        circle(0.5f, 0.15f, 0.08f, closePath = true),
        Stroke(listOf(PointF(0.28f, 0.32f), PointF(0.05f, 0.08f), PointF(0.05f, 0.62f), PointF(0.28f, 0.42f)), closePath = true),
        Stroke(listOf(PointF(0.72f, 0.32f), PointF(0.95f, 0.08f), PointF(0.95f, 0.62f), PointF(0.72f, 0.42f)), closePath = true),
        Stroke(listOf(PointF(0.32f, 0.55f), PointF(0.1f, 0.55f), PointF(0.1f, 0.78f), PointF(0.34f, 0.62f)), closePath = true),
        Stroke(listOf(PointF(0.68f, 0.55f), PointF(0.9f, 0.55f), PointF(0.9f, 0.78f), PointF(0.66f, 0.62f)), closePath = true),
        line(0.5f, 0.08f, 0.42f, 0.02f),
        line(0.5f, 0.08f, 0.58f, 0.02f)
    ))

    val 鸟 = DrawTemplate("鸟", listOf("鸟", "bird", "小鸟"), listOf(
        circle(0.32f, 0.35f, 0.15f, closePath = true),
        arcStroke(0.5f, 0.4f, 0.25f, count = 20, startAngle = -0.15 * PI, endAngle = 1.15 * PI),
        line(0.7f, 0.22f, 0.8f, 0.25f),
        line(0.7f, 0.22f, 0.72f, 0.18f),
        circle(0.35f, 0.32f, 0.03f, closePath = true),
        line(0.36f, 0.58f, 0.32f, 0.7f),
        line(0.38f, 0.58f, 0.40f, 0.7f),
        line(0.6f, 0.58f, 0.58f, 0.7f)
    ))

    val 蜗牛 = DrawTemplate("蜗牛", listOf("蜗牛", "snail"), listOf(
        arcStroke(0.5f, 0.42f, 0.25f, count = 35, startAngle = PI, endAngle = 3 * PI),
        arcStroke(0.5f, 0.42f, 0.18f, count = 25, startAngle = PI, endAngle = 3 * PI),
        line(0.5f, 0.17f, 0.25f, 0.6f),
        line(0.25f, 0.6f, 0.3f, 0.85f),
        line(0.3f, 0.85f, 0.8f, 0.8f),
        line(0.28f, 0.5f, 0.15f, 0.48f),
        line(0.15f, 0.48f, 0.12f, 0.42f),
        line(0.12f, 0.42f, 0.15f, 0.48f),
        line(0.28f, 0.55f, 0.18f, 0.52f)
    ))

    // ================================================================
    //  物品/建筑类
    // ================================================================

    val 房子 = DrawTemplate("房子", listOf("房子", "house", "房屋"), listOf(
        polygonStroke(listOf(0.5f to 0.05f, 0.05f to 0.35f, 0.95f to 0.35f)),
        polygonStroke(listOf(0.1f to 0.35f, 0.1f to 0.9f, 0.9f to 0.9f, 0.9f to 0.35f)),
        polygonStroke(listOf(0.3f to 0.9f, 0.3f to 0.65f, 0.48f to 0.65f, 0.48f to 0.9f)),
        polygonStroke(listOf(0.62f to 0.55f, 0.62f to 0.4f, 0.8f to 0.4f, 0.8f to 0.55f)),
        line(0.71f, 0.4f, 0.71f, 0.55f),
        line(0.62f, 0.475f, 0.8f, 0.475f)
    ))

    val 爱心 = DrawTemplate("爱心", listOf("爱心", "heart", "心", "红心"), listOf(
        Stroke(listOf(
            PointF(0.5f, 0.88f),
            PointF(0.1f, 0.35f),
            PointF(0.5f, 0.05f),
            PointF(0.9f, 0.35f),
            PointF(0.5f, 0.88f)
        ), closePath = true)
    ))

    val 笑脸 = DrawTemplate("笑脸", listOf("笑脸", "smile", "smiley", "微笑", "笑"), listOf(
        circle(0.5f, 0.5f, 0.42f, closePath = true),
        circle(0.35f, 0.38f, 0.06f, closePath = true),
        circle(0.65f, 0.38f, 0.06f, closePath = true),
        arcStroke(0.5f, 0.48f, 0.2f, count = 20, startAngle = 0.1 * PI, endAngle = 0.9 * PI)
    ))

    val 伞 = DrawTemplate("伞", listOf("伞", "umbrella", "雨伞"), listOf(
        arcStroke(0.5f, 0.35f, 0.44f, count = 30, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.3f, 0.35f, 0.44f, count = 20, startAngle = 0.0, endAngle = 0.4 * PI),
        arcStroke(0.7f, 0.35f, 0.44f, count = 20, startAngle = 0.6 * PI, endAngle = PI),
        line(0.5f, 0.35f, 0.5f, 0.05f),
        line(0.5f, 0.79f, 0.5f, 0.95f),
        arcStroke(0.5f, 0.85f, 0.1f, count = 10, startAngle = 0.0, endAngle = PI)
    ))

    val 杯子 = DrawTemplate("杯子", listOf("杯子", "cup", "水杯", "茶杯"), listOf(
        polygonStroke(listOf(0.25f to 0.2f, 0.75f to 0.2f, 0.78f to 0.75f, 0.22f to 0.75f)),
        arcStroke(0.5f, 0.75f, 0.08f, count = 10, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.75f, 0.35f, 0.15f, count = 15, startAngle = PI / 2, endAngle = 1.5 * PI),
        line(0.9f, 0.28f, 0.9f, 0.42f)
    ))

    val 冰淇淋 = DrawTemplate("冰淇淋", listOf("冰淇淋", "icecream", "雪糕", "冰激凌"), listOf(
        arcStroke(0.5f, 0.25f, 0.3f, count = 25, startAngle = PI, endAngle = 2 * PI),
        polygonStroke(listOf(0.2f to 0.28f, 0.8f to 0.28f, 0.5f to 0.95f)),
        arcStroke(0.5f, 0.3f, 0.12f, count = 10, startAngle = 0.0, endAngle = PI),
        arcStroke(0.4f, 0.15f, 0.08f, count = 10, startAngle = PI, endAngle = 2 * PI),
        arcStroke(0.6f, 0.12f, 0.08f, count = 10, startAngle = PI, endAngle = 2 * PI),
        circle(0.32f, 0.18f, 0.03f, closePath = true),
        circle(0.68f, 0.15f, 0.03f, closePath = true)
    ))

    val 蛋糕 = DrawTemplate("蛋糕", listOf("蛋糕", "cake"), listOf(
        polygonStroke(listOf(0.1f to 0.8f, 0.9f to 0.8f, 0.95f to 0.5f, 0.05f to 0.5f)),
        polygonStroke(listOf(0.15f to 0.5f, 0.85f to 0.5f, 0.9f to 0.3f, 0.1f to 0.3f)),
        arcStroke(0.5f, 0.3f, 0.2f, count = 15, startAngle = PI, endAngle = 2 * PI),
        line(0.5f, 0.1f, 0.5f, 0.02f),
        line(0.45f, 0.08f, 0.45f, 0.04f),
        line(0.55f, 0.06f, 0.55f, 0.02f),
        line(0.25f, 0.65f, 0.75f, 0.65f),
        line(0.2f, 0.55f, 0.8f, 0.55f)
    ))

    val 气球 = DrawTemplate("气球", listOf("气球", "balloon"), listOf(
        arcStroke(0.5f, 0.42f, 0.3f, count = 35, startAngle = PI, endAngle = 2.5 * PI),
        polygonStroke(listOf(0.42f to 0.72f, 0.58f to 0.72f, 0.55f to 0.78f, 0.45f to 0.78f)),
        line(0.5f, 0.78f, 0.5f, 0.95f)
    ))

    val 礼物 = DrawTemplate("礼物", listOf("礼物", "gift", "礼盒", "盒子"), listOf(
        polygonStroke(listOf(0.15f to 0.4f, 0.85f to 0.4f, 0.85f to 0.88f, 0.15f to 0.88f)),
        polygonStroke(listOf(0.1f to 0.4f, 0.9f to 0.4f, 0.95f to 0.28f, 0.05f to 0.28f)),
        line(0.5f, 0.28f, 0.5f, 0.88f),
        line(0.32f, 0.4f, 0.68f, 0.4f),
        line(0.5f, 0.28f, 0.5f, 0.05f),
        arcStroke(0.5f, 0.02f, 0.08f, count = 8, startAngle = PI, endAngle = 2 * PI),
        line(0.15f, 0.4f, 0.1f, 0.28f),
        line(0.85f, 0.4f, 0.9f, 0.28f)
    ))

    val 圣诞树 = DrawTemplate("圣诞树", listOf("圣诞树", "christmas"), listOf(
        polygonStroke(listOf(0.5f to 0.05f, 0.15f to 0.35f, 0.85f to 0.35f)),
        polygonStroke(listOf(0.5f to 0.22f, 0.1f to 0.55f, 0.9f to 0.55f)),
        polygonStroke(listOf(0.5f to 0.4f, 0.05f to 0.75f, 0.95f to 0.75f)),
        polygonStroke(listOf(0.4f to 0.75f, 0.4f to 0.95f, 0.6f to 0.95f, 0.6f to 0.75f)),
        circle(0.5f, 0.05f, 0.05f, closePath = true),
        circle(0.3f, 0.3f, 0.04f, closePath = true),
        circle(0.7f, 0.25f, 0.04f, closePath = true),
        circle(0.45f, 0.45f, 0.04f, closePath = true),
        circle(0.65f, 0.5f, 0.04f, closePath = true),
        circle(0.25f, 0.55f, 0.04f, closePath = true),
        circle(0.8f, 0.62f, 0.04f, closePath = true)
    ))

    // ================================================================
    //  交通工具类
    // ================================================================

    val 汽车 = DrawTemplate("汽车", listOf("汽车", "car", "车", "小汽车"), listOf(
        arcStroke(0.35f, 0.55f, 0.3f, count = 20, startAngle = PI, endAngle = 2 * PI),
        polygonStroke(listOf(0.62f to 0.55f, 0.92f to 0.55f, 0.92f to 0.38f, 0.78f to 0.38f, 0.62f to 0.25f, 0.35f to 0.25f)),
        line(0.35f, 0.25f, 0.05f, 0.38f),
        line(0.05f, 0.38f, 0.05f, 0.55f),
        line(0.05f, 0.55f, 0.35f, 0.55f),
        circle(0.25f, 0.82f, 0.18f, closePath = true),
        circle(0.72f, 0.82f, 0.18f, closePath = true),
        line(0.35f, 0.38f, 0.35f, 0.55f),
        polygonStroke(listOf(0.45f to 0.32f, 0.45f to 0.25f, 0.6f to 0.25f, 0.6f to 0.32f)),
        polygonStroke(listOf(0.7f to 0.32f, 0.7f to 0.25f, 0.78f to 0.25f, 0.78f to 0.38f))
    ))

    val 船 = DrawTemplate("船", listOf("船", "boat", "小船"), listOf(
        polygonStroke(listOf(0.1f to 0.65f, 0.9f to 0.65f, 0.8f to 0.82f, 0.2f to 0.82f)),
        line(0.5f, 0.65f, 0.5f, 0.08f),
        polygonStroke(listOf(0.5f to 0.08f, 0.5f to 0.6f, 0.88f to 0.6f)),
        line(0.3f, 0.82f, 0.28f, 0.88f),
        line(0.7f, 0.82f, 0.72f, 0.88f)
    ))

    val 火箭 = DrawTemplate("火箭", listOf("火箭", "rocket"), listOf(
        polygonStroke(listOf(0.5f to 0.05f, 0.78f to 0.55f, 0.22f to 0.55f)),
        polygonStroke(listOf(0.28f to 0.55f, 0.28f to 0.88f, 0.72f to 0.88f, 0.72f to 0.55f)),
        circle(0.5f, 0.48f, 0.08f, closePath = true),
        polygonStroke(listOf(0.22f to 0.55f, 0.08f to 0.72f, 0.22f to 0.78f)),
        polygonStroke(listOf(0.78f to 0.55f, 0.92f to 0.72f, 0.78f to 0.78f)),
        polygonStroke(listOf(0.28f to 0.88f, 0.18f to 0.95f, 0.28f to 0.95f), close = false),
        polygonStroke(listOf(0.58f to 0.88f, 0.58f to 0.95f, 0.72f to 0.95f), close = false)
    ))

    // ================================================================
    //  其他
    // ================================================================

    val 雪人 = DrawTemplate("雪人", listOf("雪人", "snowman"), listOf(
        circle(0.5f, 0.35f, 0.2f, closePath = true),
        circle(0.5f, 0.72f, 0.28f, closePath = true),
        polygonStroke(listOf(0.4f to 0.15f, 0.4f to 0.0f, 0.6f to 0.0f, 0.6f to 0.15f)),
        circle(0.42f, 0.32f, 0.03f, closePath = true),
        circle(0.58f, 0.32f, 0.03f, closePath = true),
        polygonStroke(listOf(0.48f to 0.38f, 0.5f to 0.42f, 0.52f to 0.38f)),
        circle(0.42f, 0.58f, 0.04f, closePath = true),
        circle(0.58f, 0.58f, 0.04f, closePath = true),
        circle(0.5f, 0.68f, 0.04f, closePath = true)
    ))

    val 幽灵 = DrawTemplate("幽灵", listOf("幽灵", "ghost", "鬼"), listOf(
        arcStroke(0.5f, 0.3f, 0.3f, count = 30, startAngle = PI, endAngle = 2 * PI),
        line(0.2f, 0.3f, 0.2f, 0.6f),
        line(0.8f, 0.3f, 0.8f, 0.6f),
        arcStroke(0.2f, 0.6f, 0.08f, count = 8, startAngle = -PI / 2, endAngle = PI / 2),
        arcStroke(0.35f, 0.6f, 0.08f, count = 8, startAngle = -PI / 2, endAngle = PI / 2),
        arcStroke(0.5f, 0.6f, 0.08f, count = 8, startAngle = -PI / 2, endAngle = PI / 2),
        arcStroke(0.65f, 0.6f, 0.08f, count = 8, startAngle = -PI / 2, endAngle = PI / 2),
        circle(0.38f, 0.28f, 0.05f, closePath = true),
        circle(0.62f, 0.28f, 0.05f, closePath = true),
        arcStroke(0.5f, 0.4f, 0.08f, count = 10, startAngle = 0.2 * PI, endAngle = 0.8 * PI)
    ))

    val 电视 = DrawTemplate("电视", listOf("电视", "tv", "电视机"), listOf(
        polygonStroke(listOf(0.05f to 0.15f, 0.95f to 0.15f, 0.95f to 0.72f, 0.05f to 0.72f)),
        polygonStroke(listOf(0.15f to 0.22f, 0.85f to 0.22f, 0.85f to 0.65f, 0.15f to 0.65f)),
        polygonStroke(listOf(0.35f to 0.72f, 0.35f to 0.88f, 0.65f to 0.88f, 0.65f to 0.72f)),
        line(0.5f, 0.15f, 0.5f, 0.05f)
    ))

    val 手机 = DrawTemplate("手机", listOf("手机", "phone", "智能手机"), listOf(
        polygonStroke(listOf(0.28f to 0.02f, 0.72f to 0.02f, 0.72f to 0.98f, 0.28f to 0.98f), close = true),
        circle(0.5f, 0.92f, 0.04f, closePath = true),
        line(0.56f, 0.06f, 0.6f, 0.08f),
        polygonStroke(listOf(0.35f to 0.15f, 0.65f to 0.15f, 0.65f to 0.78f, 0.35f to 0.78f))
    ))

    val 钟表 = DrawTemplate("钟表", listOf("钟表", "clock", "表", "时钟"), listOf(
        circle(0.5f, 0.5f, 0.42f, closePath = true),
        circle(0.5f, 0.5f, 0.36f, closePath = true),
        line(0.5f, 0.5f, 0.5f, 0.22f),
        line(0.5f, 0.5f, 0.7f, 0.5f),
        circle(0.5f, 0.5f, 0.04f, closePath = true)
    ))

    // ================================================================
    //  成语/场景
    // ================================================================

    val 猴子捞月 = DrawTemplate("猴子捞月", listOf("猴子捞月", "monkey moon"), listOf(
        // 井口/树枝
        line(0.1f, 0.25f, 0.9f, 0.25f),
        line(0.1f, 0.25f, 0.1f, 0.4f),
        line(0.9f, 0.25f, 0.9f, 0.4f),
        // 猴子身体（倒挂）
        arcStroke(0.5f, 0.35f, 0.12f, count = 20, startAngle = PI, endAngle = 2 * PI),
        // 后腿挂井沿
        line(0.42f, 0.35f, 0.38f, 0.25f),
        line(0.58f, 0.35f, 0.62f, 0.25f),
        // 身体下垂
        arcStroke(0.5f, 0.55f, 0.15f, count = 25, startAngle = 0.0, endAngle = PI),
        line(0.35f, 0.55f, 0.35f, 0.75f),
        line(0.65f, 0.55f, 0.65f, 0.75f),
        // 手臂向下伸
        line(0.5f, 0.75f, 0.5f, 0.92f),
        // 头
        circle(0.5f, 0.32f, 0.08f, closePath = true),
        circle(0.47f, 0.30f, 0.015f, closePath = true),
        circle(0.53f, 0.30f, 0.015f, closePath = true),
        // 水面
        arcStroke(0.5f, 0.88f, 0.35f, count = 25, startAngle = 0.0, endAngle = PI),
        // 水中月亮倒影
        circle(0.5f, 0.90f, 0.06f, closePath = true),
        // 天上月亮
        arcStroke(0.75f, 0.12f, 0.08f, count = 20, startAngle = PI, endAngle = 2 * PI)
    ))

    val 守株待兔 = DrawTemplate("守株待兔", listOf("守株待兔"), listOf(
        // 树桩
        polygonStroke(listOf(0.2f to 0.35f, 0.35f to 0.35f, 0.38f to 0.95f, 0.17f to 0.95f)),
        // 树冠
        arcStroke(0.28f, 0.35f, 0.22f, count = 20, startAngle = PI, endAngle = 2 * PI),
        // 兔子身体
        arcStroke(0.62f, 0.62f, 0.18f, count = 25, startAngle = -0.2 * PI, endAngle = 1.2 * PI),
        // 兔子耳朵
        line(0.55f, 0.52f, 0.5f, 0.35f),
        line(0.58f, 0.5f, 0.55f, 0.35f),
        // 人坐着等待
        arcStroke(0.75f, 0.7f, 0.12f, count = 15, startAngle = 0.0, endAngle = PI),
        line(0.75f, 0.7f, 0.75f, 0.95f),
        line(0.63f, 0.78f, 0.87f, 0.78f)
    ))

    val 画龙点睛 = DrawTemplate("画龙点睛", listOf("画龙点睛"), listOf(
        // 龙身蜿蜒
        Stroke(listOf(
            PointF(0.1f, 0.5f), PointF(0.2f, 0.3f), PointF(0.35f, 0.5f),
            PointF(0.5f, 0.2f), PointF(0.65f, 0.5f), PointF(0.8f, 0.3f),
            PointF(0.9f, 0.5f)
        )),
        // 龙头
        arcStroke(0.15f, 0.45f, 0.12f, count = 20, startAngle = -0.5 * PI, endAngle = PI),
        // 龙眼（点睛之笔）
        circle(0.1f, 0.42f, 0.03f, closePath = true),
        // 龙角
        line(0.08f, 0.35f, 0.05f, 0.2f),
        line(0.12f, 0.35f, 0.15f, 0.2f),
        // 龙尾
        line(0.9f, 0.5f, 0.95f, 0.35f),
        line(0.9f, 0.5f, 0.95f, 0.65f),
        // 画笔
        Stroke(listOf(
            PointF(0.78f, 0.55f), PointF(0.85f, 0.65f), PointF(0.9f, 0.75f), PointF(0.95f, 0.85f)
        )),
        line(0.78f, 0.55f, 0.72f, 0.5f)
    ))

    // ================================================================
    //  模板集合
    // ================================================================

    val allTemplates: List<DrawTemplate> = listOf(
        西瓜, 苹果, 草莓, 香蕉, 樱桃, 葡萄, 菠萝, 梨, 橙子,
        太阳, 月亮, 星星, 云, 彩虹, 闪电,
        树, 花, 叶子, 蘑菇, 仙人掌,
        猫, 狗, 鱼, 兔子, 乌龟, 蝴蝶, 鸟, 蜗牛,
        房子, 爱心, 笑脸, 伞, 杯子, 冰淇淋, 蛋糕, 气球, 礼物, 圣诞树,
        汽车, 船, 火箭,
        雪人, 幽灵, 电视, 手机, 钟表,
        猴子捞月, 守株待兔, 画龙点睛
    )

    /**
     * 根据输入文本匹配模板
     */
    fun findTemplate(input: String): DrawTemplate? {
        val lowerInput = input.trim().lowercase()
        return allTemplates.firstOrNull { template ->
            template.keywords.any { keyword ->
                lowerInput.contains(keyword.lowercase())
            }
        }
    }

    /**
     * 获取所有可用关键词
     */
    fun allKeywords(): List<String> = allTemplates.flatMap { it.keywords }.distinct()
}
