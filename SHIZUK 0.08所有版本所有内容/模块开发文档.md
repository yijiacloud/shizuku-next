# Shizuku Next 模块开发文档

> **版本**: v0.08 | **更新日期**: 2026-09-06

## 1. 概述

Shizuku Next 模块系统参考 KernelSU (KSU) 的模块设计，允许开发者创建扩展模块，通过 Shizuku 获得的特权执行 shell 命令和系统操作。

模块以 zip 格式分发，安装后解压到应用私有目录中管理。

### 核心特性

- ✅ 从 zip 文件安装模块（支持第三方文件管理器选择）
- ✅ 启用/禁用模块
- ✅ 卸载模块（支持卸载脚本）
- ✅ HTML 配置界面（WebUI，Material Design 3 风格）
- ✅ 操作脚本执行（流式实时输出）
- ✅ 安装/卸载脚本自动执行（带日志输出）
- ✅ 文件选择器（支持 MT 管理器等第三方文件管理器）
- ✅ 应用列表查询与应用图标懒加载
- ✅ 支持 uid=2000（shell）或 root 权限执行
- ✅ 流式终端输出（支持长时间运行的脚本）

### 与 KSU 模块的区别

| 特性 | KSU 模块 | Shizuku Next 模块 |
|------|----------|-------------------|
| 权限级别 | root | shell (uid=2000) 或 root |
| 安装方式 | Flash zip | 应用内导入 zip |
| 文件位置 | /data/adb/modules | 应用私有目录 |
| 配置界面 | WebUI | HTML (WebView) |
| 执行环境 | root shell | Shizuku shell |
| 文件选择 | 有限 | 支持第三方文件管理器 |
| 应用信息 | 无 | 内置应用列表/图标接口 |

---

## 2. 模块结构

模块以 zip 文件分发，zip 内部结构如下：

```
module.zip
├── module.prop          # 【必需】模块元数据
├── install.sh           # 【可选】安装脚本（安装时自动执行）
├── uninstall.sh         # 【可选】卸载脚本（卸载时自动执行）
├── action.sh            # 【可选】操作脚本（手动触发）
├── config.html          # 【可选】HTML 配置界面（WebUI）
└── ...                  # 其他模块文件（二进制、配置等）
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

模块安装时自动执行的脚本。执行结果会实时输出到日志界面。

```bash
#!/system/bin/sh
# $1 = 模块目录的绝对路径 (MODDIR)
MODDIR="$1"

echo "==================================="
echo "  示例模块 安装中..."
echo "==================================="

# 创建必要的目录
mkdir -p /data/local/tmp/my_module

# 复制二进制文件并赋予权限
cp "$MODDIR/mybinary" /data/local/tmp/my_module/
chmod 755 /data/local/tmp/my_module/mybinary

echo "[✓] 安装完成"
```

**执行环境：**
- 通过 Shizuku 获得的 shell 权限
- uid=2000（shell 用户）或 uid=0（root，取决于 Shizuku 启动方式）
- `$1` 参数为模块目录绝对路径

**重要：**
- 二进制文件权限应使用 `chmod 755`，不要使用 `chmod 700`（可能导致权限问题）
- 如需创建日志目录，请在脚本中先 `mkdir -p` 创建

### 2.3 uninstall.sh（可选）

模块卸载时执行的脚本。

```bash
#!/system/bin/sh
echo "正在清理..."
rm -rf /data/local/tmp/my_module
echo "卸载完成"
```

### 2.4 action.sh（可选）

用户在模块列表中点击「执行」按钮时运行的脚本。适合需要手动触发的操作。输出会实时流式显示在终端界面。

```bash
#!/system/bin/sh
echo "执行系统信息查询..."
getprop ro.build.version.release
dumpsys battery
```

### 2.5 config.html（可选）

模块的 HTML 配置界面（WebUI），使用 WebView 渲染。支持 JavaScript 交互。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>模块配置</title>
<style>
/* 自定义样式（系统会自动注入基础 Material Design 3 样式） */
.btn { padding: 10px 24px; border: none; border-radius: 20px; background: #6750a4; color: white; }
.log-box { background: #1d1b20; color: #cdd6f4; border-radius: 12px; padding: 12px; font-family: monospace; }
</style>
</head>
<body>
<h2>模块配置</h2>
<div class="log-box" id="log"></div>
<button class="btn" onclick="execCmd()">执行命令</button>

<script>
// 设置回调
window.ShizukuNext.onOutput = function(text, type) {
    var log = document.getElementById('log');
    log.innerHTML += text + '\n';
    log.scrollTop = log.scrollHeight;
};

window.ShizukuNext.onComplete = function(code) {
    var log = document.getElementById('log');
    log.innerHTML += '\n[退出码: ' + code + ']\n';
};

// 执行命令
function execCmd() {
    window.ShizukuNext.exec("getprop ro.build.version.release");
}
</script>
</body>
</html>
```

