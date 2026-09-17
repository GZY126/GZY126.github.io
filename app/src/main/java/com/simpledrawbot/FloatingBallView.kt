package com.simpledrawbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * 悬浮球视图：可展开/收起的浮动 UI
 * 支持两种模式：AI 生成 / 搜图绘制
 */
class FloatingBallView(private val context: Context) {

    interface Callback {
        fun onStartDraw(text: String, rect: android.graphics.RectF, durationSeconds: Int)
        fun onConfirmDraw(
            template: Any,
            text: String,
            rect: android.graphics.RectF,
            durationSeconds: Int,
            complexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity
        )
        fun onStopDraw()
        fun onPauseResumeDraw()
        fun onSelectArea()
        fun onOpenSettings()
        fun onClose()
        fun onDrawFromImage(
            bitmap: Bitmap,
            rect: android.graphics.RectF,
            durationSeconds: Int,
            complexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity
        )
    }

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 统一的协程作用域，用于管理所有异步任务生命周期
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var downloadJob: Job? = null

    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var areaSelectorView: AreaSelectorView? = null

    private var collapsedParams: WindowManager.LayoutParams? = null
    private var expandedParams: WindowManager.LayoutParams? = null
    private var selectorParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var selectedRect: android.graphics.RectF? = null
    private var callback: Callback? = null

    // AI 生成模式控件
    private var layoutAiGenerate: LinearLayout? = null
    private var layoutWebSearch: LinearLayout? = null
    private var tabAiGenerate: TextView? = null
    private var tabWebSearch: TextView? = null

    private var inputText: EditText? = null
    private var tvStatus: TextView? = null
    private var progressBar: ProgressBar? = null
    private var rgMode: RadioGroup? = null
    private var rbSimple: RadioButton? = null
    private var rbComplex: RadioButton? = null
    private var rbMax: RadioButton? = null
    private var rgModel: RadioGroup? = null
    private var rbZTurbo: RadioButton? = null
    private var rbZImage: RadioButton? = null
    private var sliderDuration: com.google.android.material.slider.Slider? = null
    private var tvDuration: TextView? = null

    // 预览控件
    private var layoutPreview: FrameLayout? = null
    private var ivPreview: ImageView? = null
    private var tvPreviewStrokes: TextView? = null
    private var btnPreviewDraw: Button? = null

    // 绘制模式控件
    private var layoutDrawingControl: LinearLayout? = null
    private var btnPauseResume: Button? = null
    private var progressDraw: ProgressBar? = null
    private var tvDrawProgress: TextView? = null
    private var tvDrawEta: TextView? = null
    private var cbCountdown: CheckBox? = null
    private var tvCountdownOverlay: TextView? = null
    private var btnConfirmDraw: Button? = null
    private var btnStartDraw: Button? = null
    private var btnStop: Button? = null
    private var btnSelectArea: Button? = null
    // 预览裁剪
    private var layoutPreviewButtons: LinearLayout? = null
    private var btnCropPreview: Button? = null
    private var btnResetCrop: Button? = null
    private var rgDrawMode: RadioGroup? = null
    private var rgSortMode: RadioGroup? = null
    private var rgThickness: RadioGroup? = null
    private var cbSingleLine: CheckBox? = null
    private var sliderDetail: com.google.android.material.slider.Slider? = null

    // 速度调节控件
    private var btnSpeedDown: ImageButton? = null
    private var btnSpeedUp: ImageButton? = null
    private var tvSpeedLabel: TextView? = null
    private var currentSpeedMultiplier: Float = 1.0f

    // 搜图模式控件
    private var webViewSearch: WebView? = null
    private var tvWebStatus: TextView? = null
    private var btnWebExtractDraw: Button? = null
    private var btnWebClearImage: Button? = null
    private var etWebSearchKeyword: EditText? = null
    private var btnWebSearch: Button? = null

    // 当前选中的标签
    private var currentTab: String = "ai_generate"

    // 缓存：AI 生成完成后暂存模板
    private var pendingTemplate: Any? = null
    private var pendingText: String = ""
    private var pendingRect: android.graphics.RectF? = null
    private var pendingDuration: Int = 30
    private var pendingComplexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity =
        com.simpledrawbot.ai.AiSketchGenerator.Complexity.SIMPLE

    // 搜图模式：已下载的图片
    private var downloadedImageBitmap: Bitmap? = null
    // 拦截图片请求：缓存最大图片的字节数据
    private var largestImageBytes: ByteArray? = null
    // JS转Base64的待处理数据
    private var pendingBase64: String? = null
    // Top3候选图选择
    private val candidateImages = mutableListOf<CandidateImage>()
    private var pendingCandidatesReady = false
    private var pendingExtractRect: android.graphics.RectF? = null
    private var pendingExtractDuration = 30
    private var pendingExtractComplexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity? = null
    private data class CandidateImage(
        val base64: String, val width: Int, val height: Int, val score: Int
    )
    // 预览裁剪：原始完整模板 + 裁剪区域(归一化坐标0~1)
    private var originalFullTemplate: com.simpledrawbot.model.DrawTemplate? = null
    // 实时预览：全量笔画缓存（不重跑OpenCV，只内存过滤）
    private var allStrokesCache: List<com.simpledrawbot.model.Stroke>? = null
    private var cropRect: android.graphics.RectF? = null  // 归一化 0~1
    private var isCropping = false
    // 笔画删除+撤回
    private var deletedStrokeHistory = mutableListOf<com.simpledrawbot.model.Stroke>()
    private var btnUndoDelete: Button? = null
    private var btnDeleteStroke: Button? = null
    private var downloadedImageFile: File? = null

    // 图片下载目录
    private val imageDownloadDir: File by lazy {
        File(context.cacheDir, "web_images").also { it.mkdirs() }
    }

    private val prefs by lazy { com.simpledrawbot.util.PreferenceHelper(context) }

    fun setCallback(cb: Callback) { callback = cb }

    fun getComplexity(): com.simpledrawbot.ai.AiSketchGenerator.Complexity {
        return when (rgMode?.checkedRadioButtonId) {
            R.id.rbMax -> com.simpledrawbot.ai.AiSketchGenerator.Complexity.MAX
            R.id.rbComplex -> com.simpledrawbot.ai.AiSketchGenerator.Complexity.COMPLEX
            else -> com.simpledrawbot.ai.AiSketchGenerator.Complexity.SIMPLE
        }
    }

    fun getImageModel(): String {
        return if (rgModel?.checkedRadioButtonId == R.id.rbZImage) "zimage" else "zturbo"
    }

    fun getSortMode(): com.simpledrawbot.DrawingAccessibilityService.SortMode {
        return when (rgSortMode?.checkedRadioButtonId) {
            R.id.rbSortTopDown -> com.simpledrawbot.DrawingAccessibilityService.SortMode.TOP_TO_BOTTOM
            R.id.rbSortNone -> com.simpledrawbot.DrawingAccessibilityService.SortMode.NONE
            else -> com.simpledrawbot.DrawingAccessibilityService.SortMode.TSP
        }
    }

    fun getDrawMode(): com.simpledrawbot.DrawingAccessibilityService.DrawMode {
        return when (rgDrawMode?.checkedRadioButtonId) {
            R.id.rbDrawPrecise -> com.simpledrawbot.DrawingAccessibilityService.DrawMode.PRECISE
            else -> com.simpledrawbot.DrawingAccessibilityService.DrawMode.AUTO
        }
    }

    fun isSingleLineMode(): Boolean = cbSingleLine?.isChecked ?: true
    fun getDetailLevel(): Int = sliderDetail?.value?.toInt() ?: 60

    fun show(): Boolean {
        return try {
            createCollapsedView()
            createExpandedView()
            collapsedView?.let { windowManager.addView(it, collapsedParams) }
            expandedView?.let { v ->
                v.visibility = View.GONE
                windowManager.addView(v, expandedParams)
            }
            android.util.Log.d("FloatingBallView", "悬浮球已显示")
            true
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallView", "显示悬浮球失败", e)
            Toast.makeText(context, "悬浮球显示失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun hide() {
        // 取消所有协程
        viewScope.cancel()
        downloadJob?.cancel()

        // 先释放 WebView（必须在父 View 被 remove 之前）
        try {
            webViewSearch?.apply {
                stopLoading()
                destroy()
            }
        } catch (_: Exception) {}
        webViewSearch = null

        // 释放下载的图片
        downloadedImageBitmap?.recycle()
        downloadedImageBitmap = null
        downloadedImageFile?.delete()
        downloadedImageFile = null

        // 再移除 WindowManager 中的视图
        try {
            collapsedView?.let { windowManager.removeView(it) }
            expandedView?.let { windowManager.removeView(it) }
            areaSelectorView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}

        collapsedView = null
        expandedView = null
        areaSelectorView = null
    }

    fun updateStatus(message: String, color: Int = 0xFF666666.toInt()) {
        tvStatus?.apply {
            text = message
            setBackgroundColor(color)
        }
    }

    fun showLoading(message: String) {
        progressBar?.visibility = View.VISIBLE
        updateStatus(message, 0xFFFF8C42.toInt())
        setButtonsEnabled(false)
    }

    fun hideLoading() {
        progressBar?.visibility = View.GONE
        layoutPreview?.visibility = View.GONE
        layoutPreviewButtons?.visibility = View.GONE
        btnPreviewDraw?.visibility = View.GONE
        setButtonsEnabled(true)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnSelectArea?.isEnabled = enabled
        btnStartDraw?.isEnabled = enabled
        btnStop?.isEnabled = true
    }

    fun onAiGenerated(
        template: Any,
        text: String,
        rect: android.graphics.RectF,
        duration: Int,
        complexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity
    ) {
        pendingTemplate = template
        pendingText = text
        pendingRect = rect
        pendingDuration = duration
        pendingComplexity = complexity

        btnStartDraw?.visibility = View.GONE
        btnPreviewDraw?.visibility = View.VISIBLE
        btnConfirmDraw?.visibility = View.VISIBLE
        btnStop?.visibility = View.VISIBLE
        updateStatus("构思完成，可预览后点击「开始画」", 0xFF2196F3.toInt())

        // 自动显示预览
        if (template is com.simpledrawbot.model.DrawTemplate) {
            showPreview(template)
        }
    }

    fun showDrawingComplete(message: String) {
        if (expandedView != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            updateStatus("就绪")
        }
    }

    // ================================================================
    //  标签切换
    // ================================================================

    private fun switchTab(tab: String) {
        currentTab = tab
        if (tab == "ai_generate") {
            tabAiGenerate?.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_tab_selected)
            }
            tabWebSearch?.apply {
                setTextColor(0xFF666666.toInt())
                setBackgroundResource(R.drawable.bg_tab_unselected)
            }
            layoutAiGenerate?.visibility = View.VISIBLE
            layoutWebSearch?.visibility = View.GONE
        } else {
            tabAiGenerate?.apply {
                setTextColor(0xFF666666.toInt())
                setBackgroundResource(R.drawable.bg_tab_unselected)
            }
            tabWebSearch?.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_tab_selected)
            }
            layoutAiGenerate?.visibility = View.GONE
            layoutWebSearch?.visibility = View.VISIBLE

