package com.simpledrawbot.util

import android.content.Context
import android.content.SharedPreferences
import com.simpledrawbot.ai.AiSketchGenerator

/**
 * 本地配置：保存 API Key、Endpoint、Model 等
 */
class PreferenceHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "simple_draw_bot_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_DRAW_DURATION = "draw_duration_seconds"
        private const val KEY_COMPLEXITY = "draw_complexity"
        private const val KEY_IMAGE_MODEL = "image_model"
        private const val KEY_AREA_PRESETS = "area_presets"
    }

    // 选区预设：保存为 JSON 字符串 "name:left,top,right,bottom;..."
    fun saveAreaPreset(name: String, rect: android.graphics.RectF) {
        val presets = getAreaPresets().toMutableMap()
        presets[name] = rect
        val json = presets.map { "${it.key}:${it.value.left},${it.value.top},${it.value.right},${it.value.bottom}" }
            .joinToString(";")
        prefs.edit().putString(KEY_AREA_PRESETS, json).apply()
    }

    fun getAreaPresets(): Map<String, android.graphics.RectF> {
        val json = prefs.getString(KEY_AREA_PRESETS, "") ?: ""
        if (json.isBlank()) return emptyMap()
        val map = mutableMapOf<String, android.graphics.RectF>()
        json.split(";").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val coords = parts[1].split(",")
                if (coords.size == 4) {
                    try {
                        map[parts[0]] = android.graphics.RectF(
                            coords[0].toFloat(), coords[1].toFloat(),
                            coords[2].toFloat(), coords[3].toFloat()
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        return map
    }

    fun deleteAreaPreset(name: String) {
        val presets = getAreaPresets().toMutableMap()
        presets.remove(name)
        val json = presets.map { "${it.key}:${it.value.left},${it.value.top},${it.value.right},${it.value.bottom}" }
            .joinToString(";")
        prefs.edit().putString(KEY_AREA_PRESETS, json).apply()
    }

    fun saveImageModel(model: String) {
        prefs.edit().putString(KEY_IMAGE_MODEL, model).apply()
    }

    fun getImageModel(): String {
        return prefs.getString(KEY_IMAGE_MODEL, "zturbo") ?: "zturbo"
    }

    fun saveAiConfig(config: AiSketchGenerator.Config) {
        prefs.edit().apply {
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_ENDPOINT, config.endpoint)
            putString(KEY_MODEL, config.model)
            apply()
        }
    }

    fun getAiConfig(): AiSketchGenerator.Config {
        return AiSketchGenerator.Config(
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            endpoint = prefs.getString(KEY_ENDPOINT, "https://api.openai.com/v1/chat/completions")
                ?: "https://api.openai.com/v1/chat/completions",
            model = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        )
    }

    fun saveDrawDurationSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_DRAW_DURATION, seconds.coerceIn(5, 120)).apply()
    }

    fun getDrawDurationSeconds(): Int {
        return prefs.getInt(KEY_DRAW_DURATION, 30).coerceIn(5, 120)
    }

    fun saveComplexity(complexity: String) {
        prefs.edit().putString(KEY_COMPLEXITY, complexity).apply()
    }

    fun getComplexity(): String {
        return prefs.getString(KEY_COMPLEXITY, "simple") ?: "simple"
    }
}
