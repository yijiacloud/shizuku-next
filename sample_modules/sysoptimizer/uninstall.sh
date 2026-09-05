#!/system/bin/sh
echo "==================================="
echo "  系统优化大师 - 正在卸载..."
echo "==================================="
echo "正在恢复默认动画速度..."
settings put global window_animation_scale 1.0
settings put global transition_animation_scale 1.0
settings put global animator_duration_scale 1.0
echo "已恢复默认设置。"
echo "系统优化大师已卸载完成。"