**HTML 配置页面特性：**
- 自动注入 Material Design 3 风格的基础样式（包含按钮、输入框、卡片、日志框等组件样式）
- 自动注入 `window.ShizukuNext` JavaScript 桥接对象
- 支持 JavaScript 交互
- 支持 LocalStorage 持久化配置
- 页面 `body` 有 16px 内边距，无需额外设置

---

## 3. JS 桥接接口

在 `config.html` 中，通过 `window.ShizukuNext` 对象与 Shizuku Next 交互。

### 3.1 方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `ShizukuNext.moduleId` | - | `string` | 当前模块的 ID |
| `ShizukuNext.moduleName` | - | `string` | 当前模块的名称 |
| `ShizukuNext.exec(cmd)` | `cmd: string` | - | 执行 shell 命令（流式输出） |
| `ShizukuNext.execScript(path)` | `path: string` | - | 执行模块目录下的脚本文件 |
| `ShizukuNext.getModuleDir()` | - | `string` | 获取模块目录路径 |
| `ShizukuNext.getAppList()` | - | `Array<{pkg,name,enabled}>` | 获取已安装应用列表 |
| `ShizukuNext.getAppIcon(pkg)` | `pkg: string` | `string` | 获取应用图标（base64 data URI） |
| `ShizukuNext.pickFile()` | - | - | 调起文件选择器（支持第三方文件管理器） |
| `ShizukuNext.pickFileWithMime(mime)` | `mime: string` | - | 调起文件选择器（指定 MIME 类型） |
| `ShizukuNext.prepareFiles()` | - | - | 预处理模块文件 |
| `ShizukuNext.clearLog()` | - | - | 清除日志（触发 `onClearLog` 回调） |

### 3.2 回调

通过设置 `window.ShizukuNext` 的回调属性来接收事件：

| 回调 | 参数 | 说明 |
|------|------|------|
| `onOutput(text, type)` | `text: string`, `type: 'stdout'\|'stderr'` | 命令输出（流式，逐行触发） |
| `onComplete(code)` | `code: int` | 命令执行完成，返回退出码 |
| `onError(msg)` | `msg: string` | 执行出错 |
| `onFilePicked(path)` | `path: string` | 文件选择成功，返回文件路径 |
| `onFilePickCanceled()` | - | 文件选择被取消 |
| `onExec(cmd)` | `cmd: string` | 命令即将执行（可用于日志记录） |
| `onClearLog()` | - | 日志被清除 |

### 3.3 完整回调示例

