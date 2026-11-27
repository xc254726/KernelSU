#!/system/bin/sh
# Auto Input - 运行状态（供 WebUI 调用，输出 key=value）
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

S=0
D=0
K=0
supervisor_alive "$MODDIR" && S=1
pid_matches "$(read_pid "$MODDIR/run/daemon.pid")" daemon.sh && D=1
keeper_alive "$MODDIR" && K=1

echo "status=$(get_conf status 1)"
echo "supervisor=$S"
echo "daemon=$D"
echo "keeper=$K"
echo "pkg_count=$(grep -c . "$CONF/pkg.txt" 2>/dev/null || echo 0)"
echo "delay=$(get_conf delay.txt 5)"
echo "speed=$(get_conf speed.txt 3)"
echo "order=$(get_conf order.txt script_first)"
echo "keep_script=$(get_conf keep_script.txt 1)"
echo "min_interval=$(get_conf min_interval.txt 8)"
echo "input_mode=$(get_conf input_mode.txt script)"
