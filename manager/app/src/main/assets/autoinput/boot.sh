#!/system/bin/sh
# Auto Input - 开机自启入口（装两份，按启动来源分两种行为）
#   /data/adb/service.d/chen-auto-input.sh   普通开机：等系统开机完成、确认 root 就绪后再拉起
#   /data/adb/late-load.d/chen-auto-input.sh 越狱模式：chen 的 BootCompletedReceiver 开机完成时会自动越狱，
#                                            本脚本随 late-load 以 root 执行，root 已经就绪 -> 直接拉起，不做等待
#
# 这个脚本是最后一道保险：即使用户把参数调到会让系统卡死的值，也必须能正常开机。
# 三道保护：
#   1) 紧急开关：/data/adb/auto_input/DISABLED 存在 -> 完全不自启
#   2) 开机熔断：上一次开机启动后 3 分钟内就重启、没留下健康标记 -> 判定上次卡死，
#      自动写 DISABLED 并跳过自启（本次开机正常进系统）
#   3) 运行期看护：启动后盯 /proc/loadavg，负载持续超过 4 倍核数就停掉监听并熔断
MODDIR=/data/adb/auto_input
BIN_DIR="$MODDIR/bin"
LOG=/data/adb/auto_input/daemon.log
DISABLED="$MODDIR/DISABLED"

# service.d 环境 PATH 很精简，dumpsys/input/getprop 都可能找不到，
# 守护进程会表现为「识别不到前台 -> 永远不触发」。这里先补全 PATH 再启动。
export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:$PATH

mkdir -p "$MODDIR" "$MODDIR/run" 2>/dev/null

blog() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null; }

# 从哪个目录被执行的，就决定了这次开机的启动策略
case "$0" in
    *late-load.d*) BOOT_MODE=late ;;
    *)             BOOT_MODE=service ;;
esac
blog "boot.sh invoked (mode=$BOOT_MODE, self=$0, uid=$(id -u 2>/dev/null))"

# ---------- 保护 1：紧急开关 ----------
if [ -f "$DISABLED" ]; then
    blog "boot: DISABLED 存在，跳过自启（删掉该文件并在监听页重新打开开关即可恢复）"
    exit 0
fi

# ---------- 保护 2：开机熔断 ----------
# 用 boot_id 区分「新的一次开机」和「同一次开机里又跑了一遍脚本」：
# 越狱模式会被重复触发（开机自动越狱、手动越狱、重新打开开关），
# 同一开机的第二次执行既不能再判熔断，也不能再拉一个看护（会双份输入）。
ATT="$MODDIR/run/boot.attempts"
HEALTH="$MODDIR/run/boot.health"
now=$(date +%s 2>/dev/null)
[ -z "$now" ] && now=0

BOOTID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
if [ -z "$BOOTID" ]; then
    # boot_id 读不到时用「开机时刻」近似（now - uptime，按分钟取整），同一开机内稳定
    up=$(cut -d. -f1 /proc/uptime 2>/dev/null | tr -d ' \r\n')
    case "$up" in ''|*[!0-9]*) up="" ;; esac
    [ -n "$up" ] && BOOTID="b$(( (now - up) / 60 ))"
fi
if [ -z "$BOOTID" ]; then
    # 两者都拿不到：不做同开机去重，也不做熔断判定（宁可多起一次，也不能误杀自启）
    BOOTID="unknown"
    blog "boot: 无法识别开机标识，本次跳过熔断判定"
fi

same_boot=0
prev_ts=0
if [ -s "$ATT" ]; then
    : > "$ATT.tmp"
    while IFS= read -r line || [ -n "$line" ]; do
        line=$(printf '%s' "$line" | tr -d '\r')
        [ -z "$line" ] && continue
        bid=${line%% *}
        ts=${line##* }
        case "$ts" in ''|*[!0-9]*) ts=0 ;; esac
        # 只保留 15 分钟内的启动记录，避免长期累积
        [ "$ts" -gt 0 ] && [ $((now - ts)) -ge 900 ] && continue
        [ "$bid" = "$BOOTID" ] && same_boot=1
        [ "$ts" -gt "$prev_ts" ] && prev_ts=$ts
        echo "$bid $ts" >> "$ATT.tmp"
    done < "$ATT"
    mv -f "$ATT.tmp" "$ATT" 2>/dev/null
fi

healthy=$(cat "$HEALTH" 2>/dev/null | tr -d '\r\n')
case "$healthy" in ''|*[!0-9]*) healthy=0 ;; esac

