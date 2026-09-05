package moe.shizuku.manager.module

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class ModuleLogActivity : AppBarActivity() {

    companion object {
        const val EXTRA_SCRIPT_CONTENT = "script_content"
        const val EXTRA_MODULE_DIR = "module_dir"
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_MODE = "mode"
        const val MODE_INSTALL = "install"
        const val MODE_UNINSTALL = "uninstall"
    }

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var btnDone: View

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val logBuilder = SpannableStringBuilder()

    private val COLOR_CMD = 0xFF89B4FA.toInt()
    private val COLOR_OUTPUT = 0xFFCDD6F4.toInt()
    private val COLOR_ERROR = 0xFFF38BA8.toInt()
    private val COLOR_INFO = 0xFFA6ADC8.toInt()
    private val COLOR_SUCCESS = 0xFFA6E3A1.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_INSTALL
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = if (mode == MODE_UNINSTALL) "卸载日志" else "安装日志"
        }

        val params = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        params.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()

        val root = layoutInflater.inflate(R.layout.activity_module_log, null)
        setContentView(root, params)

        logView = root.findViewById(R.id.log_view)
        logScroll = root.findViewById(R.id.log_scroll)
        statusText = root.findViewById(R.id.status_text)
        btnDone = root.findViewById(R.id.btn_done)

        btnDone.setOnClickListener { finish() }

        val scriptContent = intent.getStringExtra(EXTRA_SCRIPT_CONTENT)
        val moduleDir = intent.getStringExtra(EXTRA_MODULE_DIR) ?: ""
        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID) ?: ""

        if (scriptContent.isNullOrEmpty()) {
            appendLog("没有安装/卸载脚本，直接完成。\n", COLOR_INFO)
            statusText.text = "无需执行脚本"
            btnDone.isEnabled = true
            if (mode == MODE_UNINSTALL && moduleId.isNotEmpty()) {
                ModuleManager.deleteModule(this, moduleId)
            }
            return
        }

        val label = if (mode == MODE_UNINSTALL) "卸载" else "安装"
        appendLog(label + "模块脚本...\n\n", COLOR_CMD, bold = true)
        executeScript(scriptContent, moduleDir, mode, moduleId)
    }

    private fun executeScript(scriptContent: String, moduleDir: String, mode: String, moduleId: String) {
        executor.execute {
            try {
                val scriptFile = java.io.File(cacheDir, "module_" + mode + ".sh")
                scriptFile.writeText(scriptContent)
                scriptFile.setExecutable(true)

                if (!Shizuku.pingBinder()) {
                    handler.post {
                        appendLog("错误：Shizuku 服务未运行\n", COLOR_ERROR, bold = true)
                        statusText.text = "执行失败"
                        btnDone.isEnabled = true
                    }
                    return@execute
                }

                val binder = Shizuku.getBinder()
                if (binder == null || !binder.pingBinder()) {
                    handler.post {
                        appendLog("错误：无法获取 Shizuku Binder\n", COLOR_ERROR)
                        statusText.text = "执行失败"
                        btnDone.isEnabled = true
                    }
                    return@execute
                }

                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)

                val cmd = if (moduleDir.isNotEmpty()) {
                    "MODDIR=\"" + moduleDir + "\" sh \"" + scriptFile.absolutePath + "\" \"" + moduleDir + "\""
                } else {
                    "sh \"" + scriptFile.absolutePath + "\""
                }

                val parts = arrayOf("sh", "-c", cmd)
                val process = service.newProcess(parts, null, null)

                val stdout = BufferedReader(
                    InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(process.inputStream))
                )
                val stderr = BufferedReader(
                    InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(process.errorStream))
                )

                val stdoutThread = Thread {
                    try {
                        var line: String?
                        while (stdout.readLine().also { line = it } != null) {
                            val text = line + "\n"
                            handler.post { appendLog(text, COLOR_OUTPUT) }
                        }
                    } catch (e: Exception) {
                        handler.post { appendLog("读取输出失败: " + e.message + "\n", COLOR_ERROR) }
                    } finally {
                        try { stdout.close() } catch (_: Exception) {}
                    }
                }

                val stderrThread = Thread {
                    try {
                        var line: String?
                        while (stderr.readLine().also { line = it } != null) {
                            val text = line + "\n"
                            handler.post { appendLog(text, COLOR_ERROR) }
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { stderr.close() } catch (_: Exception) {}
                    }
                }

                stdoutThread.start()
                stderrThread.start()
                stdoutThread.join()
                stderrThread.join()

                val exitCode = process.waitFor()
                process.destroy()
                scriptFile.delete()

                handler.post {
                    if (exitCode == 0) {
                        appendLog("\n[执行成功]\n", COLOR_SUCCESS, bold = true)
                        statusText.text = "执行成功"
                    } else {
                        appendLog("\n[退出码: " + exitCode + "]\n", COLOR_ERROR, bold = true)
                        statusText.text = "执行失败 (code=" + exitCode + ")"
                    }

                    if (mode == MODE_UNINSTALL && moduleId.isNotEmpty()) {
                        ModuleManager.deleteModule(this@ModuleLogActivity, moduleId)
                        appendLog("模块已删除\n", COLOR_INFO)
                    }

                    appendLog("----------------------------------------\n", COLOR_INFO)
                    btnDone.isEnabled = true
                }
            } catch (e: Exception) {
                handler.post {
                    appendLog("执行失败: " + e.message + "\n", COLOR_ERROR, bold = true)
                    statusText.text = "执行失败"
                    btnDone.isEnabled = true
                    if (mode == MODE_UNINSTALL && moduleId.isNotEmpty()) {
                        ModuleManager.deleteModule(this@ModuleLogActivity, moduleId)
                    }
                }
            }
        }
    }

    private fun appendLog(text: String, color: Int = COLOR_OUTPUT, bold: Boolean = false) {
        val start = logBuilder.length
        logBuilder.append(text)
        val end = logBuilder.length
        logBuilder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            logBuilder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = logBuilder
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}