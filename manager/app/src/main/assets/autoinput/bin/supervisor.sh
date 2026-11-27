#!/system/bin/sh
# Auto Input - 看门狗：常驻后台，daemon 挂了自动拉起（单例）
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

mkdir -p "$CONF" "$MODDIR/run" 2>/dev/null

# 父 shell 退出（比如 chen 被从最近任务划掉）时会给整组发 HUP，看护不能因此退出
trap '' HUP

PIDFILE="$MODDIR/run/supervisor.pid"

# 单例保护：已有存活实例则退出
OLD=$(read_pid "$PIDFILE")
if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" supervisor.sh; then
    log "supervisor already running(pid=$OLD), exit($$)"
    exit 0
fi
echo "$$" > "$PIDFILE"
log "watchdog started pid=$$"

while true; do
    sh "$BIN_DIR/daemon.sh"
    RC=$?
    log "daemon exited(code=$RC), respawn in 3s"
    sleep 3
done