if [ "$same_boot" = "1" ]; then
    # 同一次开机：不判熔断，只在看护没跑时补拉一次
    blog "boot: 本次开机已执行过（mode=$BOOT_MODE），检查看护状态"
    . "$BIN_DIR/common.sh"
    sp=$(read_pid "$MODDIR/run/supervisor.pid" 2>/dev/null)
    if [ -n "$sp" ] && proc_alive "$sp"; then
        blog "boot: 看护已在运行(pid=$sp)，跳过"
        # keeper 也补一次：它负责在 chen 被划掉后把看护拉回来
        start_keeper "$MODDIR"
        exit 0
    fi
    blog "boot: 看护未运行，补拉一次"
else
    # 新的一次开机：上一次启动记录之后没有新的健康标记 -> 那次开机没活过 3 分钟，按卡死处理
    if [ "$prev_ts" -gt 0 ] && { [ "$healthy" -eq 0 ] || [ "$healthy" -lt "$prev_ts" ]; }; then
        {
            echo "开机自启已被安全熔断。"
            echo ""
            echo "原因：上一次开机在启动后 3 分钟内就重启了，疑似监听把系统拖死。"
            echo "本文件存在期间不会自动拉起监听（不影响其它任何功能）。"
            echo "删掉本文件、并在应用监听页重新打开开关即可恢复；恢复前请先把"
            echo "「每条间隔」调大（不要设 0），并确认脚本和输入内容没有问题。"
            echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S')"
        } > "$DISABLED"
        blog "boot: 上次开机未留下健康标记，已熔断自启（写 DISABLED）"
        exit 0
    fi
    echo "$BOOTID $now" >> "$ATT"
fi

# 开机时清理上一次运行残留的 pid 文件，避免单例保护误拦
rm -f "$MODDIR/run/"*.pid 2>/dev/null

# ---------- 后台等待时机成熟后拉起看门狗 ----------
(
    if [ "$BOOT_MODE" = "late" ]; then
        # 越狱：root 是这次 late-load 自己带起来的，早就绪了，直接拉起
        blog "boot: 越狱模式，root 已就绪，直接拉起看护"
        sleep 2
    else
        # 其他模式：等系统开机完成
        i=0
        while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 180 ]; do
            sleep 2
            i=$((i + 1))
        done
        sleep 5
    fi

    # 拉起前确认真的拿到了 root（越狱/授权都是异步的，等不到就别起，免得半死状态）
    i=0
    while [ "$(id -u 2>/dev/null)" != "0" ] && [ "$i" -lt 60 ]; do
        sleep 1
        i=$((i + 1))
    done
    if [ "$(id -u 2>/dev/null)" != "0" ]; then
        blog "boot: 60 秒内没拿到 root，跳过本次自启"
        exit 0
    fi

    . "$BIN_DIR/common.sh"

    chmod 755 "$BIN_DIR/"*.sh 2>/dev/null
    mkdir -p "$MODDIR/run" 2>/dev/null
    rm -f "$MODDIR/run/"*.pid 2>/dev/null

    start_supervisor "$MODDIR"
    # 开机侧常驻看护：chen 被从最近任务划掉后，由它把看护补回来
    start_keeper "$MODDIR"
    log "boot: watchdog launched by boot.sh (mode=$BOOT_MODE)"

    # ---------- 保护 3：运行期看护 ----------
    nproc=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
    case "$nproc" in ''|*[!0-9]*) nproc=8 ;; esac
    [ "$nproc" -lt 1 ] && nproc=8
    limit=$((nproc * 400))      # loadavg 放大 100 倍后比较：4 倍核数
    hi=0
    ticks=0
    hdone=0

    while true; do
        sleep 20
        ticks=$((ticks + 1))

        sp=$(cat "$MODDIR/run/supervisor.pid" 2>/dev/null | tr -d '\r\n')
        if [ -z "$sp" ] || ! proc_alive "$sp"; then
            log "watchdog: 监听已被停止，看护退出"
            break
        fi

        # 活过 3 分钟就记为健康，下一次开机不再熔断
        if [ "$hdone" = "0" ] && [ "$ticks" -ge 9 ]; then
            date +%s > "$HEALTH" 2>/dev/null
            hdone=1
        fi

        load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null | tr -d '.')
        case "$load" in ''|*[!0-9]*) continue ;; esac
        load=$(printf '%s' "$load" | cut -c1-7)

        if [ "$load" -gt "$limit" ]; then
            hi=$((hi + 1))
        else
            hi=0
        fi

        if [ "$hi" -ge 3 ]; then
            log "watchdog: 系统负载持续过高（loadavg×100=$load > $limit），停止监听并熔断"
            {
                echo "监听已因系统负载过高被自动停止（安全熔断）。"
                echo ""
                echo "loadavg 连续 60 秒超过 $((${nproc} * 4))，说明监听把系统拖住了。"
                echo "请把「每条间隔」调大、减少输入条数，再重新打开开关。"
                echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S')"
            } > "$DISABLED"
            kill_daemon "$MODDIR"
            kill "$sp" 2>/dev/null
            break
        fi
    done
) </dev/null >/dev/null 2>&1 &
