#!/system/bin/sh
#
# action.sh - Shizuku Next 模块操作脚本
# 当用户在模块列表中点击「执行」按钮时运行
#
# 可用变量：
#   MODDIR    - 模块目录的绝对路径

MODDIR="${1:-$(dirname "$0")}"
echo "========================================"
echo "  Shizuku Next 示例模块 - 操作脚本"
echo "========================================"
echo ""
echo "模块目录: $MODDIR"
echo "当前用户: $(id)"
echo "时间: $(date)"
echo ""

echo "===== 电池信息 ====="
dumpsys battery 2>/dev/null | head -20 || echo "无法获取电池信息"
echo ""

echo "===== 内存信息 ====="
cat /proc/meminfo 2>/dev/null | head -5 || echo "无法获取内存信息"
echo ""

echo "===== 存储信息 ====="
df /data 2>/dev/null || echo "无法获取存储信息"
echo ""

echo "操作脚本执行完毕。"