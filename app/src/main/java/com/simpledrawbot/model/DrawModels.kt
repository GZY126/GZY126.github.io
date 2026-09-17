package com.simpledrawbot.model

import android.graphics.PointF

/**
 * 绘画模板数据类
 * @param name 图案名称
 * @param keywords 匹配关键词
 * @param strokes 笔画列表（归一化坐标0~1）
 */
data class DrawTemplate(
    val name: String,
    val keywords: List<String>,
    val strokes: List<Stroke>
)

/**
 * 单笔笔画：一组连续的点
 */
data class Stroke(
    val points: List<PointF>,
    val closePath: Boolean = false
)