            // 首次切换到搜图模式时加载百度搜图
            if (webViewSearch?.url == null) {
                initWebView()
            }
        }
    }

    // ================================================================
    //  收起的悬浮球
    // ================================================================

    private fun createCollapsedView() {
        val ballSize = context.resources.getDimensionPixelSize(R.dimen.floating_ball_size)
        val ballMargin = context.resources.getDimensionPixelSize(R.dimen.floating_ball_margin)
        val view = android.widget.ImageView(context).apply {
            setBackgroundResource(R.drawable.bg_floating_ball_fallback)
            try {
                setImageResource(R.drawable.ic_floating_ball)
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallView", "悬浮球图标加载失败，使用兜底背景", e)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }

        val params = WindowManager.LayoutParams(
            ballSize, ballSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballMargin
            y = ballMargin * 3
        }

        collapsedParams = params
        collapsedView = view

        view.setOnClickListener { toggleExpand() }

        setupDrag(view, params)
    }

    // ================================================================
    //  展开的面板
    // ================================================================

    private fun createExpandedView() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.floating_panel, null)
        val panelWidth = context.resources.getDimensionPixelSize(R.dimen.floating_panel_width)
        val ballMargin = context.resources.getDimensionPixelSize(R.dimen.floating_ball_margin)

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballMargin
            y = ballMargin * 3
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        expandedParams = params
        expandedView = view

        // --- 标签 ---
        tabAiGenerate = view.findViewById(R.id.tabAiGenerate)
        tabWebSearch = view.findViewById(R.id.tabWebSearch)
        layoutAiGenerate = view.findViewById(R.id.layoutAiGenerate)
        layoutWebSearch = view.findViewById(R.id.layoutWebSearch)

        tabAiGenerate?.setOnClickListener { switchTab("ai_generate") }
        tabWebSearch?.setOnClickListener { switchTab("web_search") }

        // --- AI 生成控件 ---
        inputText = view.findViewById(R.id.etInput)
        btnSelectArea = view.findViewById(R.id.btnSelectArea)
        btnStartDraw = view.findViewById(R.id.btnStartDraw)
        btnConfirmDraw = view.findViewById(R.id.btnConfirmDraw)
        btnStop = view.findViewById(R.id.btnStop)
        tvStatus = view.findViewById(R.id.tvStatus)
        progressBar = view.findViewById(R.id.progressBar)
        rgMode = view.findViewById(R.id.rgMode)
        rbSimple = view.findViewById(R.id.rbSimple)
        rbComplex = view.findViewById(R.id.rbComplex)
        rbMax = view.findViewById(R.id.rbMax)
        rgModel = view.findViewById(R.id.rgModel)
        rbZTurbo = view.findViewById(R.id.rbZTurbo)
        rbZImage = view.findViewById(R.id.rbZImage)
        sliderDuration = view.findViewById(R.id.sliderDuration)
        tvDuration = view.findViewById(R.id.tvDuration)
        val btnCollapse = view.findViewById<ImageButton>(R.id.btnCollapse)

        // --- 预览控件 ---
        layoutPreview = view.findViewById(R.id.layoutPreview)
        ivPreview = view.findViewById(R.id.ivPreview)
        tvPreviewStrokes = view.findViewById(R.id.tvPreviewStrokes)
        btnPreviewDraw = view.findViewById(R.id.btnPreviewDraw)
        layoutPreviewButtons = view.findViewById(R.id.layoutPreviewButtons)
        btnCropPreview = view.findViewById(R.id.btnCropPreview)
        btnResetCrop = view.findViewById(R.id.btnResetCrop)
        btnUndoDelete = view.findViewById(R.id.btnUndoDelete)
        btnDeleteStroke = view.findViewById(R.id.btnDeleteStroke)

        // --- 速度调节控件 ---
        btnSpeedDown = view.findViewById(R.id.btnSpeedDown)
        btnSpeedUp = view.findViewById(R.id.btnSpeedUp)
        tvSpeedLabel = view.findViewById(R.id.tvSpeedLabel)

        // --- 画法模式 ---
        rgDrawMode = view.findViewById(R.id.rgDrawMode)

        // --- 绘制控制控件 ---
        layoutDrawingControl = view.findViewById(R.id.layoutDrawingControl)
        btnPauseResume = view.findViewById(R.id.btnPauseResume)
        progressDraw = view.findViewById(R.id.progressDraw)
        tvDrawProgress = view.findViewById(R.id.tvDrawProgress)
        tvDrawEta = view.findViewById(R.id.tvDrawEta)
        cbCountdown = view.findViewById(R.id.cbCountdown)
        // tvCountdownOverlay 已从 v2.2 UI 中移除，倒计时改用 Toast 提示
        tvCountdownOverlay = null
        rgSortMode = view.findViewById(R.id.rgSortMode)
        rgThickness = view.findViewById(R.id.rgThickness)
        cbSingleLine = view.findViewById(R.id.cbSingleLine)
        sliderDetail = view.findViewById(R.id.sliderDetail)
        // 细节程度滑块实时预览
        sliderDetail?.addOnChangeListener { _, value, _ ->
            com.simpledrawbot.ai.ImageContourExtractor.detailLevel = value.toInt()
            // 如果有缓存的全量笔画，实时过滤并重新渲染预览
            val cache = allStrokesCache
            if (cache != null && cache.isNotEmpty()) {
                val filtered = filterStrokesByDetail(cache, value.toInt())
                val updated = com.simpledrawbot.model.DrawTemplate("preview", listOf(), filtered)
                pendingTemplate = updated
                originalFullTemplate = updated
                renderPreviewBitmap(updated)
            }
        }

        // --- 搜图控件 ---
        webViewSearch = view.findViewById(R.id.webViewSearch)
        tvWebStatus = view.findViewById(R.id.tvWebStatus)
        btnWebExtractDraw = view.findViewById(R.id.btnWebExtractDraw)
        btnWebClearImage = view.findViewById(R.id.btnWebClearImage)
        etWebSearchKeyword = view.findViewById(R.id.etWebSearchKeyword)
        btnWebSearch = view.findViewById(R.id.btnWebSearch)

        btnConfirmDraw?.visibility = View.GONE

        // 恢复保存的绘画模式
        val savedComplexity = prefs.getComplexity()
        when (savedComplexity) {
            "max" -> rbMax?.isChecked = true
            "complex" -> rbComplex?.isChecked = true
            else -> rbSimple?.isChecked = true
        }

        // 恢复保存的模型选择
        val savedModel = prefs.getImageModel()
        if (savedModel == "zimage") rbZImage?.isChecked = true
        else rbZTurbo?.isChecked = true

        // 恢复保存的绘画时长
        val savedDuration = prefs.getDrawDurationSeconds().coerceIn(5, 60)
        sliderDuration?.value = savedDuration.toFloat()
        tvDuration?.text = "${savedDuration}s"

        // 模式切换时保存
        rgMode?.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbMax -> "max"
                R.id.rbComplex -> "complex"
                else -> "simple"
            }
            prefs.saveComplexity(mode)
        }

        // 模型切换时保存
        rgModel?.setOnCheckedChangeListener { _, checkedId ->
            val model = if (checkedId == R.id.rbZImage) "zimage" else "zturbo"
            prefs.saveImageModel(model)
        }

        sliderDuration?.addOnChangeListener { _, value, _ ->
            val secs = value.toInt()
            tvDuration?.text = "${secs}s"
            prefs.saveDrawDurationSeconds(secs)
        }

        // 点击事件
        btnCollapse.setOnClickListener { toggleExpand() }

        btnSelectArea?.setOnClickListener { callback?.onSelectArea() }

        btnStartDraw?.setOnClickListener {
            val text = inputText?.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                Toast.makeText(context, "请输入要画的内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val rect = selectedRect
            if (rect == null) {
                Toast.makeText(context, "请先选择绘画区域", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val duration = sliderDuration?.value?.toInt() ?: prefs.getDrawDurationSeconds()
            callback?.onStartDraw(text, rect, duration)
        }

        btnConfirmDraw?.setOnClickListener {
            val template = pendingTemplate
            val text = pendingText
            val rect = pendingRect
            val duration = pendingDuration
            val complexity = pendingComplexity

            if (template != null && rect != null) {
                btnConfirmDraw?.visibility = View.GONE
                btnStartDraw?.visibility = View.VISIBLE
                btnStop?.visibility = View.VISIBLE
                updateStatus("正在画: $text", 0xFF4CAF50.toInt())
                callback?.onConfirmDraw(template, text, rect, duration, complexity)
            }
            pendingTemplate = null
        }

        btnStop?.setOnClickListener { callback?.onStopDraw() }

        // --- 暂停/继续按钮 ---
        btnPauseResume?.setOnClickListener { callback?.onPauseResumeDraw() }

        // --- 预览按钮 ---
        btnPreviewDraw?.setOnClickListener {
            val template = pendingTemplate
            if (template is com.simpledrawbot.model.DrawTemplate) {
                showPreview(template)
            }
        }

        // 裁剪按钮：提示用户在预览区拖拽选择区域
        btnCropPreview?.setOnClickListener {
            Toast.makeText(context, "在预览图上拖动选择要保留的区域", Toast.LENGTH_SHORT).show()
        }

        // 重置裁剪：恢复完整预览
        btnResetCrop?.setOnClickListener {
            cropRect = null
            val ot = originalFullTemplate
            if (ot != null) {
                pendingTemplate = ot
                deletedStrokeHistory.clear()
                btnUndoDelete?.visibility = View.GONE
                renderPreviewBitmap(ot)
                btnResetCrop?.visibility = View.GONE
                tvPreviewStrokes?.text = "${ot.strokes.size} 笔"
                updateStatus("已恢复完整预览", 0xFF2196F3.toInt())
            }
        }

        // 撤回删除：恢复最后删除的笔画
        btnUndoDelete?.setOnClickListener { undoDeleteStroke() }

        // 删除笔画提示
        btnDeleteStroke?.setOnClickListener {
            Toast.makeText(context, "点击预览图中的笔画即可删除", Toast.LENGTH_SHORT).show()
        }

        // --- 速度调节按钮 ---
        btnSpeedDown?.setOnClickListener { adjustSpeed(-0.25f) }
        btnSpeedUp?.setOnClickListener { adjustSpeed(0.25f) }

        // --- 搜图搜索栏 ---
        btnWebSearch?.setOnClickListener {
            val keyword = etWebSearchKeyword?.text?.toString()?.trim() ?: ""
            if (keyword.isBlank()) {
                Toast.makeText(context, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            } else {
                performWebSearch(keyword)
            }
        }
        etWebSearchKeyword?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val keyword = etWebSearchKeyword?.text?.toString()?.trim() ?: ""
                if (keyword.isNotBlank()) performWebSearch(keyword)
                true
            } else false
        }

        // --- 搜图按钮 ---
        btnWebExtractDraw?.setOnClickListener {
            val rect = selectedRect
            if (rect == null) {
                Toast.makeText(context, "请先选择绘画区域", Toast.LENGTH_SHORT).show()
                callback?.onSelectArea()
                return@setOnClickListener
            }
            val duration = sliderDuration?.value?.toInt() ?: prefs.getDrawDurationSeconds()
            val complexity = getComplexity()
            pendingExtractRect = rect
            pendingExtractDuration = duration
            pendingExtractComplexity = complexity
            tvWebStatus?.text = "正在查找页面图片..."
            tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
            btnWebExtractDraw?.isEnabled = false

            // 简化方案：JS收集所有大图URL → 原生下载缩略图 → 弹出选择面板
            collectImageUrls { urls ->
                if (urls.isNotEmpty()) {
                    tvWebStatus?.text = "找到${urls.size}张图，加载缩略图..."
                    downloadThumbnailsForPicker(urls, rect, duration, complexity)
                } else {
                    // 降级：WebView截图
                    tvWebStatus?.text = "未找到图片，使用截图..."
                    val screenshot = captureWebViewBitmap()
                    if (screenshot != null) {
                        callback?.onDrawFromImage(screenshot, rect, duration, complexity)
                    } else {
                    showExtractFailed()
                    }
                }
            }
        }

        btnWebClearImage?.setOnClickListener {
            downloadedImageBitmap?.recycle()
            downloadedImageBitmap = null
            downloadedImageFile?.delete()
            downloadedImageFile = null
            btnWebClearImage?.visibility = View.GONE
            btnWebExtractDraw?.isEnabled = false
            tvWebStatus?.text = "长按图片可下载，然后点击「提取轮廓画图」"
            tvWebStatus?.setBackgroundColor(0xFF666666.toInt())
        }

        // 拖拽
        setupDrag(view, params)

        // 初始化默认标签
        switchTab("ai_generate")
    }

    // ================================================================
    //  WebView 初始化
    // ================================================================

    private fun initWebView() {
        val wv = webViewSearch ?: return

        // 启用 WebView 调试（仅 debug 构建，方便 Chrome://inspect 抓日志）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-P610) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            savePassword = false
            databaseEnabled = false
            // 关键：允许不安全内容、禁用硬件加速可能的问题
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            // 阻止混合内容的自动升级（部分 ROM 会强制升级 http→https 导致失败）
            blockNetworkLoads = false
            blockNetworkImage = false
        }

        // 关闭硬件加速的潜在问题（部分平板 GPU 渲染 WebView 在悬浮窗中异常）
        wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        wv.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onImageClick(imageUrl: String) {
                android.util.Log.d("FloatingBallView", "JS图片点击: $imageUrl")
                mainHandler.post { downloadImageFromUrl(imageUrl) }
            }

            @android.webkit.JavascriptInterface
            fun onImageData(base64: String, width: Int, height: Int) {
                android.util.Log.d("FloatingBallView", "JS base64回调: ${width}x${height}, len=${base64.length}")
                if (base64.isNotEmpty() && width >= 50 && height >= 50) {
                    mainHandler.post { pendingBase64 = base64 }
                }
            }

            @android.webkit.JavascriptInterface
            fun onCandidateData(index: Int, base64: String, width: Int, height: Int, score: Int) {
                android.util.Log.d("FloatingBallView",
                    "JS候选#${index}: ${width}x${height} score=$score len=${base64.length}")
                if (base64.isNotEmpty() && width >= 50 && height >= 50) {
                    mainHandler.post {
                        candidateImages.add(CandidateImage(base64, width, height, score))
                    }
                }
            }

            @android.webkit.JavascriptInterface
            fun onCandidatesReady(count: Int) {
                android.util.Log.d("FloatingBallView", "JS候选就绪: $count 张")
                mainHandler.post {
                    if (!pendingCandidatesReady) {
                        pendingCandidatesReady = true
                        if (candidateImages.isNotEmpty()) {
                            // 弹出选择面板（rect/duration/complexity通过闭包传递）
                            showCandidatePickerFromJs()
                        }
                    }
                }
            }
        }, "ImageClickBridge")

        wv.webViewClient = object : WebViewClient() {
            private var mainFrameError = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameError = false
                tvWebStatus?.text = "加载中..."
                tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
                android.util.Log.d("FloatingBallView", "WebView onPageStarted: $url")
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // 页面内容已渲染，说明网络和 WebView 都正常
                tvWebStatus?.text = "点击图片即可下载，然后点「提取轮廓画图」"
                tvWebStatus?.setBackgroundColor(0xFF666666.toInt())
                android.util.Log.d("FloatingBallView", "WebView onPageCommitVisible: $url")
                // 注入 JS（在内容可见后注入更安全）
                injectBaiduImageJs(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.d("FloatingBallView", "WebView onPageFinished: $url")
                if (!mainFrameError) {
                    // 二次注入兜底
                    injectBaiduImageJs(view)
                }
            }

            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                android.util.Log.w("FloatingBallView", "SSL error: primary=${error?.primaryError}, url=${error?.url}")
                // 百度 SSL 证书通常没问题，但部分 CDN 域名可能有问题
                handler?.proceed()
            }

            // API 21-22 的旧回调
            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
            ) {
                android.util.Log.e("FloatingBallView", "WebView error(deprecated): $errorCode $description $failingUrl")
                // 只有主页面的错误才显示（子资源错误不显示）
                if (failingUrl != null && failingUrl == view?.url) {
                    mainFrameError = true
                    showWebError("网络错误: $description")
                }
            }

            // API 23+ 的新回调
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                val isMainFrame = request?.isForMainFrame == true
                android.util.Log.e("FloatingBallView",
                    "WebView error: code=${error?.errorCode}, desc=${error?.description}, " +
                    "url=${request?.url}, isMainFrame=$isMainFrame")
                if (isMainFrame) {
                    mainFrameError = true
                    showWebError("网络加载失败(${error?.errorCode})")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                val isMainFrame = request?.isForMainFrame == true
                android.util.Log.e("FloatingBallView",
                    "HTTP error: ${errorResponse?.statusCode}, url=${request?.url}, isMainFrame=$isMainFrame")
                if (isMainFrame && (errorResponse?.statusCode ?: 0) >= 400) {
                    mainFrameError = true
                    showWebError("服务器错误(${errorResponse?.statusCode})")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                android.util.Log.d("FloatingBallView", "shouldOverrideUrlLoading: ${request?.url}")
                return false
            }

            // 拦截图片请求：缓存最大图片的字节数据
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                // 只处理图片类型请求
                if (url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") ||
                    url.contains(".webp") || url.contains(".gif") || url.contains("bdimg.com") ||
                    url.contains("bdstatic.com") || url.contains("img?") || url.contains("/image/")) {
                    // 异步下载并缓存
                    viewScope.launch(Dispatchers.IO) {
                        try {
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val req = okhttp3.Request.Builder().url(url).get()
                                .header("Referer", "https://image.baidu.com")
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-T870) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36")
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val bytes = resp.body?.bytes() ?: return@launch
                                    // 只缓存 > 10KB 的图片（过滤小图标）
                                    if (bytes.size > 10240) {
                                        // 跟踪最大的图片
                                        if (largestImageBytes == null || bytes.size > (largestImageBytes?.size ?: 0)) {
                                            largestImageBytes = bytes
                                            android.util.Log.d("FloatingBallView",
                                                "拦截图片: ${bytes.size/1024}KB ${bytes.size}bytes")
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                return null // 返回null让WebView正常加载
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                android.util.Log.d("FloatingBallView", "WebView progress: $newProgress%")
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                android.util.Log.d("FloatingBallView", "WebView title: $title")
            }
        }

        wv.setDownloadListener { url, _, _, mimeType, _ ->
            android.util.Log.d("FloatingBallView", "下载请求: $url, mime: $mimeType")
            if (mimeType?.startsWith("image/") == true || url.contains(".jpg") ||
                url.contains(".png") || url.contains(".jpeg") || url.contains(".webp") ||
                url.contains(".gif")) {
                downloadImageFromUrl(url)
            } else {
                Toast.makeText(context, "请点击图片进行下载", Toast.LENGTH_SHORT).show()
            }
        }

        // 加载百度搜图（使用移动端页面，兼容 WebView）
        android.util.Log.d("FloatingBallView", "开始加载百度搜图...")
        wv.loadUrl("https://image.baidu.com/")
    }

    private fun showWebError(msg: String) {
        tvWebStatus?.post {
            tvWebStatus?.text = msg
            tvWebStatus?.setBackgroundColor(0xFFFF4444.toInt())
        }
    }

    /**
     * 注入 JS：净化百度图片页面 + 点击取图
     * 所有代码包裹 try-catch，防止 JS 报错阻断页面加载
     */
    private fun injectBaiduImageJs(view: WebView?) {
        val js = """
        (function(){
            try{
                // ========== 1. 页面净化：移除广告、导航、弹窗 ==========
                var hideSelectors = [
                    '#head','.head-wrapper','.top-nav','#foot','.foot-wrapper',
                    '.app-download','.app-guide','.recommend','.related-search',
                    '.popup','.modal','.mask','.float-bar','.bottom-bar','.share-bar',
                    '.c-ad-block','.ec_wise_ad','.ad-block','[class*="ad-"]',
                    '[id*="banner"]','[class*="banner"]'
                ];
                for(var s=0;s<hideSelectors.length;s++){
                    var els = document.querySelectorAll(hideSelectors[s]);
                    for(var j=0;j<els.length;j++){ els[j].style.display='none'; }
                }
                // 移除 iframe
                var iframes = document.querySelectorAll('iframe');
                for(var k=0;k<iframes.length;k++){ try{iframes[k].remove()}catch(e){} }
                // 大图详情页多余元素（工具栏、下载按钮、推荐栏）
                var detailHide = ['#imgInfo','.tool-bar','.bottom-bar','.related-wrap',
                    '.share-wrap','.download-bar','.ai-tool','.fix-bar','.guide-wrap'];
                for(var d=0;d<detailHide.length;d++){
                    var els = document.querySelectorAll(detailHide[d]);
                    for(var j=0;j<els.length;j++){ els[j].style.display='none'; }
                }
            }catch(e){}

            try{
                // ========== 2. 点击取图 ==========
                // 适配 m.baidu.com 搜索结果页 + 大图详情页
                var imgSelector = [
                    // 大图详情页主图
                    '.current-img', '#currentImg', '.main-pic img', '.img-view-box img',
                    '.pic-content img', '.viewer-img', '.preview-img',
                    // 搜索结果网格页
                    'img.main_img', 'img.img', '.imgbox img', '.imgitem img',
                    'img[src*="baidu.com"]', 'img[data-imgurl]', 'img[data-src]',
                    '.img-wrapper img', '.image-wrap img', '[class*="img"] img',
                    '.card-image img', '.image-card img', '.pic-item img',
                    '.img-container img', '.result-item img', '.img-wrap img',
                    'img[src*="bdimg.com"]', 'img[src*="bdstatic.com"]'
                ].join(',');
                var imgs = document.querySelectorAll(imgSelector);
                for(var i=0;i<imgs.length;i++){
                    (function(el){
                        if(el._imgClickInited) return;
                        el._imgClickInited = true;
                        el.addEventListener('click', function(e){
                            e.preventDefault(); e.stopPropagation();
                            // 优先取原图地址：data-origin > data-imgurl > data-src > src
                            var src = el.getAttribute('data-origin') ||
                                      el.getAttribute('data-original') ||
                                      el.getAttribute('data-imgurl') ||
                                      el.getAttribute('data-src') ||
                                      el.getAttribute('data-url') ||
                                      el.getAttribute('src') ||
                                      el.currentSrc || '';
                            if(src && src.length>10 && src.indexOf('data:')!==0){
                                try{ ImageClickBridge.onImageClick(src); }catch(ex){}
                            }
                        }, true);
                    })(imgs[i]);
                }
            }catch(e){}

            // ========== 3. 大图定位裁剪 ==========
            try{
                var bigSelectors = ['.current-img','#currentImg','.main-pic img',
                    '.img-view-box img','.pic-content img','.viewer-img','.preview-img',
                    '.img-detail img','.big-img img'];
                var target = null;
                for(var s=0;s<bigSelectors.length;s++){
                    var el = document.querySelector(bigSelectors[s]);
                    if(el && (el.tagName==='IMG'||el.querySelector('img'))){
                        target = el.tagName==='IMG' ? el : el.querySelector('img');
                        break;
                    }
                }
                if(target && target.naturalWidth>0){
                    var rect = target.getBoundingClientRect();
                    if(rect.width>50 && rect.height>50){
                        ImageClickBridge.onCropRect(rect.left,rect.top,rect.width,rect.height);
                    }
                }
            }catch(e){}
        })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    /**
     * 从 URL 下载图片并保存到本地（使用统一的 viewScope 管理）
     */
    private fun downloadImageFromUrl(url: String) {
        downloadJob?.cancel()
        downloadJob = viewScope.launch(Dispatchers.IO) {
            try {
                tvWebStatus?.post {
                    tvWebStatus?.text = "正在下载图片..."
                    tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
                }

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder().url(url).get()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-T870) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Referer", "https://image.baidu.com")
                    .header("Accept", "image/webp,image/*,*/*;q=0.8")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        tvWebStatus?.post {
                            tvWebStatus?.text = "下载失败: HTTP ${response.code}"
                            tvWebStatus?.setBackgroundColor(0xFFFF4444.toInt())
                        }
                        return@launch
                    }

                    val bytes = response.body?.bytes() ?: return@launch

                    // 先计算采样率，防止大图 OOM（限制最长边 2048）
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val maxDim = maxOf(opts.outWidth, opts.outHeight)
                    val sampleSize = if (maxDim > 2048) (maxDim / 2048).coerceAtLeast(1) else 1

                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // 省一半内存
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)

                    if (bitmap == null) {
                        tvWebStatus?.post {
                            tvWebStatus?.text = "图片解码失败"
                            tvWebStatus?.setBackgroundColor(0xFFFF4444.toInt())
                        }
                        return@launch
                    }

                    // 保存文件
                    val fileName = "web_img_${System.currentTimeMillis()}.jpg"
                    val file = File(imageDownloadDir, fileName)
                    FileOutputStream(file).use { fos -> fos.write(bytes) }

                    // 释放旧图
                    downloadedImageBitmap?.recycle()
                    downloadedImageFile?.delete()

                    downloadedImageBitmap = bitmap
                    downloadedImageFile = file

                    tvWebStatus?.post {
                        tvWebStatus?.text = "图片已下载 (${bitmap.width}x${bitmap.height})，自动提取轮廓..."
                        tvWebStatus?.setBackgroundColor(0xFF4CAF50.toInt())
                        btnWebClearImage?.visibility = View.VISIBLE
                    }

                    android.util.Log.d("FloatingBallView",
                        "图片下载成功: ${bitmap.width}x${bitmap.height}, sampleSize=$sampleSize, 文件: ${file.absolutePath}")

                    // 自动触发提取轮廓
                    val rect = selectedRect
                    if (rect != null) {
                        val duration = sliderDuration?.value?.toInt() ?: prefs.getDrawDurationSeconds()
                        val complexity = getComplexity()
                        mainHandler.post {
                            callback?.onDrawFromImage(bitmap, rect, duration, complexity)
                        }
                    } else {
                        // 区域未选，提示但不自动提取
                        tvWebStatus?.post {
                            tvWebStatus?.text = "图片已下载，请先选区域再点「提取轮廓画图」"
                            tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
                            btnWebExtractDraw?.isEnabled = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消，正常
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallView", "图片下载失败", e)
                tvWebStatus?.post {
                    tvWebStatus?.text = "下载失败: ${e.message}"
                    tvWebStatus?.setBackgroundColor(0xFFFF4444.toInt())
                }
            }
        }
    }

    /**
     * 搜图模式提取完成后的回调
     */
    fun onWebExtractComplete(template: Any, text: String) {
        pendingTemplate = template
        pendingText = text
        pendingRect = selectedRect
        pendingDuration = sliderDuration?.value?.toInt() ?: 30
        pendingComplexity = getComplexity()

        tvWebStatus?.text = "轮廓提取完成，切换到「AI生成」标签确认绘制"
        tvWebStatus?.setBackgroundColor(0xFF2196F3.toInt())
        btnWebExtractDraw?.isEnabled = true

        // 切换到 AI 生成标签，显示确认按钮
        switchTab("ai_generate")
        btnStartDraw?.visibility = View.GONE
        btnPreviewDraw?.visibility = View.VISIBLE
        btnConfirmDraw?.visibility = View.VISIBLE
        btnStop?.visibility = View.VISIBLE
        updateStatus("搜图轮廓已提取，可预览后点击「开始画」", 0xFF2196F3.toInt())

        // 自动显示预览
        if (template is com.simpledrawbot.model.DrawTemplate) {
            showPreview(template)
        }
    }

    fun onWebExtractFailed(message: String) {
        tvWebStatus?.text = message
        tvWebStatus?.setBackgroundColor(0xFFFF4444.toInt())
        btnWebExtractDraw?.isEnabled = true
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop

        view.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    isDragging
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        isDragging = false
                        true
                    } else {
                        false
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    false
                }
                else -> false
            }
        }
    }

    // ================================================================
    //  区域选择器
    // ================================================================

    fun showAreaSelector() {
        if (areaSelectorView != null) return

        val selectorView = AreaSelectorView(context)
        selectedRect?.let { selectorView.setInitialRect(it) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        selectorView.setOnConfirm { rect ->
            selectedRect = rect
            hideAreaSelector()
            updateStatus(
                "区域已选: ${rect.width().toInt()}×${rect.height().toInt()}",
                0xFF2196F3.toInt()
            )
        }

        selectorView.setOnCancel { hideAreaSelector() }

        selectorParams = params
        areaSelectorView = selectorView
        try {
            windowManager.addView(selectorView, params)
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallView", "显示区域选择器失败", e)
            Toast.makeText(context, "区域选择器显示失败: ${e.message}", Toast.LENGTH_LONG).show()
            areaSelectorView = null
        }
    }

    fun hideAreaSelector() {
        areaSelectorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        areaSelectorView = null
    }

    // ================================================================
    //  笔画预览
    // ================================================================

    /**
     * 将模板的归一化笔画渲染为 Bitmap 并显示在预览区
     */
    private fun showPreview(template: com.simpledrawbot.model.DrawTemplate) {
        val preview = layoutPreview ?: return
        val iv = ivPreview ?: return

        // 保存原始完整模板（用于裁剪重置）
        originalFullTemplate = template
        allStrokesCache = template.strokes.toList() // 缓存全量供滑块实时过滤
        cropRect = null
        isCropping = false

        preview.visibility = View.VISIBLE
        layoutPreviewButtons?.visibility = View.VISIBLE
        btnResetCrop?.visibility = View.GONE
        tvPreviewStrokes?.text = "${template.strokes.size} 笔"

        // 启用触摸裁剪
        setupPreviewCrop(iv)

        renderPreviewBitmap(template)
    }

    /**
     * 渲染预览 Bitmap（支持裁剪区域过滤）
     */
    private fun renderPreviewBitmap(template: com.simpledrawbot.model.DrawTemplate) {
        val iv = ivPreview ?: return
        val crop = cropRect

        viewScope.launch(Dispatchers.IO) {
            try {
                val size = 720
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(0xFFFFFFFF.toInt())

                val paint = Paint().apply {
                    color = 0xFF000000.toInt(); strokeWidth = 5f
                    style = Paint.Style.STROKE; isAntiAlias = true
                    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                }
                val cropPaint = Paint().apply {
                    color = 0x40FF6B35.toInt(); style = Paint.Style.FILL; isAntiAlias = true
                }
                val cropBorder = Paint().apply {
                    color = 0xFFFF6B35.toInt(); style = Paint.Style.STROKE
                    strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
                }

                val pad = 20f; val drawSize = size - pad * 2

                for (stroke in template.strokes) {
                    val pts = stroke.points
                    if (pts.size < 2) continue

                    // 裁剪过滤：笔画中心点不在裁剪区域内则跳过
                    if (crop != null) {
                        val cx = pts.map { it.x }.average().toFloat()
                        val cy = pts.map { it.y }.average().toFloat()
                        if (cx < crop.left || cx > crop.right || cy < crop.top || cy > crop.bottom) continue
                    }

                    val path = Path()
                    path.moveTo(pad + pts[0].x * drawSize, pad + pts[0].y * drawSize)
                    if (pts.size > 2) {
                        for (i in 1 until pts.size - 1) {
                            val midX = (pts[i].x + pts[i + 1].x) / 2f
                            val midY = (pts[i].y + pts[i + 1].y) / 2f
                            path.quadTo(
                                pad + pts[i].x * drawSize, pad + pts[i].y * drawSize,
                                pad + midX * drawSize, pad + midY * drawSize
                            )
                        }
                    }
                    path.lineTo(pad + pts.last().x * drawSize, pad + pts.last().y * drawSize)
                    canvas.drawPath(path, paint)
                }

                // 绘制裁剪区域
                if (crop != null) {
                    // 外部半透明遮罩
                    val cl = pad + crop.left * drawSize; val ct = pad + crop.top * drawSize
                    val cr = pad + crop.right * drawSize; val cb = pad + crop.bottom * drawSize
                    canvas.drawRect(0f, 0f, size.toFloat(), ct, cropPaint)          // 上
                    canvas.drawRect(0f, cb, size.toFloat(), size.toFloat(), cropPaint) // 下
                    canvas.drawRect(0f, ct, cl, cb, cropPaint)                      // 左
                    canvas.drawRect(cr, ct, size.toFloat(), cb, cropPaint)           // 右
                    canvas.drawRect(cl, ct, cr, cb, cropBorder)                      // 边框

                    // 四角把手（8px圆角矩形，方便看清可拖动）
                    val handlePaint = Paint().apply {
                        color = 0xFFFF6B35.toInt(); style = Paint.Style.FILL; isAntiAlias = true
                    }
                    val handleSize = 12f
                    val corners = listOf(
                        Pair(cl - handleSize/2, ct - handleSize/2),
                        Pair(cr - handleSize/2, ct - handleSize/2),
                        Pair(cl - handleSize/2, cb - handleSize/2),
                        Pair(cr - handleSize/2, cb - handleSize/2)
                    )
                    for ((hx, hy) in corners) {
                        canvas.drawRoundRect(hx, hy, hx + handleSize, hy + handleSize, 3f, 3f, handlePaint)
                    }
                }

                withContext(Dispatchers.Main) {
                    iv.setImageBitmap(bitmap)
                    val filteredCount = if (crop != null) template.strokes.count {
                        val cx = it.points.map { p -> p.x }.average().toFloat()
                        val cy = it.points.map { p -> p.y }.average().toFloat()
                        cx in crop.left..crop.right && cy in crop.top..crop.bottom
                    } else template.strokes.size
                    tvPreviewStrokes?.text = "$filteredCount 笔"
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallView", "预览渲染失败", e)
            }
        }
    }

    /**
     * 设置预览 ImageView 的触摸交互：
     * - 拖动空白区：新建/移动裁剪区域
     * - 拖动四角把手：调整裁剪区域大小
     * - 单击：删除最近笔画
     */
    private fun setupPreviewCrop(iv: ImageView) {
        var startX = 0f; var startY = 0f
        var hasMoved = false
        // 拖动把手：记录初始裁剪区域和把手位置
        var dragCorner = -1 // -1=新建, 0=左上, 1=右上, 2=左下, 3=右下
        var dragStartCrop: android.graphics.RectF? = null

        iv.setOnTouchListener { _, event ->
            if (iv.drawable == null) return@setOnTouchListener false

            val imgW = iv.width.toFloat(); val imgH = iv.height.toFloat()
            val x = event.x.coerceIn(0f, imgW); val y = event.y.coerceIn(0f, imgH)

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = x; startY = y; isCropping = true; hasMoved = false
                    dragCorner = -1; dragStartCrop = null

                    // 检测是否点在现有裁剪区域的四角把手上（8px半径）
                    val cr = cropRect
                    if (cr != null) {
                        val pad = 20f; val drawSize = imgW - pad * 2
                        val cl = pad + cr.left * drawSize; val ct = pad + cr.top * drawSize
                        val crPx = pad + cr.right * drawSize; val cb = pad + cr.bottom * drawSize
                        val hitRadius = 20f
                        val corners = listOf(cl to ct, crPx to ct, cl to cb, crPx to cb)
                        for (i in corners.indices) {
                            val (cx, cy) = corners[i]
                            if (kotlin.math.abs(x - cx) < hitRadius && kotlin.math.abs(y - cy) < hitRadius) {
                                dragCorner = i; dragStartCrop = android.graphics.RectF(cr); break
                            }
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!isCropping) return@setOnTouchListener false
                    val dx = kotlin.math.abs(x - startX); val dy = kotlin.math.abs(y - startY)
                    if (dx > 4 || dy > 4) hasMoved = true

                    if (hasMoved) {
                        if (dragCorner >= 0 && dragStartCrop != null) {
                            // 拖动四角把手：调整现有裁剪区域
                            val normX = x / imgW; val normY = y / imgH
                            val newCrop = android.graphics.RectF(dragStartCrop)
                            when (dragCorner) {
                                0 -> { newCrop.left = normX.coerceIn(0f, newCrop.right - 0.02f); newCrop.top = normY.coerceIn(0f, newCrop.bottom - 0.02f) }
                                1 -> { newCrop.right = normX.coerceIn(newCrop.left + 0.02f, 1f); newCrop.top = normY.coerceIn(0f, newCrop.bottom - 0.02f) }
                                2 -> { newCrop.left = normX.coerceIn(0f, newCrop.right - 0.02f); newCrop.bottom = normY.coerceIn(newCrop.top + 0.02f, 1f) }
                                3 -> { newCrop.right = normX.coerceIn(newCrop.left + 0.02f, 1f); newCrop.bottom = normY.coerceIn(newCrop.top + 0.02f, 1f) }
                            }
                            cropRect = newCrop
                            val ot = originalFullTemplate
                            if (ot != null) renderPreviewBitmap(ot)
                        } else {
                            // 新建裁剪区域
                            val left = (startX.coerceAtMost(x) / imgW).coerceIn(0f, 1f)
                            val top = (startY.coerceAtMost(y) / imgH).coerceIn(0f, 1f)
                            val right = (startX.coerceAtLeast(x) / imgW).coerceIn(0f, 1f)
                            val bottom = (startY.coerceAtLeast(y) / imgH).coerceIn(0f, 1f)
                            if (right - left > 0.02f && bottom - top > 0.02f) {
                                cropRect = android.graphics.RectF(left, top, right, bottom)
                                val ot = originalFullTemplate
                                if (ot != null) renderPreviewBitmap(ot)
                            }
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    isCropping = false
                    if (hasMoved) {
                        if (cropRect != null) {
                            btnResetCrop?.visibility = View.VISIBLE
                            val ot = originalFullTemplate
                            if (ot != null) {
                                updatePendingTemplateWithCrop(ot, cropRect!!)
                            }
                        }
                    } else {
                        // 单击 → 删除最近笔画
                        val normX = x / imgW; val normY = y / imgH
                        deleteNearestStroke(normX, normY)
                    }
                    dragCorner = -1; dragStartCrop = null; true
                }
                else -> false
            }
        }
    }

    /**
     * 删除距离点击位置最近的笔画
     */
    /**
     * 纯内存按细节程度过滤笔画（不重跑OpenCV，用于滑块实时预览）
     */
    private fun filterStrokesByDetail(strokes: List<com.simpledrawbot.model.Stroke>, detailLevel: Int): List<com.simpledrawbot.model.Stroke> {
        if (detailLevel >= 100 || strokes.isEmpty()) return strokes
        val targetRatio = detailLevel / 100.0

        // 计算每个笔画的包围盒面积作为权重
        val weighted = strokes.map { s ->
            val xs = s.points.map { it.x }; val ys = s.points.map { it.y }
            val minX = xs.minOrNull() ?: 0f; val maxX = xs.maxOrNull() ?: 0f
            val minY = ys.minOrNull() ?: 0f; val maxY = ys.maxOrNull() ?: 0f
            val area = ((maxX - minX) * (maxY - minY)).toDouble()
            Pair(s, area)
        }
        val totalArea = weighted.sumOf { it.second }
        if (totalArea <= 0.0) return strokes

        val sorted = weighted.sortedByDescending { it.second }
        val result = mutableListOf<com.simpledrawbot.model.Stroke>()
        var accumulated = 0.0
        for ((stroke, area) in sorted) {
            result.add(stroke)
            accumulated += area
            if (accumulated / totalArea >= targetRatio) break
        }
        return result
    }

    private fun deleteNearestStroke(normX: Float, normY: Float) {
        val template = pendingTemplate as? com.simpledrawbot.model.DrawTemplate
        if (template == null || template.strokes.isEmpty()) {
            Toast.makeText(context, "没有可删除的笔画", Toast.LENGTH_SHORT).show()
            return
        }

        val strokes = template.strokes.toMutableList()
        var minDist = Float.MAX_VALUE; var minIdx = -1
        for (i in strokes.indices) {
            val cx = strokes[i].points.map { it.x }.average().toFloat()
            val cy = strokes[i].points.map { it.y }.average().toFloat()
            val dist = kotlin.math.sqrt(((cx - normX) * (cx - normX) + (cy - normY) * (cy - normY)).toDouble()).toFloat()
            if (dist < minDist) { minDist = dist; minIdx = i }
        }

        if (minIdx >= 0 && minDist < 0.12f) {  // 距离阈值0.12，防止误删
            deletedStrokeHistory.add(strokes[minIdx])
            strokes.removeAt(minIdx)
            val updated = com.simpledrawbot.model.DrawTemplate(template.name, template.keywords, strokes)
            pendingTemplate = updated
            originalFullTemplate = updated
            cropRect = null; btnResetCrop?.visibility = View.GONE
            btnUndoDelete?.visibility = View.VISIBLE
            renderPreviewBitmap(updated)
            updateStatus("已删除1笔，${strokes.size}笔剩余 | 可点击撤回", 0xFFFF8C42.toInt())
            Toast.makeText(context, "已删除笔画，点击「撤回」可恢复", Toast.LENGTH_SHORT).show()
        }
    }

    private fun undoDeleteStroke() {
        if (deletedStrokeHistory.isEmpty()) return
        val template = pendingTemplate as? com.simpledrawbot.model.DrawTemplate ?: return
        val restored = deletedStrokeHistory.removeLast()
        val strokes = template.strokes.toMutableList()
        val restoreY = restored.points.map { it.y }.average().toFloat()
        var insertIdx = strokes.size
        for (i in strokes.indices) {
            if (strokes[i].points.map { it.y }.average().toFloat() > restoreY) {
                insertIdx = i; break
            }
        }
        strokes.add(insertIdx, restored)
        val updated = com.simpledrawbot.model.DrawTemplate(template.name, template.keywords, strokes)
        pendingTemplate = updated; originalFullTemplate = updated
        if (deletedStrokeHistory.isEmpty()) btnUndoDelete?.visibility = View.GONE
        cropRect = null; btnResetCrop?.visibility = View.GONE
        renderPreviewBitmap(updated)
        updateStatus("已撤回，${strokes.size}笔", 0xFF2196F3.toInt())
        Toast.makeText(context, "已撤回删除", Toast.LENGTH_SHORT).show()
    }

    /**
     * 根据裁剪区域更新 pendingTemplate
     */
    private fun updatePendingTemplateWithCrop(
        full: com.simpledrawbot.model.DrawTemplate, crop: android.graphics.RectF
    ) {
        val filteredStrokes = full.strokes.filter { stroke ->
            val cx = stroke.points.map { it.x }.average().toFloat()
            val cy = stroke.points.map { it.y }.average().toFloat()
            cx in crop.left..crop.right && cy in crop.top..crop.bottom
        }
        // 重新映射坐标到0~1（裁剪区域内归一化）
        val cw = crop.width(); val ch = crop.height()
        val remapped = filteredStrokes.map { stroke ->
            com.simpledrawbot.model.Stroke(
                stroke.points.map { p ->
                    android.graphics.PointF(
                        ((p.x - crop.left) / cw).coerceIn(0f, 1f),
                        ((p.y - crop.top) / ch).coerceIn(0f, 1f)
                    )
                },
                stroke.closePath
            )
        }
        pendingTemplate = com.simpledrawbot.model.DrawTemplate(
            full.name, full.keywords, remapped
        )
        updateStatus("已裁剪选区，${remapped.size} 笔画", 0xFF4CAF50.toInt())
    }

    // ================================================================
    //  速度实时调节
    // ================================================================

    private val speedSteps = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)

    private fun adjustSpeed(delta: Float) {
        val currentIdx = speedSteps.indexOfFirst { it >= currentSpeedMultiplier - 0.01f }
            .let { if (it < 0) speedSteps.size / 2 else it }
        val newIdx = (currentIdx + if (delta > 0) 1 else -1)
            .coerceIn(0, speedSteps.size - 1)

        currentSpeedMultiplier = speedSteps[newIdx]
        com.simpledrawbot.DrawingAccessibilityService.speedMultiplier = currentSpeedMultiplier
        tvSpeedLabel?.text = "${currentSpeedMultiplier}x"

        android.util.Log.d("FloatingBallView", "速度调整为: ${currentSpeedMultiplier}x")
    }

    // ================================================================
    //  搜图一键搜索
    // ================================================================
    //  WebView 截图（绕开防盗链和DOM解析，兜底提取轮廓）
    // ================================================================

    /**
     * 获取提取用的 Bitmap：拦截的图片 > 下载的图片 > WebView截图
     */
    private fun getExtractBitmap(): Bitmap? {
        // 1. 优先使用拦截到的最大图片（WebView 自己请求的，天然绕过防盗链）
        val intercepted = largestImageBytes
        if (intercepted != null) {
            try {
                // 限制解码尺寸防止 OOM：图片 > 4MP 时降采样
                val maxPixels = 2048 * 2048
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(intercepted, 0, intercepted.size, opts)
                val pixelCount = opts.outWidth * opts.outHeight
                val sampleSize = if (pixelCount > maxPixels) {
                    kotlin.math.sqrt(pixelCount.toDouble() / maxPixels).toInt().coerceAtLeast(1)
                } else 1
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeByteArray(intercepted, 0, intercepted.size, decodeOpts)
                if (bitmap != null && bitmap.width > 50 && bitmap.height > 50) {
                    android.util.Log.d("FloatingBallView",
                        "使用拦截图片: ${bitmap.width}x${bitmap.height} (sample=$sampleSize, raw=${opts.outWidth}x${opts.outHeight}, ${intercepted.size/1024}KB)")
                    return bitmap
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallView", "拦截图片解码失败", e)
            }
        }

        // 2. 降级：已下载的图片
        val downloaded = downloadedImageBitmap
        if (downloaded != null) {
            android.util.Log.d("FloatingBallView", "使用下载图片: ${downloaded.width}x${downloaded.height}")
            return downloaded
        }

        // 3. 兜底：WebView截图
        return captureWebViewBitmap()
    }

    /**
     * 注入JS：定位大图 → Canvas绘制 → toDataURL转base64 → 回调原生
     */
    private fun injectImageToBase64Js(view: WebView?) {
        val js = """
        (function(){
            try{
                var candidates = [];
                var vpW = window.innerWidth, vpH = window.innerHeight;
                var vpCenterX = vpW/2, vpCenterY = vpH/2;
                var vpArea = vpW * vpH;
                var maxDist = Math.sqrt(vpCenterX*vpCenterX + vpCenterY*vpCenterY);

                function scoreElement(el, isCanvas, naturalW, naturalH){
                    var rect = el.getBoundingClientRect();
                    var rw = rect.width, rh = rect.height;
                    if(naturalW < 150 || naturalH < 150) return null;
                    if(rw < 80 || rh < 80) return null;
                    if(el.offsetParent === null) return null;

                    // 1. 尺寸占比分(0-50)
                    var areaRatio = (rw * rh) / vpArea;
                    var sizeScore = Math.min(areaRatio * 100, 50);

                    // 2. 中心距离分(0-30)
                    var cx = rect.left + rw/2, cy = rect.top + rh/2;
                    var dist = Math.sqrt(Math.pow(cx-vpCenterX,2)+Math.pow(cy-vpCenterY,2));
                    var centerScore = (1 - dist/maxDist) * 30;

                    // 3. 可见性分(0-20)
                    var visL = Math.max(rect.left,0), visR = Math.min(rect.right,vpW);
                    var visT = Math.max(rect.top,0), visB = Math.min(rect.bottom,vpH);
                    var visArea = Math.max(0,visR-visL) * Math.max(0,visB-visT);
                    var visibleScore = (visArea / (rw*rh)) * 20;

                    var totalScore = sizeScore + centerScore + visibleScore;
                    return {element:el, isCanvas:isCanvas, score:totalScore, width:naturalW, height:naturalH};
                }

                // 遍历所有 img
                var imgs = document.querySelectorAll('img');
                for(var i=0;i<imgs.length;i++){
                    var w = imgs[i].naturalWidth||imgs[i].width||0;
                    var h = imgs[i].naturalHeight||imgs[i].height||0;
                    var s = scoreElement(imgs[i], false, w, h);
                    if(s && s.score >= 15) candidates.push(s);
                }

                // 遍历所有 canvas
                var canvases = document.querySelectorAll('canvas');
                for(var i=0;i<canvases.length;i++){
                    var w = canvases[i].width, h = canvases[i].height;
                    var s = scoreElement(canvases[i], true, w, h);
                    if(s && s.score >= 15) candidates.push(s);
                }

                if(candidates.length === 0){
                    ImageClickBridge.onImageData('',0,0); return;
                }

                // 按分数降序，取Top3
                candidates.sort(function(a,b){ return b.score - a.score; });
                var top3 = candidates.slice(0, Math.min(3, candidates.length));

                // 逐个转base64回调原生层
                for(var idx=0; idx<top3.length; idx++){
                    var best = top3[idx];
                    var base64='';
                    if(best.isCanvas){
                        base64 = best.element.toDataURL('image/jpeg',0.85);
                    }else{
                        var maxDim = 600;
                        var w = best.width, h = best.height;
                        var scale = maxDim/Math.max(w,h);
                        if(scale < 1){ w = Math.round(w*scale); h = Math.round(h*scale); }
                        var c = document.createElement('canvas');
                        c.width = w; c.height = h;
                        var ctx = c.getContext('2d');
                        try{
                            ctx.drawImage(best.element,0,0,w,h);
                            base64 = c.toDataURL('image/jpeg',0.8);
                        }catch(e){ continue; }
                    }
                    ImageClickBridge.onCandidateData(idx, base64, best.width, best.height, Math.round(best.score));
                }
                // 发送就绪信号（无论几张）
                ImageClickBridge.onCandidatesReady(top3.length);
            }catch(e){
                ImageClickBridge.onImageData('',0,0);
            }
        })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    /**
     * 解码 pendingBase64 为 Bitmap
     */
    private fun decodeBase64Bitmap(): Bitmap? {
        val b64 = pendingBase64 ?: return null
        pendingBase64 = null
        if (b64.isEmpty()) return null
        return try {
            val pure = if (b64.contains(",")) b64.substringAfter(",") else b64
            val bytes = Base64.decode(pure, Base64.DEFAULT)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val pixels = opts.outWidth * opts.outHeight
            val maxPixels = 2048 * 2048
            val sampleSize = if (pixels > maxPixels) {
                kotlin.math.sqrt(pixels.toDouble() / maxPixels).toInt().coerceAtLeast(1)
            } else 1
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            android.util.Log.d("FloatingBallView",
                "base64解码: raw=${opts.outWidth}x${opts.outHeight} → ${bitmap?.width}x${bitmap?.height} (sample=$sampleSize, ${bytes.size/1024}KB)")
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallView", "base64解码失败", e)
            null
        }
    }

    /**
     * 截取当前 WebView 显示内容（兜底方案）
     */
    private fun captureWebViewBitmap(): Bitmap? {
        val wv = webViewSearch ?: return null
        if (wv.width <= 0 || wv.height <= 0) return null

        return try {
            val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val originalLayerType = wv.layerType
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.draw(canvas)
            wv.setLayerType(originalLayerType, null)
            android.util.Log.d("FloatingBallView", "WebView截图: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallView", "WebView截图失败", e)
            null
        }
    }

    /**
     * JS 获取当前页面最大图片URL
     */
    private fun getBigImageUrl(onResult: (String?) -> Unit) {
        val wv = webViewSearch
        if (wv == null) { onResult(null); return }

        val js = """
        (function(){
            var maxImg = null, maxArea = 0;
            var imgs = document.querySelectorAll('img');
            for(var i=0;i<imgs.length;i++){
                var w = imgs[i].naturalWidth || imgs[i].width;
                var h = imgs[i].naturalHeight || imgs[i].height;
                var area = w * h;
                if(area > maxArea && area > 5000){
                    maxArea = area; maxImg = imgs[i];
                }
            }
            if(maxImg){
                return maxImg.src || maxImg.currentSrc || maxImg.getAttribute('data-src') || '';
            }
            return '';
        })();
        """.trimIndent()

        wv.evaluateJavascript(js) { result ->
            val url = result?.trim('"')?.trim()?.replace("\\\"", "\"") ?: ""
            if (url.isNotEmpty() && url.startsWith("http")) {
                android.util.Log.d("FloatingBallView", "JS找到大图: ${url.take(80)}")
                onResult(url)
            } else {
                android.util.Log.d("FloatingBallView", "JS未找到大图URL: '$result'")
                onResult(null)
            }
        }
    }

    /**
     * 下载指定URL的图片用于提取（带Referer + 分辨率控制）
     */
    private fun downloadImageForExtract(url: String, onResult: (Bitmap?) -> Unit) {
        viewScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url(url).get()
                    .header("Referer", "https://image.baidu.com")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-T870) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "image/webp,image/*,*/*;q=0.8")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.e("FloatingBallView", "下载失败: HTTP ${resp.code}")
                        mainHandler.post { onResult(null) }
                        return@launch
                    }
                    val bytes = resp.body?.bytes() ?: run {
                        mainHandler.post { onResult(null) }; return@launch
                    }
                    // 分辨率控制
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val pixels = opts.outWidth * opts.outHeight
                    val maxPixels = 2048 * 2048
                    val sampleSize = if (pixels > maxPixels) {
                        kotlin.math.sqrt(pixels.toDouble() / maxPixels).toInt().coerceAtLeast(1)
                    } else 1
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    android.util.Log.d("FloatingBallView",
                        "下载大图: ${opts.outWidth}x${opts.outHeight} → ${bitmap?.width}x${bitmap?.height} (sample=$sampleSize, ${bytes.size/1024}KB)")
                    mainHandler.post { onResult(bitmap) }
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallView", "下载大图异常", e)
                mainHandler.post { onResult(null) }
            }
        }
    }

    private fun showExtractFailed() {
        tvWebStatus?.text = "提取失败，请先点击图片查看大图"
        tvWebStatus?.setBackgroundColor(0xFFFF4444.toInt())
        btnWebExtractDraw?.isEnabled = true
        Toast.makeText(context, "提取失败，请先点击搜索结果中的图片查看大图", Toast.LENGTH_SHORT).show()
    }

    /**
     * JS回调触发：弹出候选图选择面板
     */
    /**
     * JS收集页面上所有大图的URL（打分制Top6）
     */
    private fun collectImageUrls(onResult: (List<String>) -> Unit) {
        val wv = webViewSearch
        if (wv == null) { onResult(emptyList()); return }

        val js = """
        (function(){
            try{
                var vpW = window.innerWidth, vpH = window.innerHeight;
                var vpCenterX = vpW/2, vpCenterY = vpH/2;
                var vpArea = vpW * vpH;
                var maxDist = Math.sqrt(vpCenterX*vpCenterX + vpCenterY*vpCenterY);
                var candidates = [];

                var imgs = document.querySelectorAll('img');
                for(var i=0;i<imgs.length;i++){
                    var el = imgs[i];
                    var nw = el.naturalWidth||el.width||0;
                    var nh = el.naturalHeight||el.height||0;
                    if(nw < 150 || nh < 150) continue;
                    var rect = el.getBoundingClientRect();
                    if(rect.width < 80 || rect.height < 80) continue;
                    if(el.offsetParent === null) continue;

                    var areaRatio = (rect.width * rect.height) / vpArea;
                    var sizeScore = Math.min(areaRatio * 100, 50);
                    var cx = rect.left + rect.width/2, cy = rect.top + rect.height/2;
                    var dist = Math.sqrt((cx-vpCenterX)*(cx-vpCenterX)+(cy-vpCenterY)*(cy-vpCenterY));
                    var centerScore = (1 - dist/maxDist) * 30;
                    var visArea = Math.max(0, Math.min(rect.right,vpW)-Math.max(rect.left,0)) *
                                  Math.max(0, Math.min(rect.bottom,vpH)-Math.max(rect.top,0));
                    var visibleScore = (visArea / (rect.width*rect.height)) * 20;
                    var score = sizeScore + centerScore + visibleScore;

                    var src = el.src || el.currentSrc || '';
                    if(src && src.indexOf('data:') !== 0 && src.length > 20){
                        candidates.push({src: src, score: score});
                    }
                }

                candidates.sort(function(a,b){ return b.score - a.score; });
                var top = candidates.slice(0, Math.min(6, candidates.length));
                var urls = top.map(function(c){ return c.src; });
                return JSON.stringify(urls);
            }catch(e){ return '[]'; }
        })();
        """.trimIndent()

        wv.evaluateJavascript(js) { result ->
            try {
                val json = result?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "[]"
                val urls = com.google.gson.JsonParser.parseString(json)
                    .asJsonArray.map { it.asString }
                android.util.Log.d("FloatingBallView", "JS收集到 ${urls.size} 张图URL")
                onResult(urls)
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallView", "JS URL解析失败: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    /**
     * 下载多张图缩略图并弹出选择面板
     */
    private fun downloadThumbnailsForPicker(
        urls: List<String>, rect: android.graphics.RectF,
        duration: Int, complexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity
    ) {
        val thumbnails = mutableMapOf<Int, Bitmap>() // index → bitmap

        viewScope.launch(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // 并发下载所有缩略图（4秒超时，3秒连接超时）
            val jobs = urls.indices.map { i ->
                async {
                    try {
                        val req = okhttp3.Request.Builder().url(urls[i]).get()
                            .header("Referer", "https://image.baidu.com")
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bytes = resp.body?.bytes() ?: return@async null
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                if (bmp != null) {
                                    val thumb = Bitmap.createScaledBitmap(bmp, 150, 150, true)
                                    bmp.recycle()
                                    Pair(i, thumb)
                                } else null
                            } else null
                        }
                    } catch (_: Exception) { null }
                }
            }
            // 等待所有下载完成（最多4秒）
            jobs.forEach { job ->
                val result = job.await()
                if (result != null) thumbnails[result.first] = result.second
            }

            withContext(Dispatchers.Main) {
                if (thumbnails.isNotEmpty()) {
                    tvWebStatus?.text = "请选择要提取的图片"
                    tvWebStatus?.setBackgroundColor(0xFF4CAF50.toInt())
                    btnWebExtractDraw?.isEnabled = true
                    showImagePickerDialog(thumbnails, urls, rect, duration, complexity)
                } else {
                    // 下载全部失败，用截图兜底
                    tvWebStatus?.text = "缩略图加载失败，使用截图..."
                    val screenshot = captureWebViewBitmap()
                    if (screenshot != null) {
                        callback?.onDrawFromImage(screenshot, rect, duration, complexity)
                    } else {
                        showExtractFailed()
                    }
                }
            }
        }
    }

    /**
     * 弹出图片选择对话框
     */
    private fun showImagePickerDialog(
        thumbnails: Map<Int, Bitmap>, urls: List<String>,
        rect: android.graphics.RectF, duration: Int,
        complexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity
    ) {
        // 用悬浮窗代替AlertDialog（Service Context无法弹Dialog）
        val pickerView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 16)
        }

        // 标题栏
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleBar.addView(TextView(context).apply {
            text = "选择要提取的图片（${thumbnails.size}张）"; textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val closeBtn = Button(context).apply {
            text = "取消"; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFFF4444.toInt())
        }
        titleBar.addView(closeBtn)
        pickerView.addView(titleBar)

        // 网格
        val scrollView = android.widget.ScrollView(context)
        val gridLayout = android.widget.GridLayout(context).apply {
            columnCount = 2; setPadding(0, 8, 0, 8)
        }

        for ((i, bmp) in thumbnails) {
            val frame = android.widget.FrameLayout(context).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0; height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1, 1f)
                    setMargins(4, 4, 4, 4)
                }
            }
            val imgView = android.widget.ImageView(context).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(280, 280)
            }
            val label = TextView(context).apply {
                text = "图${i + 1}"; textSize = 10f; setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x80000000.toInt())
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 30,
                    android.view.Gravity.BOTTOM
                )
            }
            frame.addView(imgView); frame.addView(label)
            frame.setOnClickListener {
                removePickerWindow()
                tvWebStatus?.text = "正在下载原图并提取..."
                tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
                btnWebExtractDraw?.isEnabled = false
                downloadImageForExtract(urls[i]) { bitmap ->
                    if (bitmap != null) {
                        callback?.onDrawFromImage(bitmap, rect, duration, complexity)
                    } else {
                        callback?.onDrawFromImage(thumbnails[i]!!, rect, duration, complexity)
                    }
                }
            }
            gridLayout.addView(frame)
        }
        scrollView.addView(gridLayout)
        pickerView.addView(scrollView)

        // 添加悬浮窗（用 ApplicationContext 避免 Token 问题）
        val ctx = context.applicationContext
        val dm = ctx.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.9).toInt(),  // 宽度90%屏宽，手动指定
            (dm.heightPixels * 0.7).toInt(), // 高度70%屏高，手动指定
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.CENTER }

        // 背景设为白色圆角，防止默认黑色
        pickerView.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = 16f
            setStroke(2, 0xFFFF6B35.toInt())
        }

        closeBtn.setOnClickListener {
            removePickerWindow()
            btnWebExtractDraw?.isEnabled = true
            tvWebStatus?.text = "已取消选择"
        }

        pickerWindowView = pickerView
        pickerWindowParams = params
        try {
            windowManager.addView(pickerView, params)
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallView", "悬浮选择面板显示失败", e)
            // 降级：直接用第一张图
            val firstKey = thumbnails.keys.first()
            downloadImageForExtract(urls[firstKey]) { bitmap ->
                if (bitmap != null) callback?.onDrawFromImage(bitmap, rect, duration, complexity)
                else callback?.onDrawFromImage(thumbnails[firstKey]!!, rect, duration, complexity)
            }
        }
    }

    private var pickerWindowView: View? = null
    private var pickerWindowParams: WindowManager.LayoutParams? = null

    private fun removePickerWindow() {
        try { pickerWindowView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        pickerWindowView = null
    }

    private fun showCandidatePickerFromJs() {
        val rect = pendingExtractRect
        val duration = pendingExtractDuration
        val complexity = pendingExtractComplexity
        if (rect == null || complexity == null) return
        showCandidatePicker(rect, duration, complexity)
    }

    /**
     * 弹出Top3候选图选择面板
     */
    private fun showCandidatePicker(
        rect: android.graphics.RectF, duration: Int,
        complexity: com.simpledrawbot.ai.AiSketchGenerator.Complexity
    ) {
        val candidates = candidateImages.toList()
        if (candidates.isEmpty()) return

        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("选择要提取的图片（${candidates.size}张候选）")
            .create()

        val scrollView = android.widget.ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        for ((i, c) in candidates.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            // 缩略图
            val imgView = android.widget.ImageView(context).apply {
                val bytes = Base64.decode(
                    if (c.base64.contains(",")) c.base64.substringAfter(",") else c.base64,
                    Base64.DEFAULT
                )
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val thumb = Bitmap.createScaledBitmap(bmp, 120, 120, true)
                    setImageBitmap(thumb)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { marginEnd = 12 }
            }
            row.addView(imgView)

            // 信息
            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(context).apply {
                text = "候选 #${i + 1}"
                textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(context).apply {
                text = "${c.width}×${c.height} | 分数: ${c.score}"
                textSize = 12f; setTextColor(0xFF999999.toInt())
            })

            // 选择按钮
            val btn = Button(context).apply {
                text = "选这张"
                textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF4CAF50.toInt())
                setOnClickListener {
                    dialog.dismiss()
                    tvWebStatus?.text = "正在提取轮廓..."
                    tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
                    val bytes = Base64.decode(
                        if (c.base64.contains(",")) c.base64.substringAfter(",") else c.base64,
                        Base64.DEFAULT
                    )
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        callback?.onDrawFromImage(bmp, rect, duration, complexity)
                    } else {
                        showExtractFailed()
                    }
                }
            }

            row.addView(info)
            row.addView(btn)
            container.addView(row)

            // 分隔线
            if (i < candidates.size - 1) {
                container.addView(android.view.View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0xFFE0E0E0.toInt())
                })
            }
        }

        scrollView.addView(container)
        dialog.setView(scrollView)
        dialog.show()
    }

    private fun performWebSearch(keyword: String) {
        val wv = webViewSearch ?: return
        // 自动追加"简笔画"关键词，搜索更精准
        val searchKeyword = if (!keyword.contains("简笔画") && !keyword.contains("简笔")) {
            "$keyword 简笔画"
        } else {
            keyword
        }
        val encoded = java.net.URLEncoder.encode(searchKeyword, "UTF-8")
        // 使用百度图片移动端搜索接口（image.baidu.com/search 会反爬返回 error.html）
        val searchUrl = "https://m.baidu.com/sf/vsearch?pd=image_content&word=$encoded&tn=vsearch&atn=page&ie=utf-8"
        wv.loadUrl(searchUrl)
        tvWebStatus?.text = "搜索: $searchKeyword"
        tvWebStatus?.setBackgroundColor(0xFFFF8C42.toInt())
        etWebSearchKeyword?.clearFocus()
        android.util.Log.d("FloatingBallView", "搜图: $searchKeyword -> $searchUrl")
    }

    // ================================================================
    //  绘制模式管理：进度、暂停、延时、自动隐藏
    // ================================================================

    /** 进入绘制模式 */
    fun enterDrawingMode(text: String, totalStrokes: Int, totalSeconds: Int) {
        btnStartDraw?.visibility = View.GONE
        btnPreviewDraw?.visibility = View.GONE
        btnConfirmDraw?.visibility = View.GONE
        layoutDrawingControl?.visibility = View.VISIBLE
        progressDraw?.max = totalStrokes
        progressDraw?.progress = 0
        tvDrawProgress?.text = "0 / $totalStrokes 笔"
        tvDrawEta?.text = "剩余 ${totalSeconds}s"
        updateStatus("绘制中: $text", 0xFF4CAF50.toInt())
    }

    /** 退出绘制模式 */
    fun exitDrawingMode() {
        layoutDrawingControl?.visibility = View.GONE
        btnStartDraw?.visibility = View.VISIBLE
        btnPreviewDraw?.visibility = View.GONE
        btnConfirmDraw?.visibility = View.GONE
        updateStatus("就绪")
    }

    /** 更新绘制进度 */
    fun updateDrawProgress(current: Int, total: Int, elapsedSec: Int, totalSec: Int) {
        progressDraw?.progress = current
        tvDrawProgress?.text = "$current / $total 笔"
        val remaining = (totalSec - elapsedSec).coerceAtLeast(0)
        tvDrawEta?.text = "剩余 ${remaining}s"
    }

    /** 暂停状态切换 */
    fun onPauseStateChanged(paused: Boolean) {
        btnPauseResume?.text = if (paused) "继续" else "暂停"
        updateStatus(if (paused) "已暂停" else "绘制中...", if (paused) 0xFFFF8C42.toInt() else 0xFF4CAF50.toInt())
    }

    /** 是否启用延时启动 */
    fun isCountdownEnabled(): Boolean = cbCountdown?.isChecked == true

    /** 显示/隐藏倒计时遮罩 */
    fun showCountdownOverlay(show: Boolean) {
        tvCountdownOverlay?.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 更新倒计时文字 */
    fun updateCountdownText(text: String) {
        tvCountdownOverlay?.text = text
    }

    /** 自动隐藏悬浮球（绘制时收至边缘半透明，同时收起展开面板） */
    fun autoHideDuringDrawing(hide: Boolean) {
        if (hide) {
            // 先强制收起展开面板
            if (isExpanded) {
                expandedView?.visibility = View.GONE
                collapsedView?.visibility = View.VISIBLE
                isExpanded = false
            }
            // 收起态移至左上角并半透明
            collapsedView?.let { cv ->
                collapsedParams?.let { p ->
                    cv.alpha = 0.3f
                    p.x = 0; p.y = 0
                    try { windowManager.updateViewLayout(cv, p) } catch (_: Exception) {}
                }
            }
        } else {
            // 恢复
            collapsedView?.let { cv ->
                collapsedParams?.let { p ->
                    cv.alpha = 1.0f
                    val ballMargin = context.resources.getDimensionPixelSize(R.dimen.floating_ball_margin)
                    p.x = ballMargin; p.y = ballMargin * 3
                    try { windowManager.updateViewLayout(cv, p) } catch (_: Exception) {}
                }
            }
        }
    }

    // ================================================================
    //  展开/收起
    // ================================================================

    private fun toggleExpand() {
        if (isExpanded) {
            expandedView?.visibility = View.GONE
            collapsedView?.visibility = View.VISIBLE
        } else {
            collapsedView?.visibility = View.GONE
            expandedView?.visibility = View.VISIBLE

            collapsedParams?.let { cp ->
                expandedParams?.let { ep ->
                    val offset = context.resources.getDimensionPixelSize(R.dimen.floating_ball_size) / 4
                    ep.x = cp.x - offset
                    ep.y = cp.y - offset
                    expandedView?.let { windowManager.updateViewLayout(it, ep) }
                }
            }
        }
        isExpanded = !isExpanded
    }
}
