package moe.shizuku.manager.module

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityModuleListBinding
import moe.shizuku.manager.databinding.ItemModuleBinding
import rikka.recyclerview.BaseViewHolder
import java.io.File
import java.io.FileOutputStream
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout

/**
 * 模块列表页面
 *
 * 功能：
 * - 显示所有已安装的模块
 * - 从 zip 文件安装新模块
 * - 启用/禁用模块
 * - 卸载模块
 * - 查看模块配置页面（如果有）
 * - 执行模块操作脚本
 */
class ModuleListActivity : AppBarActivity() {

    private lateinit var binding: ActivityModuleListBinding
    private lateinit var adapter: ModuleListAdapter
    private lateinit var pickZipLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityModuleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.module_list_title)

        // 设置 RecyclerView
        adapter = ModuleListAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 文件选择器
        pickZipLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                installFromUri(uri)
            }
        }

        // 安装按钮
        binding.fab.setOnClickListener {
            pickZipLauncher.launch("application/zip")
        }

        // 处理导航栏 inset，防止 FAB 被 3 按钮导航栏遮挡
        val density = resources.displayMetrics.density
        val baseMargin = (16 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.fab) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val params = view.layoutParams as CoordinatorLayout.LayoutParams
            params.bottomMargin = baseMargin + navBars.bottom
            params.rightMargin = baseMargin + navBars.right
            view.layoutParams = params
            insets
        }

        loadModules()
    }

    override fun onResume() {
        super.onResume()
        loadModules()
    }

    private fun loadModules() {
        val modules = ModuleManager.listModules(this)
        adapter.update(modules)

        if (modules.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun installFromUri(uri: Uri) {
        try {
            // 将 URI 内容复制到临时文件
            val tempFile = File.createTempFile("module_install", ".zip", cacheDir)
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, R.string.module_install_failed, Toast.LENGTH_SHORT).show()
                return
            }
            input.use { ins ->
                FileOutputStream(tempFile).use { output ->
                    ins.copyTo(output)
                }
            }

            val result = ModuleManager.installModule(this, tempFile)
            tempFile.delete()

            // 如果有安装脚本，启动日志页面执行
            if (!result.installScript.isNullOrEmpty() && result.module != null) {
                val moduleDir = ModuleManager.getModuleDirPath(this, result.module.id)
                val intent = Intent(this, ModuleLogActivity::class.java).apply {
                    putExtra(ModuleLogActivity.EXTRA_SCRIPT_CONTENT, result.installScript)
                    putExtra(ModuleLogActivity.EXTRA_MODULE_DIR, moduleDir)
                    putExtra(ModuleLogActivity.EXTRA_MODULE_ID, result.module.id)
                    putExtra(ModuleLogActivity.EXTRA_MODE, ModuleLogActivity.MODE_INSTALL)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
            loadModules()

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.module_install_failed) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 模块列表适配器
     */
    private inner class ModuleListAdapter : RecyclerView.Adapter<ModuleViewHolder>() {

        private val modules = mutableListOf<Module>()

        fun update(newModules: List<Module>) {
            modules.clear()
            modules.addAll(newModules)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder {
            val binding = ItemModuleBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ModuleViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
            holder.bind(modules[position])
        }

        override fun getItemCount(): Int = modules.size
    }

    /**
     * 模块项 ViewHolder
     */
    private inner class ModuleViewHolder(
        private val binding: ItemModuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(module: Module) {
            binding.moduleName.text = module.name
            binding.moduleVersion.text = module.version
            binding.moduleAuthor.text = module.author
            binding.moduleDescription.text = module.description

            // 启用/禁用开关
            binding.moduleSwitch.isChecked = module.enabled
            binding.moduleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    ModuleManager.enableModule(this@ModuleListActivity, module.id)
                } else {
                    ModuleManager.disableModule(this@ModuleListActivity, module.id)
                }
            }

            // 配置按钮
            binding.btnConfig.visibility = if (module.hasConfig) View.VISIBLE else View.GONE
            binding.btnConfig.setOnClickListener {
                val html = ModuleManager.getConfigHtml(this@ModuleListActivity, module.id)
                if (html != null) {
                    val intent = Intent(this@ModuleListActivity, ModuleConfigActivity::class.java).apply {
                        putExtra(ModuleConfigActivity.EXTRA_MODULE_ID, module.id)
                        putExtra(ModuleConfigActivity.EXTRA_MODULE_NAME, module.name)
                        putExtra(ModuleConfigActivity.EXTRA_HTML_CONTENT, html)
                    }
                    startActivity(intent)
                }
            }

            // 执行操作脚本按钮
            binding.btnAction.visibility = View.GONE
            binding.btnAction.setOnClickListener {
                val script = ModuleManager.getActionScript(this@ModuleListActivity, module.id)
                if (script != null) {
                    // 跳转到终端执行
                    val intent = Intent(this@ModuleListActivity, moe.shizuku.manager.shell.ShellTerminalActivity::class.java).apply {
                        putExtra("script_content", script)
                        putExtra("module_dir", ModuleManager.getModuleDirPath(this@ModuleListActivity, module.id))
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this@ModuleListActivity, R.string.module_no_action_script, Toast.LENGTH_SHORT).show()
                }
            }

            // 卸载按钮
            binding.btnUninstall.setOnClickListener {
                MaterialAlertDialogBuilder(this@ModuleListActivity)
                    .setTitle(R.string.module_uninstall_title)
                    .setMessage(getString(R.string.module_uninstall_confirm, module.name))
                    .setPositiveButton(R.string.module_uninstall) { _, _ ->
                        val script = ModuleManager.getUninstallScript(this@ModuleListActivity, module.id)
                        val moduleDir = ModuleManager.getModuleDirPath(this@ModuleListActivity, module.id)
                        if (!script.isNullOrEmpty()) {
                            // 有卸载脚本，启动日志页面执行
                            val intent = Intent(this@ModuleListActivity, ModuleLogActivity::class.java).apply {
                                putExtra(ModuleLogActivity.EXTRA_SCRIPT_CONTENT, script)
                                putExtra(ModuleLogActivity.EXTRA_MODULE_DIR, moduleDir)
                                putExtra(ModuleLogActivity.EXTRA_MODULE_ID, module.id)
                                putExtra(ModuleLogActivity.EXTRA_MODE, ModuleLogActivity.MODE_UNINSTALL)
                            }
                            startActivity(intent)
                        } else {
                            // 没有卸载脚本，直接删除
                            ModuleManager.deleteModule(this@ModuleListActivity, module.id)
                            loadModules()
                            Toast.makeText(this@ModuleListActivity, R.string.module_uninstalled, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
