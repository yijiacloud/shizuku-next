# Shizuku Next — 交接文档

> 文档用途：将当前项目状态完整交接给后续 AI 继续开发
> 最后更新：2026-08-30

---

## 一、项目概述

### 1.1 项目是什么

**Shizuku Next** 是基于 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 的二次开发版本。

Shizuku 是一个 Android 特权 API 管理器，让应用在不 root 的情况下以 ADB/root 权限执行操作。Shizuku Next 在此基础上扩展功能并重构 UI。

### 1.2 核心原则

| 项目 | 说明 |
|------|------|
| **包名不变** | 沿用 `moe.shizuku.privileged.api`，确保所有依赖 Shizuku 包名识别的第三方应用无缝兼容 |
| **AIDL 接口不变** | `IShizukuService` 等接口签名保持不变，不破坏第三方调用方 |
| **向下兼容** | 不破坏原项目核心功能，新功能以模块化方式叠加 |

### 1.3 模块规划（共 4 个模块）

| 模块 | 名称 | 优先级 | 状态 |
|------|------|--------|------|
| 模块 1 | 悬浮窗模式（Floating Window） | ★ 最高 | ✅ 已完成 |
| 模块 2 | 指令模块（Shell Terminal） | 高 | ✅ 基础已完成 |
| 模块 3 | UI 重构（Win2UI 风格） | 中 | ❌ 未开始 |
| 模块 4 | 模块系统（KSU 风格） | 中 | ❌ 未开始 |

### 1.4 模块详细说明

#### 模块 1：悬浮窗模式（已完成）

**背景**：原 Shizuku 的 ADB 配对依赖弹出 Dialog 悬浮窗，但部分机型已阉割悬浮窗弹出能力，导致配对流程断裂。

**方案**：Shizuku Next 应用**自身以悬浮窗形式常驻**于屏幕，不依赖目标应用弹窗。

**原配对方式保留不变**：原 Dialog 弹窗配对（`AdbPairDialogFragment`）、通知栏配对（`AdbPairingService`）完整保留，悬浮窗作为**第二种可选方式**。

**配对流程**（参考原版 `AdbPairDialogFragment`）：
1. 用户在"通过无线调试启动"卡片中点击"通过悬浮窗配对"
2. 启动 `FloatingWindowService`，创建常驻悬浮窗
3. 开始 mDNS 搜索 ADB 配对服务 → 悬浮窗显示"正在搜索设备…"，输入框隐藏
4. 发现设备端口后 → 悬浮窗显示"已找到设备，请输入配对码"，输入框出现
5. 用户输入配对码并回车 → 执行 `AdbPairingClient` 配对
6. 显示成功/失败结果

**输入法适配**：
- 悬浮窗默认带 `FLAG_NOT_FOCUSABLE`（无法获焦，不挡其他窗口操作）
- 点击输入框时动态移除该 flag，添加 `FLAG_ALT_FOCUSABLE_IM`，调用 `InputMethodManager.showSoftInput()` 唤起输入法
- 提交输入或失去焦点后恢复 `FLAG_NOT_FOCUSABLE`

**关键文件**：
| 文件 | 作用 |
|------|------|
| `overlay/FloatingWindowService.kt` | 悬浮窗服务，窗口创建/拖拽/折叠/输入法适配/状态管理 |
| `overlay/FloatingWindowManager.kt` | 管理器，权限检查/启动停止/更新提示 |
| `overlay/OverlayPairingController.kt` | ADB 配对控制器，mDNS 搜索→配对码输入→AdbPairingClient 执行 |
| `overlay/OverlayInputReceiver.kt` | 广播接收器，路由用户输入到配对控制器或授权回调 |
| `overlay/OverlayServiceHolder.kt` | 单例桥接器，连接 Receiver 和 Service 控制器 |
| `res/layout/overlay_floating_window.xml` | 悬浮窗布局（提示文字 + 配对码输入框） |

#### 模块 2：指令模块 / 终端（基础已完成）

**功能**：应用内终端，以 Shizuku 特权身份执行 Shell 命令。

