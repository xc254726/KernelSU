#!/system/bin/sh
# Chen 找回守护 —— 完全不依赖 Chen 本体的 root 守护
#
# 为什么要有它：Chen 被卸载之后，手机里没有任何应用能收到拨号暗号 / 广播，
# 所以「怎么把 Chen 静默装回来」只能靠一个不依赖任何 app 的 root 进程。
# 这个脚本就是以 root 常驻的那个进程：
#
#   开机      /data/adb/service.d/chen-guard.sh  ->  guard.sh start（init 侧，root，
#             和 Chen 的进程树无关，Chen 被卸载、被划掉都不影响它）
#   触发      前台应用 == TARGET，按配置等音量键（或直接触发）
#   动作      pm install 把 chen.apk 静默装回 -> 恢复应用数据 -> am start 跳到 Chen
#
# 配置：/data/adb/chen_guard/conf
#   TARGET=com.xxx         触发用的应用（用户自己选）
#   TRIGGER=volup|voldown|any|auto
#   ENABLED=1
#   PKG=me.weishu.kernelsu 要装回来的包
#   MAIN=包名/.GateActivity 装回来之后拉起的组件
#
# 命令：
#   start   后台常驻（已在跑则忽略）
#   run     前台主循环（service.d 内部用）
#   once    立刻执行一次找回，打完日志退出（app 里的「测试找回」）
#   stop    停止守护
#   status  打印状态
#   uninstall 立即备份数据并卸载 Chen（app 通过 uninstall.req 触发，也可手动跑）

MODDIR=/data/adb/chen_guard
CONF="$MODDIR/conf"
APK="$MODDIR/chen.apk"
LOG="$MODDIR/guard.log"
BAK="$MODDIR/data"
RUNDIR="$MODDIR/run"
PIDFILE="$RUNDIR/pid"

export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:$PATH

mkdir -p "$MODDIR" "$RUNDIR" 2>/dev/null

# ---------------------------------------------------------------- 基础

log() {
    # 日志上限 200KB，超了截断一半，避免长期常驻把 /data 撑大
    if [ -f "$LOG" ]; then
        sz=$(wc -c < "$LOG" 2>/dev/null | tr -dc '0-9')
        [ -n "$sz" ] && [ "$sz" -gt 204800 ] && tail -c 102400 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
    fi
    echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null
}

cfg() {
    TARGET=$(sed -n 's/^TARGET=//p' "$CONF" 2>/dev/null | head -n 1 | tr -d '\r')
    TRIGGER=$(sed -n 's/^TRIGGER=//p' "$CONF" 2>/dev/null | head -n 1 | tr -d '\r')
    ENABLED=$(sed -n 's/^ENABLED=//p' "$CONF" 2>/dev/null | head -n 1 | tr -d '\r')
    PKG=$(sed -n 's/^PKG=//p' "$CONF" 2>/dev/null | head -n 1 | tr -d '\r')
    MAIN=$(sed -n 's/^MAIN=//p' "$CONF" 2>/dev/null | head -n 1 | tr -d '\r')
    [ -z "$PKG" ] && PKG=me.weishu.kernelsu
    [ -z "$MAIN" ] && MAIN="$PKG/.GateActivity"
    [ -z "$TRIGGER" ] && TRIGGER=volup
    [ -z "$ENABLED" ] && ENABLED=1
}

# cgroup 路径是否还在普通应用的 uid 子树里（uid 10000~99999）；
# uid_1000 是 system，不能前缀匹配成 app
in_app_cgroup() {
    echo "$1" | grep -qE '/uid_[1-9][0-9]{4}(/|$)'
}

# 把进程从 app 的 cgroup 挪到 root 侧：否则卸载 Chen / 清理后台时整棵子树被冻结或清掉，
# 表现就是「卸载完就不管用了」
escape_app_cgroup() {
    local pid="$1" cur d after try
    [ -n "$pid" ] || pid=$$
    [ -r "/proc/$pid/cgroup" ] || return 1
    cur=$(cat "/proc/$pid/cgroup" 2>/dev/null)
    if ! in_app_cgroup "$cur"; then
        echo -1000 > "/proc/$pid/oom_score_adj" 2>/dev/null
        return 0
    fi
    mkdir -p /sys/fs/cgroup/uid_0 /sys/fs/cgroup/system 2>/dev/null
    for try in 1 2 3; do
        for d in /sys/fs/cgroup/uid_0 /sys/fs/cgroup/system /sys/fs/cgroup/init /sys/fs/cgroup; do
            [ -e "$d/cgroup.procs" ] || continue
            echo "$pid" > "$d/cgroup.procs" 2>/dev/null || continue
            after=$(cat "/proc/$pid/cgroup" 2>/dev/null)
            if ! in_app_cgroup "$after"; then
                echo -1000 > "/proc/$pid/oom_score_adj" 2>/dev/null
                return 0
            fi
        done
        sleep 1
    done
    return 1
}

