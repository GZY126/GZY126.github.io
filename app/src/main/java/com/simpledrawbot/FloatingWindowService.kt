package com.simpledrawbot

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.simpledrawbot.ai.AiSketchGenerator
import com.simpledrawbot.ai.ImageContourExtractor
import com.simpledrawbot.drawing.TemplateLibrary
import com.simpledrawbot.model.DrawTemplate
import com.simpledrawbot.util.PreferenceHelper
import kotlinx.coroutines.*

/**
 * 浮动窗口 Service：管理悬浮球显示，并协调模板匹配 / AI 生成 / 搜图绘制
 */
class FloatingWindowService : Service() {

    private lateinit var floatingBallView: FloatingBallView
    private var isDrawing = false
    private var isPaused = false
    private var aiJob: Job? = null
    private var countdownJob: Job? = null
    private lateinit var prefs: PreferenceHelper
    private val aiGenerator = AiSketchGenerator()

    companion object {
        const val CHANNEL_ID = "floating_window_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_DRAW = "com.simpledrawbot.STOP_DRAW"
        const val ACTION_PAUSE_DRAW = "com.simpledrawbot.PAUSE_DRAW"
        const val ACTION_SERVICE_STATE = "com.simpledrawbot.SERVICE_STATE"
        const val EXTRA_SERVICE_RUNNING = "service_running"
        const val TAG = "FloatingWindowSvc"

        var instance: FloatingWindowService? = null
            private set

        @JvmStatic
        fun clearInstance() { instance = null }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferenceHelper(this)

        ImageContourExtractor.initOpenCV()

        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "前台服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        initFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_DRAW -> stopDrawing()
            ACTION_PAUSE_DRAW -> togglePauseResume()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        aiJob?.cancel()
        countdownJob?.cancel()
        if (::floatingBallView.isInitialized) floatingBallView.hide()
        instance = null
        sendServiceStateBroadcast(false)
        super.onDestroy()
    }

    private fun sendServiceStateBroadcast(running: Boolean) {
        try {
            sendBroadcast(Intent(ACTION_SERVICE_STATE).apply {
                putExtra(EXTRA_SERVICE_RUNNING, running)
                `package` = packageName
            })
        } catch (e: Exception) {
            Log.w(TAG, "发送服务状态广播失败", e)
        }
    }