```javascript
// 命令输出回调（流式）
window.ShizukuNext.onOutput = function(text, type) {
    console.log('[' + type + '] ' + text);
    // type 为 'stdout' 或 'stderr'
};

// 命令完成回调
window.ShizukuNext.onComplete = function(exitCode) {
    console.log('命令完成，退出码: ' + exitCode);
};

// 错误回调
window.ShizukuNext.onError = function(msg) {
    console.error('错误: ' + msg);
};

// 文件选择回调
window.ShizukuNext.onFilePicked = function(path) {
    console.log('已选择文件: ' + path);
    // 可以直接使用路径执行安装等操作
    window.ShizukuNext.exec("pm install -r '" + path + "'");
};

window.ShizukuNext.onFilePickCanceled = function() {
    console.log('文件选择已取消');
};
```

---

## 4. 内置 CSS 样式

系统自动注入以下 CSS 样式，可直接在 HTML 中使用：

### 4.1 基础样式

| 类名 | 说明 |
|------|------|
| `button` | Material Design 3 圆角按钮（紫色主题） |
| `button.secondary` | 次要按钮（浅紫色背景） |
| `input`, `select`, `textarea` | 输入框样式 |
| `.card` | 卡片容器 |
| `.log-box` | 日志输出框（深色背景，等宽字体） |
| `.log-line-stdout` | 标准输出行 |
| `.log-line-stderr` | 错误输出行（红色） |
| `.status-badge` | 状态徽章 |
| `.status-idle` | 空闲状态（紫色） |
| `.status-running` | 运行中状态（橙色） |
| `.status-success` | 成功状态（绿色） |
| `.status-failed` | 失败状态（红色） |

### 4.2 颜色变量

```css
/* 主色调（Material Design 3 紫色主题） */
--bg:      #fef7ff  /* 背景 */
--card:    #f3edf7  /* 卡片背景 */
--primary: #6750a4  /* 主色 */
--pl:      #e7e0eb  /* 浅色 */
--text:    #1d1b20  /* 文字 */
--ts:      #49454f  /* 次要文字 */
```

---

## 5. 模块规章

### 5.1 命名规范

- 模块 `id` 必须唯一，建议使用 `com.author.module_name` 或 `author_module_name` 格式
- `id` 只能包含 `[a-zA-Z0-9_-]`
- 模块名称应简洁明了，不超过 30 个字符

### 5.2 安全规范

1. **不得包含恶意代码**：模块不得包含任何破坏系统稳定性、窃取用户数据的代码
2. **最小权限原则**：只请求必要的操作，不滥用 shell 权限
3. **透明性**：模块描述应清楚说明模块的功能和影响
4. **卸载清理**：如果安装脚本创建了文件/目录，卸载脚本应负责清理
5. **路径安全**：脚本中不应硬编码其他应用的路径
6. **权限设置**：二进制文件使用 `chmod 755`，不要使用 `chmod 700`

### 5.3 兼容性规范

1. 模块应兼容 Android 11 (API 30) 及以上版本
2. 脚本使用 `#!/system/bin/sh` 作为解释器
3. 不依赖 `busybox`，使用 Android 自带的 `toybox` 命令
4. 考虑不同设备的差异，脚本应有错误处理

### 5.4 分发规范

1. 模块以 zip 格式分发
2. zip 文件根目录应直接包含 `module.prop`
3. 不应包含 `__MACOSX` 或 `.DS_Store` 等无关文件
4. 建议在 zip 文件名中包含模块名和版本号，如 `example_module_v1.0.0.zip`

### 5.5 UI 规范

1. 所有界面文字使用中文
2. 配置页面使用 Material Design 3 风格
3. 长时间操作应显示进度和日志
4. 用户交互操作（如确认、选择）建议使用弹窗/对话框，不要直接在终端输出
5. 应用列表如有图标，使用 `getAppIcon()` 懒加载，不要在 `getAppList()` 中批量生成

---

## 6. 安装流程

1. 用户在 Shizuku Next 主页点击「模块」卡片
2. 进入模块管理页面
3. 点击右下角「+」按钮
4. 系统调起文件选择器（支持 MT 管理器等第三方文件管理器）
5. 选择模块 zip 文件
6. 系统自动解压并安装
7. 如果有 `install.sh`，自动执行安装脚本（日志实时显示）
8. 安装完成后，模块出现在列表中