pid_alive() {
    local p
    p=$(cat "$PIDFILE" 2>/dev/null | tr -dc '0-9')
    [ -n "$p" ] || return 1
    [ -d "/proc/$p" ] || return 1
    grep -q 'guard.sh' "/proc/$p/cmdline" 2>/dev/null
}

# ---------------------------------------------------------------- 前台应用

is_valid_pkg() {
    echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'
}

parse_pkg_line() {
    sed -nE \
        -e 's/.*[[:space:]]u[0-9]+[[:space:]]+([A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+)\/[^ ]*.*/\1/p' \
        -e 's/.*ACTIVITY[[:space:]]+([A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+)\/[^ ]*.*/\1/p' \
        | head -n 1
}

# 和 autoinput 一样的三级兜底：window（输出最小）-> activities -> top
# 每条都套 timeout：dumpsys 卡住也不能把守护挂死
fg_pkg() {
    local pkg=""
    pkg=$(timeout 3 dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | parse_pkg_line)
    if ! is_valid_pkg "$pkg"; then
        pkg=$(timeout 5 dumpsys activity activities 2>/dev/null \
            | grep -m1 -E 'topResumedActivity|mResumedActivity' | parse_pkg_line)
    fi
    if ! is_valid_pkg "$pkg"; then
        pkg=$(timeout 5 dumpsys activity top 2>/dev/null | grep -m1 'ACTIVITY' | parse_pkg_line)
    fi
    is_valid_pkg "$pkg" || pkg=""
    printf '%s' "$pkg"
}

# ---------------------------------------------------------------- 音量键

# 当前媒体音量（getevent 被缓冲时的兜底判据）
music_vol() {
    settings get system volume_music_speaker 2>/dev/null | tr -dc '0-9'
}

# 等一次音量键。
#   $1 = volup | voldown | any        $2 = 超时秒数
# 返回 0 = 按到了
#
# 双通道：
#   1) getevent 实时读按键（能区分上下）
#   2) getevent 若被 libc 缓冲住看不到输出，就用媒体音量是否变化来判（变化了也算）
key_gate() {
    local mode="$1" secs="$2" tmp devs d v0 v1 hit i total pids p
    tmp="$RUNDIR/keys.txt"
    : > "$tmp" 2>/dev/null
    devs=""
    for d in /dev/input/event*; do
        [ -e "$d" ] || continue
        if timeout 3 getevent -lp "$d" 2>/dev/null | grep -q 'KEY_VOLUMEUP'; then
            devs="$devs $d"
        fi
    done
    if [ -z "$devs" ]; then
        log "key_gate: 没找到带音量键的输入设备"
        sleep 3
        return 1
    fi

    v0=$(music_vol)
    pids=""
    for d in $devs; do
        timeout -s KILL "$secs" getevent -lt "$d" >> "$tmp" 2>/dev/null &
        pids="$pids $!"
    done

    hit=0
    total=$((secs * 2))
    i=0
    while [ "$i" -lt "$total" ]; do
        case "$mode" in
            volup)   grep -qE 'KEY_VOLUMEUP[[:space:]]+DOWN' "$tmp" 2>/dev/null && hit=1 ;;
            voldown) grep -qE 'KEY_VOLUMEDOWN[[:space:]]+DOWN' "$tmp" 2>/dev/null && hit=1 ;;
            *)       grep -qE 'KEY_VOLUME(UP|DOWN)[[:space:]]+DOWN' "$tmp" 2>/dev/null && hit=1 ;;
        esac
        if [ "$hit" != "1" ] && [ -n "$v0" ] && [ $((i % 2)) -eq 0 ]; then
            v1=$(music_vol)
            if [ -n "$v1" ] && [ "$v1" != "$v0" ]; then
                log "key_gate: 音量变化 $v0 -> $v1（getevent 无输出，按音量键判）"
                hit=1
            fi
        fi
        [ "$hit" = "1" ] && break
        # 人已经离开目标应用就不再等（每 ~3 秒查一次，避免反复 dump 系统状态）
        if [ $((i % 6)) -eq 0 ] && [ "$i" -gt 0 ]; then
            [ "$(fg_pkg)" != "$TARGET" ] && break
        fi
        sleep 0.5
        i=$((i + 1))
    done

    # 收掉还挂着的 getevent，别让它在后台继续读输入设备
    pkill -f 'getevent -lt' 2>/dev/null
    for p in $pids; do kill -9 "$p" 2>/dev/null; done
    [ "$hit" = "1" ]
}

