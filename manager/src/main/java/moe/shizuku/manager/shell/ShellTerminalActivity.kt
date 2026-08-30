package moe.shizuku.manager.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityShellTerminalBinding
import rikka.shizuku.Shizuku
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors

class ShellTerminalActivity : AppBarActivity() {

    companion object {
        private const val PREFS_NAME = "terminal_prefs"
        private const val KEY_HISTORY = "command_history"
        private const val MAX_HISTORY = 50
        private const val MAX_OUTPUT_CHARS = 100_000

        private val QUICK_COMMANDS = listOf(
            "pm list packages" to "pm list",
            "pm grant" to "pm grant",
            "pm disable-user" to "pm disable",
            "am force-stop" to "am stop",
            "am start" to "am start",
            "dumpsys activity" to "activity",
            "dumpsys window" to "window",
            "dumpsys package" to "pkg info",
            "settings get" to "settings get",
            "settings put" to "settings put",
            "getprop" to "getprop",
            "setprop" to "setprop",
            "top -n 1" to "top",
            "df -h" to "df",
            "free -h" to "free",
            "ls -la" to "ls -la"
        )
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
        setupQuickBar()
        setupInput()

        appendOutput("Shizuku Next Terminal v1.0\n", color = 0x89b4fa, bold = true)
        appendOutput("以 Shizuku 特权身份执行 Shell 命令。\n", color = 0xa6adc8)
        appendOutput("输入命令并按回车执行，长按输入框可查看历史。\n\n", color = 0xa6adc8)
        printPrompt()
    }

    private fun setupQuickBar() {
        for ((cmd, label) in QUICK_COMMANDS) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = false
                isClickable = true
                setOnClickListener {
                    binding.inputField.setText(cmd)
                    binding.inputField.requestFocus()
                    binding.inputField.setSelection(cmd.length)
                }
            }
            binding.quickBar.addView(chip)
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

        // Show the command in output
        appendOutput(cmd + "\n", color = 0xf9e2af, bold = true)

        // Save to history
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
            if (commandHistory.size > MAX_HISTORY) {
                commandHistory.removeAt(0)
            }
            saveHistory()
        }
        historyIndex = -1

        // Check if Shizuku is running
        if (!Shizuku.pingBinder()) {
            appendOutput("错误: Shizuku 服务未运行\n", color = 0xf38ba8, bold = true)
            printPrompt()
            return
        }

        // Execute via Shizuku
        executor.execute {
            try {
                val binder = Shizuku.getBinder()
                if (binder == null || !binder.pingBinder()) {
                    handler.post {
                        appendOutput("错误: 无法获取 Shizuku Binder\n", color = 0xf38ba8)
                        printPrompt()
                    }
                    return@execute
                }

                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
                val parts = parseCommand(cmd)
                val process = service.newProcess(parts, null, null)

                val stdinFd = process.getOutputStream()
                val stdoutFd = process.getInputStream()
                val stderrFd = process.getErrorStream()

                val stdout = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stdoutFd)))
                val stderr = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stderrFd)))

                val stdoutText = StringBuilder()
                val stderrText = StringBuilder()

                var line: String?
                while (stdout.readLine().also { line = it } != null) {
                    stdoutText.append(line).append("\n")
                }
                while (stderr.readLine().also { line = it } != null) {
                    stderrText.append(line).append("\n")
                }

                val exitCode = process.waitFor()
                process.destroy()

                handler.post {
                    if (stdoutText.isNotEmpty()) {
                        appendOutput(stdoutText.toString(), color = 0xcdd6f4)
                    }
                    if (stderrText.isNotEmpty()) {
                        appendOutput(stderrText.toString(), color = 0xf9e2af)
                    }
                    if (exitCode != 0) {
                        appendOutput("[退出码: $exitCode]\n", color = 0xf38ba8)
                    }
                    printPrompt()
                }
            } catch (e: Exception) {
                handler.post {
                    appendOutput("错误: ${e.message}\n", color = 0xf38ba8, bold = true)
                    printPrompt()
                }
            }
        }
    }

    private fun parseCommand(cmd: String): Array<String> {
        // Simple shell-like parsing: split by spaces, respect quotes
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

        // If no arguments, use sh -c
        return if (result.size == 1) {
            arrayOf("sh", "-c", cmd)
        } else {
            result.toTypedArray()
        }
    }

    private fun appendOutput(text: String, color: Int = 0xcdd6f4, bold: Boolean = false) {
        val start = outputBuilder.length
        outputBuilder.append(text)
        val end = outputBuilder.length

        outputBuilder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            outputBuilder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Trim if too large
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
        appendOutput("$ ", color = 0x89b4fa, bold = true)
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
        prefs.edit {
            putString(KEY_HISTORY, commandHistory.joinToString("\n"))
        }
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
                appendOutput("已清除\n\n", color = 0xa6adc8)
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
                    commandHistory.forEachIndexed { i, cmd ->
                        sb.append("${i + 1}. $cmd\n")
                    }
                    appendOutput("\n${sb}\n", color = 0x89b4fa)
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
