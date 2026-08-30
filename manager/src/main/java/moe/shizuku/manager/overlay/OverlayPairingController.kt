package moe.shizuku.manager.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.Logger.LOGGER
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

/**
 * 悬浮窗 ADB 配对控制器
 *
 * 完整流程（借鉴原版 Shizuku 配对 + 启动流程）：
 *
 * 第一阶段 — 配对：
 *   1. mDNS 搜索 _adb-tls-pairing._tcp → 悬浮窗"正在搜索设备…"
 *   2. 发现端口 → "已找到设备，请输入配对码" + 数字键盘
 *   3. 用户输入配对码 → AdbPairingClient 配对
 *
 * 第二阶段 — 连接 ADB 并启动 Shizuku（配对成功后自动执行）：
 *   4a. 首先读取系统属性 service.adb.tcp.port（与原版 Start 按钮一致）
 *   4b. 若读到端口 → 直接启动 StarterActivity 连接
 *   5a. 若读不到 → mDNS 搜索 _adb-tls-connect._tcp（10秒超时）
 *   5b. 若找到端口 → AdbClient 连接 → 执行 Starter 命令
 *   6. 若全部失败 → 弹回 ManualConnectActivity 让用户手动输入 IP+端口
 */
class OverlayPairingController(private val context: Context) {

