#!/system/bin/sh
# Auto Input - 开机侧常驻看护（keeper）
#
# 为什么需要它：监听是 chen 的 root shell 拉起来的（点开关 / 进监听页自愈都会走 restart.sh），
# 那个 shell 属于 app 的进程树。把 chen 从最近任务划掉时，ROM 会把 shell 连同子进程一起清掉，
# 而 chen 自己的「60 秒自愈」也随 app 一起没了，于是监听就再也不启动。
#
# keeper 由开机脚本（service.d / late-load.d）拉起，父进程在 init 侧，与 chen 无关：
# 只要开关还是打开状态，发现看护不在了就补拉一次。开关关掉或已熔断则自行退出。
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

MODDIR="$(cd "$BIN_DIR/.." && pwd)"
PIDFILE="$MODDIR/run/keeper.pid"

mkdir -p "$MODDIR/run" 2>/dev/null

# 单例保护
OLD=$(read_pid "$PIDFILE")
if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" keeper.sh; then
    exit 0
fi
echo "$$" > "$PIDFILE"
log "keeper started pid=$$"

while true; do
    if [ -f "$MODDIR/DISABLED" ]; then
        log "keeper: 已安全熔断(DISABLED)，keeper 退出"
        break
    fi
    if [ "$(get_conf status 1)" != "1" ]; then
        log "keeper: 监听开关已关，keeper 退出"
        break
    fi
    if ! supervisor_alive "$MODDIR"; then
        log "keeper: 看护不在，补拉一次"
        start_supervisor "$MODDIR"
    fi
    sleep 15
done

[ "$(read_pid "$PIDFILE")" = "$$" ] && rm -f "$PIDFILE" 2>/dev/null
log "keeper down (pid=$$)"
