package com.simpledrawbot.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.simpledrawbot.model.DrawTemplate
import com.simpledrawbot.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 简笔画生成器
 * 流程：Seedream 生成简笔画图片 → 下载 → 轮廓提取 → 笔画坐标
 * 兜底：结构化绘图指令 / SVG path / 旧坐标格式
 */
class AiSketchGenerator {

    companion object {
        private const val TAG = "AiSketchGenerator"
        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val IMAGE_ENDPOINT = "https://api.siliconflow.cn/v1/images/generations"
    }

    private val gson = Gson()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    enum class Complexity {
        SIMPLE,
        COMPLEX,
        MAX
    }

    data class Config(
        val apiKey: String,
        val endpoint: String = DEFAULT_ENDPOINT,
        val model: String = DEFAULT_MODEL
    )

    /**
     * 根据文字描述生成简笔画模板
     * 优先使用图片生成 + 轮廓提取
     * 兜底使用结构化绘图指令
     */
    suspend fun generate(
        description: String,
        config: Config,
        complexity: Complexity = Complexity.SIMPLE,
        imageModel: String = "zturbo"
    ): DrawTemplate? =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) {
                Log.e(TAG, "API Key 为空")
                return@withContext null
            }

            // 1. 尝试图片生成 + 轮廓提取
            val imageResult = generateViaImage(description, config, complexity, imageModel)
            if (imageResult != null) {
                Log.d(TAG, "图片生成+轮廓提取成功: ${imageResult.name}, ${imageResult.strokes.size} 笔")
                return@withContext imageResult
            }

            // 2. 兜底：结构化绘图指令（文本模型）
            Log.w(TAG, "图片生成失败，降级到文本模型")
            generateViaText(description, config, complexity)
        }

    /**
     * 方式1：Seedream 图片生成 → 下载 → 轮廓提取
     */
    private suspend fun generateViaImage(
        description: String,
        config: Config,
        complexity: Complexity,
        imageModel: String = "zturbo"
    ): DrawTemplate? {
        try {
            // 1.1 调用图片生成 API
            val imageUrl = callSeedream(description, config, complexity, imageModel) ?: return null
            Log.d(TAG, "Seedream 生成完成: $imageUrl")

            // 1.2 下载图片
            val bitmap = downloadImage(imageUrl) ?: return null
            Log.d(TAG, "图片下载完成: ${bitmap.width}x${bitmap.height}")

            // 1.3 轮廓提取（按模式区分精细度）
            val strokes = ImageContourExtractor.extract(bitmap, complexity)

            // 1.4 释放 Bitmap（1024×1024 ≈ 4MB）
            bitmap.recycle()

            if (strokes.isEmpty()) {
                Log.e(TAG, "轮廓提取失败，无有效笔画")
                return null
            }
            Log.d(TAG, "轮廓提取完成: ${strokes.size} 条笔画")

            return DrawTemplate(description, listOf(description), strokes)
        } catch (e: Exception) {
            Log.e(TAG, "图片生成流程失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 调用 Seedream API 生成图片
     * POST /api/v3/images/generations
     */
    private fun callSeedream(
        description: String,
        config: Config,
        complexity: Complexity,
        imageModel: String = "zturbo"
    ): String? {
        val prompt = buildSeedreamPrompt(description, complexity)
        val modelName = if (imageModel == "zimage") "Tongyi-MAI/Z-Image" else "Tongyi-MAI/Z-Image-Turbo"
        val steps = if (imageModel == "zimage") 8 else 4  // Z-Image 更精细，多用几步
        val body = gson.toJson(
            mapOf(
                "model" to modelName,
                "prompt" to prompt,
                "image_size" to "1024x1024",
                "batch_size" to 1,
                "num_inference_steps" to steps
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(IMAGE_ENDPOINT)
            .post(body)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Seedream 请求失败: ${response.code} ${response.message}")
                return null
            }
            val json = response.body?.string() ?: return null
            val result = gson.fromJson(json, Map::class.java)
            // 硅基流动返回 images 字段
            val images = result["images"] as? List<*> ?: (result["data"] as? List<*>)
            val first = images?.firstOrNull() as? Map<*, *> ?: return null
            return first["url"] as? String
        }
    }

    /**
     * 下载图片
     */
    private fun downloadImage(url: String): Bitmap? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun buildSeedreamPrompt(description: String, complexity: Complexity): String {
        val base = when (complexity) {
            Complexity.SIMPLE -> SEEDREAM_PROMPT
            Complexity.COMPLEX -> SEEDREAM_PROMPT_COMPLEX
            Complexity.MAX -> SEEDREAM_PROMPT_COMPLEX  // MAX 用复杂模式的 prompt
        }
        // 拼接英文：prompt 以 "Drawing of" 结尾，直接跟描述
        return "$base $description"
    }

    /**
     * 方式2（兜底）：结构化绘图指令（文本模型）
     */
    private suspend fun generateViaText(
        description: String,
        config: Config,
        complexity: Complexity
    ): DrawTemplate? {
        val prompt = "请以" + when (complexity) {
            Complexity.SIMPLE -> "简单模式（6-14笔）"
            Complexity.COMPLEX -> "复杂模式（16-35笔）"
            Complexity.MAX -> "MAX模式（35-60笔）"
        } + "为\"" + description + "\"生成简笔画绘图指令。只返回JSON，不要任何解释。"

        val requestBody = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                Message(role = "system", content = buildTextSystemPrompt(complexity)),
                Message(role = "user", content = prompt)
            ),
            temperature = 0.7,
            maxTokens = null
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())
        val finalEndpoint = normalizeEndpoint(config.endpoint)

        val request = Request.Builder()
            .url(finalEndpoint)
            .post(body)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@generateViaText null
                val content = gson.fromJson(
                    response.body?.string() ?: return@generateViaText null,
                    ChatCompletionResponse::class.java
                ).choices?.firstOrNull()?.message?.content ?: return@generateViaText null
                parseAiResponse(content, description)
            }
        } catch (e: Exception) {
            Log.e(TAG, "文本生成兜底失败: ${e.message}", e)
            null
        }
    }

    private fun buildTextSystemPrompt(complexity: Complexity): String {
        return when (complexity) {
            Complexity.SIMPLE -> TEXT_SYSTEM_PROMPT_SIMPLE
            Complexity.COMPLEX -> TEXT_SYSTEM_PROMPT_COMPLEX
            Complexity.MAX -> TEXT_SYSTEM_PROMPT_COMPLEX
        }
    }

    private fun parseAiResponse(content: String, description: String): DrawTemplate? {
        val jsonText = extractJson(content) ?: content.trim()

        // 1. 优先解析新格式（结构化绘图指令）
        if (jsonText.contains("\"draw_step\"") || jsonText.contains("\"elements\"")) {
            try {
                val result = gson.fromJson(jsonText, AiStructuredResult::class.java)
                if (result.draw_step.isNotEmpty()) {
                    val strokes = ShapeToStrokeConverter.convert(result.draw_step)
                    if (strokes.isNotEmpty()) {
                        Log.d(TAG, "结构化绘图指令解析成功: ${strokes.size} 笔")
                        return DrawTemplate(
                            result.title.ifEmpty { description },
                            listOf(description),
                            strokes
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "结构化指令解析失败，尝试旧格式", e)
            }
        }

        // 2. 尝试解析 SVG 格式
        val svgResult = parseSvgResponse(content)
        if (svgResult != null) return svgResult

        // 3. 兜底：旧的 JSON 坐标格式
        return try {
            val result = gson.fromJson(jsonText, AiSketchResult::class.java)
            if (result.strokes.isEmpty()) {
                Log.e(TAG, "AI 返回空笔画")
                return null
            }
            val strokes = result.strokes.mapNotNull { aiStroke ->
                val points = aiStroke.points.map { PointF(clamp(it.x), clamp(it.y)) }
                if (points.size >= 2) Stroke(points, aiStroke.closePath) else null
            }
            if (strokes.isEmpty()) return null
            DrawTemplate(result.name.ifEmpty { description }, listOf(description), strokes)
        } catch (e: Exception) {
            Log.e(TAG, "解析 AI 返回失败: ${e.message}\n$content", e)
            null
        }
    }

    /**
     * 解析 SVG 格式的 AI 返回
     * 支持两种格式：
     * 1. JSON: {"name":"...","paths":[{"d":"M...","fill":"none"}]}
     * 2. 直接 SVG: <svg><path d="M..."/></svg>
     */
    private fun parseSvgResponse(content: String): DrawTemplate? {
        return try {
            // 方式 1：JSON 包装的 SVG paths
            val jsonText = extractJson(content) ?: content.trim()
            if (jsonText.contains("\"paths\"")) {
                val svgResult = gson.fromJson(jsonText, AiSvgResult::class.java)
                if (svgResult.paths.isEmpty()) return null
                val strokes = svgResult.paths.flatMap { path ->
                    SvgPathParser.parse(path.d).filter { it.points.size >= 2 }
                }
                if (strokes.isEmpty()) return null
                return DrawTemplate(svgResult.name.ifEmpty { "" }, emptyList(), strokes)
            }
            // 方式 2：直接是 <svg>...</svg>
            if (content.contains("<svg") || content.contains("<path")) {
                val pathRegex = "<path[^>]*d=[\"']([^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)
                val nameRegex = "<title[^>]*>([^<]+)</title>".toRegex(RegexOption.IGNORE_CASE)
                val paths = pathRegex.findAll(content).map { it.groupValues[1] }.toList()
                val name = nameRegex.find(content)?.groupValues?.get(1)?.trim() ?: ""
                if (paths.isEmpty()) return null
                val strokes = paths.flatMap { d -> SvgPathParser.parse(d).filter { it.points.size >= 2 } }
                if (strokes.isEmpty()) return null
                return DrawTemplate(name, emptyList(), strokes)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "SVG 解析失败: ${e.message}", e)
            null
        }
    }

    /**
     * 测试 API 连接是否可用
     * @param config API 配置
     * @return Pair<是否成功, 提示信息>
     */
    suspend fun testConnection(config: Config): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) {
                return@withContext false to "API Key 为空，请先填写"
            }
            try {
                // 测试 Z-Image-Turbo 图片生成（保持英文 prompt 一致）
                val prompt = "$SEEDREAM_PROMPT an apple"
                val reqBody = gson.toJson(
                    mapOf(
                        "model" to "Tongyi-MAI/Z-Image-Turbo",
                        "prompt" to prompt,
                        "image_size" to "1024x1024",
                        "batch_size" to 1,
                        "num_inference_steps" to 4
                    )
                ).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(IMAGE_ENDPOINT)
                    .post(reqBody)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val err = gson.fromJson(body, Map::class.java)
                        (err["error"] as? Map<*, *>)?.get("message")?.toString() ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $body"
                    }
                    return@withContext false to "连接失败：$errorMsg"
                }

                val result = gson.fromJson(body, Map::class.java)
                val images = result["images"] as? List<*> ?: (result["data"] as? List<*>)
                if (images.isNullOrEmpty()) {
                    return@withContext false to "图片生成返回为空"
                }

                true to "连接成功！Z-Image-Turbo 可正常生成简笔画（约3-5秒）"
            } catch (e: Exception) {
                Log.e(TAG, "测试连接失败: ${e.message}", e)
                false to "连接异常：${e.message}"
            }
        }

    private fun extractJson(text: String): String? {
        val regex = "```(?:json)?\\s*([\\s\\S]*?)```".toRegex()
        return regex.find(text)?.groupValues?.get(1)?.trim()
    }

    private fun clamp(value: Float): Float = value.coerceIn(0f, 1f)

    /**
     * 从 AI 返回的 JSON 中提取 SVG URL，下载 SVG 文件并解析为笔画
     * 支持 JSON 格式：{"name":"...","svg_url":"https://..."}
     * 也支持直接返回 URL 文本
     */
    private suspend fun downloadAndParseSvgUrl(content: String, description: String): DrawTemplate? {
        return try {
            // 提取 URL
            val url = extractSvgUrl(content) ?: return null
            Log.d(TAG, "AI 返回的 SVG URL: $url")

            // 下载 SVG 文件
            val svgContent = downloadSvg(url) ?: return null
            Log.d(TAG, "SVG 文件大小: ${svgContent.length} 字符")

            // 解析 SVG
            val strokes = parseSvgContent(svgContent)
            if (strokes.isEmpty()) {
                Log.e(TAG, "SVG 文件解析后无有效笔画")
                return null
            }

            // 提取名称
            val name = extractName(content).ifEmpty { description }
            DrawTemplate(name, emptyList(), strokes)
        } catch (e: Exception) {
            Log.e(TAG, "下载解析 SVG URL 失败: ${e.message}", e)
            null
        }
    }

    private fun extractSvgUrl(content: String): String? {
        // 从 JSON 中提取
        val jsonText = extractJson(content) ?: content.trim()
        val urlRegex = "\"svg_url\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
        val match = urlRegex.find(jsonText)
        if (match != null) return match.groupValues[1]

        // 从纯文本中提取 URL（以 https 开头且以 .svg 结尾）
        val directUrlRegex = "https?://[^\\s\"'<>]+\\.svg[^\\s\"'<>]*".toRegex(RegexOption.IGNORE_CASE)
        return directUrlRegex.find(content)?.value
    }

    private fun extractName(content: String): String {
        val jsonText = extractJson(content) ?: return ""
        val nameRegex = "\"name\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
        return nameRegex.find(jsonText)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun downloadSvg(url: String): String? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "SVG 下载失败: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank() || (!body.contains("<svg") && !body.contains("<path"))) {
                    Log.e(TAG, "下载的内容不是有效 SVG")
                    return null
                }
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "SVG 下载异常: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 SVG 文件内容，提取所有 path 的 d 属性，转换为笔画
     * 同时把 SVG 的 viewBox 坐标归一化到 0~1
     */
    private fun parseSvgContent(svg: String): List<Stroke> {
        // 提取 viewBox
        val viewBoxRegex = "viewBox=[\"']([\\d.\\-\\s]+)[\"']".toRegex(RegexOption.IGNORE_CASE)
        val viewBoxMatch = viewBoxRegex.find(svg)
        val (vbX, vbY, vbW, vbH) = if (viewBoxMatch != null) {
            val parts = viewBoxMatch.groupValues[1].trim().split(Regex("\\s+"))
            val x = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val y = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val w = parts.getOrNull(2)?.toFloatOrNull() ?: 100f
            val h = parts.getOrNull(3)?.toFloatOrNull() ?: 100f
            floatArrayOf(x, y, w, h)
        } else {
            floatArrayOf(0f, 0f, 100f, 100f)
        }

        // 如果有 width/height 属性，用它们作为 viewBox 兜底
        val finalW: Float
        val finalH: Float
        if (vbW <= 0f || vbH <= 0f) {
            val wRegex = "width=[\"'](\\d+)".toRegex(RegexOption.IGNORE_CASE)
            val hRegex = "height=[\"'](\\d+)".toRegex(RegexOption.IGNORE_CASE)
            finalW = wRegex.find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: 100f
            finalH = hRegex.find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: 100f
        } else {
            finalW = vbW
            finalH = vbH
        }

        val scaleX = 1f / finalW
        val scaleY = 1f / finalH
        val offsetX = -vbX * scaleX
        val offsetY = -vbY * scaleY

        // 提取所有 path 的 d 属性
        val pathRegex = "<path[^>]*\\sd=[\"']([^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)
        val paths = pathRegex.findAll(svg).map { it.groupValues[1] }.toList()

        val allStrokes = mutableListOf<Stroke>()
        for (d in paths) {
            val parsed = SvgPathParser.parse(d, scaleX, scaleY)
            // 应用偏移
            val adjusted = parsed.map { stroke ->
                Stroke(stroke.points.map { PointF(it.x + offsetX, it.y + offsetY) }, stroke.closePath)
            }
            allStrokes.addAll(adjusted.filter { it.points.size >= 2 })
        }

        // 如果没有 path，尝试 polygon
        if (allStrokes.isEmpty()) {
            val polyRegex = "<polygon[^>]*\\spoints=[\"']([^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE)
            for (match in polyRegex.findAll(svg)) {
                val pointsStr = match.groupValues[1].trim()
                val coords = pointsStr.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                val pts = coords.chunked(2).mapNotNull { pair ->
                    if (pair.size >= 2) {
                        PointF(pair[0] * scaleX + offsetX, pair[1] * scaleY + offsetY)
                    } else null
                }
                if (pts.size >= 2) allStrokes.add(Stroke(pts, true))
            }
        }

        return allStrokes
    }

    /**
     * 自动修正 endpoint：
     * - /responses → /chat/completions（豆包等平台默认地址用 responses，但 OpenAI 兼容格式需要 chat/completions）
     * - 无路径 → 追加 /chat/completions
     * - 已是 /chat/completions → 保持不变
     */
    private fun normalizeEndpoint(endpoint: String): String {
        var url = endpoint.trim().removeSuffix("/")
        // 去掉末尾的 /responses 或 /v1/responses
        if (url.endsWith("/responses")) {
            url = url.removeSuffix("/responses")
        }
        // 如果已经有 /chat/completions，直接返回
        if (url.endsWith("/chat/completions")) {
            return url
        }
        // 追加 /chat/completions
        return "$url/chat/completions"
    }

    // 图片生成 prompt：强调极简轮廓风格，让轮廓提取能直接复制
    // 使用英文（对图像生成模型效果更好）
    // SIMPLE：极简单线简笔画（thin single line，配合骨架细化效果最佳）
    private val SEEDREAM_PROMPT = (
        "A minimal single line art drawing, clean thin black outline on pure white background. " +
        "Coloring book style, sticker style. NO shading, NO gradient, NO color fills, NO textures, NO interior details. " +
        "NO stroke width variation, NO double lines, NO overlapping strokes, NO thick bold strokes. " +
        "Only the outermost clean contour lines, like a simple icon. " +
        "Continuous smooth thin lines, minimum number of strokes. " +
        "Drawing of"
    )
    // COMPLEX / MAX：简笔画，单线细节稍多
    private val SEEDREAM_PROMPT_COMPLEX = (
        "A clean single line art drawing, thin black outline on pure white background. " +
        "Coloring book style. NO shading, NO gradient, NO color fills, NO textures, NO hatching. " +
        "NO stroke width variation, NO double lines, NO overlapping strokes. " +
        "Only clear contour lines and a few essential feature lines (eyes, mouth etc). " +
        "Smooth continuous thin strokes. " +
        "Drawing of"
    )

    // 文本模型兜底提示词
    private val TEXT_SYSTEM_PROMPT_SIMPLE = """
仅输出标准JSON，禁止多余文字、注释、换行。
硬性规则：
1.输入为名词或成语；核心识别元素≤3个；
2.总线条6-14笔，只用圆形、直线、三角、圆弧基础几何构图；
3.只保留核心辨识度特征，剔除多余细节；
4.所有坐标归一化到0~1，画布竖向（高>宽），主体居中。

draw_step格式：{"step":序号,"element":"元素名","type":"形状类型","params":"坐标参数"}
形状类型：circle(centerX,centerY,radius), oval(centerX,centerY,rx,ry), arc(centerX,centerY,radius,startAngle,sweepAngle), line(x1,y1,x2,y2), curve(x1,y1,cx1,cy1,cx2,cy2,x2,y2), rect(left,top,right,bottom), triangle(x1,y1,x2,y2,x3,y3)

示例：
{"title":"苹果","style":"纯黑单线","line_total":"6","elements":[{"name":"轮廓","shape":"圆形偏上"},{"name":"梗","shape":"短弧线向上"},{"name":"叶子","shape":"椭圆倾斜"}],"draw_step":[{"step":1,"element":"轮廓","type":"circle","params":"0.5,0.45,0.28"},{"step":2,"element":"梗","type":"curve","params":"0.5,0.17,0.48,0.2,0.5,0.15,0.5,0.1"},{"step":3,"element":"叶子","type":"oval","params":"0.58,0.14,0.06,0.03"}],"ratio":"1:1"}
""".trimIndent()

    private val TEXT_SYSTEM_PROMPT_COMPLEX = """
仅输出标准JSON，禁止多余文字。
硬性规则：
1.核心识别元素3-5个，主体突出；
2.总线条16-35笔，基础几何搭配柔和弧线；
3.核心特征不变前提下增加柔和细节；
4.坐标归一化到0~1，竖向画布，主体居中。

draw_step格式同上。类型同简单模式。
""".trimIndent()
}
