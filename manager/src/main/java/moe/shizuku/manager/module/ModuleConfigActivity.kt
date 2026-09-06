package moe.shizuku.manager.module

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import rikka.core.res.isNight
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

class ModuleConfigActivity : AppBarActivity() {

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_MODULE_NAME = "module_name"
        const val EXTRA_HTML_CONTENT = "html_content"
        private const val TAG = "ModuleConfigActivity"
    }

    private lateinit var webView: WebView
    private var pickFileLauncher: ActivityResultLauncher<String>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newCachedThreadPool()
    private var moduleId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleName = intent.getStringExtra(EXTRA_MODULE_NAME) ?: "模块配置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = moduleName

        moduleId = intent.getStringExtra(EXTRA_MODULE_ID) ?: ""

        pickFileLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    val input = contentResolver.openInputStream(uri)
                    if (input != null) {
                        var fileName: String? = null
                        try {
                            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0 && cursor.moveToFirst()) {
                                    fileName = cursor.getString(nameIndex)
                                }
                            }
                        } catch (e: Exception) {
                        }
                        if (fileName.isNullOrBlank()) {
                            fileName = "picked_file_" + System.currentTimeMillis()
                        }
                        val tempFile = File(cacheDir, fileName!!)
                        input.use { ins ->
                            FileOutputStream(tempFile).use { out ->
                                ins.copyTo(out)
                            }
                        }
                        val path = tempFile.absolutePath
                        handler.post {
                            webView.evaluateJavascript(
                                "if(window.ShizukuNext&&window.ShizukuNext._onFilePicked){window.ShizukuNext._onFilePicked('" + path.replace("'", "\'") + "');}",
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    handler.post {
                        webView.evaluateJavascript(
                            "if(window.ShizukuNext&&window.ShizukuNext._onError){window.ShizukuNext._onError('" + (e.message ?: "").replace("'", "") + "');}",
                            null
                        )
                    }
                }
            } else {
                handler.post {
                    webView.evaluateJavascript(
                        "if(window.ShizukuNext&&window.ShizukuNext._onFilePickCanceled){window.ShizukuNext._onFilePickCanceled();}",
                        null
                    )
                }
            }
        }

        webView = WebView(this)

        val params = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        params.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        setContentView(webView, params)

        val htmlContent = intent.getStringExtra(EXTRA_HTML_CONTENT) ?: ""

        webView.addJavascriptInterface(ModuleJsBridge(this, moduleId), "ShizukuNative")

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

    inner class ModuleJsBridge(private val activity: ModuleConfigActivity, private val modId: String) {

        @JavascriptInterface
        fun getModuleDir(): String {
            return "/data/data/moe.shizuku.privileged.api/files/modules/" + modId
        }

                /**
         * Get installed app list as JSON string (without icons for performance).
         * Returns: [{"pkg":"com.example","name":"Example","enabled":true}, ...]
         * Use getAppIcon(pkg) to get individual icons lazily.
         */
        @JavascriptInterface
        fun getAppList(): String {
            try {
                val pm = activity.packageManager
                val apps = pm.getInstalledApplications(0)
                val sb = StringBuilder()
                sb.append("[")
                var first = true
                for (app in apps) {
                    if (!first) sb.append(",")
                    first = false
                    sb.append("{")
                    sb.append("\"pkg\":\"").append(escapeJson(app.packageName)).append("\"")
                    val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
                    sb.append(",\"name\":\"").append(escapeJson(label)).append("\"")
                    sb.append(",\"enabled\":").append(app.enabled)
                    sb.append("}")
                }
                sb.append("]")
                return sb.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAppList error", e)
                return "[]"
            }
        }

        /**
         * Get a single app icon as base64 data URI (lazy loading).
         * Returns: "data:image/png;base64,..." or "" if failed.
         */
        @JavascriptInterface
        fun getAppIcon(pkg: String): String {
            try {
                val pm = activity.packageManager
                val drawable = pm.getApplicationIcon(pkg)
                val size = 48
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 70, baos)
                bitmap.recycle()
                return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (ex: Exception) {
                return ""
            }
        }

                private var cwd: String = "/"

        @JavascriptInterface
        fun prepareFiles(): String {
            try {
                if (!Shizuku.pingBinder()) return ""
                val binder = Shizuku.getBinder() ?: return ""
                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)

                val tempDir = "/data/local/tmp/sn_mod_" + modId
                runShell(service, "mkdir -p '" + tempDir + "'")

                val moduleDir = File(activity.filesDir, "modules/" + modId)
                if (moduleDir.exists()) {
                    moduleDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relPath = file.relativeTo(moduleDir).path
                            val destPath = tempDir + "/" + relPath
                            val parentDir = destPath.substring(0, destPath.lastIndexOf('/'))
                            runShell(service, "mkdir -p '" + parentDir + "'")
                            copyFileViaStdin(service, destPath, file)
                            runShell(service, "chmod 755 '" + destPath + "'")
                        }
                    }
                }
                return tempDir
            } catch (e: Exception) {
                Log.e(TAG, "prepareFiles error", e)
                return ""
            }
        }

        @JavascriptInterface
        fun pickFile() {
            handler.post {
                activity.pickFileLauncher?.launch("*/*")
            }
        }

        @JavascriptInterface
        fun pickFileWithMime(mime: String) {
            handler.post {
                activity.pickFileLauncher?.launch(mime)
            }
        }

        private fun escapeJson(s: String): String {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        }

        @JavascriptInterface
        fun exec(cmd: String) {
            Log.i(TAG, "exec: " + cmd)
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
                    val wrappedCmd = "cd \"" + cwd + "\" 2>/dev/null; " + cmd + "; printf \"\\n__SN_CWD__:%s\" \"$(pwd)\""
                    val parts = arrayOf("sh", "-c", wrappedCmd)
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
                                val rawLine = line!!
                                if (rawLine.startsWith("__SN_CWD__:")) {
                                    cwd = rawLine.substring("__SN_CWD__:".length)
                                } else {
                                    val text = line!!.replace("\\", "\\\\").replace("'", "\'").replace("\n", "\\n")
                                    handler.post {
                                    webView.evaluateJavascript(
                                    "if(window.ShizukuNext&&window.ShizukuNext._onOutput){window.ShizukuNext._onOutput('" + text + "','stdout');}",
                                    null
                                    )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "stdout read error", e)
                        } finally {
                            try { stdout.close() } catch (_: Exception) {}
                        }
                    }

                    val stderrThread = Thread {
                        try {
                            var line: String?
                            while (stderr.readLine().also { line = it } != null) {
                                val text = line!!.replace("\\", "\\\\").replace("'", "\'").replace("\n", "\\n")
                                handler.post {
                                    webView.evaluateJavascript(
                                        "if(window.ShizukuNext&&window.ShizukuNext._onOutput){window.ShizukuNext._onOutput('" + text + "','stderr');}",
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
                            "if(window.ShizukuNext&&window.ShizukuNext._onComplete){window.ShizukuNext._onComplete(" + exitCode + ");}",
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "exec error", e)
                    handler.post {
                        webView.evaluateJavascript(
                            "if(window.ShizukuNext&&window.ShizukuNext._onError){window.ShizukuNext._onError('" + (e.message ?: "").replace("'", "\'") + "');}",
                            null
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun execScript(scriptPath: String) {
            // Copy script to temp dir first, then execute
            try {
                if (!Shizuku.pingBinder()) return
                val binder = Shizuku.getBinder() ?: return
                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)

                val tempDir = "/data/local/tmp/sn_mod_" + modId
                runShell(service, "mkdir -p '" + tempDir + "'")

                // Copy all module files
                val moduleDir = File(activity.filesDir, "modules/" + modId)
                if (moduleDir.exists()) {
                    moduleDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relPath = file.relativeTo(moduleDir).path
                            val destPath = tempDir + "/" + relPath
                            val parentDir = destPath.substring(0, destPath.lastIndexOf('/'))
                            runShell(service, "mkdir -p '" + parentDir + "'")
                            copyFileViaStdin(service, destPath, file)
                            if (relPath.endsWith(".sh") || !relPath.contains(".")) {
                                runShell(service, "chmod 755 '" + destPath + "'")
                            }
                        }
                    }
                }

                exec("sh '" + tempDir + "/" + scriptPath + "'")
            } catch (e: Exception) {
                Log.e(TAG, "execScript error", e)
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

        private fun copyFileViaStdin(service: moe.shizuku.server.IShizukuService, destPath: String, file: java.io.File) {
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
                Log.e(TAG, "copyFile error: " + destPath, e)
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
                Log.e(TAG, "copyBytes error: " + destPath, e)
            }
        }
    }

        private fun wrapHtml(content: String, moduleName: String, modId: String): String {
        val isMD3 = moe.shizuku.manager.app.ThemeHelper.isMD3Theme(this)
        val isDark = resources.configuration.isNight()

        val css = if (isMD3) {
            val bg = if (isDark) "#1C1B1F" else "#FFFFFF"
            val surface = if (isDark) "#28272D" else "#F0F1F3"
            val surfaceLow = if (isDark) "#242329" else "#F8F9FA"
            val surfaceHigh = if (isDark) "#2E2C33" else "#E8E9EB"
            val primary = if (isDark) "#B0C7FF" else "#5C6BC0"
            val onPrimary = if (isDark) "#1A237E" else "#FFFFFF"
            val primaryContainer = if (isDark) "#3D4A8F" else "#E8EAF6"
            val onPrimaryContainer = if (isDark) "#E8EAF6" else "#1A237E"
            val onBg = if (isDark) "#E6E1E5" else "#1C1B1F"
            val onSurfaceVariant = if (isDark) "#CAC4D0" else "#49454F"
            val outline = if (isDark) "#938F99" else "#79747E"
            val outlineVariant = if (isDark) "#49454F" else "#CAC4D0"
            val error = if (isDark) "#F2B8B5" else "#B3261E"
            val errorContainer = if (isDark) "#8C1D18" else "#F9DEDC"
            val textColor = if (isDark) "#E6E1E5" else "#1C1B1F"
            val secondaryContainer = if (isDark) "#4A4458" else "#E8DEF8"
            val onSecondaryContainer = if (isDark) "#E8DEF8" else "#1D192B"
            """* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Roboto', 'PingFang SC', 'Microsoft YaHei', sans-serif; padding: 16px; background: $bg; color: $textColor; -webkit-font-smoothing: antialiased; transition: background-color 0.3s; }
h1,h2,h3 { margin-bottom: 12px; font-weight: 600; }
h1 { font-size: 1.5rem; } h2 { font-size: 1.25rem; } h3 { font-size: 1.1rem; }
p { margin-bottom: 12px; line-height: 1.6; color: $onSurfaceVariant; }
button { padding: 10px 24px; border: none; border-radius: 20px; background: $primary; color: $onPrimary; font-size: 14px; font-weight: 500; cursor: pointer; margin: 4px 0; transition: all 0.2s cubic-bezier(0.4,0,0.2,1); box-shadow: 0 1px 2px rgba(0,0,0,0.1); }
button:active { transform: scale(0.95); border-radius: 14px; }
button:disabled { opacity: 0.38; cursor: not-allowed; box-shadow: none; }
button.secondary { background: $secondaryContainer; color: $onSecondaryContainer; box-shadow: none; }
button.outlined { background: transparent; color: $primary; border: 1px solid $outline; box-shadow: none; }
button.outlined:active { background: $primaryContainer; }
input,select,textarea { padding: 12px 16px; border: 1px solid $outlineVariant; border-radius: 12px; font-size: 14px; width: 100%; margin-bottom: 12px; background: $surfaceLow; color: $textColor; transition: border-color 0.2s; }
input:focus,select:focus,textarea:focus { border-color: $primary; outline: none; }
.card { background: $surface; border-radius: 16px; padding: 16px; margin-bottom: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
.log-box { background: $surfaceHigh; color: $textColor; border-radius: 12px; padding: 12px; font-family: 'Courier New', monospace; font-size: 13px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; max-height: 400px; overflow-y: auto; min-height: 100px; }
.log-line-stderr { color: $error; }
.log-line-stdout { color: $textColor; }
.status-badge { display: inline-block; padding: 4px 12px; border-radius: 12px; font-size: 13px; font-weight: bold; }
.status-idle { background: $secondaryContainer; color: $onSecondaryContainer; }
.status-running { background: #FFB74D; color: white; }
.status-success { background: #4CAF50; color: white; }
.status-failed { background: $errorContainer; color: $error; }
.divider { height: 1px; background: $outlineVariant; margin: 16px 0; border: none; }
.app-item { display: flex; align-items: center; gap: 12px; padding: 12px; background: $surfaceLow; border-radius: 12px; margin-bottom: 8px; transition: background 0.2s; }
.app-item:active { background: $surface; }
.app-icon { width: 40px; height: 40px; border-radius: 8px; flex-shrink: 0; }
.app-name { font-weight: 500; font-size: 14px; color: $textColor; }
.app-pkg { font-size: 12px; color: $onSurfaceVariant; }
"""
        } else {
            """* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, "Segoe UI", Roboto, sans-serif; padding: 16px; background: #fef7ff; color: #1d1b20; }
h1,h2,h3 { margin-bottom: 12px; }
p { margin-bottom: 12px; line-height: 1.6; }
button { padding: 10px 24px; border: none; border-radius: 20px; background: #6750a4; color: white; font-size: 14px; cursor: pointer; margin: 4px; }
button:active { opacity: 0.8; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
button.secondary { background: #e7e0eb; color: #6750a4; }
input,select,textarea { padding: 10px; border: 1px solid #cac4d0; border-radius: 8px; font-size: 14px; width: 100%; margin-bottom: 12px; }
.card { background: #f3edf7; border-radius: 16px; padding: 16px; margin-bottom: 16px; }
.log-box { background: #1d1b20; color: #cdd6f4; border-radius: 12px; padding: 12px; font-family: "Courier New", monospace; font-size: 13px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; max-height: 400px; overflow-y: auto; min-height: 100px; }
.log-line-stderr { color: #f38ba8; }
.log-line-stdout { color: #cdd6f4; }
.status-badge { display: inline-block; padding: 4px 12px; border-radius: 12px; font-size: 13px; font-weight: bold; }
.status-idle { background: #e7e0eb; color: #6750a4; }
.status-running { background: #ffb74d; color: white; }
.status-success { background: #4caf50; color: white; }
.status-failed { background: #f44336; color: white; }
"""
        }

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>$css</style>
<script>
window.ShizukuNext={
moduleId:'$modId',moduleName:'$moduleName',
getAppList:function(){return JSON.parse(window.ShizukuNative.getAppList());},
getAppIcon:function(pkg){return window.ShizukuNative.getAppIcon(pkg);},
prepareFiles:function(){return window.ShizukuNative.prepareFiles();},
_onOutput:function(t,ty){if(this.onOutput)this.onOutput(t,ty);},
_onComplete:function(c){if(this.onComplete)this.onComplete(c);},
_onError:function(m){if(this.onError)this.onError(m);},
_onFilePicked:function(p){if(this.onFilePicked)this.onFilePicked(p);},
_onFilePickCanceled:function(){if(this.onFilePickCanceled)this.onFilePickCanceled();},
exec:function(cmd){if(this.onExec)this.onExec(cmd);window.ShizukuNative.exec(cmd);},
execScript:function(p){window.ShizukuNative.execScript(p);},
getModuleDir:function(){return window.ShizukuNative.getModuleDir();},
pickFile:function(){window.ShizukuNative.pickFile();},
pickFileWithMime:function(m){window.ShizukuNative.pickFileWithMime(m);},
clearLog:function(){if(this.onClearLog)this.onClearLog();},
onOutput:null,onComplete:null,onError:null,onFilePicked:null,onFilePickCanceled:null,onExec:null,onClearLog:null
};
</script>
</head><body>
$content
</body></html>
"""
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        webView.destroy()
    }
}
