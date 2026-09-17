package com.simpledrawbot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.simpledrawbot.ai.AiSketchGenerator
import com.simpledrawbot.util.OppoUtils
import com.simpledrawbot.util.PreferenceHelper

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnToggleService: MaterialButton

    private lateinit var etApiKey: TextInputEditText
    private lateinit var etEndpoint: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var sliderDuration: Slider
    private lateinit var tvDuration: TextView
    private lateinit var btnSaveAiSettings: MaterialButton
    private lateinit var btnTestAi: MaterialButton
    private lateinit var rgAiComplexity: RadioGroup

    private lateinit var prefs: PreferenceHelper
    private val aiGenerator = AiSketchGenerator()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var testJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "通知权限被拒绝，可能影响前台服务运行", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingWindowService.ACTION_SERVICE_STATE) {
                val running = intent.getBooleanExtra(FloatingWindowService.EXTRA_SERVICE_RUNNING, false)
                updateToggleButton(running)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferenceHelper(this)

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnToggleService = findViewById(R.id.btnToggleService)

        etApiKey = findViewById(R.id.etApiKey)
        etEndpoint = findViewById(R.id.etEndpoint)
        etModel = findViewById(R.id.etModel)
        sliderDuration = findViewById(R.id.sliderDuration)
        tvDuration = findViewById(R.id.tvDuration)
        btnSaveAiSettings = findViewById(R.id.btnSaveAiSettings)
        btnTestAi = findViewById(R.id.btnTestAi)
        rgAiComplexity = findViewById(R.id.rgAiComplexity)

        findViewById<MaterialButton>(R.id.btnGrantOverlay)
            .setOnClickListener { requestOverlayPermission() }

        findViewById<MaterialButton>(R.id.btnGrantAccessibility)
            .setOnClickListener { openAccessibilitySettings() }

        btnToggleService.setOnClickListener { toggleService() }

        btnSaveAiSettings.setOnClickListener { saveAiSettings() }

        btnTestAi.setOnClickListener { testAiConnection() }

        sliderDuration.addOnChangeListener { _, value, _ ->
            tvDuration.text = "${value.toInt()}s"
        }

        loadAiSettings()
        requestNotificationPermissionIfNeeded()
        checkBatteryOptimizationIfNeeded()

        // 注册悬浮球服务状态广播，确保按钮状态与真实服务状态一致
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                serviceStateReceiver,
                IntentFilter(FloatingWindowService.ACTION_SERVICE_STATE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(serviceStateReceiver, IntentFilter(FloatingWindowService.ACTION_SERVICE_STATE))
        }
    }

    /**
     * ColorOS / OPPO 深度适配：提示关闭电池优化，否则悬浮服务容易被杀
     */
    private fun checkBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !OppoUtils.isIgnoringBatteryOptimizations(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("OPPO 平板适配")
                .setMessage("为保证悬浮球在便签、画板等应用上方长期稳定运行，建议关闭本应用的电池优化，并允许后台运行。")
                .setPositiveButton("去设置") { _, _ ->
                    OppoUtils.requestIgnoreBatteryOptimizations(this)
                }
                .setNegativeButton("稍后再说", null)
                .setNeutralButton("ColorOS 后台管理") { _, _ ->
                    OppoUtils.openColorOsAppManager(this)
                }
                .show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> { /* 已有权限 */ }
                shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) ->
                    Toast.makeText(this, "请允许通知权限，否则悬浮球服务可能无法运行", Toast.LENGTH_LONG).show()
                else -> notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun loadAiSettings() {
        val config = prefs.getAiConfig()
        etApiKey.setText(config.apiKey)
        etEndpoint.setText(config.endpoint)
        etModel.setText(config.model)

        val duration = prefs.getDrawDurationSeconds().coerceIn(5, 60)
        sliderDuration.value = duration.toFloat()
        tvDuration.text = "${duration}s"

        val isComplex = prefs.getComplexity() == "complex"
        rgAiComplexity.check(if (isComplex) R.id.rbAiComplex else R.id.rbAiSimple)
    }

    private fun saveAiSettings() {
        val config = AiSketchGenerator.Config(
            apiKey = etApiKey.text?.toString()?.trim() ?: "",
            endpoint = etEndpoint.text?.toString()?.trim()
                ?: "https://api.openai.com/v1/chat/completions",
            model = etModel.text?.toString()?.trim() ?: "gpt-4o-mini"
        )
        prefs.saveAiConfig(config)
        prefs.saveDrawDurationSeconds(sliderDuration.value.toInt())
        prefs.saveComplexity(
            if (rgAiComplexity.checkedRadioButtonId == R.id.rbAiComplex) "complex" else "simple"
        )
        Toast.makeText(this, "AI 设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun testAiConnection() {
        // 先保存当前输入，确保测试用的是最新配置
        saveAiSettings()

        val config = AiSketchGenerator.Config(
            apiKey = etApiKey.text?.toString()?.trim() ?: "",
            endpoint = etEndpoint.text?.toString()?.trim()
                ?: "https://api.openai.com/v1/chat/completions",
            model = etModel.text?.toString()?.trim() ?: "gpt-4o-mini"
        )

        if (config.apiKey.isBlank()) {
            Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestAi.isEnabled = false
        btnTestAi.text = "测试中..."

        testJob?.cancel()
        testJob = mainScope.launch {
            try {
                val (success, message) = withContext(Dispatchers.IO) {
                    aiGenerator.testConnection(config)
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                // ignore
            } finally {
                btnTestAi.isEnabled = true
                btnTestAi.text = "测试 AI 连接"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        mainScope.cancel()
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun updateToggleButton(serviceRunning: Boolean) {
        btnToggleService.text = if (serviceRunning) "关闭悬浮球" else "开启悬浮球"
    }

    private fun checkPermissions() {
        // 悬浮窗权限
        if (Settings.canDrawOverlays(this)) {
            tvOverlayStatus.text = "已授权"
            tvOverlayStatus.setTextColor(0xFF4CAF50.toInt())
            tvOverlayStatus.setBackgroundResource(R.drawable.bg_status_green)
        } else {
            tvOverlayStatus.text = "未授权"
            tvOverlayStatus.setTextColor(0xFFFF4444.toInt())
            tvOverlayStatus.setBackgroundResource(R.drawable.bg_status_red)
        }

        // 无障碍权限
        if (isAccessibilityServiceEnabled()) {
            tvAccessibilityStatus.text = "已开启"
            tvAccessibilityStatus.setTextColor(0xFF4CAF50.toInt())
            tvAccessibilityStatus.setBackgroundResource(R.drawable.bg_status_green)
        } else {
            tvAccessibilityStatus.text = "未开启"
            tvAccessibilityStatus.setTextColor(0xFFFF4444.toInt())
            tvAccessibilityStatus.setBackgroundResource(R.drawable.bg_status_red)
        }

        // 两个权限都 OK 才允许开启服务
        btnToggleService.isEnabled =
            Settings.canDrawOverlays(this) && isAccessibilityServiceEnabled()

        // 根据服务状态更新按钮文字
        if (FloatingWindowService.instance != null) {
            btnToggleService.text = "关闭悬浮球"
        } else {
            btnToggleService.text = "开启悬浮球"
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleService() {
        val intent = Intent(this, FloatingWindowService::class.java)

        if (FloatingWindowService.instance != null) {
            // 关闭服务
            stopService(intent)
            btnToggleService.text = "开启悬浮球"
            FloatingWindowService.clearInstance()
        } else {
            // Android 13+ 前台服务依赖通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请先允许通知权限", Toast.LENGTH_SHORT).show()
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }

            // 开启服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            btnToggleService.text = "关闭悬浮球"

            // 前台服务启动可能失败或被系统立即终止，延迟校验一次真实状态
            Handler(Looper.getMainLooper()).postDelayed({
                if (FloatingWindowService.instance == null) {
                    updateToggleButton(false)
                    Toast.makeText(this, "悬浮球服务未能启动，请检查通知/悬浮窗权限或后台限制", Toast.LENGTH_LONG).show()
                }
            }, 1500)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name ==
                    "${DrawingAccessibilityService::class.java.name}"
        }
    }
}
