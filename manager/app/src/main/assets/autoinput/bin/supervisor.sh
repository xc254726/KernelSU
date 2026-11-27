#!/system/bin/sh
# Auto Input - 看门狗：常驻后台，daemon 挂了自动拉起（单例）
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

mkdir -p "$CONF" "$MODDIR/run" 2>/dev/null

# 父 shell 退出（比如 chen 被从最近任务划掉）时会给整组发 HUP；ROM 清理后台
# 也常先发 TERM。看护不能因为这个退出 —— 停下它只走 kill -9（kill_supervisor）。
trap '' HUP INT TERM QUIT

PIDFILE="$MODDIR/run/supervisor.pid"

# 单例保护：已有存活实例则退出
OLD=$(read_pid "$PIDFILE")
if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" supervisor.sh; then
    log "supervisor already running(pid=$OLD), exit($$)"
    exit 0
fi
echo "$$" > "$PIDFILE"
# 与 keeper 同理：必须离开 app 的 cgroup，否则划掉 chen 后台会被整棵冻结/清理
if escape_app_cgroup "$$"; then
    log "watchdog started pid=$$ cgroup=$(cat /proc/self/cgroup 2>/dev/null)"
else
    log "watchdog started pid=$$ [警告] 仍在 app cgroup=$(cat /proc/self/cgroup 2>/dev/null)"
fi

while true; do
    sh "$BIN_DIR/daemon.sh"
    RC=$?
    log "daemon exited(code=$RC), respawn in 3s"
    sleep 3
done