# ---------------------------------------------------------------- 装回来

install_payload() {
    local old rc
    [ -f "$APK" ] || { log "install: 找不到 $APK"; return 1; }

    # ColorOS 的安装校验有时会拦住 root/pm 的静默安装，先临时关掉，装完还原
    old=$(settings get global verifier_verify_adb_installs 2>/dev/null | tr -d '\r')
    if [ "$old" != "0" ]; then
        settings put global verifier_verify_adb_installs 0 2>/dev/null
    fi

    if [ -f "$MODDIR/split1.apk" ]; then
        set -- "$APK"
        for d in "$MODDIR"/split*.apk; do
            [ -f "$d" ] && set -- "$@" "$d"
        done
        pm install-multiple -r -d -g --user 0 "$@" >>"$LOG" 2>&1
        rc=$?
    else
        pm install -r -d -g --user 0 "$APK" >>"$LOG" 2>&1
        rc=$?
        if [ "$rc" != "0" ]; then
            # 老版本 pm 不认 --user，退回最简形式
            pm install -r -d "$APK" >>"$LOG" 2>&1
            rc=$?
        fi
    fi

    if [ -n "$old" ] && [ "$old" != "0" ]; then
        settings put global verifier_verify_adb_installs "$old" 2>/dev/null
    fi

    sleep 1
    if pm path "$PKG" >/dev/null 2>&1; then
        log "install: ok (rc=$rc)"
        return 0
    fi
    log "install: 失败 (rc=$rc)，详见上面 pm 输出"
    return 1
}

# 恢复卸载前备份的应用数据（激活状态、设置都在这）
restore_data() {
    local uid
    [ -d "$BAK" ] || return 0
    [ -d "/data/data/$PKG" ] || mkdir -p "/data/data/$PKG" 2>/dev/null
    uid=$(stat -c %u "/data/data/$PKG" 2>/dev/null)
    case "$uid" in ''|*[!0-9]*) uid="" ;; esac
    if [ -z "$uid" ]; then
        uid=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 'userId=' | tr -dc '0-9')
    fi
    if [ -z "$uid" ]; then
        log "restore_data: 拿不到 uid，跳过（Chen 会像首次安装一样干净）"
        return 0
    fi
    cp -a "$BAK/." "/data/data/$PKG/" 2>>"$LOG"
    chown -R "$uid:$uid" "/data/data/$PKG" 2>/dev/null
    chmod 700 "/data/data/$PKG" 2>/dev/null
    restorecon -R "/data/data/$PKG" >/dev/null 2>&1
    log "restore_data: 已恢复应用数据 (uid=$uid)"
}

launch_chen() {
    local i
    i=0
    while [ "$i" -lt 3 ]; do
        am start --user 0 -n "$MAIN" >>"$LOG" 2>&1 && return 0
        sleep 1
        i=$((i + 1))
    done
    # 退路：按 LAUNCHER 意图拉起
    am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >>"$LOG" 2>&1
}

restore() {
    local why="$1"
    log "restore: 触发（$why）"
    if ! pm path "$PKG" >/dev/null 2>&1; then
        install_payload || return 1
        restore_data
    fi
    # 以前开过「隐藏桌面图标」的话，入口组件是禁用的，先放开
    pm enable "$PKG" >/dev/null 2>&1
    pm enable "$MAIN" >/dev/null 2>&1
    launch_chen && log "restore: 已拉起 Chen"
    return 0
}

# ---------------------------------------------------------------- 卸载（备份数据）

