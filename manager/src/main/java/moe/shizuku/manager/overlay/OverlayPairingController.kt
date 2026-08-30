package moe.shizuku.manager.overlay

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.net.ConnectException

/**
 * 悬浮窗 ADB 配对控制器
 *
 * 配对流程（与原版 AdbPairDialogFragment 一致）：
 * 1. 开始 mDNS 搜索 ADB 配对服务 → 悬浮窗显示"正在搜索设备…"，输入框隐藏
 * 2. 发现端口后 → 悬浮窗显示"已找到设备，请输入配对码"，输入框出现
 * 3. 用户输入配对码 → 执行 AdbPairingClient
 * 4. 显示成功/失败
 *
 * 直接调用 FloatingWindowService.updateState() 更新状态，不通过 Intent 绕圈。
 */
class OverlayPairingController(private val context: Context) {

    companion object {
        private const val TAG = "OverlayPairingCtrl"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbMdns: AdbMdns? = null
    private var currentPort: Int = -1
    private var searching = false

    private val portObserver = Observer<Int> { port ->
        Log.i(TAG, "Pairing service port: $port")
        if (port <= 0) {
            currentPort = -1
            updateState(FloatingWindowService.STATE_SEARCHING,
                context.getString(moe.shizuku.manager.R.string.floating_window_hint_searching))
            return@Observer
        }

        currentPort = port
        updateState(FloatingWindowService.STATE_WAITING_PAIR,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_waiting_pair))
    }

    /**
     * 直接调用 Service 的 updateState，不通过 Intent
     */
    private fun updateState(state: Int, hint: String? = null) {
        val service = context as? FloatingWindowService
        if (service != null) {
            service.updateState(state, hint)
        } else {
            Log.w(TAG, "Context is not FloatingWindowService, falling back to intent")
            FloatingWindowManager.updateHint(context, state, hint)
        }
    }

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
        currentPort = -1

        updateState(FloatingWindowService.STATE_SEARCHING,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_searching))

        adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING, portObserver).apply { start() }
        Log.i(TAG, "mDNS search started")
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
                    Log.i(TAG, "Pairing succeeded")
                    updateState(FloatingWindowService.STATE_SUCCESS,
                        context.getString(moe.shizuku.manager.R.string.floating_window_hint_success))
                    stopSearch()
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

    fun cancel() {
        stopSearch()
        scope.cancel()
    }
}
