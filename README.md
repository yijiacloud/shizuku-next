# Shizuku Next

> 基于 [Shizuku](https://github.com/RikkaApps/Shizuku) 的增强分支，新增悬浮窗配对、终端、模块系统、MD3 主题等功能。

## 功能特性

### 原有功能（保持兼容）
- 通过 ADB / Root 启动 Shizuku 服务
- 无线调试配对（原版对话框方式）
- 应用授权管理
- AIDL 接口完全兼容原版 Shizuku

### 新增功能

#### 1. 悬浮窗配对模式
- 通过悬浮窗进行无线调试配对，适用于无法弹出配对对话框的设备
- 自带数字键盘，无需系统输入法
- 自动搜索设备，找到后直接输入配对码
- 配对成功后自动搜索 ADB 端口并连接
- 支持手动输入 IP+端口作为后备方案

#### 2. 终端
- 应用内直接执行 Shell 命令
- 流式实时输出（stdout / stderr 双线程）
- 命令历史记录（上下键翻阅）
- 彩色终端输出
- 上下文感知（cd 命令切换目录后保持）

#### 3. 模块系统（参考 KernelSU）
- 从 zip 文件安装模块
- 模块启用 / 禁用 / 卸载
- 支持 HTML 配置界面（WebView + JS 桥接）
- 支持安装脚本、卸载脚本、操作脚本
- 实时日志输出
- 文件选择器支持（兼容第三方文件管理器）
- 安装/卸载日志输出

#### 4. MD3 主题（v0.11 新增）
- Material Design 3 Next 主题风格
- 在设置中一键切换原版风格与 MD3 风格
- 浅色/深色模式自动适配
- 卡片、图标、按钮遵循 MD3 设计规范
- 模块 WebUI 页面支持 MD3 CSS

#### 5. 自动更新检查（v0.10 新增）
- 启动时自动检查 GitHub 最新版本
- 设置中可手动检查更新

## 下载

前往 [Releases](../../releases) 页面下载最新版本。

当前最新版本：**v0.11** — [下载 APK](../../releases/download/v0.11/shizuku-v0.11-debug.apk)

## 模块开发

模块开发文档请参考 [docs/module-development.md](docs/module-development.md)。

### 模块结构

```
module.zip
├── module.prop          # 模块元数据（必需）
├── install.sh           # 安装脚本（可选）
├── uninstall.sh         # 卸载脚本（可选）
├── action.sh            # 操作脚本（可选）
├── config.html          # HTML 配置界面（可选）
└── ...                  # 其他文件
```

### 示例模块

项目包含以下示例模块：
- `sample_module.zip` — 基础功能演示
- KivenRoot — 提权操作模块
- Blackbox — 应用管理模块（冻结/解冻应用）
- AppInstaller — 应用安装/卸载模块
- SysOptimizer — 系统优化模块

## 构建

### 环境要求
- JDK 17+
- Android SDK（API 36）
- Android NDK 29
- CMake 3.31.0

### 编译

```bash
./gradlew :manager:assembleDebug --no-daemon
```

编译后的 APK 位于：`manager/build/outputs/apk/debug/`

## 包名兼容

包名保持 `moe.shizuku.privileged.api`，与原版 Shizuku 完全兼容，其他依赖 Shizuku 的应用无需修改。

## 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku) - RikkaApps
- [KernelSU](https://github.com/tiann/KernelSU) - 模块系统设计参考

## License

遵循原项目 Shizuku 的许可证。