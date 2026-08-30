# Shizuku Next 企划书

> 项目代号：Shizuku Next
> 基础项目：RikkaApps/Shizuku (Apache License 2.0)
> 制定日期：2026-08-29
> 负责人：Codex Agent

---

## 一、项目概述

Shizuku Next 是基于 RikkaApps/Shizuku 的二次开发版本，在保持原项目核心功能与兼容性的前提下，对其功能模块进行扩展，并对其 UI 进行全面重构，使其更美观、更易用、更强大。

**核心原则：**
- **包名保持一致**：沿用原项目 `moe.shizuku.privileged.api` 包名，确保所有依赖 Shizuku 包名识别的第三方应用（如 Sui、Icebox、ShizukuAPI 调用方等）能够无缝兼容。
- **向下兼容**：不破坏原有 ShizukuAPI 接口契约，保持 AIDL 接口签名一致。
- **模块化扩展**：以模块化方式叠加新功能，避免侵入式改动原核心逻辑。

---

## 二、模块规划

本次改造计划新增/重构以下模块：


### 模块 1：悬浮窗模式（Floating Window Mode）★ 优先级最高 — 极简常驻输入窗

- **背景**：原 Shizuku 的授权 / ADB 配对流程依赖「临时弹出一个 Dialog 悬浮窗」让用户确认。但部分机型 / 目标应用已阉割悬浮窗弹出能力，导致 Dialog 无法显示，授权流程断裂。
- **保留原方式**：原 Dialog 弹窗配对方式完整保留，悬浮窗模式作为**第二种可选方式**，用户可在设置中切换或同时开启。两种方式并存，互不冲突。
- **核心思路**：Shizuku Next 应用**自身以悬浮窗形式常驻**于屏幕，**不依赖目标应用弹窗**。悬浮窗极简设计——**只有一个输入框 + 一条提示文字**。
- **工作流程**：
  1. 用户开启悬浮窗模式 → Shizuku Next 通过 `SYSTEM_ALERT_WINDOW` 权限在屏幕上挂一个常驻小窗口
  2. 悬浮窗默认展示一条提示（如"等待授权请求…"或当前需要配对的应用名）
  3. 当某应用需要授权 / 需要 ADB 配对时，提示文字更新为对应指令
  4. 用户直接在悬浮窗内的输入框中**输入配对码 / 密码**
  5. 回车提交 → 完成授权或配对 → 提示更新为结果
- **功能点**：
  - 常驻悬浮窗，不依赖目标应用弹出能力，适配阉割机型
  - 极简 UI：输入框 + 提示文字，可拖拽、可吸附边缘、可折叠为图标
  - 输入框支持配对码 / 授权密码双模式
  - 提示文字根据当前状态动态更新（空闲 / 等待配对 / 等待授权 / 成功 / 失败）
  - 可选：半透明待机态，点击展开为完整输入态
- **技术点**：
  - `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` 常驻窗口
  - 需 `SYSTEM_ALERT_WINDOW` 权限，首次使用引导用户到设置授予
  - 窗口内使用 Compose 渲染（或远程视图 RemoteViews 兜底）
  - 输入框提交的配对码 / 密码由内置校验逻辑处理，无需外部授权模块

### 模块 2：指令模块（Shell Command Module）

- **目标**：提供一个精美且好用的 Shell 指令执行器。
- **功能点**：
  - 内置终端界面，支持命令输入、历史记录、自动补全
  - 以 Shizuku 的特权身份执行 Shell 指令
  - 支持脚本批量执行、管道、重定向
  - 输出着色、可复制、可导出
  - 常用命令快捷面板（如 pm、am、dumpsys、settings 等）
  - 安全沙箱选项：可限制可执行命令白名单

### 模块 3：UI 重构（Win2UI / WinUI 风格重构）

- **目标**：参考 Win2UI / WinUI 3 的设计语言，全面重构 Shizuku 的用户界面。
- **设计语言**：
  - Mica / Acrylic 半透明材质背景
  - 圆角卡片、细分隔线、Z 轴层级阴影
  - Fluent Design 控件风格（Pivot、NavigationView、CommandBar）
  - 深色/浅色主题统一，跟随系统
