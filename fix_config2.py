# -*- coding: utf-8 -*-
import re

p = 'manager/src/main/java/moe/shizuku/manager/module/ModuleConfigActivity.kt'
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: Replace getAppList to NOT include icons, add getAppIcon method
# Find the old getAppList method and getAppIconBase64 method and replace both
old_block = '''        /**
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
                    sb.append("\\"pkg\\":\\"").append(escapeJson(app.packageName)).append("\\"")
                    val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
                    sb.append(",\\"name\\":\\"").append(escapeJson(label)).append("\\"")
                    sb.append(",\\"enabled\\":").append(app.enabled)
                    sb.append(",\\"icon\\":\\"").append(getAppIconBase64(pm, app.packageName)).append("\\"")
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
            } catch (ex: Exception) {
                return ""
            }
        }'''

new_block = '''        /**
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
                    sb.append("\\"pkg\\":\\"").append(escapeJson(app.packageName)).append("\\"")
                    val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
                    sb.append(",\\"name\\":\\"").append(escapeJson(label)).append("\\"")
                    sb.append(",\\"enabled\\":").append(app.enabled)
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
        }'''

if old_block in content:
    content = content.replace(old_block, new_block)
    print("Fix 1: getAppList replaced successfully")
else:
    print("Fix 1: FAILED - old block not found, trying regex...")
    # Try regex approach
    pattern = r'/\*\*\s*\n\s*\* Get installed app list.*?private fun getAppIconBase64.*?return ""\s*\n\s*\}\s*\n'
    match = re.search(pattern, content, re.DOTALL)
    if match:
        content = content[:match.start()] + new_block + content[match.end():]
        print("Fix 1: getAppList replaced via regex")
    else:
        print("Fix 1: FAILED - could not find block")

# Fix 2: Change chmod 700 to chmod 755 for xpad2 in prepareFiles
content = content.replace(
    'if (relPath.endsWith(".sh") || relPath == "xpad2" || !relPath.contains(".")) {\n                            runShell(service, "chmod 700 \'",
    'if (relPath.endsWith(".sh") || relPath == "xpad2" || !relPath.contains(".")) {\n                            runShell(service, "chmod 755 \''
)
print("Fix 2: chmod 700 -> 755 in prepareFiles")

# Fix 3: Add getAppIcon to the JS bridge in wrapHtml
old_bridge = 'getAppList:function(){return JSON.parse(window.ShizukuNative.getAppList());},'
new_bridge = 'getAppList:function(){return JSON.parse(window.ShizukuNative.getAppList());},\ngetAppIcon:function(pkg){return window.ShizukuNative.getAppIcon(pkg);},'
if old_bridge in content:
    content = content.replace(old_bridge, new_bridge)
    print("Fix 3: getAppIcon added to JS bridge")
else:
    # Check if it was already added
    if 'getAppIcon:function' in content:
        print("Fix 3: getAppIcon already in JS bridge")
    else:
        print("Fix 3: FAILED - bridge not found")

with open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("All fixes applied to ModuleConfigActivity.kt")