**实现方式**：通过 `Shizuku.getBinder()` 获取 `IShizukuService`，调用 `newProcess()` 执行命令。所有命令统一用 `sh -c` 执行以支持管道和重定向。

**当前状态**：
- ✅ 终端界面（深色背景，等宽字体，输出着色）
- ✅ 命令历史记录（持久化到 SharedPreferences，↑↓ 键翻历史）
- ✅ 菜单（清屏、复制、查看历史）
- ✅ 标题改为"终端使用"
- ❌ 常用指令快捷栏已隐藏（用户要求删除以简化界面）

**关键文件**：
| 文件 | 作用 |
|------|------|
| `shell/ShellTerminalActivity.kt` | 终端 Activity，命令输入/执行/输出显示/历史管理 |
| `res/layout/activity_shell_terminal.xml` | 终端布局（Toolbar + 输出区 + 输入栏） |
| `res/menu/terminal_menu.xml` | 菜单（清屏/复制/历史） |
| `home/TerminalViewHolder.kt` | 首页"终端使用"卡片，点击进入终端 |

#### 模块 3：UI 重构（未开始）

参考 Win2UI / WinUI 3 设计语言，基于 Jetpack Compose + Material3 重构 UI。Mica/Acrylic 半透明材质、圆角卡片、Fluent Design 控件。

#### 模块 4：模块系统（未开始）

参考 KernelSU 的模块管理能力，支持 root2000 操作、静默/HTML 双模式、模块规章。

---

## 二、开发环境

### 2.1 路径与工具链

| 项目 | 路径 / 版本 |
|------|-------------|
| 项目根目录 | `D:\shizukunext` |
| Java | `D:\jdk`（junction 到 `C:\Users\电脑\.jdks\openjdk-23.0.1`，Java 23） |
| Android SDK | `D:\Android\Sdk`（platform-tools, platforms;android-36, build-tools;36.0.0, NDK 29, CMake 3.31.0） |
| Gradle 缓存 | `D:\gradle_home`（junction 到 `C:\Users\电脑\.gradle`） |
| 临时目录 | `D:\tmp`（避免中文路径导致 prefab 编译失败） |
| Gradle | 8.14（pre-downloaded to wrapper cache） |
| Git | 已初始化，`safe.directory` 已配置 |

### 2.2 为什么用 junction 路径

Windows 用户名为中文 `电脑`，导致 `C:\Users\电脑\...` 路径在 AIDL 生成代码的注释和 prefab 批处理文件中产生编码错误。通过 junction 链接为纯 ASCII 路径解决：
- `D:\jdk` → `C:\Users\电脑\.jdks\openjdk-23.0.1`
- `D:\gradle_home` → `C:\Users\电脑\.gradle`
- `D:\tmp` → 临时目录（替代 `C:\Users\电脑\AppData\Local\Temp`）

### 2.3 构建命令

```bat
D:\shizukunext\build-debug.bat
```

或手动执行：

```powershell
cd D:\shizukunext
$env:JAVA_HOME="D:\jdk"
$env:ANDROID_HOME="D:\Android\Sdk"
$env:ANDROID_SDK_ROOT="D:\Android\Sdk"
$env:GRADLE_USER_HOME="D:\gradle_home"
$env:TEMP="D:\tmp"
$env:TMP="D:\tmp"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& .\gradlew.bat :manager:assembleDebug --no-daemon
```

**构建耗时**：约 2-3 分钟（首次更长）

**APK 输出**：
- 原始：`manager\build\outputs\apk\debug\shizuku-v13.6.0.r1.unknown-debug.apk`
- 便捷：`out\apk\shizuku-next-debug.apk`（约 15MB，Debug 签名，可直接安装）

### 2.4 build.gradle 关键修改

```groovy
subprojects {
    tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }  // 修复 AIDL 中文编码
    plugins.withId("com.android.base") {
        android {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            ndkVersion = "29.0.13113456"
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
    plugins.withId('org.jetbrains.kotlin.android') {
        android.kotlinOptions { jvmTarget = '21' }  // 对齐 Java 21，修复 JVM target 不一致
    }
}
```

---

## 三、已完成的全部工作（按时间线）

