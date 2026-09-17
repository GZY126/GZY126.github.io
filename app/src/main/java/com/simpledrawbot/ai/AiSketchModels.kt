package com.simpledrawbot.ai

import com.google.gson.annotations.SerializedName

/**
 * OpenAI 兼容的 Chat Completions 请求
 * maxTokens 设为可空，Gson 默认不序列化 null 字段，避免某些 API 不兼容
 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 0.3,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null
)

data class Message(
    val role: String,
    val content: String
)

/**
 * Chat Completions 响应
 */
data class ChatCompletionResponse(
    val choices: List<Choice>?,
    val error: ApiError? = null
)

data class Choice(
    val message: Message?
)

data class ApiError(
    val message: String? = null,
    val type: String? = null
)

/**
 * AI 返回的简笔画 JSON 结构（旧格式兜底）
 */
data class AiSketchResult(
    val name: String = "",
    val strokes: List<AiStroke> = emptyList()
)

/**
 * 新格式：结构化绘图指令
 * {"title":"苹果","style":"...","line_total":"8","elements":[...],"draw_step":[...],"ratio":"1:1"}
 */
data class AiStructuredResult(
    val title: String = "",
    val style: String = "",
    val line_total: String = "",
    val elements: List<AiElement> = emptyList(),
    val draw_step: List<AiDrawStep> = emptyList(),
    val ratio: String = "1:1"
)

data class AiElement(
    val name: String = "",
    val shape: String = ""
)

data class AiDrawStep(
    val step: Int = 0,
    val element: String = "",
    val type: String = "",       // circle, line, arc, oval, triangle, rect, curve
    val params: String = ""      // 参数，如 "centerX:0.5,centerY:0.3,radius:0.15"
)

data class AiStroke(
    val points: List<AiPoint>,
    val closePath: Boolean = false
)

data class AiPoint(
    val x: Float,
    val y: Float
)

/**
 * AI 返回的 SVG 格式简笔画
 * 多个 <path> 组成一个图画
 */
data class AiSvgResult(
    val name: String = "",
    val paths: List<AiSvgPath> = emptyList()
)

data class AiSvgPath(
    val d: String = "",           // SVG path data，如 "M 0.5 0.2 C 0.6 0.2 0.7 0.3 0.7 0.4 Z"
    val fill: String = "none",    // 填充色，"none" 表示不填充
    val stroke: String = "#000000"
)
