# Shizuku Next

> 基于 [Shizuku](https://github.com/RikkaApps/Shizuku) 的增强分支，新增悬浮窗配对、终端、模块系统等功能。

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

#### 3. 模块系统（参考 KernelSU）
- 从 zip 文件安装模块
- 模块启用 / 禁用 / 卸载
- 支持 HTML 配置界面（WebView + JS 桥接）
- 支持安装脚本、卸载脚本、操作脚本
- 实时日志输出

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

项目包含一个示例模块 `sample_module.zip`，演示模块系统的基本功能。

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

## 下载

前往 [Releases](../../releases) 页面下载最新版本。

