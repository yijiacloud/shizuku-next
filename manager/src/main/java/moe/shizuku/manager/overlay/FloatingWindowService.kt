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
import android.view.WindowManager.LayoutParams
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
 * 配对流程（与原版 AdbPairDialogFragment 一致）：
 * 1. 先 mDNS 搜索设备，悬浮窗显示"正在搜索设备…"，输入框隐藏
 * 2. 发现设备后，悬浮窗显示"已找到设备，请输入配对码"，输入框出现
 * 3. 用户输入配对码并提交 → 执行 AdbPairingClient
 * 4. 显示成功/失败结果
 *
 * 输入法适配：
 * 悬浮窗默认使用 FLAG_NOT_FOCUSABLE，点击输入框时动态移除该 flag 以唤起输入法。
 */
class FloatingWindowService : Service() {

    companion object {
        const val ACTION_UPDATE_HINT = "moe.shizuku.manager.overlay.UPDATE_HINT"
        const val ACTION_START_PAIRING = "moe.shizuku.manager.overlay.START_PAIRING"
        const val EXTRA_HINT = "hint"
        const val EXTRA_STATE = "state"

        const val STATE_IDLE = 0
        const val STATE_SEARCHING = 5       // 正在搜索设备
        const val STATE_WAITING_PAIR = 1     // 找到设备，等待配对码
        const val STATE_WAITING_AUTH = 2     // 等待授权密码
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
    private lateinit var layoutParams: LayoutParams
    private lateinit var hintText: TextView
    private lateinit var inputField: EditText
    private lateinit var collapseIcon: View
    private lateinit var expandedContainer: View
    private val handler = Handler(Looper.getMainLooper())

    private var isCollapsed = false
    private var currentState = STATE_IDLE
    private var isFocused = false

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

        inputReceiver = OverlayInputReceiver()
        registerReceiver(inputReceiver, IntentFilter(OverlayInputReceiver.ACTION_INPUT_SUBMITTED))

        pairingController = OverlayPairingController(this)
        OverlayServiceHolder.pairingController = pairingController

        createOverlayWindow()
        markRunning(true)
        LOGGER.i("FloatingWindowService", "Overlay window created")
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
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_PHONE
        }

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
        inputField = overlayView.findViewById(R.id.overlay_input)
        collapseIcon = overlayView.findViewById(R.id.overlay_collapse)
        expandedContainer = overlayView.findViewById(R.id.overlay_expanded)

        setupDrag()
        setupInput()
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

    private fun setupInput() {
        // 点击输入框时，移除 FLAG_NOT_FOCUSABLE 以唤起输入法
        inputField.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                requestFocusForInput()
            }
            false // 让 EditText 正常处理点击
        }

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

        // 输入框失去焦点时恢复 FLAG_NOT_FOCUSABLE
        inputField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isFocused) {
                clearFocusFromInput()
            }
        }
    }

    /**
     * 让悬浮窗获得焦点，以便输入框能唤起输入法。
     * 移除 FLAG_NOT_FOCUSABLE，添加 FLAG_ALT_FOCUSABLE_IM。
     */
    private fun requestFocusForInput() {
        if (isFocused) return
        isFocused = true
        try {
            layoutParams.flags = layoutParams.flags and
                    LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            layoutParams.flags = layoutParams.flags or
                    LayoutParams.FLAG_ALT_FOCUSABLE_IM
            windowManager.updateViewLayout(overlayView, layoutParams)
            inputField.requestFocus()
            // 延迟一点再显示输入法
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as
                    android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            LOGGER.i("FloatingWindowService", "Input focus requested, IME should show")
        } catch (e: Exception) {
            LOGGER.w(e, "FloatingWindowService: requestFocusForInput failed")
        }
    }

    /**
     * 恢复悬浮窗为无焦点状态。
     */
    private fun clearFocusFromInput() {
        if (!isFocused) return
        isFocused = false
        try {
            layoutParams.flags = layoutParams.flags or LayoutParams.FLAG_NOT_FOCUSABLE
            layoutParams.flags = layoutParams.flags and LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
            windowManager.updateViewLayout(overlayView, layoutParams)
            LOGGER.i("FloatingWindowService", "Input focus cleared")
        } catch (e: Exception) {
            LOGGER.w(e, "FloatingWindowService: clearFocusFromInput failed")
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
                STATE_SEARCHING -> getString(R.string.floating_window_hint_searching)
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
                else -> resolveThemeColor()
            }
            hintText.setTextColor(accentColor)

            // 搜索阶段隐藏输入框；找到设备或等待授权时显示输入框
            when (state) {
                STATE_SEARCHING, STATE_IDLE -> {
                    inputField.visibility = View.GONE
                    inputField.setText("")
                    if (isFocused) clearFocusFromInput()
                }
                STATE_WAITING_PAIR, STATE_WAITING_AUTH -> {
                    inputField.visibility = View.VISIBLE
                    inputField.setText("")
                }
                STATE_SUCCESS, STATE_FAILED -> {
                    inputField.setText("")
                    if (isFocused) clearFocusFromInput()
                }
            }

            if (state == STATE_SUCCESS || state == STATE_FAILED) {
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

        // 提交后恢复无焦点
        clearFocusFromInput()

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
