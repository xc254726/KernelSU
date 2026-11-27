#!/system/bin/sh
# Auto Input - 开机自启入口（装两份，按启动来源分两种行为）
#   /data/adb/service.d/chen-auto-input.sh   普通开机：等系统开机完成、确认 root 就绪后再拉起
#   /data/adb/late-load.d/chen-auto-input.sh 越狱模式：chen 的 BootCompletedReceiver 开机完成时会自动越狱，
#                                            本脚本随 late-load 以 root 执行，root 已经就绪 -> 直接拉起，不做等待
#
# 这个脚本是最后一道保险：即使用户把参数调到会让系统卡死的值，也必须能正常开机。
# 三道保护（现在都不会「自动关掉监听」）：
#   1) 紧急开关：/data/adb/auto_input/DISABLED 存在 -> 完全不自启（用户手工禁用时才用）
#   2) 开机记录：上一次开机若没留下健康标记，只写日志便于排查，照常启动，不再熔断
#   3) 运行期看护：由 keeper（bin/keeper.sh）常驻盯 /proc/loadavg，
#      负载持续超过 4 倍核数就暂停 60 秒后自动恢复，开关始终保持「开」
#
# 这里只负责「把 keeper 拉起来」，启停/自愈/看护都在 keeper 里做：
# keeper 的父进程在 init 侧，chen 被划掉或卸载都不影响它。
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
# DISABLED 现在只作为「用户手工禁用」的开关使用。
# 旧版本会在判定卡死时自动写这个文件，导致升级后监听永远起不来：
# 带旧版熔断说明的文件自动清掉，用户自己写的（内容不同）仍然生效。
if [ -f "$DISABLED" ]; then
    if grep -qE '安全熔断|负载过高|监听把系统拖' "$DISABLED" 2>/dev/null; then
        blog "boot: 发现旧版本自动熔断留下的 DISABLED，自动清除并继续启动"
        rm -f "$DISABLED" 2>/dev/null
    else
        blog "boot: DISABLED 存在（用户手工禁用），跳过自启"
        exit 0
    fi
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
    # 新的一次开机。
    # 这里以前会「上一次开机没留下健康标记 -> 判定卡死 -> 写 DISABLED 永久禁用自启」。
    # 那个判定太容易误伤：只要用户开机后几分钟内重启过手机（调机、刷模块、电量断电），
    # 下次开机监听就被永久关掉，表现就是「过一会自己变成已安全熔断」。
    # 现在只记日志用于排查，绝不再自动关闭监听 —— 启停完全由用户的开关决定。
    if [ "$prev_ts" -gt 0 ] && { [ "$healthy" -eq 0 ] || [ "$healthy" -lt "$prev_ts" ]; }; then
        blog "boot: 上次开机未留下健康标记（可能启动后很快重启过），按开关设置照常启动，不熔断"
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
    # 只清看护/daemon 的旧 pid；keeper.pid 留着，避免同一次开机动出两个 keeper
    rm -f "$MODDIR/run/supervisor.pid" "$MODDIR/run/daemon.pid" 2>/dev/null

    # keeper 常驻（init 侧进程）：自愈、开关启停、负载看护都归它，和 chen 的进程无关。
    # 所以 chen 被从最近任务划掉、甚至卸载，都不影响监听继续跑。
    start_keeper "$MODDIR"

    if [ "$(get_conf status 1)" = "1" ]; then
        start_supervisor "$MODDIR"
    fi
    log "boot: keeper launched (mode=$BOOT_MODE, status=$(get_conf status 1))"
) </dev/null >/dev/null 2>&1 &
