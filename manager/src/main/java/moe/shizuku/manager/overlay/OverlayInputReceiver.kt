package moe.shizuku.manager.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.utils.Logger.LOGGER

/**
 * 悬浮窗输入接收器
 *
 * 监听 FloatingWindowService 发出的 INPUT_SUBMITTED 广播，
 * 根据当前状态将用户输入路由到对应的处理器：
 * - STATE_WAITING_PAIR → OverlayPairingController.onPairingCodeInput()
 * - STATE_WAITING_AUTH → 授权处理（配合 RequestPermissionActivity 流程）
 *
 * 此接收器在 FloatingWindowService 中动态注册，随 Service 生命周期管理。
 */
class OverlayInputReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INPUT_SUBMITTED = "moe.shizuku.manager.overlay.INPUT_SUBMITTED"
        const val EXTRA_INPUT = "input"
        const val EXTRA_STATE = "state"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INPUT_SUBMITTED) return

        val input = intent.getStringExtra(EXTRA_INPUT) ?: return
        val state = intent.getIntExtra(EXTRA_STATE, FloatingWindowService.STATE_IDLE)

        LOGGER.i("OverlayInputReceiver", "Received input for state=$state, length=${input.length}")

        when (state) {
            FloatingWindowService.STATE_WAITING_PAIR -> {
                // 配对码输入 → 交给配对控制器处理
                OverlayServiceHolder.pairingController?.onPairingCodeInput(input)
            }
            FloatingWindowService.STATE_WAITING_AUTH -> {
                // 授权密码输入 → 交给授权处理器处理
                OverlayServiceHolder.onAuthPasswordInput(context, input)
            }
            else -> {
                LOGGER.w("OverlayInputReceiver", "Input received in unexpected state=$state, ignoring")
            }
        }
    }
}