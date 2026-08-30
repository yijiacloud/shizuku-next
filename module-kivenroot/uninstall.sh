#!/system/bin/sh
#
# uninstall.sh - KivenRoot 临时提权模块卸载脚本
# 清理 /data/local/tmp/xpad2
#

XPAD2_DST="/data/local/tmp/xpad2"

echo "============================================"
echo "  KivenRoot 临时提权模块 - 卸载脚本"
echo "============================================"
echo ""

if [ -f "$XPAD2_DST" ]; then
    echo "清理 xpad2: $XPAD2_DST"
    rm -f "$XPAD2_DST"
    echo "清理完成。"
else
    echo "xpad2 不存在，无需清理。"
fi

echo "卸载脚本执行完毕。"