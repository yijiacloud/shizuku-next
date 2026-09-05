#!/system/bin/sh
MODDIR="$1"
echo "==================================="
echo "  小黑盒 - 系统工具箱 安装中..."
echo "==================================="
echo ""
echo "模块目录: $MODDIR"
echo ""

# 检查 Shizuku 是否运行
if [ -f /data/local/tmp/shizuku_starter ]; then
    echo "[✓] Shizuku 服务已检测到"
else
    echo "[!] Shizuku 服务可能未运行"
fi

echo ""
echo "安装完成！点击「配置」打开工具箱界面。"
echo "==================================="