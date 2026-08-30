package moe.shizuku.manager.module

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 模块配置页面
 *
 * 使用 WebView 显示模块的 config.html 配置界面
 * 模块开发者可以在 HTML 中使用 JavaScript 与 Shizuku 交互
 *
 * JS 桥接接口（window.ShizukuNext）：
 * - moduleId: 模块 ID
 * - moduleName: 模块名称
 * - exec(cmd): 执行 shell 命令，输出实时回传到 JS 回调
 * - execScript(scriptPath): 执行脚本文件
 * - getModuleDir(): 获取模块目录路径
 */
class ModuleConfigActivity : AppBarActivity() {

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_MODULE_NAME = "module_name"
        const val EXTRA_HTML_CONTENT = "html_content"
        private const val TAG = "ModuleConfigActivity"
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleName = intent.getStringExtra(EXTRA_MODULE_NAME) ?: "Module Config"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = moduleName

        webView = WebView(this)

        // 使用 ScrollingViewBehavior 让 WebView 自动定位到工具栏下方
        // 解决顶部内容被 AppBar 遮挡的问题
        val params = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        params.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        setContentView(webView, params)

        val htmlContent = intent.getStringExtra(EXTRA_HTML_CONTENT) ?: ""
        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID) ?: ""

        // 注册 JavascriptInterface
        webView.addJavascriptInterface(ModuleJsBridge(moduleId), "ShizukuNative")

        // 包装 HTML，注入模块信息
        val wrappedHtml = wrapHtml(htmlContent, moduleName, moduleId)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadDataWithBaseURL(
            "about:blank",
            wrappedHtml,
            "text/html",
            "UTF-8",
            null
        )
    }

    /**
     * JS 桥接对象，在 WebView 的 JavaScript 线程中调用
     */
    inner class ModuleJsBridge(private val moduleId: String) {

        /**
         * 获取模块目录路径
         */
        @JavascriptInterface
        fun getModuleDir(): String {
            return "/data/data/moe.shizuku.privileged.api/files/modules/$moduleId"
        }

        /**
         * 执行 shell 命令，输出通过 JS 回调实时推送
         * JS 端通过 window.ShizukuNext._onOutput(line) 和 _onComplete(code) 接收
         */
        @JavascriptInterface
        fun exec(cmd: String) {
            Log.i(TAG, "exec: $cmd")
            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        handler.post {
                            webView.evaluateJavascript(
                                "if(window.ShizukuNext&&window.ShizukuNext._onError){window.ShizukuNext._onError('Shizuku 服务未运行');}",
                                null
                            )
                        }
                        return@execute
                    }

                    val binder = Shizuku.getBinder()
                    if (binder == null || !binder.pingBinder()) {
                        handler.post {
                            webView.evaluateJavascript(
                                "if(window.ShizukuNext&&window.ShizukuNext._onError){window.ShizukuNext._onError('无法获取 Binder');}",
                                null
                            )
                        }
                        return@execute
                    }

                    val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
                    val parts = arrayOf("sh", "-c", cmd)
                    val process = service.newProcess(parts, null, null)

                    val stdout = BufferedReader(
                        InputStreamReader(
                            ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                        )
                    )
                    val stderr = BufferedReader(
                        InputStreamReader(
                            ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                        )
                    )

                    // stdout 实时推送
                    val stdoutThread = Thread {
                        try {
                            var line: String?
                            while (stdout.readLine().also { line = it } != null) {
                                val text = line!!.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                                handler.post {
                                    webView.evaluateJavascript(
                                        "if(window.ShizukuNext&&window.ShizukuNext._onOutput){window.ShizukuNext._onOutput('$text','stdout');}",
                                        null
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "stdout read error", e)
                        } finally {
                            try { stdout.close() } catch (_: Exception) {}
                        }
                    }

                    // stderr 实时推送
                    val stderrThread = Thread {
                        try {
                            var line: String?
                            while (stderr.readLine().also { line = it } != null) {
                                val text = line!!.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                                handler.post {
                                    webView.evaluateJavascript(
                                        "if(window.ShizukuNext&&window.ShizukuNext._onOutput){window.ShizukuNext._onOutput('$text','stderr');}",
                                        null
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "stderr read error", e)
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

                    handler.post {
                        webView.evaluateJavascript(
                            "if(window.ShizukuNext&&window.ShizukuNext._onComplete){window.ShizukuNext._onComplete($exitCode);}",
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "exec error", e)
                    handler.post {
                        webView.evaluateJavascript(
                            "if(window.ShizukuNext&&window.ShizukuNext._onError){window.ShizukuNext._onError('${e.message?.replace("'", "\\'")}');}",
                            null
                        )
                    }
                }
            }
        }

        /**
         * 执行脚本文件
         */
        @JavascriptInterface
        fun execScript(scriptPath: String) {
            exec("sh '$scriptPath'")
        }
    }

    /**
     * 包装模块的 HTML，添加基础样式和 Shizuku Next JS 桥接
     */
    private fun wrapHtml(content: String, moduleName: String, moduleId: String): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, 'Segoe UI', Roboto, sans-serif;
            padding: 16px;
            background: #fef7ff;
            color: #1d1b20;
        }
        h1, h2, h3 { margin-bottom: 12px; }
        p { margin-bottom: 12px; line-height: 1.6; }
        button {
            padding: 10px 24px;
            border: none;
            border-radius: 20px;
            background: #6750a4;
            color: white;
            font-size: 14px;
            cursor: pointer;
            margin: 4px;
        }
        button:active { opacity: 0.8; }
        button:disabled { opacity: 0.5; cursor: not-allowed; }
        button.secondary {
            background: #e7e0eb;
            color: #6750a4;
        }
        input, select, textarea {
            padding: 10px;
            border: 1px solid #cac4d0;
            border-radius: 8px;
            font-size: 14px;
            width: 100%;
            margin-bottom: 12px;
        }
        .card {
            background: #f3edf7;
            border-radius: 16px;
            padding: 16px;
            margin-bottom: 16px;
        }
        .log-box {
            background: #1d1b20;
            color: #cdd6f4;
            border-radius: 12px;
            padding: 12px;
            font-family: 'Courier New', monospace;
            font-size: 13px;
            line-height: 1.5;
            white-space: pre-wrap;
            word-break: break-all;
            max-height: 400px;
            overflow-y: auto;
            min-height: 100px;
        }
        .log-line-stderr { color: #f38ba8; }
        .log-line-stdout { color: #cdd6f4; }
        .status-badge {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 12px;
            font-size: 13px;
            font-weight: bold;
        }
        .status-idle { background: #e7e0eb; color: #6750a4; }
        .status-running { background: #ffb74d; color: white; }
        .status-success { background: #4caf50; color: white; }
        .status-failed { background: #f44336; color: white; }
    </style>
    <script>
        // Shizuku Next 模块桥接接口
        window.ShizukuNext = {
            moduleId: '$moduleId',
            moduleName: '$moduleName',
            _onOutput: function(text, type) {
                var logBox = document.getElementById('sn-log-box');
                if (logBox) {
                    var span = document.createElement('span');
                    span.className = 'log-line-' + (type || 'stdout');
                    span.textContent = text + '\n';
                    logBox.appendChild(span);
                    logBox.scrollTop = logBox.scrollHeight;
                }
            },
            _onComplete: function(code) {
                var logBox = document.getElementById('sn-log-box');
                if (logBox) {
                    var span = document.createElement('span');
                    span.className = 'log-line-stderr';
                    span.textContent = '\n[退出码: ' + code + ']\n';
                    logBox.appendChild(span);
                    logBox.scrollTop = logBox.scrollHeight;
                }
                var status = document.getElementById('sn-status');
                if (status) {
                    if (code === 0) {
                        status.className = 'status-badge status-success';
                        status.textContent = '执行成功';
                    } else {
                        status.className = 'status-badge status-failed';
                        status.textContent = '执行失败 (code=' + code + ')';
                    }
                }
                document.querySelectorAll('button').forEach(function(b) { b.disabled = false; });
            },
            _onError: function(msg) {
                var logBox = document.getElementById('sn-log-box');
                if (logBox) {
                    var span = document.createElement('span');
                    span.className = 'log-line-stderr';
                    span.textContent = '错误: ' + msg + '\n';
                    logBox.appendChild(span);
                    logBox.scrollTop = logBox.scrollHeight;
                }
                var status = document.getElementById('sn-status');
                if (status) {
                    status.className = 'status-badge status-failed';
                    status.textContent = '错误';
                }
                document.querySelectorAll('button').forEach(function(b) { b.disabled = false; });
            },
            // 执行 shell 命令，输出实时回传
            exec: function(cmd) {
                var logBox = document.getElementById('sn-log-box');
                if (logBox) {
                    var span = document.createElement('span');
                    span.style.color = '#89b4fa';
                    span.textContent = '$ ' + cmd + '\n';
                    logBox.appendChild(span);
                    logBox.scrollTop = logBox.scrollHeight;
                }
                document.querySelectorAll('button').forEach(function(b) { b.disabled = true; });
                var status = document.getElementById('sn-status');
                if (status) {
                    status.className = 'status-badge status-running';
                    status.textContent = '执行中...';
                }
                window.ShizukuNative.exec(cmd);
            },
            // 执行脚本文件
            execScript: function(path) {
                this.exec("sh '" + path + "'");
            },
            // 获取模块目录
            getModuleDir: function() {
                return window.ShizukuNative.getModuleDir();
            },
            // 清空日志
            clearLog: function() {
                var logBox = document.getElementById('sn-log-box');
                if (logBox) logBox.innerHTML = '';
            }
        };
    </script>
</head>
<body>
    $content
</body>
</html>
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        webView.destroy()
    }
}
