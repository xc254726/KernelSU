#!/system/bin/sh
# Auto Input - 自检：环境信息 / 触发链路 / 输入与回车测试
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

echo "== Auto Input 自检 =="
echo "时间      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "uid       : $(id -u 2>/dev/null)"
echo "selinux   : $(getenforce 2>/dev/null)"
echo "input命令 : $(resolve_input)"

# ---------- 触发链路 ----------
FG=$(get_foreground_pkg)
echo "当前前台  : ${FG:-（读不到）}"
echo "监听开关  : $(get_conf status 1)"
echo "监听列表  : $(tr '\n' ' ' < "$CONF/pkg.txt" 2>/dev/null | tr -d '\r')"
if [ -z "$FG" ]; then
    echo "是否命中  : 读不到前台，无法判断"
elif is_target "$FG"; then
    echo "是否命中  : 是（在监听列表里）"
else
    echo "是否命中  : 否（不在监听列表里）"
fi
if supervisor_alive "$MODDIR"; then echo "看门狗    : 运行中"; else echo "看门狗    : 未运行"; fi
if pid_matches "$(read_pid "$MODDIR/run/daemon.pid")" daemon.sh; then
    echo "守护进程  : 运行中"
else
    echo "守护进程  : 未运行"
fi

# ---------- 输入链路 ----------
echo "输入方式  : $(get_conf input_mode.txt script)"
echo "脚本路径  : $(get_script_path)"
echo "预案行数  : $(grep -c '[^[:space:]]' "$CONF/input.txt" 2>/dev/null)"
echo "等待秒数  : $(get_conf delay.txt 5)"
echo "输入间隔  : $(get_conf speed.txt 3)"
echo "回车次数  : $(get_conf enter_count.txt 1)"
echo "最小间隔  : $(get_conf min_interval.txt 8)"
echo "ADBKeyboard: $(has_adbkeyboard && echo 已安装 || echo 未安装)"

BIN=$(resolve_input)
if [ -n "$BIN" ]; then
    OUT=$($BIN text "_" </dev/null 2>&1)
    RC=$?
    echo "文字测试  : rc=$RC out=${OUT:-<无输出>}"
    OUT=$($BIN keyevent 66 </dev/null 2>&1)
    RC=$?
    echo "回车测试  : rc=$RC out=${OUT:-<无输出>}"
else
    echo "文字测试  : 找不到 input 命令"
    echo "回车测试  : 找不到 input 命令"
fi

echo "== 说明：文字/回车测试 rc=0 且无输出表示命令执行成功，能否落屏取决于当时有没有输入框获得光标 =="
echo "== 完成 =="