- **实现**：
  - 基于 Jetpack Compose + Material3，自定义主题令牌对齐 WinUI 视觉
  - 引入 Compose Acrylic/Mica 实现方案（BlurEffect + 渐变叠层）
  - 顶部导航采用 NavigationBar / NavigationRail 自适应

### 模块 4：模块系统（Module System，参考 KSU 模块体系）

- **目标**：引入类似 KernelSU (KSU) 的模块管理能力，使 Shizuku Next 能够加载、管理、执行第三方模块。
- **功能点**：
  1. **Root2000 操作支持**：参考 KSU 的模块机制，支持以 root 权限执行预定义操作集（"root2000" 操作）。
  2. **静默模式 / HTML 编辑界面双模式**：
     - **静默模式**：模块在后台执行，无 UI 干预，适合自动化场景。
     - **HTML 配置界面**：模块可携带 `webroot/` 目录，提供基于 HTML 的配置/修改界面，用户可在应用内 WebView 中交互式调整模块行为。
  3. **模块规章（Module Specification）**：
     - 制定一套《Shizuku Next 模块开发规章》
     - 规定模块目录结构：
       ```
       module.prop        # 元信息（id、name、version、author、description）
       install.sh         # 安装脚本
       customize.sh       # 可选自定义脚本
       webroot/           # 可选 HTML 配置界面
       action.sh          # 可选 root2000 操作脚本
       ```
     - 规定模块生命周期：安装 → 启用 → 执行 → 禁用 → 卸载
     - 规定权限与沙箱边界：模块声明所需权限，运行时校验
     - 规定 HTML 界面通信协议：`postMessage` 与沙箱 JS Bridge

---

## 三、技术栈

| 层级 | 技术选型 |
|------|----------|
| 语言 | Kotlin (主) + Java (兼容) |
| UI | Jetpack Compose + Material3 + 自定义 WinUI 主题 |
| 架构 | MVVM + Clean Architecture |
| DI | 手动 / 轻量 ServiceLocator |
| 持久化 | Room + DataStore |
| 加密 | javax.crypto + Argon2（JNI/纯Java实现） |
| 悬浮窗 | WindowManager Overlay + Compose |
| 模块系统 | 脚本执行（ProcessBuilder）+ WebView (HTML界面) |
| 构建 | Gradle (Kotlin DSL) |

---

## 四、兼容性保证

| 项目 | 保证 |
|------|------|
| 包名 | `moe.shizuku.privileged.api`（不变） |
| AIDL 接口 | `IShizukuService` 签名不变 |
| ShizukuAPI 第三方库 | 兼容，无需改动调用方 |
| 最低 Android 版本 | Android 9 (API 28)（与原项目对齐） |
| 目标 Android 版本 | Android 15 (API 35) |

---

## 五、里程碑

| 阶段 | 内容 | 状态 |
|------|------|------|
| M0 | 克隆原项目，搭建开发环境 | ✅ 完成 |
| M1 | 包名锁定 + 构建验证 | ✅ 完成 |
| M2 | 悬浮窗模式核心实现（FloatingWindowService/Manager/PairingController） | ✅ 完成 |
| M2.1 | 配对/授权流程集成（mDNS搜索→输入配对码→AdbPairingClient执行） | ✅ 完成 |
| M2.2 | 首次编译成功，APK 输出 (out/apk/shizuku-next-debug.apk) | ✅ 完成 |
| M3 | 指令模块实现（Shell Command Module） | ⏳ 待开始 |
| M4 | UI 重构（Win2UI 风格） | 待开始 |
| M5 | 模块系统（KSU 风格） | 待开始 |
| M6 | 集成测试 + 发布构建 | 待开始 |

---

## 六、许可证

原项目 Shizuku 采用 Apache License 2.0。Shizuku Next 将在遵守原许可证的基础上进行二次开发，并在项目中保留原有版权声明与许可证文件。

---

## 七、备注

- 本企划书为初版，实施过程中可能根据技术可行性调整细节。
- 所有新增模块均以"不破坏兼容性"为前提推进。
- 模块开发规章将随模块5一同输出为独立文档《Shizuku Next Module Specification》。
