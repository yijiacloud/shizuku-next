# Shizuku Next 模块开发文档

## 1. 概述

Shizuku Next 模块系统参考 KernelSU (KSU) 的模块设计，允许开发者创建扩展模块，通过 Shizuku 获得的特权执行 shell 命令和系统操作。

模块以 zip 格式分发，安装后解压到应用私有目录中管理。

### 特性

- 从 zip 文件安装模块
- 启用/禁用模块
- 卸载模块（支持卸载脚本）
- HTML 配置界面（可选）
- 操作脚本执行（可选）
- 安装脚本自动执行（可选）
- 支持 uid=2000（shell）或 root 权限执行

### 与 KSU 模块的区别

| 特性 | KSU 模块 | Shizuku Next 模块 |
|------|----------|-------------------|
| 权限级别 | root | shell (uid=2000) 或 root |
| 安装方式 | Flash zip | 应用内导入 zip |
| 文件位置 | /data/adb/modules | 应用私有目录 |
| 配置界面 | WebUI | HTML (WebView) |
| 执行环境 | root shell | Shizuku shell |

---

## 2. 模块结构

模块以 zip 文件分发，zip 内部结构如下：

```
module.zip
├── module.prop          # 【必需】模块元数据
├── install.sh           # 【可选】安装脚本
├── uninstall.sh         # 【可选】卸载脚本
├── action.sh            # 【可选】操作脚本（手动触发）
├── config.html          # 【可选】HTML 配置界面
└── ...                  # 其他模块文件
```

### 2.1 module.prop（必需）

模块属性文件，格式为 `key=value`，每行一个属性。

```properties
# 模块唯一标识符，只能包含字母、数字、下划线和连字符
id=example_module

# 模块显示名称
name=示例模块

# 模块版本号（显示用）
version=v1.0.0

# 模块版本代码（整数，用于版本比较）
versionCode=1

# 模块作者
author=你的名字

# 模块描述
description=模块的功能描述
```

**注意：**
- `id` 必须唯一，重复的 id 会覆盖旧版本
- `id` 只能包含 `[a-zA-Z0-9_-]`
- `versionCode` 必须是整数

### 2.2 install.sh（可选）

模块安装时自动执行的脚本。

```bash
#!/system/bin/sh
# MODDIR 变量包含模块目录的绝对路径

echo "正在安装示例模块..."
mkdir -p /data/local/tmp/my_module
echo "安装完成"
```

**执行环境：**
- 通过 Shizuku 获得的 shell 权限
- uid=2000（shell 用户）或 uid=0（root，取决于 Shizuku 启动方式）
- 当前工作目录为模块目录

### 2.3 uninstall.sh（可选）

模块卸载时执行的脚本。

```bash
#!/system/bin/sh
echo "正在清理..."
rm -rf /data/local/tmp/my_module
echo "卸载完成"
```

### 2.4 action.sh（可选）

用户在模块列表中点击「执行」按钮时运行的脚本。适合需要手动触发的操作。

```bash
#!/system/bin/sh
echo "执行系统信息查询..."
getprop ro.build.version.release
dumpsys battery
```

### 2.5 config.html（可选）

模块的 HTML 配置界面，使用 WebView 渲染。

```html
<h2>模块配置</h2>
<p>这里可以放置配置表单和交互按钮。</p>
<button onclick="execCmd()">执行命令</button>
<script>
    // ShizukuNext JS 桥接对象
    // window.ShizukuNext.moduleId    - 模块 ID
    // window.ShizukuNext.moduleName  - 模块名称
    // window.ShizukuNext.exec(cmd)   - 执行 shell 命令
    // window.ShizukuNext.getModuleDir() - 获取模块目录路径
    function execCmd() {
        window.ShizukuNext.exec("getprop ro.build.version.release");
    }
</script>
```

**HTML 配置页面特性：**
- 自动注入 Material Design 3 风格的基础样式
- 自动注入 `window.ShizukuNext` JavaScript 桥接对象
- 支持 JavaScript 交互
- 支持 LocalStorage 持久化配置

---

## 3. 模块规章

### 3.1 命名规范

- 模块 `id` 必须唯一，建议使用 `com.author.module_name` 或 `author_module_name` 格式
- `id` 只能包含 `[a-zA-Z0-9_-]`
- 模块名称应简洁明了，不超过 30 个字符

### 3.2 安全规范

1. **不得包含恶意代码**：模块不得包含任何破坏系统稳定性、窃取用户数据的代码
2. **最小权限原则**：只请求必要的操作，不滥用 shell 权限
3. **透明性**：模块描述应清楚说明模块的功能和影响
4. **卸载清理**：如果安装脚本创建了文件/目录，卸载脚本应负责清理
5. **路径安全**：脚本中不应硬编码其他应用的路径

### 3.3 兼容性规范

