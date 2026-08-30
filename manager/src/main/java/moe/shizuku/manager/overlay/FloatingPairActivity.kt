package moe.shizuku.manager.overlay

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityFloatingPairBinding

/**
 * 悬浮窗配对教程页面
 *
 * 配对流程：
 * 1. 步骤1：在开发者选项中开启无线调试
 * 2. 步骤2：允许应用在设置上重叠显示（悬浮窗权限）
 * 3. 步骤3：在开发者选项中勾选「允许其他设备重叠在设置上」
 * 4. 步骤4：点击「开始配对」→ 启动悬浮窗服务并开始 mDNS 搜索（页面不关闭）
 * 5. 步骤5：点击「使用配对码配对设备」，生成配对码和端口
 * 6. 步骤6：在悬浮窗中用自带键盘输入配对码完成配对
 * 7. 配对成功后自动搜索 ADB 端口并连接，若失败则弹回此页让用户手动输入 IP+端口
 */
@RequiresApi(Build.VERSION_CODES.R)
class FloatingPairActivity : AppBarActivity() {

    private lateinit var binding: ActivityFloatingPairBinding
    private var waitingForPermission = false
    private var pairingStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFloatingPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.home_wireless_adb_pair_via_floating_window)

        // 步骤1：跳转开发者选项
        binding.developerOptions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.development_settings, Toast.LENGTH_SHORT).show()
            }
        }

        // 步骤2：跳转悬浮窗权限设置
        binding.overlayOptions.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: ActivityNotFoundException) {}
                }
            }
        }

        // 步骤3：跳转开发者选项（允许其他设备重叠在设置上）
        binding.settingsOverlayOptions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.development_settings, Toast.LENGTH_SHORT).show()
            }
        }

        // 步骤4：开始配对（不关闭页面，悬浮窗会显示在最上层）
        binding.startPairing.setOnClickListener {
            startPairing()
        }

        // 步骤5：跳转开发者选项中的无线配对
        binding.wirelessPairingOptions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(":settings:fragment_args_key", "adb_wireless")
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.development_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPermission) {
            waitingForPermission = false
            if (FloatingWindowManager.hasPermission(this)) {
                startPairing()
            }
        }
    }

    private fun startPairing() {
        if (!FloatingWindowManager.hasPermission(this)) {
            waitingForPermission = true
            Toast.makeText(this, R.string.floating_pair_permission_msg, Toast.LENGTH_LONG).show()
            FloatingWindowManager.requestPermission(this)
            return
        }

        val intent = Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_START_PAIRING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // 不调用 finish()，页面保持打开，悬浮窗显示在最上层
        // 用户可以继续看步骤5的按钮跳转到开发者选项生成配对码
        pairingStarted = true
        binding.startPairing.text = getString(R.string.floating_pair_started)
        binding.startPairing.isEnabled = false
    }
}
