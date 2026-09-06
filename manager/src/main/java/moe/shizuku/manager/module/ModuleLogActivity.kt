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
import java.io.File
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
            title = if (mode == MODE_UNINSTALL) "\u5378\u8f7d\u65e5\u5fd7" else "\u5b89\u88c5\u65e5\u5fd7"
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
            appendLog("\u6ca1\u6709\u5b89\u88c5/\u5378\u8f7d\u811a\u672c\uff0c\u76f4\u63a5\u5b8c\u6210\u3002\n", COLOR_INFO)
            statusText.text = "\u65e0\u9700\u6267\u884c\u811a\u672c"
            btnDone.isEnabled = true
            if (mode == MODE_UNINSTALL && moduleId.isNotEmpty()) {
                ModuleManager.deleteModule(this, moduleId)
            }
            return
        }

        val label = if (mode == MODE_UNINSTALL) "\u5378\u8f7d" else "\u5b89\u88c5"
        appendLog(label + "\u6a21\u5757\u811a\u672c...\n", COLOR_CMD, bold = true)
        executeScript(scriptContent, moduleDir, mode, moduleId)
    }

    private fun executeScript(scriptContent: String, moduleDir: String, mode: String, moduleId: String) {
        executor.execute {
            try {
                if (!Shizuku.pingBinder()) {
                    handler.post {
                        appendLog("\u9519\u8bef\uff1aShizuku \u670d\u52a1\u672a\u8fd0\u884c\n", COLOR_ERROR, bold = true)
                        statusText.text = "\u6267\u884c\u5931\u8d25"
                        btnDone.isEnabled = true
                    }
                    return@execute
                }

                val binder = Shizuku.getBinder()
                if (binder == null || !binder.pingBinder()) {
                    handler.post {
                        appendLog("\u9519\u8bef\uff1a\u65e0\u6cd5\u83b7\u53d6 Shizuku Binder\n", COLOR_ERROR)
                        statusText.text = "\u6267\u884c\u5931\u8d25"
                        btnDone.isEnabled = true
                    }
                    return@execute
                }

                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)

                // 1. Create temp dir in /data/local/tmp/ (shell user can access)
                val tempDir = "/data/local/tmp/sn_mod_" + moduleId
                runShell(service, "mkdir -p '" + tempDir + "'")

                // 2. Copy all module files to temp dir via stdin (streaming in chunks)
                val moduleDirFile = File(moduleDir)
                if (moduleDirFile.exists()) {
                    moduleDirFile.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relPath = file.relativeTo(moduleDirFile).path
                            val destPath = tempDir + "/" + relPath
                            val parentDir = destPath.substring(0, destPath.lastIndexOf('/'))
                            runShell(service, "mkdir -p '" + parentDir + "'")
                            copyFileViaStdin(service, destPath, file)
                            if (relPath.endsWith(".sh") || relPath == "xpad2" || !relPath.contains(".")) {
                                runShell(service, "chmod 755 '" + destPath + "'")
                            }
                            handler.post { appendLog("\u590d\u5236\u6587\u4ef6: " + relPath + "\n", COLOR_INFO) }
                        }
                    }
                }

                // 3. Write script to temp dir
                val scriptPath = tempDir + "/" + mode + ".sh"
                copyBytesViaStdin(service, scriptPath, scriptContent.toByteArray())
                runShell(service, "chmod 755 '" + scriptPath + "'")

                // 4. Execute script with MODDIR set to temp dir
                val cmd = "MODDIR='" + tempDir + "' sh '" + scriptPath + "' '" + tempDir + "'"
                handler.post { appendLog("\u5f00\u59cb\u6267\u884c\u811a\u672c...\n", COLOR_CMD) }

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
                        handler.post { appendLog("\u8bfb\u53d6\u8f93\u51fa\u5931\u8d25: " + e.message + "\n", COLOR_ERROR) }
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

                // Cleanup temp dir
                runShell(service, "rm -rf '" + tempDir + "'")

                handler.post {
                    if (exitCode == 0) {
                        appendLog("\n[\u6267\u884c\u6210\u529f]\n", COLOR_SUCCESS, bold = true)
                        statusText.text = "\u6267\u884c\u6210\u529f"
                    } else {
                        appendLog("\n[\u9000\u51fa\u7801: " + exitCode + "]\n", COLOR_ERROR, bold = true)
                        statusText.text = "\u6267\u884c\u5931\u8d25 (code=" + exitCode + ")"
                    }

                    if (mode == MODE_UNINSTALL && moduleId.isNotEmpty()) {
                        ModuleManager.deleteModule(this@ModuleLogActivity, moduleId)
                        appendLog("\u6a21\u5757\u5df2\u5220\u9664\n", COLOR_INFO)
                    }

                    appendLog("----------------------------------------\n", COLOR_INFO)
                    btnDone.isEnabled = true
                }
            } catch (e: Exception) {
                handler.post {
                    appendLog("\u6267\u884c\u5931\u8d25: " + e.message + "\n", COLOR_ERROR, bold = true)
                    statusText.text = "\u6267\u884c\u5931\u8d25"
                    btnDone.isEnabled = true
                    if (mode == MODE_UNINSTALL && moduleId.isNotEmpty()) {
                        ModuleManager.deleteModule(this@ModuleLogActivity, moduleId)
                    }
                }
            }
        }
    }

    private fun runShell(service: moe.shizuku.server.IShizukuService, cmd: String): Int {
        return try {
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val code = process.waitFor()
            process.destroy()
            code
        } catch (e: Exception) {
            -1
        }
    }

    private fun copyFileViaStdin(service: moe.shizuku.server.IShizukuService, destPath: String, file: File) {
        try {
            val process = service.newProcess(arrayOf("sh", "-c", "cat > '" + destPath + "'"), null, null)
            val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    outputStream.write(buffer, 0, len)
                }
            }
            outputStream.flush()
            outputStream.close()
            process.waitFor()
            process.destroy()
        } catch (e: Exception) {
            handler.post { appendLog("\u590d\u5236\u6587\u4ef6\u5931\u8d25: " + destPath + " - " + e.message + "\n", COLOR_ERROR) }
        }
    }

    private fun copyBytesViaStdin(service: moe.shizuku.server.IShizukuService, destPath: String, data: ByteArray) {
        try {
            val process = service.newProcess(arrayOf("sh", "-c", "cat > '" + destPath + "'"), null, null)
            val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream)
            val buffer = ByteArray(8192)
            var offset = 0
            while (offset < data.size) {
                val len = minOf(buffer.size, data.size - offset)
                System.arraycopy(data, offset, buffer, 0, len)
                outputStream.write(buffer, 0, len)
                offset += len
            }
            outputStream.flush()
            outputStream.close()
            process.waitFor()
            process.destroy()
        } catch (e: Exception) {
            handler.post { appendLog("\u590d\u5236\u6587\u4ef6\u5931\u8d25: " + destPath + " - " + e.message + "\n", COLOR_ERROR) }
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
