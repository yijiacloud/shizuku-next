package moe.shizuku.manager.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.Toast
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityShellTerminalBinding
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class ShellTerminalActivity : AppBarActivity() {

    companion object {
        private const val PREFS_NAME = "terminal_prefs"
        private const val KEY_HISTORY = "command_history"
        private const val MAX_HISTORY = 50
        private const val MAX_OUTPUT_CHARS = 100_000

        // 颜色 — 必须带 0xFF 前缀（alpha=255），否则文字透明
        private const val COLOR_PROMPT = 0xFF89B4FA.toInt()     // 蓝色 — 提示符
        private const val COLOR_CMD = 0xFFF9E2AF.toInt()        // 黄色 — 用户输入的命令
        private const val COLOR_OUTPUT = 0xFFCDD6F4.toInt()     // 浅色 — 标准输出
        private const val COLOR_ERROR = 0xFFF38BA8.toInt()      // 红色 — 错误
        private const val COLOR_INFO = 0xFFA6ADC8.toInt()       // 灰色 — 提示信息
        private const val COLOR_EXITCODE = 0xFFF38BA8.toInt()   // 红色 — 非零退出码
    }

    private lateinit var binding: ActivityShellTerminalBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val outputBuilder = SpannableStringBuilder()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var currentInputBase = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.terminal_title)
        }

        loadHistory()
        setupInput()

        // 隐藏快捷指令栏，界面更简洁
        binding.quickBarScroll.visibility = View.GONE

        appendOutput("Shizuku Next 终端 v1.0\n", COLOR_PROMPT, bold = true)
        appendOutput("以 Shizuku 特权身份执行 Shell 命令。\n", COLOR_INFO)
        appendOutput("输入命令并按回车执行，↑↓ 键翻历史。\n\n", COLOR_INFO)
        printPrompt()

        // 处理来自模块的脚本执行请求
        val scriptContent = intent.getStringExtra("script_content")
        if (!scriptContent.isNullOrEmpty()) {
            val moduleDir = intent.getStringExtra("module_dir") ?: ""
            // 将脚本写入临时文件并执行
            executor.execute {
                try {
                    val scriptFile = java.io.File(cacheDir, "module_action.sh")
                    scriptFile.writeText(scriptContent)
                    scriptFile.setExecutable(true)

                    if (!Shizuku.pingBinder()) {
                        handler.post {
                            appendOutput("错误：Shizuku 服务未运行\n", COLOR_ERROR, bold = true)
                            printPrompt()
                        }
                        return@execute
                    }

                    val binder = Shizuku.getBinder()
                    if (binder == null || !binder.pingBinder()) {
                        handler.post {
                            appendOutput("错误：无法获取 Shizuku Binder\n", COLOR_ERROR)
                            printPrompt()
                        }
                        return@execute
                    }

                    val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)

                    // 将脚本文件路径传给 sh 执行
                    val cmd = if (moduleDir.isNotEmpty()) {
                        "MODDIR=\"$moduleDir\" sh \"$scriptFile\" \"$moduleDir\""
                    } else {
                        "sh \"$scriptFile\""
                    }

                    handler.post {
                        appendOutput("执行模块脚本...\n", COLOR_INFO)
                    }

                    val parts = arrayOf("sh", "-c", cmd)
                    val process = service.newProcess(parts, null, null)

                    val stdoutFd = process.getInputStream()
                    val stderrFd = process.getErrorStream()

                    val stdout = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stdoutFd)))
                    val stderr = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stderrFd)))

                    val stdoutThread = Thread {
                        try {
                            var line: String?
                            while (stdout.readLine().also { line = it } != null) {
                                val text = line + "\n"
                                handler.post { appendOutput(text, COLOR_OUTPUT) }
                            }
                        } catch (e: Exception) {
                            handler.post { appendOutput("读取输出失败: ${e.message}\n", COLOR_ERROR) }
                        } finally {
                            try { stdout.close() } catch (_: Exception) {}
                        }
                    }

                    val stderrThread = Thread {
                        try {
                            var line: String?
                            while (stderr.readLine().also { line = it } != null) {
                                val text = line + "\n"
                                handler.post { appendOutput(text, COLOR_ERROR) }
                            }
                        } catch (e: Exception) {}
                        finally {
                            try { stderr.close() } catch (_: Exception) {}
                        }
                    }

                    stdoutThread.start()
                    stderrThread.start()
                    stdoutThread.join()
                    stderrThread.join()

                    val exitCode = process.waitFor()
                    process.destroy()

                    handler.post {
                        if (exitCode != 0) {
                            appendOutput("[退出码: $exitCode]\n", COLOR_EXITCODE)
                        }
                        appendOutput("模块脚本执行完毕。\n\n", COLOR_INFO)
                        printPrompt()
                    }

                    scriptFile.delete()
                } catch (e: Exception) {
                    handler.post {
                        appendOutput("执行模块脚本失败: ${e.message}\n", COLOR_ERROR, bold = true)
                        printPrompt()
                    }
                }
            }
        }
    }

    private fun setupInput() {
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                executeCommand()
                true
            } else false
        }

        binding.inputField.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        executeCommand()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        navigateHistory(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navigateHistory(1)
                        true
                    }
                    else -> false
                }
            } else false
        }

        binding.sendButton.setOnClickListener {
            executeCommand()
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        if (historyIndex == -1) {
            currentInputBase = binding.inputField.text.toString()
            historyIndex = commandHistory.size
        }
        historyIndex += direction
        if (historyIndex < 0) historyIndex = 0
        if (historyIndex > commandHistory.size) {
            historyIndex = commandHistory.size
            binding.inputField.setText(currentInputBase)
        } else if (historyIndex == commandHistory.size) {
            binding.inputField.setText(currentInputBase)
        } else {
            binding.inputField.setText(commandHistory[historyIndex])
        }
        binding.inputField.setSelection(binding.inputField.text.length)
    }

    private fun executeCommand() {
        val cmd = binding.inputField.text.toString().trim()
        if (cmd.isEmpty()) return

        binding.inputField.setText("")

        // 在输出区显示用户输入的命令
        appendOutput(cmd + "\n", COLOR_CMD, bold = true)

        // 保存到历史
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
            if (commandHistory.size > MAX_HISTORY) {
                commandHistory.removeAt(0)
            }
            saveHistory()
        }
        historyIndex = -1

        // 检查 Shizuku 是否运行
        if (!Shizuku.pingBinder()) {
            appendOutput("错误：Shizuku 服务未运行\n", COLOR_ERROR, bold = true)
            printPrompt()
            return
        }

        // 通过 Shizuku 执行命令 — 流式输出
        executor.execute {
            try {
                val binder = Shizuku.getBinder()
                if (binder == null || !binder.pingBinder()) {
                    handler.post {
                        appendOutput("错误：无法获取 Shizuku Binder\n", COLOR_ERROR)
                        printPrompt()
                    }
                    return@execute
                }

                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
                val parts = parseCommand(cmd)
                val process = service.newProcess(parts, null, null)

                val stdoutFd = process.getInputStream()
                val stderrFd = process.getErrorStream()

                val stdout = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stdoutFd)))
                val stderr = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stderrFd)))

                // 双线程分别读取 stdout 和 stderr，实时推送到 UI
                val stdoutThread = Thread {
                    try {
                        var line: String?
                        while (stdout.readLine().also { line = it } != null) {
                            val text = line + "\n"
                            handler.post { appendOutput(text, COLOR_OUTPUT) }
                        }
                    } catch (e: Exception) {
                        handler.post { appendOutput("读取输出失败: ${e.message}\n", COLOR_ERROR) }
                    } finally {
                        try { stdout.close() } catch (_: Exception) {}
                    }
                }

                val stderrThread = Thread {
                    try {
                        var line: String?
                        while (stderr.readLine().also { line = it } != null) {
                            val text = line + "\n"
                            handler.post { appendOutput(text, COLOR_ERROR) }
                        }
                    } catch (e: Exception) {
                        // 静默处理
                    } finally {
                        try { stderr.close() } catch (_: Exception) {}
                    }
                }

                stdoutThread.start()
                stderrThread.start()

                // 等待两个输出线程结束
                stdoutThread.join()
                stderrThread.join()

                val exitCode = process.waitFor()
                process.destroy()

                handler.post {
                    if (exitCode != 0) {
                        appendOutput("[退出码: $exitCode]\n", COLOR_EXITCODE)
                    }
                    printPrompt()
                }
            } catch (e: Exception) {
                handler.post {
                    appendOutput("错误：${e.message}\n", COLOR_ERROR, bold = true)
                    printPrompt()
                }
            }
        }
    }

    private fun parseCommand(cmd: String): Array<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var quoteChar = ' '

        for (ch in cmd) {
            when {
                inQuote -> {
                    if (ch == quoteChar) {
                        inQuote = false
                    } else {
                        current.append(ch)
                    }
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                }
                ch == ' ' || ch == '\t' -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        // 始终用 sh -c 执行，支持管道、重定向等
        return arrayOf("sh", "-c", cmd)
    }

    private fun appendOutput(text: String, color: Int = COLOR_OUTPUT, bold: Boolean = false) {
        val start = outputBuilder.length
        outputBuilder.append(text)
        val end = outputBuilder.length

        outputBuilder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            outputBuilder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (outputBuilder.length > MAX_OUTPUT_CHARS) {
            val excess = outputBuilder.length - MAX_OUTPUT_CHARS
            outputBuilder.delete(0, excess)
        }

        binding.outputView.text = outputBuilder
        binding.outputScroll.post {
            binding.outputScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun printPrompt() {
        appendOutput("$ ", COLOR_PROMPT, bold = true)
    }

    private fun loadHistory() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val history = prefs.getString(KEY_HISTORY, "") ?: ""
        if (history.isNotEmpty()) {
            commandHistory.addAll(history.split("\n").filter { it.isNotEmpty() })
        }
    }

    private fun saveHistory() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, commandHistory.joinToString("\n")).apply()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear -> {
                outputBuilder.clear()
                binding.outputView.text = ""
                appendOutput("已清除\n\n", COLOR_INFO)
                printPrompt()
                true
            }
            R.id.action_copy -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("terminal", binding.outputView.text))
                Toast.makeText(this, R.string.terminal_copied, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_history -> {
                if (commandHistory.isEmpty()) {
                    Toast.makeText(this, R.string.terminal_no_history, Toast.LENGTH_SHORT).show()
                } else {
                    val sb = StringBuilder("命令历史:\n")
                    commandHistory.forEachIndexed { i, c ->
                        sb.append("${i + 1}. $c\n")
                    }
                    appendOutput("\n${sb}\n", COLOR_PROMPT)
                    printPrompt()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