---

## 7. 模块管理

### 启用/禁用

- 每个模块右侧有开关，切换启用/禁用状态
- 禁用的模块不会执行任何脚本
- 禁用状态通过模块目录下的 `disable` 标记文件实现

### 卸载

- 点击模块卡片中的「卸载」按钮
- 确认后，如果有 `uninstall.sh` 则先执行卸载脚本（日志实时显示）
- 然后删除整个模块目录

### 配置

- 如果模块包含 `config.html`，会显示「配置」按钮
- 点击后在 WebView 中显示配置页面

### 执行操作

- 如果模块包含 `action.sh`，会显示「执行」按钮
- 点击后在终端中执行操作脚本，显示实时流式输出

---

## 8. 完整示例

### 8.1 示例模块清单

项目包含以下示例模块：

| 模块 | 目录 | 说明 |
|------|------|------|
| KivenRoot 临时提权 | `moudle-kivenroot/` | 通过 xpad2 执行临时 root，WebUI 显示运行日志 |
| APK 安装器 | `sample_modules/appinstaller/` | 静默安装 APK，支持文件选择和应用列表管理 |
| 小黑盒 | `sample_modules/blackbox/` | 应用冻结/解冻工具，显示应用图标，弹窗确认操作 |
| 系统优化大师 | `sample_modules/sysoptimizer/` | 动画速度调节、SELinux 管理等高级功能 |

### 8.2 KivenRoot 临时提权示例

```
moudle-kivenroot/
├── module.prop       # 模块属性
├── install.sh        # 安装脚本（复制 xpad2，赋权，执行提权）
├── uninstall.sh      # 卸载脚本
├── config.html       # WebUI（检测状态、执行提权、显示日志）
└── xpad2             # 提权二进制文件
```

**install.sh 关键代码：**

```bash
#!/system/bin/sh
MODDIR="$1"
# 复制 xpad2 到 /data/local/tmp/
cp "$MODDIR/xpad2" /data/local/tmp/xpad2
chmod 755 /data/local/tmp/xpad2
# 创建日志目录
mkdir -p /data/local/tmp/.xpad2/logs
# 执行提权
/data/local/tmp/xpad2 install
```

### 8.3 APK 安装器示例（文件选择）

```javascript
// 使用文件选择器选择 APK
window.ShizukuNext.onFilePicked = function(path) {
    appendLog('开始安装: ' + path);
    window.ShizukuNext.exec("pm install -r '" + path + "'");
};

// 点击按钮调起文件选择器
function selectAndInstall() {
    window.ShizukuNext.pickFileWithMime('application/vnd.android.package-archive');
}
```

### 8.4 小黑盒示例（应用列表 + 图标懒加载）

```javascript
var allApps = [];
var iconCache = {};

// 加载应用列表（不包含图标，速度快）
function loadApps() {
    var raw = window.ShizukuNative.getAppList();
    allApps = JSON.parse(raw);
    renderApps(allApps);
    loadVisibleIcons();
}

// 懒加载应用图标
function loadVisibleIcons() {
    allApps.forEach(function(app) {
        if (iconCache[app.pkg]) return;
        try {
            var icon = window.ShizukuNative.getAppIcon(app.pkg);
            if (icon) {
                iconCache[app.pkg] = icon;
                var img = document.querySelector("[data-pkg='" + app.pkg + "'] img");
                if (img) img.src = icon;
            }
        } catch(e) {}
    });
}

// 冻结应用（使用弹窗确认，不用终端）
function freezeApp(pkg, name) {
    showDialog("冻结应用", "确定要冻结「" + name + "」吗？", function() {
        window.ShizukuNext.exec("pm disable-user --user 0 " + pkg);
    });
}
```

### 8.5 状态机示例（多种操作共用回调）