### M0-M1：项目搭建
- 从 `D:\download` 解压 Shizuku 源码到 `D:\shizukunext`
- 下载 Shizuku-API 子模块到 `api/`
- 创建 `hidden-api-stub` 最小桩（原 API 下载缺失）
- Git 初始化，`local.properties` 配置 SDK 路径
- 安装 Android SDK（platform-tools, android-36, build-tools 36.0.0, NDK 29）

### M2：悬浮窗核心
- 创建 `overlay/` 包下全部 5 个 Kotlin 文件
- 创建悬浮窗布局和 drawable 资源
- 修改 `ShizukuSettings.java` 添加悬浮窗相关常量
- 修改 `AndroidManifest.xml` 添加 `SYSTEM_ALERT_WINDOW` 权限和 Service 注册
- 在首页添加悬浮窗入口和"通过悬浮窗配对"按钮

### M2.1：配对流程集成
- 实现 mDNS 搜索 → 输入配对码 → AdbPairingClient 执行的完整流程

### M2.2：首次编译成功
- 修复 AIDL UTF-8 编码错误（JavaCompile `options.encoding = 'UTF-8'`）
- 修复 JVM target 不一致（Kotlin jvmTarget = 21）
- 安装 CMake 3.31.0
- 用 junction 路径解决中文用户名导致的 prefab 编译失败
- 修复 `button4` ID 从 `@android:id/button4` 改为 `@+id/button4`
- **首次 BUILD SUCCESSFUL**，APK 输出

### 第一轮 Bug 修复
1. 去掉桌面悬浮窗卡片（`HomeAdapter` 移除 `FloatingWindowViewHolder`）
2. 配对流程改为先搜索设备再显示输入框（新增 `STATE_SEARCHING` 状态）
3. 修复输入法无法唤起（动态切换 `FLAG_NOT_FOCUSABLE`）
4. 全部文案改为中文

### 第二轮 Bug 修复
1. `FloatingWindowService` 添加 `startForeground()` — 修复 ANR/崩溃
2. 去掉设置里的悬浮窗开关 — 只保留无线调试配对按钮入口
3. 注册 `ShellTerminalActivity` 到 Manifest — 终端入口可访问
4. `TerminalViewHolder` 改为跳转 `ShellTerminalActivity`
5. `HomeAdapter`：Shizuku 已授权后不显示启动卡片
6. `OverlayPairingController`：重复点击配对时先停止再重新搜索

### 第三轮 Bug 修复（最新）
1. 悬浮窗配对卡在"就绪"：`OverlayPairingController` 改为直接调用 `Service.updateState()`，不通过 Intent 绕圈
2. 终端不显示输出：颜色值缺少 `0xFF` alpha 前缀导致文字透明，全部修正
3. 标题改为"终端使用"
4. 隐藏快捷指令栏，界面更简洁
5. 所有命令统一用 `sh -c` 执行，支持管道重定向

---

## 四、Git 提交历史

```
06bdda6 修复5个bug：悬浮窗配对卡死、终端透明文字、标题改名、删除快捷栏
ec65311 修复悬浮窗卡死/闪退 + 去掉设置开关 + 注册终端入口 + 启动卡片条件显示
aed10f2 修复三个问题 + M3 指令模块
faf146d docs: update PROJECT_PLAN.md milestones - M0-M2.2 complete, first APK built
686e909 fix: build configuration - UTF-8 encoding, JVM target 21, CMake 3.31.0, ASCII paths for prefab
a4a2a76 Shizuku Next initial
```

---

## 五、当前文件结构（新增/修改部分）

