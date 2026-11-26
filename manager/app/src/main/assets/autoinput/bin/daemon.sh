#!/system/bin/sh
# Auto Input - 前台应用监听守护进程
# 流程: 进入目标应用 -> 等待 N 秒 -> 执行脚本 -> 逐条输入 + 回车
# 配置每轮实时读取，WebUI 改动无需重启即生效

BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

MODDIR="$(cd "$BIN_DIR/.." && pwd)"
migrate_conf "$MODDIR"
migrate_order

DAEMON_PID=$$

mkdir -p "$CONF" "$MODDIR/run" 2>/dev/null
: >> "$LOG"

# ---------- 单例锁：防止出现两个 daemon 各跑一遍脚本 ----------
LOCK_DIR="$MODDIR/run/daemon.lock"
if mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "$$" > "$LOCK_DIR/pid" 2>/dev/null
else
    OLD=$(cat "$LOCK_DIR/pid" 2>/dev/null | tr -d '\r\n')
    if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" daemon.sh; then
        log "another daemon is running (pid=$OLD), exit"
        exit 0
    fi
    # 残留的失效锁，接管
    rm -rf "$LOCK_DIR" 2>/dev/null
    mkdir -p "$LOCK_DIR" 2>/dev/null && echo "$$" > "$LOCK_DIR/pid" 2>/dev/null
fi

echo "$$" > "$MODDIR/run/daemon.pid" 2>/dev/null
log "daemon up (pid=$$)"

# 按「保持运行」开关决定用哪种方式执行脚本
start_script() {
    if [ "$1" = "1" ]; then
        run_user_script_hold
    else
        run_user_script
    fi
}

run_flow() {
    local pkg="$1" delay order keep mode sp

    mode=$(get_conf input_mode.txt screen | tr -d '\r\n')
    [ -z "$mode" ] && mode="screen"

    # 模式一：把预输入内容喂给脚本（互动式脚本），不做任何屏幕模拟输入
    if [ "$mode" != "screen" ]; then
        sp=$(get_script_path)
        if [ -z "$sp" ] || [ ! -f "$sp" ]; then
            # 没有可用脚本时继续走脚本模式等于什么都不做，直接按屏幕输入执行
            log "mode=script 但没有可用脚本（'$sp'），自动改用屏幕输入"
            mode="screen"
        fi
    fi

    if [ "$mode" != "screen" ]; then
        delay=$(get_conf delay.txt 5 | tr -dc '0-9')
        [ -z "$delay" ] && delay=5
        log "== target: $pkg | mode=script | 进入后等待 ${delay}s 再执行脚本"
        run_script_feed
        log "== flow done（脚本已在后台运行，输入在后台喂入）"
        return 0
    fi

    # 模式二：把预输入内容模拟输入到当前界面
    delay=$(get_conf delay.txt 5 | tr -dc '0-9')
    [ -z "$delay" ] && delay=5
    order=$(get_conf order.txt script_first | tr -d '\r\n')
    [ -z "$order" ] && order="script_first"
    keep=$(get_conf keep_script.txt 1 | tr -d '\r\n')
    [ -z "$keep" ] && keep=1

    log "== target: $pkg | mode=screen | order=$order | wait=${delay}s | keep_script=$keep"

    if [ "$order" = "wait_first" ]; then
        # 备选：先等待 -> 脚本 -> 输入
        log "-- 先等待 ${delay}s（等界面加载）"
        sleep "$delay"
        start_script "$keep"
        sleep 1
    else
        # 默认：进入应用 -> 执行脚本 -> 等待 -> 输入
        start_script "$keep"
        if [ "$delay" -gt 0 ]; then
            log "-- 脚本已启动，等待 ${delay}s 后开始输入"
            sleep "$delay"
        fi
    fi

    if ! input_focused; then
        log "!! 当前没有聚焦的输入框，input 注入可能不落字（先点一下目标输入框）"
    fi
    log "-- 开始逐条输入（第 1 条立即输入）"
    do_input

    # 输入全部完成后，才让脚本进程退出
    release_user_script
    log "== flow done"
}

FIRED=""        # 当前已触发过的目标应用
AWAY=0          # 连续看到非目标应用的次数
LAST_PKG=""     # 上次触发过的应用
LAST_TS=0       # 上次触发结束的时间戳（秒）

# daemon 被正常信号结束时，别把脚本进程留在后台
cleanup() {
    release_user_script 2>/dev/null
    [ "$(cat "$LOCK_DIR/pid" 2>/dev/null | tr -d '\r\n')" = "$$" ] && rm -rf "$LOCK_DIR" 2>/dev/null
    log "daemon down (pid=$$)"
}
trap cleanup INT TERM HUP

while true; do
    if [ "$(get_conf status 1)" != "1" ]; then
        FIRED=""
        AWAY=0
        sleep 2
        continue
    fi

    CUR=$(get_foreground_pkg)

    # 取不到 / 输入法、系统 UI、权限弹窗这类噪音：保持状态不变，避免抖动误判
    if [ -z "$CUR" ] || is_noise_pkg "$CUR"; then
        sleep 1
        continue
    fi

    if [ "$CUR" = "$FIRED" ]; then
        AWAY=0
        sleep 1
        continue
    fi

    if is_target "$CUR"; then
        # 冷却：同一应用在最小间隔内不重复执行
        ignore=$(get_conf min_interval.txt 8 | tr -dc '0-9')
        [ -z "$ignore" ] && ignore=8
        now=$(date +%s 2>/dev/null)
        [ -z "$now" ] && now=0
        gap=$((now - LAST_TS))

        if [ "$CUR" = "$LAST_PKG" ] && [ "$ignore" -gt 0 ] && [ "$gap" -lt "$ignore" ]; then
            log "skip: $CUR 距上次触发仅 ${gap}s（最小间隔 ${ignore}s）"
            FIRED="$CUR"
            AWAY=0
            sleep 1
            continue
        fi

        run_flow "$CUR"
        FIRED="$CUR"
        LAST_PKG="$CUR"
        LAST_TS=$(date +%s 2>/dev/null)
        [ -z "$LAST_TS" ] && LAST_TS=0
        AWAY=0
        continue
    fi

    # 非目标应用：连续 3 次（≈3 秒）确认离开后才重置，一次抖动不算离开
    AWAY=$((AWAY + 1))
    if [ "$AWAY" -ge 3 ] && [ -n "$FIRED" ]; then
        log "离开已确认: $FIRED -> $CUR"
        FIRED=""
        AWAY=0
    fi
    sleep 1
done
