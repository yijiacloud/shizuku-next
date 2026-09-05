package moe.shizuku.manager.module

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 模块管理器
 *
 * 负责：
 * - 模块的安装（从 zip 文件解压到模块目录）
 * - 模块的列举（扫描模块目录）
 * - 模块的启用/禁用
 * - 模块的卸载
 * - 模块配置页面的读取
 * - 模块脚本的执行（通过 Shizuku 权限）
 *
 * 模块目录结构：
 * /data/data/moe.shizuku.privileged.api/files/modules/<module_id>/
 *     ├── module.prop          模块元数据（必需）
 *     ├── install.sh           安装脚本（可选，安装时执行）
 *     ├── uninstall.sh         卸载脚本（可选，卸载时执行）
 *     ├── action.sh            操作脚本（可选，手动触发执行）
 *     ├── config.html          配置页面（可选，HTML 格式）
 *     ├── disable              禁用标记文件（存在即表示禁用）
 *     └── ...                  其他模块文件
 */
object ModuleManager {

    private const val TAG = "ModuleManager"

    private const val MODULES_DIR = "modules"
    private const val MODULE_PROP = "module.prop"
    private const val DISABLE_FILE = "disable"
    private const val CONFIG_HTML = "config.html"
    private const val INSTALL_SH = "install.sh"
    private const val UNINSTALL_SH = "uninstall.sh"
    private const val ACTION_SH = "action.sh"

