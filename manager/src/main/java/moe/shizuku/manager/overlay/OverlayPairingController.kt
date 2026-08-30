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
import moe.shizuku.manager.adb.AdbKeyStore
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.net.ConnectException

/**
 * 悬浮窗 ADB 配对控制器
 *
 * 工作流程：
 * 1. 开始 mDNS 搜索 ADB 配对服务
 * 2. 发现端口后，更新悬浮窗提示为"等待配对码"
 * 3. 用户在悬浮窗输入配对码后，执行 AdbPairingClient
 * 4. 更新悬浮窗显示成功/失败
 *
 * 这是对原 AdbPairingService（通知栏输入配对码）和 AdbPairDialogFragment（弹窗输入）的第三种方式，
 * 适配无法弹出 Dialog 的机型。
 */
class OverlayPairingController(private val context: Context) {

    companion object {
        private const val TAG = "OverlayPairingCtrl"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbMdns: AdbMdns? = null
    private var currentPort: Int = -1
    private var searching = false

    /**
     * mDNS 发现回调 — 发现配对服务端口时更新悬浮窗
     */
    private val portObserver = Observer<Int> { port ->
        Log.i(TAG, "Pairing service port: $port")
        if (port <= 0) {
            currentPort = -1
            FloatingWindowManager.updateHint(
                context,
                FloatingWindowService.STATE_IDLE,
                context.getString(moe.shizuku.manager.R.string.floating_window_hint_idle)
            )
            return@Observer
        }

        currentPort = port
        FloatingWindowManager.updateHint(
            context,
            FloatingWindowService.STATE_WAITING_PAIR,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_waiting_pair)
        )
    }

    /**
     * 开始搜索 ADB 配对服务
     */
    fun startSearch() {
        if (searching) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Wireless ADB pairing requires Android 11+")
            FloatingWindowManager.updateHint(
                context,
                FloatingWindowService.STATE_FAILED,
                "Wireless ADB pairing requires Android 11+"
            )
            return
        }
        searching = true
        adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING, portObserver).apply { start() }

        FloatingWindowManager.updateHint(
            context,
            FloatingWindowService.STATE_IDLE,
            context.getString(moe.shizuku.manager.R.string.floating_window_hint_idle)
        )
    }

    /**
     * 停止搜索
     */
    fun stopSearch() {
        if (!searching) return
        searching = false
        adbMdns?.stop()
        adbMdns = null
    }

    /**
     * 用户在悬浮窗输入了配对码，执行配对
     * @param pairCode 用户输入的配对码
     */
    fun onPairingCodeInput(pairCode: String) {
        if (currentPort <= 0) {
            Log.w(TAG, "No pairing service found yet")
            FloatingWindowManager.updateHint(
                context,
                FloatingWindowService.STATE_FAILED,
                "No pairing service found"
            )
            return
        }

        val port = currentPort
        val host = "127.0.0.1"

        scope.launch {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create AdbKey", e)
                FloatingWindowManager.updateHint(
                    context,
                    FloatingWindowService.STATE_FAILED,
                    context.getString(moe.shizuku.manager.R.string.adb_error_key_store)
                )
                return@launch
            }

            val client = AdbPairingClient(host, port, pairCode, key)
            val result = client.runCatching { start() }

            client.close()

            result.onSuccess { success ->
                if (success) {
                    Log.i(TAG, "Pairing succeeded")
                    FloatingWindowManager.updateHint(
                        context,
                        FloatingWindowService.STATE_SUCCESS,
                        context.getString(moe.shizuku.manager.R.string.floating_window_hint_success)
                    )
                    // 配对成功后停止搜索
                    stopSearch()
                } else {
                    Log.w(TAG, "Pairing returned false")
                    FloatingWindowManager.updateHint(
                        context,
                        FloatingWindowService.STATE_FAILED,
                        context.getString(moe.shizuku.manager.R.string.floating_window_hint_failed)
                    )
                }
            }.onFailure { exception ->
                Log.w(TAG, "Pairing failed", exception)
                val message = when (exception) {
                    is ConnectException -> context.getString(moe.shizuku.manager.R.string.cannot_connect_port)
                    is AdbInvalidPairingCodeException -> context.getString(moe.shizuku.manager.R.string.paring_code_is_wrong)
                    is AdbKeyException -> context.getString(moe.shizuku.manager.R.string.adb_error_key_store)
                    else -> context.getString(moe.shizuku.manager.R.string.floating_window_hint_failed)
                }
                FloatingWindowManager.updateHint(
                    context,
                    FloatingWindowService.STATE_FAILED,
                    message
                )
            }
        }
    }

    fun cancel() {
        stopSearch()
        scope.cancel()
    }
}