package moe.shizuku.manager.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import moe.shizuku.manager.ShizukuSettings

/**
 * 悬浮窗模式管理器
 *
 * 负责悬浮窗模式的开关、权限检查、与 Service 的交互。
 * 原 Dialog 弹窗配对方式保留不变，悬浮窗模式作为第二种可选方式。
 *
 * 两种方式并存：
 * 1. 原方式：AdbPairDialogFragment / AdbPairingService（通知栏）/ RequestPermissionActivity
 * 2. 悬浮窗方式：FloatingWindowService 常驻输入窗
 */
object FloatingWindowManager {

    /**
     * 检查是否拥有悬浮窗权限
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 跳转到悬浮窗权限设置页
     */
    fun requestPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * 开启悬浮窗模式
     * @return true 如果成功启动，false 如果需要先授权
     */
    fun start(context: Context): Boolean {
        if (!hasPermission(context)) {
            requestPermission(context)
            return false
        }
        FloatingWindowService.start(context)
        ShizukuSettings.getPreferences()
            .edit()
            .putBoolean(ShizukuSettings.FLOATING_WINDOW_ENABLED, true)
            .apply()
        return true
    }

    /**
     * 关闭悬浮窗模式
     */
    fun stop(context: Context) {
        FloatingWindowService.stop(context)
        ShizukuSettings.getPreferences()
            .edit()
            .putBoolean(ShizukuSettings.FLOATING_WINDOW_ENABLED, false)
            .apply()
    }

    /**
     * 悬浮窗模式是否已开启（用户设置层面）
     */
    fun isEnabled(): Boolean {
        return ShizukuSettings.getPreferences()
            .getBoolean(ShizukuSettings.FLOATING_WINDOW_ENABLED, false)
    }

    /**
     * 悬浮窗是否正在运行
     */
    fun isRunning(): Boolean {
        return FloatingWindowService.isRunning()
    }

    /**
     * 更新悬浮窗提示状态
     */
    fun updateHint(context: Context, state: Int, hint: String? = null) {
        val intent = Intent(context, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_UPDATE_HINT
            putExtra(FloatingWindowService.EXTRA_STATE, state)
            if (hint != null) {
                putExtra(FloatingWindowService.EXTRA_HINT, hint)
            }
        }
        context.startService(intent)
    }

    /**
     * 通过悬浮窗启动 ADB 配对搜索
     * 悬浮窗会进入"等待配对码"状态
     */
    fun startPairing(context: Context) {
        val intent = Intent(context, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_START_PAIRING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * 通过悬浮窗请求授权
     * 悬浮窗会进入"等待授权密码"状态
     * @param callback 当用户输入密码后的回调
     */
    fun requestAuth(context: Context, callback: (String) -> Unit) {
        OverlayServiceHolder.setAuthCallback(callback)
        updateHint(context, FloatingWindowService.STATE_WAITING_AUTH)
    }
}