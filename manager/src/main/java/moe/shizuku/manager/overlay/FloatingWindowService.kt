package moe.shizuku.manager.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * 悬浮窗服务 - 自带数字键盘，无需系统输入法
 *
 * 配对流程：
 * 1. 先 mDNS 搜索设备，悬浮窗显示"正在搜索设备…"，键盘隐藏
 * 2. 发现设备后，悬浮窗显示"已找到设备，请输入配对码"，数字键盘出现
 * 3. 用户用自带键盘输入配对码，点击 ✓ 确认 → 执行 AdbPairingClient
 * 4. 显示成功/失败结果
 *
 * 自带数字键盘（0-9 + 删除 + 确认），完全不依赖系统输入法，
 * 彻底解决悬浮窗无法调出输入法的问题。
 */
class FloatingWindowService : Service() {

    companion object {
        const val ACTION_UPDATE_HINT = "moe.shizuku.manager.overlay.UPDATE_HINT"
        const val ACTION_START_PAIRING = "moe.shizuku.manager.overlay.START_PAIRING"
        const val EXTRA_HINT = "hint"
        const val EXTRA_STATE = "state"

        const val STATE_IDLE = 0
        const val STATE_SEARCHING = 5
        const val STATE_WAITING_PAIR = 1
        const val STATE_WAITING_AUTH = 2
        const val STATE_SUCCESS = 3
        const val STATE_FAILED = 4
        const val STATE_CONNECTING = 6  // 配对成功后正在连接 ADB

        private const val NOTIFICATION_CHANNEL = "floating_window"
        private const val NOTIFICATION_ID = 2
        private const val MAX_CODE_LENGTH = 16

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
    private lateinit var layoutParams: LayoutParams
    private lateinit var hintText: TextView
    private lateinit var codeDisplay: TextView
    private lateinit var keypadContainer: View
    private lateinit var collapseIcon: View
    private lateinit var expandedContainer: View
    private val handler = Handler(Looper.getMainLooper())

    private var isCollapsed = false
    private var currentState = STATE_IDLE

    // 用户输入的配对码
    private val codeBuilder = StringBuilder()

    private var pairingController: OverlayPairingController? = null
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

        createNotificationChannel()
        startForeground()

        inputReceiver = OverlayInputReceiver()
        registerReceiver(inputReceiver, IntentFilter(OverlayInputReceiver.ACTION_INPUT_SUBMITTED))

        pairingController = OverlayPairingController(this)
        OverlayServiceHolder.pairingController = pairingController

        createOverlayWindow()
        markRunning(true)
        LOGGER.i("FloatingWindowService", "Overlay window created with built-in keypad")
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.floating_window_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.floating_window_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            LOGGER.w(e, "FloatingWindowService: startForeground failed")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                LOGGER.w(e2, "FloatingWindowService: startForeground fallback failed")
            }
        }
    }

    private fun createOverlayWindow() {
        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_PHONE
        }

        // 始终保持 FLAG_NOT_FOCUSABLE — 不需要焦点，自带键盘不依赖输入法
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_floating_window, null)
        hintText = overlayView.findViewById(R.id.overlay_hint)
        codeDisplay = overlayView.findViewById(R.id.overlay_code_display)
        keypadContainer = overlayView.findViewById(R.id.overlay_keypad)
        collapseIcon = overlayView.findViewById(R.id.overlay_collapse)
        expandedContainer = overlayView.findViewById(R.id.overlay_expanded)

        setupDrag()
        setupKeypad()
        setupCollapse()

        updateState(STATE_IDLE)

        try {
            windowManager.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            LOGGER.w(e, "FloatingWindowService: addView failed")
            stopSelf()
        }
    }

    private fun setupDrag() {
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, layoutParams)
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

    /**
     * 设置自带数字键盘
     * 0-9 追加数字，⌫ 删除，✓ 确认提交
     */
    private fun setupKeypad() {
        val numberKeys = intArrayOf(
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9
        )

        for (id in numberKeys) {
            overlayView.findViewById<TextView>(id).setOnClickListener {
                appendDigit((it as TextView).text.toString())
            }
        }

        overlayView.findViewById<TextView>(R.id.key_delete).setOnClickListener {
            deleteDigit()
        }

        overlayView.findViewById<TextView>(R.id.key_confirm).setOnClickListener {
            confirmCode()
        }
    }

    private fun appendDigit(digit: String) {
        if (codeBuilder.length >= MAX_CODE_LENGTH) return
        codeBuilder.append(digit)
        updateCodeDisplay()
    }

    private fun deleteDigit() {
        if (codeBuilder.isEmpty()) return
        codeBuilder.deleteCharAt(codeBuilder.length - 1)
        updateCodeDisplay()
    }

    private fun updateCodeDisplay() {
        codeDisplay.text = codeBuilder.toString()
    }

    private fun confirmCode() {
        val code = codeBuilder.toString().trim()
        if (code.isEmpty()) return
        LOGGER.i("FloatingWindowService", "User confirmed pairing code (length=${code.length})")
        codeBuilder.clear()
        updateCodeDisplay()
        submitInput(code)
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
                STATE_SEARCHING -> getString(R.string.floating_window_hint_searching)
                STATE_CONNECTING -> getString(R.string.floating_window_hint_connecting)
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
                STATE_SEARCHING -> 0xFF42A5F5.toInt()
                STATE_CONNECTING -> 0xFF42A5F5.toInt()
                else -> resolveThemeColor()
            }
            hintText.setTextColor(accentColor)

            when (state) {
                STATE_SEARCHING, STATE_IDLE, STATE_CONNECTING -> {
                    keypadContainer.visibility = View.GONE
                    codeBuilder.clear()
                    updateCodeDisplay()
                }
                STATE_WAITING_PAIR, STATE_WAITING_AUTH -> {
                    keypadContainer.visibility = View.VISIBLE
                    codeBuilder.clear()
                    updateCodeDisplay()
                }
                STATE_SUCCESS, STATE_FAILED -> {
                    codeBuilder.clear()
                    updateCodeDisplay()
                }
            }

            if (state == STATE_SUCCESS) {
                // 配对成功后延迟 3 秒自动关闭悬浮窗
                handler.postDelayed({
                    if (currentState == STATE_SUCCESS) {
                        stopSelf()
                    }
                }, 3000)
            } else if (state == STATE_FAILED) {
                handler.postDelayed({
                    if (currentState == STATE_FAILED) {
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
        val intent = Intent(OverlayInputReceiver.ACTION_INPUT_SUBMITTED).apply {
            setPackage(packageName)
            putExtra(OverlayInputReceiver.EXTRA_INPUT, text)
            putExtra(OverlayInputReceiver.EXTRA_STATE, currentState)
        }
        sendBroadcast(intent)

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
                pairingController?.startSearch()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        pairingController?.cancel()
        pairingController = null
        OverlayServiceHolder.pairingController = null
        OverlayServiceHolder.setAuthCallback(null)

        try {
            inputReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        inputReceiver = null

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
