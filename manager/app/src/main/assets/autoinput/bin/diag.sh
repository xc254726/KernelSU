#!/system/bin/sh
# Auto Input - 自检：输出环境信息与一次注入测试结果
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

echo "== Auto Input 自检 =="
echo "时间      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "uid       : $(id -u 2>/dev/null)"
echo "selinux   : $(getenforce 2>/dev/null)"
echo "PATH      : $PATH"
echo "input命令 : $(resolve_input)"
echo "当前前台  : $(get_foreground_pkg)"
echo "预案行数  : $(grep -c '[^[:space:]]' "$CONF/input.txt" 2>/dev/null)"
echo "监听开关  : $(get_conf status 1)"
echo "等待秒数  : $(get_conf delay.txt 5)"
echo "输入间隔  : $(get_conf speed.txt 3)"
echo "执行顺序  : $(get_conf order.txt script_first)"
echo "ADBKeyboard: $(has_adbkeyboard && echo 已安装 || echo 未安装)"

BIN=$(resolve_input)
if [ -n "$BIN" ]; then
    OUT=$($BIN text "_" </dev/null 2>&1)
    RC=$?
    echo "注入测试  : rc=$RC out=${OUT:-<无输出>}"
else
    echo "注入测试  : 找不到 input 命令"
fi

echo "== 完成（若注入测试 rc=0 且无输出，说明命令执行成功；文字是否落屏取决于输入框是否有光标）=="
