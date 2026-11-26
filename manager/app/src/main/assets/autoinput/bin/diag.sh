#!/system/bin/sh
# Auto Input - 自检：输出环境信息、守护进程状态与最近日志
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

echo "== Auto Input 自检 =="
echo "时间      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "uid       : $(id -u 2>/dev/null)"
echo "selinux   : $(getenforce 2>/dev/null)"
echo "input命令 : $(resolve_input)"
FG=$(current_fg)
echo "当前前台  : ${FG:-（读不到）}"
if [ -n "$FG" ] && is_target "$FG"; then
    echo "是否目标  : 是（会触发）"
else
    echo "是否目标  : 否（不在监听列表里）"
fi
if input_focused; then
    echo "输入框焦点: 有（可以注入）"
else
    echo "输入框焦点: 无（先点一下目标输入框再试）"
fi

echo "---- 配置 ----"
if [ "$(get_conf input_mode.txt screen)" = "screen" ]; then
    echo "输入方式  : 屏幕输入"
else
    echo "输入方式  : 脚本喂入"
fi
PKGN=$(grep -c . "$CONF/pkg.txt" 2>/dev/null); [ -z "$PKGN" ] && PKGN=0
LINEN=$(grep -c '[^[:space:]]' "$CONF/input.txt" 2>/dev/null); [ -z "$LINEN" ] && LINEN=0
SP=$(get_script_path); [ -z "$SP" ] && SP="(未设置)"
echo "监听应用  : $PKGN 个"
echo "预案行数  : $LINEN"
echo "等待秒数  : $(get_conf delay.txt 5)"
echo "输入间隔  : $(get_conf speed.txt 3)"
echo "最小间隔  : $(get_conf min_interval.txt 8)"
echo "监听开关  : $(get_conf status 1)"
echo "脚本路径  : $SP"
echo "ADBKeyboard: $(has_adbkeyboard && echo 已安装 || echo 未安装)"

echo "---- 进程 ----"
if supervisor_alive "$MODDIR"; then echo "看门狗    : 运行中"; else echo "看门狗    : 未运行"; fi
if pid_matches "$(read_pid "$MODDIR/run/watcher.pid")" watcher.sh; then
    echo "事件监听  : 运行中 (缓存 $(cat "$CONF/.fg_last" 2>/dev/null | tr -d '\r\n'))"
else
    echo "事件监听  : 未运行"
fi
if pid_matches "$(read_pid "$MODDIR/run/daemon.pid")" daemon.sh; then
    echo "守护进程  : 运行中 (pid=$(read_pid "$MODDIR/run/daemon.pid"))"
else
    echo "守护进程  : 未运行（监听不会生效，请回上一页关掉再打开开关）"
fi

BIN=$(resolve_input)
if [ -n "$BIN" ]; then
    OUT=$($BIN text "_" </dev/null 2>&1)
    RC=$?
    echo "注入测试  : rc=$RC out=${OUT:-<无输出>}"
else
    echo "注入测试  : 找不到 input 命令"
fi

echo "---- 最近日志 ----"
if [ -f "$LOG" ]; then
    tail -n 14 "$LOG" 2>/dev/null
else
    echo "(还没有日志)"
fi
echo "== 完成 =="
echo "提示：注入测试 rc=0 只说明命令执行成功，文字能否落屏取决于目标输入框是否已聚焦；中文需要装 ADBKeyboard =="