```
D:\shizukunext\
├── build.gradle                          # 修改：UTF-8 编码 + JVM target 21
├── build-debug.bat                       # 新增：便捷构建脚本
├── local.properties                      # 新增：SDK 路径
├── PROJECT_PLAN.md                       # 新增：企划书
├── out/apk/shizuku-next-debug.apk        # 新增：构建输出
│
├── manager/src/main/
│   ├── AndroidManifest.xml               # 修改：SYSTEM_ALERT_WINDOW 权限、FloatingWindowService、ShellTerminalActivity
│   │
│   ├── java/moe/shizuku/manager/
│   │   ├── overlay/                      # 新增：悬浮窗模块
│   │   │   ├── FloatingWindowService.kt
│   │   │   ├── FloatingWindowManager.kt
│   │   │   ├── OverlayPairingController.kt
│   │   │   ├── OverlayInputReceiver.kt
│   │   │   └── OverlayServiceHolder.kt
│   │   │
│   │   ├── shell/
│   │   │   └── ShellTerminalActivity.kt  # 新增：终端模块
│   │   │
│   │   ├── home/
│   │   │   ├── HomeAdapter.kt            # 修改：去掉悬浮窗卡片，启动卡片条件显示
│   │   │   ├── TerminalViewHolder.kt     # 修改：跳转改为 ShellTerminalActivity
│   │   │   ├── StartWirelessAdbViewHolder.kt  # 修改：添加"通过悬浮窗配对"按钮
│   │   │   └── FloatingWindowViewHolder.kt    # 新增（已从 HomeAdapter 移除，文件保留）
│   │   │
│   │   └── settings/SettingsFragment.kt  # 修改：删除悬浮窗开关相关代码
│   │
│   └── res/
│       ├── layout/
│       │   ├── overlay_floating_window.xml    # 新增：悬浮窗布局
│       │   ├── home_floating_window.xml       # 新增：悬浮窗卡片布局（已不用）
│       │   └── activity_shell_terminal.xml    # 新增：终端布局
│       ├── menu/terminal_menu.xml             # 新增：终端菜单
│       ├── drawable/
│       │   ├── overlay_background.xml         # 新增
│       │   ├── overlay_input_background.xml   # 新增
│       │   ├── ic_floating_window_24.xml      # 新增
│       │   ├── ic_terminal_send_24.xml        # 新增
│       │   └── terminal_chip_background.xml   # 新增（已不用）
│       ├── xml/settings.xml                   # 修改：删除悬浮窗开关
│       └── values/strings.xml                 # 修改：悬浮窗/终端中文字符串
```

---

## 六、已知问题与注意事项

### 6.1 已知遗留
- `FloatingWindowViewHolder.kt` 和 `home_floating_window.xml` 文件仍存在但已不在 HomeAdapter 中使用，可清理
- `terminal_chip_background.xml` 和 `QUICK_COMMANDS` 已隐藏但文件保留
- 终端的 `ShellTutorialActivity`（rish 教程页）仍保留，`TerminalViewHolder` 已改为跳转终端

### 6.2 重要约束
- **不要修改** `RequestPermissionActivity`、`AdbPairDialogFragment` — 原配对方式保留不变
- **所有 exec 命令需要** `sandbox_permissions: "require_escalated"`
- **构建时必须设置** junction 路径环境变量（`JAVA_HOME=D:\jdk` 等），否则中文路径导致编译失败
- `signing.gradle` 自动 fallback 到 debug keystore（`signing.properties` 不存在时）

### 6.3 未完成的工作
| 优先级 | 任务 |
|--------|------|
| 高 | 终端功能实测验证（需要真机 + Shizuku 运行） |
| 高 | 悬浮窗配对实测验证（需要真机 + 无线调试） |
| 中 | 模块 3：UI 重构（Win2UI 风格，Compose + Material3） |
| 中 | 模块 4：模块系统（KSU 风格，root2000 操作，静默/HTML 双模式） |
| 低 | 清理无用文件（FloatingWindowViewHolder、home_floating_window.xml 等） |

---

## 七、用户偏好与约定

1. **用中文沟通**，所有文案用中文
2. **保留原配对方式**，悬浮窗是第二种可选方式，两种并存
3. **悬浮窗模式不单独占桌面卡片**，入口在"通过无线调试启动"卡片的配对按钮里
4. **设置里不要悬浮窗开关**，避免与服务状态冲突
5. **终端标题叫"终端使用"**，不占用"在我喜欢的终端应用"的位置
6. **终端界面要简洁**，不要常用指令快捷栏
7. **先编译出 APK 让用户测试**，再继续开发