    /**
     * 获取模块根目录
     */
    fun getModulesDir(context: Context): File {
        val dir = File(context.filesDir, MODULES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取指定模块的目录
     */
    fun getModuleDir(context: Context, moduleId: String): File {
        return File(getModulesDir(context), moduleId)
    }

    /**
     * 列举所有已安装的模块
     */
    fun listModules(context: Context): List<Module> {
        val modules = mutableListOf<Module>()
        val modulesDir = getModulesDir(context)
        val dirs = modulesDir.listFiles { f -> f.isDirectory } ?: return emptyList()

        for (dir in dirs) {
            val propFile = File(dir, MODULE_PROP)
            if (!propFile.exists()) continue

            try {
                val content = propFile.readText()
                val prop = Module.parseProp(content)
                if (prop["id"] == null) continue
                val module = Module.fromPropMap(prop, dir)
                modules.add(module)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read module: ${dir.name}", e)
            }
        }

        return modules.sortedBy { it.name }
    }

    /**
     * 从 zip 文件安装模块
     *
     * zip 文件结构：
     * module.zip
     *   ├── module.prop
     *   ├── install.sh (可选)
     *   ├── uninstall.sh (可选)
     *   ├── action.sh (可选)
     *   ├── config.html (可选)
     *   └── ... 其他文件
     *
     * @return 安装结果，成功返回模块对象，失败返回 null 和错误信息
     */
    fun installModule(context: Context, zipFile: File): InstallResult {
        try {
            ZipFile(zipFile).use { zip ->
                // 读取 module.prop
                val propEntry = zip.getEntry(MODULE_PROP)
                    ?: return InstallResult(null, "zip 文件中缺少 module.prop")

                val propContent = zip.getInputStream(propEntry).bufferedReader().use { it.readText() }
                val prop = Module.parseProp(propContent)

                val moduleId = prop["id"]
                    ?: return InstallResult(null, "module.prop 中缺少 id 字段")

                // 验证 id 只包含字母、数字、下划线和连字符
                if (!moduleId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                    return InstallResult(null, "模块 id 只能包含字母、数字、下划线和连字符")
                }

                val moduleDir = getModuleDir(context, moduleId)
                val isUpdate = moduleDir.exists()

                // 如果是更新，先删除旧目录
                if (isUpdate) {
                    moduleDir.deleteRecursively()
                }
                moduleDir.mkdirs()

                // 解压所有文件到模块目录
                var hasInstallScript = false
                var hasUninstallScript = false
                var hasConfig = false

                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach

                    // 跳过 macOS 的隐藏目录
                    if (entry.name.startsWith("__MACOSX") || entry.name.contains("/__MACOSX/")) return@forEach
                    if (entry.name.contains("/.DS_Store")) return@forEach

                    val outFile = File(moduleDir, entry.name)

                    // 安全检查：防止路径穿越
                    if (!outFile.canonicalPath.startsWith(moduleDir.canonicalPath)) {
                        return@forEach
                    }

                    outFile.parentFile?.mkdirs()

                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // 设置脚本文件可执行权限
                    if (entry.name.endsWith(".sh")) {
                        outFile.setExecutable(true)
                        if (entry.name == INSTALL_SH) hasInstallScript = true
                        if (entry.name == UNINSTALL_SH) hasUninstallScript = true
                    }

                    if (entry.name == CONFIG_HTML) hasConfig = true
                }

                val module = Module.fromPropMap(prop, moduleDir).copy(
                    hasInstallScript = hasInstallScript,
                    hasUninstallScript = hasUninstallScript,
                    hasConfig = hasConfig
                )

                Log.i(TAG, "Module installed: ${module.id} v${module.version}")
                return InstallResult(module, if (isUpdate) "模块已更新" else "模块安装成功")

            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install module", e)
            return InstallResult(null, "安装失败：${e.message}")
        }
    }

    /**
     * 启用模块
     */
    fun enableModule(context: Context, moduleId: String): Boolean {
        val moduleDir = getModuleDir(context, moduleId)
        val disableFile = File(moduleDir, DISABLE_FILE)
        return disableFile.delete()
    }

    /**
     * 禁用模块
     */
    fun disableModule(context: Context, moduleId: String): Boolean {
        val moduleDir = getModuleDir(context, moduleId)
        val disableFile = File(moduleDir, DISABLE_FILE)
        return disableFile.createNewFile()
    }

    /**
     * 获取卸载脚本内容（不删除目录）
     */
    fun getUninstallScript(context: Context, moduleId: String): String? {
        val moduleDir = getModuleDir(context, moduleId)
        if (!moduleDir.exists()) return null
        val uninstallScript = File(moduleDir, UNINSTALL_SH)
        return if (uninstallScript.exists()) uninstallScript.readText() else null
    }

    /**
     * 删除模块目录
     */
    fun deleteModule(context: Context, moduleId: String): Boolean {
        val moduleDir = getModuleDir(context, moduleId)
        if (!moduleDir.exists()) return false
        moduleDir.deleteRecursively()
        Log.i(TAG, "Module deleted: $moduleId")
        return true
    }

    /**
     * 卸载模块（兼容旧代码：读取脚本并删除目录）
     */
    fun uninstallModule(context: Context, moduleId: String): String? {
        val script = getUninstallScript(context, moduleId)
        deleteModule(context, moduleId)
        return script
    }

    /**
     * 获取模块的配置页面 HTML 内容
     */
    fun getConfigHtml(context: Context, moduleId: String): String? {
        val moduleDir = getModuleDir(context, moduleId)
        val configFile = File(moduleDir, CONFIG_HTML)
        return if (configFile.exists()) configFile.readText() else null
    }

    /**
     * 获取模块的操作脚本内容
     */
    fun getActionScript(context: Context, moduleId: String): String? {
        val moduleDir = getModuleDir(context, moduleId)
        val actionFile = File(moduleDir, ACTION_SH)
        return if (actionFile.exists()) actionFile.readText() else null
    }

    /**
     * 获取模块的安装脚本内容
     */
    fun getInstallScript(context: Context, moduleId: String): String? {
        val moduleDir = getModuleDir(context, moduleId)
        val installFile = File(moduleDir, INSTALL_SH)
        return if (installFile.exists()) installFile.readText() else null
    }

    /**
     * 获取模块目录的完整路径（用于脚本中引用模块文件）
     */
    fun getModuleDirPath(context: Context, moduleId: String): String {
        return getModuleDir(context, moduleId).absolutePath
    }

    /**
     * 安装结果
     */
    data class InstallResult(
        val module: Module?,
        val message: String,
        val installScript: String? = null
    )
}
