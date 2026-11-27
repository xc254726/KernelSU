#!/system/bin/sh
# Auto Input - 开机侧常驻看护（keeper）
#
# 为什么需要它：以前监听是 chen 的 root shell 拉起来的（点开关、进监听页都会重启一遍），
# 那个 shell 属于 app 的进程树 —— 把 chen 从最近任务划掉时会被连坐杀掉，而 chen 自己的
# 「自愈」也随 app 一起没了，于是监听再也不启动。
#
# keeper 由开机脚本（service.d / late-load.d）拉起，父进程在 init 侧，与 chen 无关，常驻到重启：
#   * status=1 且看护不在     -> 补拉看护
#   * status=0 / 已熔断       -> 停掉看护和 daemon
#   * run/reload 出现         -> 重启 daemon（app 改配置/切换开关只写这个标记，不再自己起进程）
#   * loadavg 连续 3 次超 4×核数 -> 判定把系统拖死，停掉监听并写 DISABLED
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

# 与 daemon/supervisor 一致：只有 SIGKILL 能停掉 keeper（ROM 清理后台爱发 HUP/TERM）
trap '' HUP INT TERM QUIT

MODDIR="$(cd "$BIN_DIR/.." && pwd)"
PIDFILE="$MODDIR/run/keeper.pid"
RELOAD="$MODDIR/run/reload"

mkdir -p "$MODDIR/run" 2>/dev/null

# 单例保护
OLD=$(read_pid "$PIDFILE")
if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" keeper.sh; then
    exit 0
fi
echo "$$" > "$PIDFILE"
# 从 app 的 cgroup 里挪出去：这一步才真正决定「划掉 chen 后台监听会不会死」。
# 失败也继续跑（只是可能在划掉后台时被 ROM 冻结），但必须留下痕迹便于定位。
if escape_app_cgroup "$$"; then
    log "keeper started pid=$$ (status=$(get_conf status 1)) cgroup=$(cat /proc/self/cgroup 2>/dev/null)"
else
    log "keeper started pid=$$ (status=$(get_conf status 1)) [警告] 仍在 app cgroup=$(cat /proc/self/cgroup 2>/dev/null)"
fi

nproc=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
case "$nproc" in ''|*[!0-9]*) nproc=8 ;; esac
[ "$nproc" -lt 1 ] && nproc=8
limit=$((nproc * 400))      # loadavg 放大 100 倍后比较：4 倍核数
hi=0

# 开机健康标记：keeper 活过 3 分钟就写一次，下一次开机据此判断「上次没卡死」
START_TS=$(date +%s 2>/dev/null)
[ -z "$START_TS" ] && START_TS=0
HDONE=0
STATE=""

while true; do
    if [ "$HDONE" = "0" ]; then
        _now=$(date +%s 2>/dev/null)
        [ -z "$_now" ] && _now=0
        if [ "$START_TS" -gt 0 ] && [ $((_now - START_TS)) -ge 180 ]; then
            date +%s > "$MODDIR/run/boot.health" 2>/dev/null
            HDONE=1
        fi
    fi

    # ---- 配置变更 / 开关切换：只重起 daemon，不动看护 ----
    if [ -f "$RELOAD" ]; then
        rm -f "$RELOAD" 2>/dev/null
        kill_daemon "$MODDIR"
        log "keeper: 收到 reload，daemon 已停（看护会用新配置重新拉起）"
        sleep 2
    fi

    st=$(get_conf status 1)

    if [ -f "$MODDIR/DISABLED" ]; then
        want=disabled
    elif [ "$st" != "1" ]; then
        want=off
    elif supervisor_alive "$MODDIR"; then
        want=on
    else
        want=starting
    fi

    if [ "$want" != "$STATE" ]; then
        log "keeper: 状态 $STATE -> $want"
        STATE="$want"
    fi

    case "$want" in
        disabled|off)
            if supervisor_alive "$MODDIR"; then
                kill_daemon "$MODDIR"
                kill_supervisor "$MODDIR"
                log "keeper: 监听已停（want=$want），看护与 daemon 已收掉"
            fi
            hi=0
            ;;
        starting)
            log "keeper: 看护不在，补拉一次"
            start_supervisor "$MODDIR"
            hi=0
            ;;
        on)
            # ---- 负载看护：连续 3 次（约 30 秒）超过 4×核数就熔断 ----
            load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null | tr -d '.')
            case "$load" in ''|*[!0-9]*) load=0 ;; esac
            load=$(printf '%s' "$load" | cut -c1-7)
            if [ "$load" -gt "$limit" ]; then
                hi=$((hi + 1))
            else
                hi=0
            fi
            if [ "$hi" -ge 3 ]; then
                log "keeper: 负载持续过高（loadavg×100=$load > $limit），停止监听并熔断"
                {
                    echo "监听已因系统负载过高被自动停止（安全熔断）。"
                    echo ""
                    echo "loadavg 连续 30 秒超过 $((${nproc} * 4))，说明监听把系统拖住了。"
                    echo "请把「每条间隔」调大、减少输入条数，再重新打开开关。"
                    echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S')"
                } > "$MODDIR/DISABLED"
                kill_daemon "$MODDIR"
                kill_supervisor "$MODDIR"
                hi=0
                STATE="disabled"
            fi
            ;;
    esac

    sleep 10
done
