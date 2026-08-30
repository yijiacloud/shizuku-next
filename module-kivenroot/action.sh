#!/system/bin/sh
#
# action.sh - KivenRoot 临时提权操作脚本
# 用户在模块列表中点击「执行」按钮时运行
# 重新执行 xpad2 install 提权操作
#

XPAD2_DST="/data/local/tmp/xpad2"

echo "============================================"
echo "  KivenRoot 临时提权 - 操作脚本"
echo "============================================"
echo ""
echo "当前用户: $(id)"
echo "时间: $(date)"
echo ""

# 检查 xpad2 是否存在
if [ ! -f "$XPAD2_DST" ]; then
    echo "[错误] $XPAD2_DST 不存在"
    echo "[提示] 请先重新安装此模块"
    exit 1
fi

# 检查权限
if [ ! -x "$XPAD2_DST" ]; then
    echo "[修复] 重新设置 700 权限"
    chmod 700 "$XPAD2_DST"
fi

echo "开始执行提权操作..."
echo "----------------------------------------"
"$XPAD2_DST" install
EXIT_CODE=$?
echo "----------------------------------------"
echo ""

if [ $EXIT_CODE -eq 0 ]; then
    echo "[成功] 提权操作完成 (退出码: $EXIT_CODE)"
else
    echo "[失败] 提权操作退出码: $EXIT_CODE"
fi

echo ""
echo "操作脚本执行完毕。"