do_uninstall() {
    local rc
    rm -rf "$BAK" 2>/dev/null
    mkdir -p "$BAK" 2>/dev/null
    if [ -d "/data/data/$PKG" ]; then
        cp -a "/data/data/$PKG/." "$BAK/" 2>>"$LOG"
        log "uninstall: 已备份数据 $(du -sk "$BAK" 2>/dev/null | awk '{print $1}')KB"
    fi
    pm uninstall --user 0 "$PKG" >>"$LOG" 2>&1
    rc=$?
    if [ "$rc" != "0" ]; then
        pm uninstall "$PKG" >>"$LOG" 2>&1
        rc=$?
    fi
    if pm path "$PKG" >/dev/null 2>&1; then
        log "uninstall: 失败 (rc=$rc)"
    else
        log "uninstall: ok，Chen 已卸载，守护继续运行"
    fi
    rm -f "$MODDIR/uninstall.req" 2>/dev/null
}

# ---------------------------------------------------------------- 主循环

run_loop() {
    local in_target cur
    trap '' HUP INT TERM QUIT
    escape_app_cgroup "$$" || log "run: [警告] 仍在 app cgroup，卸载 Chen 后可能被一起清掉"
    echo "$$" > "$PIDFILE" 2>/dev/null
    log "guard up (pid=$$)"

    in_target=0
    while :; do
        cfg

        # app 点了「现在隐藏」：先备份数据再卸载自己
        if [ -f "$MODDIR/uninstall.req" ]; then
            sleep 2
            log "收到卸载请求"
            do_uninstall
            in_target=0
            sleep 1
            continue
        fi

        if [ "$ENABLED" != "1" ] || [ -z "$TARGET" ]; then
            in_target=0
            sleep 3
            continue
        fi

        cur=$(fg_pkg)
        if [ "$cur" != "$TARGET" ]; then
            in_target=0
            sleep 2
            continue
        fi

        if [ "$in_target" = "1" ]; then
            sleep 2
            continue
        fi

        case "$TRIGGER" in
            auto)
                restore "auto: $cur"
                ;;
            *)
                if key_gate "$TRIGGER" 12; then
                    restore "key($TRIGGER): $cur"
                fi
                ;;
        esac

        # 装回来了才算「已处理」，等用户离开这个应用；装失败则退避 20 秒再试，
        # 否则会每秒重试一次，日志刷爆、system_server 也被拖住
        if pm path "$PKG" >/dev/null 2>&1; then
            in_target=1
        else
            in_target=0
            sleep 20
        fi
    done
}

# ---------------------------------------------------------------- 命令

cmd_start() {
    if pid_alive; then
        echo "already running (pid=$(cat "$PIDFILE" 2>/dev/null))"
        return 0
    fi
    # 脱离调用者的会话/进程组：app 被杀、被卸载都带不走它
    if command -v setsid >/dev/null 2>&1; then
        setsid sh "$0" run </dev/null >/dev/null 2>&1 &
    else
        nohup sh "$0" run </dev/null >/dev/null 2>&1 &
    fi
    sleep 1
    if pid_alive; then
        echo "started (pid=$(cat "$PIDFILE" 2>/dev/null))"
    else
        echo "start failed"
        return 1
    fi
}

cmd_stop() {
    local p
    p=$(cat "$PIDFILE" 2>/dev/null | tr -dc '0-9')
    if [ -n "$p" ]; then
        kill -9 "$p" 2>/dev/null
        log "stop: killed $p"
    fi
    # 顺手收掉可能残留的 getevent
    pkill -f 'getevent -lt' 2>/dev/null
    rm -f "$PIDFILE" 2>/dev/null
    echo stopped
}

cmd_status() {
    cfg
    echo "PID=$(cat "$PIDFILE" 2>/dev/null | tr -dc '0-9')"
    pid_alive && echo "RUNNING=1" || echo "RUNNING=0"
    echo "TARGET=$TARGET"
    echo "TRIGGER=$TRIGGER"
    echo "ENABLED=$ENABLED"
    echo "INSTALLED=$(pm path "$PKG" >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "APK=$([ -f "$APK" ] && echo 1 || echo 0)"
    echo "BACKUP=$([ -d "$BAK" ] && echo 1 || echo 0)"
    echo "--- log ---"
    tail -n 8 "$LOG" 2>/dev/null
}

case "$1" in
    start) cmd_start ;;
    stop) cmd_stop ;;
    run) run_loop ;;
    status) cmd_status ;;
    uninstall) cfg; do_uninstall ;;
    once)
        cfg
        restore "manual-test"
        echo "--- result ---"
        pm path "$PKG" >/dev/null 2>&1 && echo "INSTALLED=1" || echo "INSTALLED=0"
        tail -n 6 "$LOG" 2>/dev/null
        ;;
    *)
        echo "usage: guard.sh start|run|stop|status|once|uninstall"
        ;;
esac
