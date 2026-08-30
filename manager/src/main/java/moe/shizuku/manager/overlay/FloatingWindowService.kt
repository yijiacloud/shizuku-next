package moe.shizuku.manager.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * 悬浮窗服务 - 极简常驻输入窗
 *
 * 只有一个输入框 + 一条提示文字。
 * 不依赖目标应用弹窗能力，适配阉割悬浮窗的机型。
 *
 * 整合两种用途：
 * 1. ADB 无线配对 — 用户在悬浮窗输入配对码
 * 2. 应用授权 — 用户在悬浮窗输入授权密码
 *
 * 原 Dialog 弹窗配对方式（RequestPermissionActivity、AdbPairDialogFragment）保留不变，
 * 悬浮窗作为第二种可选方式。
 */
class FloatingWindowService : Service() {

    companion object {
        const val ACTION_UPDATE_HINT = "moe.shizuku.manager.overlay.UPDATE_HINT"
        const val ACTION_START_PAIRING = "moe.shizuku.manager.overlay.START_PAIRING"
        const val EXTRA_HINT = "hint"
        const val EXTRA_STATE = "state"

        const val STATE_IDLE = 0
        const val STATE_WAITING_PAIR = 1
        const val STATE_WAITING_AUTH = 2
        const val STATE_SUCCESS = 3
        const val STATE_FAILED = 4

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        fun isRunning(): Boolean {
            return ShizukuSettings.getPreferences()
                .getBoolean(ShizukuSettings.FLOATING_WINDOW_RUNNING, false)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var hintText: TextView
    private lateinit var inputField: EditText
    private lateinit var collapseIcon: View
    private lateinit var expandedContainer: View
    private val handler = Handler(Looper.getMainLooper())

    private var isCollapsed = false
    private var currentState = STATE_IDLE

    // 配对控制器
    private var pairingController: OverlayPairingController? = null

    // 输入接收器
    private var inputReceiver: OverlayInputReceiver? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!hasOverlayPermission()) {
            LOGGER.w("FloatingWindowService", "No SYSTEM_ALERT_WINDOW permission, stopping")
            stopSelf()
            return
        }

        // 注册输入接收器
        inputReceiver = OverlayInputReceiver()
        val filter = IntentFilter(OverlayInputReceiver.ACTION_INPUT_SUBMITTED)
        registerReceiver(inputReceiver, filter)

        // 创建配对控制器
        pairingController = OverlayPairingController(this)
        OverlayServiceHolder.pairingController = pairingController

        createOverlayWindow()
        markRunning(true)
        LOGGER.i("FloatingWindowService", "Overlay window created and controllers initialized")
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun createOverlayWindow() {
        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_floating_window, null)
        hintText = overlayView.findViewById(R.id.overlay_hint)
        inputField = overlayView.findViewById(R.id.overlay_input)
        collapseIcon = overlayView.findViewById(R.id.overlay_collapse)
        expandedContainer = overlayView.findViewById(R.id.overlay_expanded)

        setupDrag(params)
        setupInput()
        setupCollapse()

        updateState(STATE_IDLE)

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            LOGGER.w(e, "FloatingWindowService: addView failed")
            stopSelf()
        }
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                            kotlin.math.abs(event.rawY - initialTouchY) > 10
                    moved
                }
                else -> false
            }
        }
    }

    private fun setupInput() {
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val text = inputField.text.toString().trim()
                if (text.isNotEmpty()) {
                    submitInput(text)
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupCollapse() {
        collapseIcon.setOnClickListener {
            toggleCollapse()
        }
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) {
            expandedContainer.visibility = View.GONE
            collapseIcon.alpha = 0.6f
            overlayView.alpha = 0.85f
        } else {
            expandedContainer.visibility = View.VISIBLE
            collapseIcon.alpha = 1f
            overlayView.alpha = 1f
        }
    }

    fun updateState(state: Int, customHint: String? = null) {
        currentState = state
        handler.post {
            val hint = customHint ?: when (state) {
                STATE_IDLE -> getString(R.string.floating_window_hint_idle)
                STATE_WAITING_PAIR -> getString(R.string.floating_window_hint_waiting_pair)
                STATE_WAITING_AUTH -> getString(R.string.floating_window_hint_waiting_auth)
                STATE_SUCCESS -> getString(R.string.floating_window_hint_success)
                STATE_FAILED -> getString(R.string.floating_window_hint_failed)
                else -> getString(R.string.floating_window_hint_idle)
            }
            hintText.text = hint

            val accentColor = when (state) {
                STATE_SUCCESS -> 0xFF4CAF50.toInt()
                STATE_FAILED -> 0xFFFF5252.toInt()
                STATE_WAITING_PAIR, STATE_WAITING_AUTH -> 0xFFFFC107.toInt()
                else -> resolveThemeColor()
            }
            hintText.setTextColor(accentColor)

            if (state == STATE_SUCCESS || state == STATE_FAILED) {
                inputField.setText("")
                handler.postDelayed({
                    if (currentState == state) {
                        updateState(STATE_IDLE)
                    }
                }, 3000)
            }
        }
    }

    private fun resolveThemeColor(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.colorAccent, tv, true)
        return tv.data
    }

    private fun submitInput(text: String) {
        LOGGER.i("FloatingWindowService", "User submitted input (length=${text.length})")

        // 发送广播通知输入接收器
        val intent = Intent(OverlayInputReceiver.ACTION_INPUT_SUBMITTED).apply {
            setPackage(packageName)
            putExtra(OverlayInputReceiver.EXTRA_INPUT, text)
            putExtra(OverlayInputReceiver.EXTRA_STATE, currentState)
        }
        sendBroadcast(intent)

        // 暂时显示"处理中"状态
        updateState(currentState, getString(R.string.floating_window_hint_processing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_HINT -> {
                val state = intent.getIntExtra(EXTRA_STATE, STATE_IDLE)
                val hint = intent.getStringExtra(EXTRA_HINT)
                updateState(state, hint)
            }
            ACTION_START_PAIRING -> {
                // 外部请求启动 ADB 配对搜索
                pairingController?.startSearch()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 清理配对控制器
        pairingController?.cancel()
        pairingController = null
        OverlayServiceHolder.pairingController = null
        OverlayServiceHolder.setAuthCallback(null)

        // 注销接收器
        try {
            inputReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        inputReceiver = null

        // 移除悬浮窗
        try {
            if (::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
            }
        } catch (e: Exception) { }
        markRunning(false)
        LOGGER.i("FloatingWindowService", "Overlay window destroyed")
    }

    private fun markRunning(running: Boolean) {
        ShizukuSettings.getPreferences()
            .edit()
            .putBoolean(ShizukuSettings.FLOATING_WINDOW_RUNNING, running)
            .apply()
    }
}