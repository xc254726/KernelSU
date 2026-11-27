#!/system/bin/sh
# Auto Input - app 侧唯一的进程入口
#
# app 不再自己起 daemon/看护（那样进程会挂在 chen 的进程树上，划掉后台就一起没了）。
# 这里只做两件事：
#   1) 确保开机侧 keeper 在位（不在就补拉一次；正常情况下开机已经拉过）
#   2) 让配置改动立刻生效（杀掉 daemon，看护 3 秒内用新配置重新拉起）
# 用法：sh ensure.sh        应用配置
#       sh ensure.sh stop   立即停止监听（开关关闭时用）
#       sh ensure.sh keeper 只补拉 keeper，不动已经在跑的看护/daemon
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

MODDIR="$(cd "$BIN_DIR/.." && pwd)"
mkdir -p "$MODDIR/run" 2>/dev/null

case "$1" in
    stop)
        kill_daemon "$MODDIR"
        kill_supervisor "$MODDIR"
        log "ensure.sh: 监听已停止（app 请求）"
        ;;
    keeper)
        # keeper 不在时（典型：装完新包还没重启过手机）补拉一次。
        # 这里绝不能顺手重起 daemon —— 那正是老版本「一进监听页就把监听打断」的原因。
        start_keeper "$MODDIR"
        log "ensure.sh: 已请求补拉 keeper"
        ;;
    *)
        start_keeper "$MODDIR"
        kill_daemon "$MODDIR"
        : > "$MODDIR/run/reload" 2>/dev/null
        log "ensure.sh: 已请求按新配置重启 daemon"
        ;;
esac
