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
         * Prepare module files in a shell-accessible temp directory.
         * Copies all module files to /data/local/tmp/sn_mod_<id>/ and returns the path.
         * Call this before executing scripts that reference module files.
         */
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
                            if (relPath.endsWith(".sh") || relPath == "xpad2" || !relPath.contains(".")) {
                                runShell(service, "chmod 700 '" + destPath + "'")
                            }
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
                val mime = "*" + "/" + "*"
                pickFileLauncher?.launch(mime)
            }
        }

        @JavascriptInterface
        fun pickFileWithMime(mime: String) {
            handler.post {
                pickFileLauncher?.launch(mime)
            }
        }

        /**
         * Get installed app list as JSON string.
         * Returns: [{"pkg":"com.example","name":"Example","enabled":true,"icon":"data:image/png;base64,..."}, ...]
         */
        @JavascriptInterface
        fun getAppList(): String {
            try {
                val pm = activity.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
                    sb.append(",\"icon\":\"").append(getAppIconBase64(pm, app.packageName)).append("\"")
                    sb.append("}")
                }
                sb.append("]")
                return sb.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAppList error", e)
                return "[]"
            }
        }

        private fun getAppIconBase64(pm: PackageManager, pkg: String): String {
            try {
                val drawable = pm.getApplicationIcon(pkg)
                val size = 72
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 50, baos)
                bitmap.recycle()
                return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                return ""
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
                                val text = line!!.replace("\\", "\\\\").replace("'", "\'").replace("\n", "\\n")
                                handler.post {
                                    webView.evaluateJavascript(
                                        "if(window.ShizukuNext&&window.ShizukuNext._onOutput){window.ShizukuNext._onOutput('" + text + "','stdout');}",
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
                                runShell(service, "chmod 700 '" + destPath + "'")
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
        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
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
</style>
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