    private fun initFloatingBall() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_SimpleDrawBot)
        floatingBallView = FloatingBallView(themedContext)
        floatingBallView.setCallback(object : FloatingBallView.Callback {
            override fun onStartDraw(text: String, rect: RectF, durationSeconds: Int) {
                startDrawingProcess(text, rect, durationSeconds)
            }
            override fun onConfirmDraw(template: Any, text: String, rect: RectF, durationSeconds: Int, complexity: AiSketchGenerator.Complexity) {
                // 检查延时启动
                if (floatingBallView.isCountdownEnabled()) {
                    startCountdown { executeDraw(template as DrawTemplate, text, rect, complexity, durationSeconds) }
                } else {
                    executeDraw(template as DrawTemplate, text, rect, complexity, durationSeconds)
                }
            }
            override fun onStopDraw() { stopDrawing() }
            override fun onPauseResumeDraw() { togglePauseResume() }
            override fun onSelectArea() { floatingBallView.showAreaSelector() }
            override fun onOpenSettings() {
                startActivity(Intent(this@FloatingWindowService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }
            override fun onClose() { stopSelf() }
            override fun onDrawFromImage(bitmap: Bitmap, rect: RectF, durationSeconds: Int, complexity: AiSketchGenerator.Complexity) {
                extractAndDrawFromImage(bitmap, rect, durationSeconds, complexity)
            }
        })
        val ok = floatingBallView.show()
        if (!ok) {
            Log.e(TAG, "悬浮球视图添加失败，停止服务")
            Toast.makeText(this, "悬浮球显示失败，请检查悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        sendServiceStateBroadcast(true)
    }

    /**
     * 倒计时启动（3 秒后开始画）
     */
    private fun startCountdown(onComplete: () -> Unit) {
        countdownJob?.cancel()
        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            floatingBallView.showCountdownOverlay(true)
            for (i in 3 downTo 1) {
                floatingBallView.updateCountdownText("${i}秒后开始绘制...")
                delay(1000)
            }
            floatingBallView.showCountdownOverlay(false)
            onComplete()
        }
    }

    private fun extractAndDrawFromImage(bitmap: Bitmap, rect: RectF, durationSeconds: Int, complexity: AiSketchGenerator.Complexity) {
        aiJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val strokes = withContext(Dispatchers.IO) {
                    // 自动裁边：去除四周纯色边距
                    val trimmed = ImageContourExtractor.trimBorder(bitmap)
                    ImageContourExtractor.extract(trimmed, complexity)
                }
                if (strokes.isEmpty()) {
                    floatingBallView.onWebExtractFailed("轮廓提取失败，请尝试更清晰的图片")
                    Toast.makeText(this@FloatingWindowService, "轮廓提取失败，请尝试其他图片", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val template = DrawTemplate(name = "搜图绘制", keywords = listOf("web_image"), strokes = strokes)
                Log.d(TAG, "搜图轮廓提取成功: ${strokes.size} 笔, ${strokes.sumOf { it.points.size }} 个点")
                floatingBallView.onWebExtractComplete(template, "搜图绘制")
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Exception) {
                Log.e(TAG, "搜图轮廓提取失败", e)
                floatingBallView.onWebExtractFailed("提取出错: ${e.message}")
            }
        }
    }

    private fun startDrawingProcess(text: String, rect: RectF, durationSeconds: Int) {
        if (isDrawing) return
        val complexity = floatingBallView.getComplexity()

        if (complexity == AiSketchGenerator.Complexity.SIMPLE) {
            val builtIn = TemplateLibrary.findTemplate(text)
            if (builtIn != null) {
                floatingBallView.onAiGenerated(builtIn, text, rect, durationSeconds, complexity)
                return
            }
        }

        val config = prefs.getAiConfig()
        if (config.apiKey.isBlank()) {
            Toast.makeText(this, "未找到内置图案，请在设置中配置 AI API Key 以支持任意内容", Toast.LENGTH_LONG).show()
            return
        }

        floatingBallView.showLoading("AI 正在构思 \"$text\"...")
        aiJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val imageModel = floatingBallView.getImageModel()
                val template = aiGenerator.generate(text, config, complexity, imageModel)
                if (template == null) {
                    floatingBallView.hideLoading()
                    floatingBallView.updateStatus("AI 生成失败，请检查网络或 API Key", 0xFFFF4444.toInt())
                    Toast.makeText(this@FloatingWindowService, "AI 生成失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                floatingBallView.hideLoading()
                floatingBallView.onAiGenerated(template, text, rect, durationSeconds, complexity)
            } catch (e: CancellationException) {
                floatingBallView.hideLoading()
            } catch (e: Exception) {
                floatingBallView.hideLoading()
                floatingBallView.updateStatus("AI 生成出错: ${e.message}", 0xFFFF4444.toInt())
                Toast.makeText(this@FloatingWindowService, "生成出错: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeDraw(
        template: DrawTemplate,
        text: String,
        rect: RectF,
        complexity: AiSketchGenerator.Complexity = AiSketchGenerator.Complexity.SIMPLE,
        requestedDurationSeconds: Int = prefs.getDrawDurationSeconds()
    ) {
        isDrawing = true
        isPaused = false
        val totalStrokes = template.strokes.size
        val durationSeconds = when (complexity) {
            AiSketchGenerator.Complexity.MAX -> (requestedDurationSeconds * 2.0).toInt().coerceAtMost(180)
            AiSketchGenerator.Complexity.COMPLEX -> (requestedDurationSeconds * 1.5).toInt().coerceAtMost(120)
            else -> requestedDurationSeconds
        }
        floatingBallView.enterDrawingMode(text, totalStrokes, durationSeconds)

        // 自动隐藏悬浮球
        floatingBallView.autoHideDuringDrawing(true)

        val sortMode = floatingBallView.getSortMode()
        // v3.1: 应用绘制模式
        com.simpledrawbot.DrawingAccessibilityService.drawMode = floatingBallView.getDrawMode()
        com.simpledrawbot.ai.ImageContourExtractor.singleLineMode = floatingBallView.isSingleLineMode()
        com.simpledrawbot.ai.ImageContourExtractor.detailLevel = floatingBallView.getDetailLevel()
        DrawingAccessibilityService.instance?.startDrawing(
            template, rect, durationSeconds,
            callback = { success ->
                isDrawing = false
                isPaused = false
                floatingBallView.autoHideDuringDrawing(false)
                floatingBallView.exitDrawingMode()
                if (success) {
                    floatingBallView.showDrawingComplete("${template.name} 画好了！")
                }
            },
            sortMode = sortMode,
            progressCb = object : DrawingAccessibilityService.DrawProgressCallback {
                override fun onProgress(current: Int, total: Int) {
                    val elapsed = (current.toFloat() / total * durationSeconds).toInt()
                    floatingBallView.updateDrawProgress(current, total, elapsed, durationSeconds)
                }
                override fun onStateChanged(paused: Boolean) {
                    isPaused = paused
                    floatingBallView.onPauseStateChanged(paused)
                }
            }
        )
    }

    private fun togglePauseResume() {
        if (!isDrawing) return
        if (isPaused) {
            DrawingAccessibilityService.instance?.resumeDrawing()
            isPaused = false
        } else {
            DrawingAccessibilityService.instance?.pauseDrawing()
            isPaused = true
        }
    }

    private fun stopDrawing() {
        isDrawing = false
        isPaused = false
        aiJob?.cancel()
        countdownJob?.cancel()
        DrawingAccessibilityService.instance?.stopDrawing()
        floatingBallView.autoHideDuringDrawing(false)
        floatingBallView.exitDrawingMode()
        floatingBallView.hideLoading()
        floatingBallView.updateStatus("已停止")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "简易画图助手", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "悬浮球运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingWindowService::class.java).apply { action = ACTION_STOP_DRAW }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("画图助手运行中")
            .setContentText("点击悬浮球输入要画的内容")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止画图", stopPendingIntent)
            .build()
    }
}
