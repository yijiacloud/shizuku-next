package moe.shizuku.manager.overlay

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityManualConnectBinding
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils

/**
 * 手动输入 ADB 连接信息页面
 *
 * 配对成功但自动搜索 ADB 端口失败时，弹回此页面让用户手动输入 IP + 端口。
 * 默认 IP 为 127.0.0.1，端口自动尝试从系统属性读取。
 */
class ManualConnectActivity : AppBarActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityManualConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.manual_connect_title)

        // 自动填充可能的端口号
        val autoPort = EnvironmentUtils.getAdbTcpPort()
        if (autoPort > 0) {
            binding.portInput.setText(autoPort.toString())
        }

        binding.connectButton.setOnClickListener {
            val host = binding.hostInput.text.toString().trim()
            val portStr = binding.portInput.text.toString().trim()

            if (host.isEmpty()) {
                Toast.makeText(this, R.string.manual_connect_host_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = portStr.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                Toast.makeText(this, R.string.manual_connect_port_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, host)
                putExtra(StarterActivity.EXTRA_PORT, port)
            }
            startActivity(intent)
            finish()
        }
    }
}
