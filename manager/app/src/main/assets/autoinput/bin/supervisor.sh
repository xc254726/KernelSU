#!/system/bin/sh
# Auto Input - 看门狗：常驻后台，daemon 挂了自动拉起（单例）
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

mkdir -p "$CONF" "$MODDIR/run" 2>/dev/null

PIDFILE="$MODDIR/run/supervisor.pid"

# 单例保护：已有存活实例则退出
OLD=$(read_pid "$PIDFILE")
if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" supervisor.sh; then
    log "supervisor already running(pid=$OLD), exit($$)"
    exit 0
fi
echo "$$" > "$PIDFILE"
log "watchdog started pid=$$"

start_watcher "$MODDIR"
log "watchdog: watcher ensured"

while true; do
    sh "$BIN_DIR/daemon.sh"
    RC=$?
    log "daemon exited(code=$RC), respawn in 3s"
    sleep 3
done