```javascript
var currentAction = 'idle'; // 'install', 'list', 'uninstall', 'idle'

window.ShizukuNext.onOutput = function(text, type) {
    if (currentAction === 'list' && text && text.startsWith('package:')) {
        // 处理应用列表输出
        var pkg = text.replace('package:', '');
        appendAppToList(pkg);
    } else {
        // 普通日志输出
        appendLog(text, type);
    }
};

window.ShizukuNext.onComplete = function(code) {
    if (currentAction === 'install') {
        showToast(code === 0 ? '安装成功' : '安装失败');
    } else if (currentAction === 'uninstall') {
        showToast(code === 0 ? '卸载成功' : '卸载失败');
    } else if (currentAction === 'list') {
        renderAppList();
    }
    currentAction = 'idle';
};
```

---

## 9. 开发建议

1. **先在终端测试脚本**：开发脚本时，先用 Shizuku Next 的终端功能测试命令
2. **使用 echo 输出状态**：脚本中多用 `echo` 输出执行状态，方便调试
3. **错误处理**：使用 `||` 和 `if` 进行错误处理
4. **模块目录引用**：使用 `$1` 或 `MODDIR` 变量引用模块目录，不要硬编码路径
5. **HTML 配置简洁**：配置页面应简洁美观，自动注入了 Material Design 3 基础样式
6. **版本管理**：每次更新模块时递增 `versionCode`
7. **图标懒加载**：获取应用列表时不要批量生成图标，使用 `getAppIcon(pkg)` 按需加载
8. **用户交互用弹窗**：冻结/卸载等操作使用弹窗确认，不要在终端里交互
9. **权限使用 755**：二进制文件权限使用 `chmod 755`，不要用 `chmod 700`
10. **流式输出**：`exec()` 是流式输出的，适合长时间运行的脚本，回调会被多次触发

---

## 10. FAQ

### Q: 模块安装后脚本没有执行？
A: `install.sh` 在安装时自动执行。请确保 Shizuku 服务正在运行，且脚本有可执行权限。

### Q: 模块可以在没有 root 的情况下使用吗？
A: 可以。Shizuku Next 模块通过 Shizuku 获得的 shell 权限（uid=2000）执行，不需要 root。但某些需要 root 权限的操作可能无法执行。

### Q: 模块的文件存储在哪里？
A: 模块文件存储在 `/data/data/moe.shizuku.privileged.api/files/modules/<module_id>/` 目录下。

### Q: 如何更新模块？
A: 重新安装同 `id` 的 zip 文件即可更新。旧版本会被自动替换。

### Q: 文件选择器无法调起第三方文件管理器？
A: Shizuku Next 使用 `GetContent` 协议调起文件选择器，兼容 MT 管理器等第三方文件管理器。如果无法调起，请检查文件管理器是否支持 `GetContent` intent。

### Q: xpad2 提示 "Operation not permitted"？
A: 确保在执行前 `mkdir -p /data/local/tmp/.xpad2/logs` 创建日志目录，并使用 `chmod 755` 而非 `chmod 700`。

### Q: getAppList() 返回数据很慢或超时？
A: `getAppList()` 只返回包名、名称和启用状态，不包含图标。如果需要图标，使用 `getAppIcon(pkg)` 逐个懒加载。

### Q: 脚本执行时没有日志输出？
A: 确保 `install.sh` 中使用 `echo` 输出内容，并已设置 `window.ShizukuNext.onOutput` 回调。

### Q: 模块可以访问网络吗？
A: 脚本执行时可以使用 `curl`、`wget` 等命令访问网络（如果系统支持）。HTML 配置页面中的 WebView 也可以访问网络。

---

## 11. 模块规范总结（规章）

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
11. 所有界面文字使用中文
12. 二进制文件权限使用 `chmod 755`
13. 用户交互操作使用弹窗/对话框，不直接在终端交互
14. 应用图标使用 `getAppIcon()` 懒加载，不批量生成