    companion object {
        private const val TAG = "OverlayPairingCtrl"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbMdns: AdbMdns? = null
    private var currentPort: Int = -1
    private var searching = false
    private var searchPhase = 0  // 0=idle, 1=pairing, 2=connecting

    private val portObserver = Observer<Int> { port ->
        Log.i(TAG, "Phase $searchPhase port: $port")
        if (port <= 0) {
            currentPort = -1
            if (searchPhase == 1) {
                updateState(FloatingWindowService.STATE_SEARCHING,
                    context.getString(moe.shizuku.manager.R.string.floating_window_hint_searching))
            }
            return@Observer
        }

        currentPort = port
        if (searchPhase == 1) {
            // 配对阶段：发现配对端口，等待用户输入配对码
            updateState(FloatingWindowService.STATE_WAITING_PAIR,
                context.getString(moe.shizuku.manager.R.string.floating_window_hint_waiting_pair))
        } else if (searchPhase == 2) {
            // 连接阶段：发现 ADB 连接端口，自动连接
            connectAdb(port)
        }
    }

    private fun updateState(state: Int, hint: String? = null) {
        val service = context as? FloatingWindowService
        if (service != null) {
            service.updateState(state, hint)
        } else {
            Log.w(TAG, "Context is not FloatingWindowService, falling back to intent")
            FloatingWindowManager.updateHint(context, state, hint)
        }
    }

    // ==================== 第一阶段：配对搜索 ====================

    fun startSearch() {
        if (searching) {
            stopSearch()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Wireless ADB pairing requires Android 11+")
            updateState(FloatingWindowService.STATE_FAILED, "无线调试配对需要 Android 11+")
            return
        }
        searching = true
        searchPhase = 1
        currentPort = -1

        updateState(FloatingWindowService.STATE_SEARCHING,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_searching))

        // 借鉴原版 AdbPairingService 的搜索方式
        adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING, portObserver).apply { start() }
        Log.i(TAG, "Phase 1: mDNS pairing search started")
    }

    fun stopSearch() {
        if (!searching) return
        searching = false
        adbMdns?.stop()
        adbMdns = null
    }

    fun onPairingCodeInput(pairCode: String) {
        if (currentPort <= 0) {
            Log.w(TAG, "No pairing service found yet")
            updateState(FloatingWindowService.STATE_FAILED,
                context.getString(moe.shizuku.manager.R.string.floating_window_hint_no_device))
            return
        }

        val port = currentPort
        val host = "127.0.0.1"

        scope.launch {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create AdbKey", e)
                updateState(FloatingWindowService.STATE_FAILED,
                    context.getString(moe.shizuku.manager.R.string.adb_error_key_store))
                return@launch
            }

            val client = AdbPairingClient(host, port, pairCode, key)
            val result = client.runCatching { start() }
            client.close()

            result.onSuccess { success ->
                if (success) {
                    Log.i(TAG, "Phase 1: Pairing succeeded, starting phase 2")
                    stopSearch()
                    startPhase2Connect()
                } else {
                    Log.w(TAG, "Pairing returned false")
                    updateState(FloatingWindowService.STATE_FAILED,
                        context.getString(moe.shizuku.manager.R.string.floating_window_hint_failed))
                }
            }.onFailure { exception ->
                Log.w(TAG, "Pairing failed", exception)
                val message = when (exception) {
                    is ConnectException -> context.getString(moe.shizuku.manager.R.string.cannot_connect_port)
                    is AdbInvalidPairingCodeException -> context.getString(moe.shizuku.manager.R.string.paring_code_is_wrong)
                    is AdbKeyException -> context.getString(moe.shizuku.manager.R.string.adb_error_key_store)
                    else -> context.getString(moe.shizuku.manager.R.string.floating_window_hint_failed)
                }
                updateState(FloatingWindowService.STATE_FAILED, message)
            }
        }
    }

    // ==================== 第二阶段：连接 ADB ====================

    /**
     * 配对成功后，尝试连接 ADB 并启动 Shizuku
     * 优先级：系统属性端口 > mDNS 搜索 > 手动输入
     */
    private fun startPhase2Connect() {
        // 方式 1：读取系统属性中的 ADB 端口（与原版 Start 按钮一致）
        val systemPort = EnvironmentUtils.getAdbTcpPort()
        Log.i(TAG, "Phase 2: system property port = $systemPort")

        if (systemPort > 0) {
            // 找到端口，直接启动 StarterActivity（与原版完全一致）
            updateState(FloatingWindowService.STATE_CONNECTING,
                context.getString(moe.shizuku.manager.R.string.floating_window_hint_connecting_adb))

            scope.launch {
                delay(500) // 给系统一点时间稳定
                launchStarterActivity("127.0.0.1", systemPort)
            }
            return
        }

        // 方式 2：mDNS 搜索 ADB 连接端口
        Log.i(TAG, "Phase 2: system port not found, trying mDNS")
        startConnectSearch()
    }

    /**
     * mDNS 搜索 _adb-tls-connect._tcp
     */
    private fun startConnectSearch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fallbackToManualConnect()
            return
        }

        searching = true
        searchPhase = 2
        currentPort = -1

        updateState(FloatingWindowService.STATE_CONNECTING,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_connecting))

        adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT, portObserver).apply { start() }
        Log.i(TAG, "Phase 2: mDNS connect search started")

        // 10秒超时
        scope.launch {
            delay(10000)
            if (searchPhase == 2 && searching) {
                Log.w(TAG, "Phase 2: mDNS search timed out, falling back to manual")
                stopSearch()
                fallbackToManualConnect()
            }
        }
    }

    /**
     * 通过 AdbClient 连接 ADB 并执行启动命令
     */
    private fun connectAdb(port: Int) {
        stopSearch()
        val host = "127.0.0.1"

        updateState(FloatingWindowService.STATE_CONNECTING,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_connecting_adb))

        scope.launch {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create AdbKey for connect", e)
                updateState(FloatingWindowService.STATE_FAILED,
                    context.getString(moe.shizuku.manager.R.string.adb_error_key_store))
                return@launch
            }

            try {
                Log.i(TAG, "Phase 2: Connecting to ADB at $host:$port")
                val adbClient = AdbClient(host, port, key)
                adbClient.connect()

                LOGGER.i(TAG, "Phase 2: ADB connected, starting Shizuku service")
                adbClient.shellCommand(Starter.internalCommand) { data ->
                    val text = String(data)
                    Log.i(TAG, "Phase 2: $text")
                }
                adbClient.close()

                LOGGER.i(TAG, "Phase 2: Shizuku service started successfully")
                updateState(FloatingWindowService.STATE_SUCCESS,
                    context.getString(moe.shizuku.manager.R.string.floating_window_hint_started))
            } catch (e: SSLProtocolException) {
                Log.e(TAG, "Phase 2: SSL error", e)
                updateState(FloatingWindowService.STATE_FAILED,
                    context.getString(moe.shizuku.manager.R.string.adb_pair_required))
            } catch (e: ConnectException) {
                Log.e(TAG, "Phase 2: Connection failed", e)
                fallbackToManualConnect()
            } catch (e: Throwable) {
                Log.e(TAG, "Phase 2: Failed", e)
                fallbackToManualConnect()
            }
        }
    }

    /**
     * 通过 StarterActivity 启动（与原版 Start 按钮完全一致的路径）
     */
    private fun launchStarterActivity(host: String, port: Int) {
        try {
            val intent = Intent(context, StarterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, host)
                putExtra(StarterActivity.EXTRA_PORT, port)
            }
            context.startActivity(intent)

            updateState(FloatingWindowService.STATE_SUCCESS,
                context.getString(moe.shizuku.manager.R.string.floating_window_hint_started))
            Log.i(TAG, "Phase 2: StarterActivity launched with host=$host port=$port")
        } catch (e: Throwable) {
            Log.e(TAG, "Phase 2: Failed to launch StarterActivity", e)
            fallbackToManualConnect()
        }
    }

    /**
     * 回退：弹回 APP 让用户手动输入 IP + 端口
     */
    private fun fallbackToManualConnect() {
        Log.i(TAG, "Phase 2: Falling back to manual connect")
        updateState(FloatingWindowService.STATE_FAILED,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_manual_connect))

        try {
            val intent = Intent(context, ManualConnectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch ManualConnectActivity", e)
        }
    }

    fun cancel() {
        stopSearch()
        searchPhase = 0
        scope.cancel()
    }
}
