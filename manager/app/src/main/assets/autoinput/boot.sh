#!/system/bin/sh
# Auto Input - 开机自启入口（KernelSU / Magisk 的 service.d，越狱模式下走 late-load.d）
# 内容基于原模块 service.sh，MODDIR 固定到 /data/adb/auto_input（脚本常驻目录）
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

# 最早期打点，便于确认本脚本是否真的被执行
blog "service.sh invoked (MODDIR=$MODDIR)"

# ---------- 保护 1：紧急开关 ----------
if [ -f "$DISABLED" ]; then
    blog "boot: DISABLED 存在，跳过自启（删掉该文件并在监听页重新打开开关即可恢复）"
    exit 0
fi

# ---------- 保护 2：开机熔断 ----------
ATT="$MODDIR/run/boot.attempts"
HEALTH="$MODDIR/run/boot.health"
now=$(date +%s 2>/dev/null)
[ -z "$now" ] && now=0

# 只保留 15 分钟内的启动记录，避免长期累积
if [ -s "$ATT" ]; then
    : > "$ATT.tmp"
    while IFS= read -r t || [ -n "$t" ]; do
        t=$(printf '%s' "$t" | tr -d '\r')
        [ -z "$t" ] && continue
        [ $((now - t)) -lt 900 ] && echo "$t" >> "$ATT.tmp"
    done < "$ATT"
    mv -f "$ATT.tmp" "$ATT" 2>/dev/null
fi

prev=$(tail -n 1 "$ATT" 2>/dev/null | tr -d '\r\n')
healthy=$(cat "$HEALTH" 2>/dev/null | tr -d '\r\n')

# 上一次启动记录之后没有新的健康标记 -> 那次开机没活过 3 分钟，按卡死处理
if [ -n "$prev" ] && { [ -z "$healthy" ] || [ "$healthy" -lt "$prev" ]; }; then
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
echo "$now" >> "$ATT"

# 开机时清理上一次运行残留的 pid 文件，避免单例保护误拦
rm -f "$MODDIR/run/"*.pid 2>/dev/null

# ---------- 后台等待开机完成后拉起看门狗 ----------
(
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 180 ]; do
        sleep 2
        i=$((i + 1))
    done
    sleep 5

    . "$BIN_DIR/common.sh"

    chmod 755 "$BIN_DIR/"*.sh 2>/dev/null
    mkdir -p "$MODDIR/run" 2>/dev/null
    rm -f "$MODDIR/run/"*.pid 2>/dev/null

    start_supervisor "$MODDIR"
    log "boot: watchdog launched by service.sh"

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
