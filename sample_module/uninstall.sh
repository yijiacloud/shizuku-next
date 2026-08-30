#!/system/bin/sh
#
# uninstall.sh - Shizuku Next 模块卸载脚本
# 在模块卸载时执行
#
# 可用变量：
#   MODDIR    - 模块目录的绝对路径

MODDIR="${1:-$(dirname "$0")}"
echo "========================================"
echo "  Shizuku Next 示例模块 - 卸载脚本"
echo "========================================"
echo ""

# 清理测试目录
TESTDIR="/data/local/tmp/example_module"
if [ -d "$TESTDIR" ]; then
    echo "清理测试目录: $TESTDIR"
    rm -rf "$TESTDIR"
    echo "清理完成。"
else
    echo "测试目录不存在，无需清理。"
fi

echo "卸载脚本执行完毕。"