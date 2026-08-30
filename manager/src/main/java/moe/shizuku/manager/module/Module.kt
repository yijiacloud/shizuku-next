package moe.shizuku.manager.module

import java.io.File

/**
 * 模块数据模型
 *
 * 参考 KernelSU 模块系统设计，每个模块包含以下属性：
 * - id:           模块唯一标识符
 * - name:         模块名称
 * - version:      模块版本号（显示用）
 * - versionCode:  模块版本代码（整数，用于比较）
 * - author:       模块作者
 * - description:  模块描述
 * - enabled:      是否启用
 * - installed:    是否已安装
 * - hasConfig:    是否有 HTML 配置界面
 * - hasInstallScript:  是否有安装脚本
 * - hasUninstallScript: 是否有卸载脚本
 */
data class Module(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val enabled: Boolean = true,
    val hasConfig: Boolean = false,
    val hasInstallScript: Boolean = false,
    val hasUninstallScript: Boolean = false
) {

    companion object {

        /**
         * 从 module.prop 文件内容解析模块属性
         * 格式为 key=value，每行一个属性
         */
        fun parseProp(content: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    map[key] = value
                }
            }
            return map
        }

        /**
         * 从 module.prop 的属性映射构建 Module 对象
         */
        fun fromPropMap(prop: Map<String, String>, moduleDir: File): Module {
            return Module(
                id = prop["id"] ?: "unknown",
                name = prop["name"] ?: "Unknown Module",
                version = prop["version"] ?: "v1.0",
                versionCode = prop["versionCode"]?.toIntOrNull() ?: 1,
                author = prop["author"] ?: "Unknown",
                description = prop["description"] ?: "",
                enabled = !moduleDir.resolve("disable").exists(),
                hasConfig = moduleDir.resolve("config.html").exists(),
                hasInstallScript = moduleDir.resolve("install.sh").exists(),
                hasUninstallScript = moduleDir.resolve("uninstall.sh").exists()
            )
        }
    }
}
