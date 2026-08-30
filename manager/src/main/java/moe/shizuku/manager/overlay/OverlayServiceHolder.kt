package moe.shizuku.manager.overlay

import android.content.Context
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * 悬浮窗服务组件持有者
 *
 * 由于 BroadcastReceiver 无法直接访问 Service 实例，
 * 通过此单例中转，让 OverlayInputReceiver 能调用到 Service 内的控制器。
 *
 * 生命周期由 FloatingWindowService 管理：
 * - onCreate 时设置引用
 * - onDestroy 时清除引用
 */
object OverlayServiceHolder {

    @Volatile
    var pairingController: OverlayPairingController? = null
        internal set

    @Volatile
    private var authCallback: ((String) -> Unit)? = null

    /**
     * 设置授权密码回调
     * 当用户在悬浮窗输入授权密码时，通过此回调通知
     */
    fun setAuthCallback(callback: ((String) -> Unit)?) {
        authCallback = callback
    }

    /**
     * 处理授权密码输入
     */
    fun onAuthPasswordInput(context: Context, password: String) {
        val callback = authCallback
        if (callback != null) {
            LOGGER.i("OverlayServiceHolder", "Dispatching auth password input")
            callback(password)
        } else {
            LOGGER.w("OverlayServiceHolder", "No auth callback registered, password ignored")
        }
    }
}