1. 模块应兼容 Android 11 (API 30) 及以上版本
2. 脚本使用 `#!/system/bin/sh` 作为解释器
3. 不依赖 `busybox`，使用 Android 自带的 `toybox` 命令
4. 考虑不同设备的差异，脚本应有错误处理

### 3.4 分发规范

1. 模块以 zip 格式分发
2. zip 文件根目录应直接包含 `module.prop`
3. 不应包含 `__MACOSX` 或 `.DS_Store` 等无关文件
4. 建议在 zip 文件名中包含模块名和版本号，如 `example_module_v1.0.0.zip`

---

## 4. 安装流程

1. 用户在 Shizuku Next 主页点击「模块」卡片
2. 进入模块管理页面
3. 点击右下角「+」按钮
4. 选择模块 zip 文件
5. 系统自动解压并安装
6. 如果有 `install.sh`，自动执行安装脚本
7. 安装完成后，模块出现在列表中

---

## 5. 模块管理

### 启用/禁用

- 每个模块右侧有开关，切换启用/禁用状态
- 禁用的模块不会执行任何脚本
- 禁用状态通过模块目录下的 `disable` 标记文件实现

### 卸载

- 点击模块卡片中的「卸载」按钮
- 确认后，如果有 `uninstall.sh` 则先执行卸载脚本
- 然后删除整个模块目录

### 配置

- 如果模块包含 `config.html`，会显示「配置」按钮
- 点击后在 WebView 中显示配置页面

### 执行操作

- 如果模块包含 `action.sh`，会显示「执行」按钮
- 点击后在终端中执行操作脚本，显示实时输出

---

## 6. JS 桥接接口

在 `config.html` 中，可以通过 `window.ShizukuNext` 对象与 Shizuku Next 交互：

| 方法 | 说明 |
|------|------|
| `ShizukuNext.moduleId` | 当前模块的 ID |
| `ShizukuNext.moduleName` | 当前模块的名称 |
| `ShizukuNext.exec(cmd)` | 执行 shell 命令 |
| `ShizukuNext.getModuleDir()` | 获取模块目录路径 |

---

## 7. 完整示例

参见项目中的 `sample_module/` 目录和 `sample_module.zip`。

### 示例模块文件清单

```
sample_module/
├── module.prop       # 模块属性
├── install.sh        # 安装脚本（创建测试目录，显示系统信息）
├── uninstall.sh      # 卸载脚本（清理测试目录）
├── action.sh         # 操作脚本（显示电池、内存、存储信息）
└── config.html       # 配置页面（系统信息查询、自定义命令执行）
```

---

## 8. 开发建议

1. **先在终端测试脚本**：开发脚本时，先用 Shizuku Next 的终端功能测试命令
2. **使用 echo 输出状态**：脚本中多用 `echo` 输出执行状态，方便调试
3. **错误处理**：使用 `||` 和 `if` 进行错误处理
4. **模块目录引用**：使用 `MODDIR` 变量引用模块目录，不要硬编码路径
5. **HTML 配置简洁**：配置页面应简洁美观，自动注入了 Material Design 3 基础样式
6. **版本管理**：每次更新模块时递增 `versionCode`

---

## 9. FAQ

### Q: 模块安装后脚本没有执行？
A: install.sh 在安装时自动执行。请确保 Shizuku 服务正在运行，且脚本有可执行权限。

### Q: 模块可以在没有 root 的情况下使用吗？
A: 可以。Shizuku Next 模块通过 Shizuku 获得的 shell 权限（uid=2000）执行，不需要 root。但某些需要 root 权限的操作可能无法执行。

### Q: 模块的文件存储在哪里？
A: 模块文件存储在 `/data/data/moe.shizuku.privileged.api/files/modules/<module_id>/` 目录下。

### Q: 如何更新模块？
A: 重新安装同 `id` 的 zip 文件即可更新。旧版本会被自动替换。

### Q: 模块可以访问网络吗？
A: 脚本执行时可以使用 `curl`、`wget` 等命令访问网络（如果系统支持）。HTML 配置页面中的 WebView 也可以访问网络。

---

## 10. 模块规范总结（规章）

1. 模块必须包含 `module.prop` 文件，且 `id` 字段必填
2. 模块 `id` 只能包含 `[a-zA-Z0-9_-]`
3. 模块脚本使用 `#!/system/bin/sh` 解释器
4. 模块不得包含恶意代码或破坏系统稳定性的操作
5. 模块应在 `uninstall.sh` 中清理 `install.sh` 创建的文件
6. 模块描述应清楚说明功能和影响
7. 模块以 zip 格式分发，根目录直接包含 `module.prop`
8. 模块兼容 Android 11 (API 30) 及以上
9. HTML 配置页面通过 `window.ShizukuNext` 桥接对象与系统交互
10. 模块可以静默运行（只有脚本），也可以有 HTML 配置界面
