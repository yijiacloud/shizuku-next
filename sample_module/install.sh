#!/system/bin/sh
#
# install.sh - Shizuku Next 模块安装脚本
# 在模块安装时自动执行
# 运行环境：通过 Shizuku 获得的 shell 权限（uid=2000 或 root）
#
# 可用变量：
#   MODDIR    - 模块目录的绝对路径
#   SHIZUKU_API - Shizuku API 版本（如果可用）

MODDIR="${1:-$(dirname "$0")}"
echo "========================================"
echo "  Shizuku Next 示例模块 - 安装脚本"
echo "========================================"
echo ""
echo "模块目录: $MODDIR"
echo "当前用户: $(id)"
echo "系统信息: $(getprop ro.build.fingerprint 2>/dev/null || echo 'unknown')"
echo ""

# 示例：创建一个测试目录
TESTDIR="/data/local/tmp/example_module"
echo "创建测试目录: $TESTDIR"
mkdir -p "$TESTDIR"
echo "模块安装于 $(date)" > "$TESTDIR/install_time.txt"
echo "安装完成！"
echo ""

# 示例：检查并显示一些系统信息
echo "===== 系统信息 ====="
echo "Android 版本: $(getprop ro.build.version.release)"
echo "SDK 版本: $(getprop ro.build.version.sdk)"
echo "设备型号: $(getprop ro.product.model)"
echo "===================="

echo ""
echo "安装脚本执行完毕。"