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
# 版本号：界面会读这个值，用来确认 /data/adb 里跑的是不是最新脚本
GUARD_VER=4
CONF="$MODDIR/conf"
APK="$MODDIR/chen.apk"
LOG="$MODDIR/guard.log"
UNLOG="$MODDIR/uninstall.log"
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

    HOLD=$(sed -n 's/^HOLD=//p' "$CONF" 2>/dev/null | head -n 1 | tr -dc '0-9')
    WAIT=$(sed -n 's/^WAIT=//p' "$CONF" 2>/dev/null | head -n 1 | tr -dc '0-9')
    [ -z "$HOLD" ] && HOLD=3
    [ -z "$WAIT" ] && WAIT=10
    # 夹在合理区间，防止 conf 被写成 0 或者 99999 把守护卡死
    [ "$HOLD" -lt 1 ] && HOLD=1
    [ "$HOLD" -gt 20 ] && HOLD=20
    [ "$WAIT" -lt 1 ] && WAIT=1
    [ "$WAIT" -gt 120 ] && WAIT=120
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
# 长按判定：目标音量键必须按住 hold 秒才算触发。
# getevent 里该键最后一条事件还是 DOWN（说明没松手），且从首次 DOWN 起已过 hold 秒。
key_gate() {
    local mode="$1" hold="$2"
    local tmp devs d pids p pat hit i total v0 v1 vchg last down_at saw_key
    case "$hold" in ''|*[!0-9]*) hold=3 ;; esac
    [ "$hold" -lt 1 ] && hold=1

    case "$mode" in
        volup)   pat='KEY_VOLUMEUP[[:space:]]+(DOWN|UP)' ;;
        voldown) pat='KEY_VOLUMEDOWN[[:space:]]+(DOWN|UP)' ;;
        *)       pat='KEY_VOLUME(UP|DOWN)[[:space:]]+(DOWN|UP)' ;;
    esac

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
        timeout -s KILL "$((hold + 14))" getevent -lt "$d" >> "$tmp" 2>/dev/null &
        pids="$pids $!"
    done

    hit=0
    saw_key=0
    vchg=0
    down_at=-1
    total=$(( (hold + 12) * 2 ))
    i=0
    while [ "$i" -lt "$total" ]; do
        last=$(grep -E "$pat" "$tmp" 2>/dev/null | tr -d '\r' | sed 's/[[:space:]]*$//' | tail -n 1)
        if [ -n "$last" ]; then
            saw_key=1
            case "$last" in
                *DOWN)
                    if [ "$down_at" -lt 0 ]; then
                        down_at=$i
                        log "key_gate: 按下，需要按住 ${hold}s"
                    fi
                    if [ $((i - down_at)) -ge $((hold * 2)) ]; then
                        hit=1
                        log "key_gate: 长按 ${hold}s 达成"
                        break
                    fi
                    ;;
                *UP)
                    if [ "$down_at" -ge 0 ]; then
                        log "key_gate: 只按了 $(( (i - down_at) / 2 ))s，没到 ${hold}s，重新计"
                    fi
                    down_at=-1
                    ;;
            esac
        fi

        # 退路：这台机器 getevent 读不到按键时，用「媒体音量连续变化」近似长按。
        # 一旦 getevent 有过任何按键记录就不再走这条，免得点一下就误判。
        if [ "$hit" != "1" ] && [ "$saw_key" = "0" ] && [ -n "$v0" ] && [ $((i % 2)) -eq 0 ]; then
            v1=$(music_vol)
            if [ -n "$v1" ] && [ "$v1" != "$v0" ]; then
                vchg=$((vchg + 1))
                v0=$v1
                if [ "$vchg" -ge $((hold * 2)) ]; then
                    log "key_gate: 音量连续变化 ${vchg} 次（getevent 无输出），按长按处理"
                    hit=1
                    break
                fi
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

# ---------------------------------------------------------------- 包状态

# 包对 user 0 是否「看得见」。
# 不能用 pm path 判断：hide / disable-user 之后 pm path 照样有输出，
# 但桌面、应用列表、搜索里都已经没有了——那才是用户要的「删掉」。
pkg_visible() {
    pm list packages -e --user 0 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"
}

# 跑一条包管理命令，成功且包从用户视角消失才算通过。
# 每条都套 timeout：某些 ROM 上 pm 会卡在 binder 里几十秒，把界面拖死。
# 原始输出（含 exception）全量落到 uninstall.log，失败时才有东西可查。
try_pm() {
    local rc out
    out=$(timeout 12 "$@" 2>&1)
    rc=$?
    { printf '%% %s\nrc=%s\n%s\n\n' "$*" "$rc" "$out"; } >> "$UNLOG" 2>/dev/null
    # 卸载是异步的，PMS 落盘/广播要一点时间，立刻查会读旧状态
    sleep 1
    if [ "$rc" = "0" ] && ! pkg_visible; then
        log "pm: 成功 ← $*"
        return 0
    fi
    log "pm: 失败 rc=$rc ← $* $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-120)"
    return 1
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
    if pkg_visible; then
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

# am start 失败时退出码常常也是 0，只能看输出里有没有 "Starting:"
try_start() {
    local out
    out=$("$@" 2>&1)
    [ -n "$out" ] && printf '%s\n' "$out" >> "$LOG"
    case "$out" in
        *Starting*) return 0 ;;
    esac
    return 1
}

launch_chen() {
    local i
    i=0
    while [ "$i" -lt 3 ]; do
        # 依次换入口：am --user / am（默认当前用户）/ cmd activity，某些 ROM 只认其中一种
        try_start am start --user 0 -n "$MAIN" && return 0
        try_start am start -n "$MAIN" && return 0
        try_start cmd activity start-activity --user 0 -n "$MAIN" && return 0
        sleep 1
        i=$((i + 1))
    done
    # 退路：按 LAUNCHER 意图拉起 / monkey
    try_start am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" && return 0
    monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >>"$LOG" 2>&1
}

restore() {
    local why="$1" mode
    log "restore: 触发（$why）"
    mode=$(cat "$MODDIR/lastmode" 2>/dev/null | tr -d '\r\n')
    if ! pkg_visible; then
        # 可能只是被 hide / disable-user 藏起来了，先放出来，省一次重装
        pm unhide --user 0 "$PKG" >/dev/null 2>&1
        pm enable --user 0 "$PKG" >/dev/null 2>&1
        if pkg_visible; then
            log "restore: 包还在（$mode），已解除隐藏，数据未动"
        else
            install_payload || return 1
            restore_data
        fi
    fi
    # 以前开过「隐藏桌面图标」的话，入口组件是禁用的，先放开
    pm enable "$PKG" >/dev/null 2>&1
    pm enable "$MAIN" >/dev/null 2>&1
    launch_chen && log "restore: 已拉起 Chen"
    return 0
}

# ---------------------------------------------------------------- 卸载（备份数据）

do_uninstall() {
    local mode="" why=""
    : > "$UNLOG" 2>/dev/null
    rm -rf "$BAK" 2>/dev/null
    mkdir -p "$BAK" 2>/dev/null
    if [ -d "/data/data/$PKG" ]; then
        cp -a "/data/data/$PKG/." "$BAK/" 2>>"$UNLOG"
        log "uninstall: 已备份数据 $(du -sk "$BAK" 2>/dev/null | awk '{print $1}')KB"
    fi

    # 关键一步：先停掉 Chen 自己。
    # 上一版在 Chen 还活着的时候直接 pm uninstall，PMS 回的是
    # 「Failed transaction (EX_BAD_PARCELABLE)」——包正持有 PackageInstaller
    # 会话 / 正被 binder 引用时，deletePackage 会在中途抛出来，两条卸载命令全废。
    am force-stop "$PKG" >/dev/null 2>&1
    am force-stop "$MAIN" >/dev/null 2>&1
    sleep 2

    if pkg_visible; then
        if   try_pm pm uninstall --user 0 "$PKG";          then mode="uninstall"
        elif try_pm cmd package uninstall --user 0 "$PKG"; then mode="uninstall"
        elif try_pm pm uninstall -k --user 0 "$PKG";       then mode="uninstall-keep"
        elif try_pm pm hide --user 0 "$PKG";               then mode="hide"
        elif try_pm pm disable-user --user 0 "$PKG";       then mode="disable"
        elif try_pm pm disable --user 0 "$PKG";            then mode="disable"
        fi
    fi

    if [ -n "$mode" ] && ! pkg_visible; then
        echo "$mode" > "$MODDIR/lastmode" 2>/dev/null
        printf 'RESULT=ok\nMODE=%s\nAT=%s\n' "$mode" "$(date '+%m-%d %H:%M:%S')" > "$MODDIR/uninstall.result" 2>/dev/null
        log "uninstall: ok（方式 $mode），Chen 已从桌面和应用列表消失，守护继续运行"
    else
        rm -f "$MODDIR/lastmode" 2>/dev/null
        # 从 uninstall.log 里挑最后一条带异常关键词的行，比 grep guard.log 准得多
        why=$(grep -aE 'Failure|Exception|Error|denied|not installed|Unknown|Failure \[' "$UNLOG" 2>/dev/null \
            | tail -n 1 | tr -d '\r' | cut -c1-160)
        [ -z "$why" ] && why="pm 全部非 0 且 Chen 仍可见"
        printf 'RESULT=fail\nWHY=%s\nAT=%s\n' "$why" "$(date '+%m-%d %H:%M:%S')" > "$MODDIR/uninstall.result" 2>/dev/null
        log "uninstall: 失败（$why）；Chen 仍可见，守护未改动"
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
                # 打开触发应用后先等 WAIT 秒：太快就把 Chen 拉起来会打断正在用的应用
                log "auto: 已进入 $cur，等待 ${WAIT}s"
                sleep "$WAIT"
                if [ "$(fg_pkg)" = "$TARGET" ]; then
                    restore "auto(${WAIT}s): $cur"
                else
                    log "auto: 等待期间离开了 $TARGET，取消本次"
                fi
                ;;
            *)
                if key_gate "$TRIGGER" "$HOLD"; then
                    restore "hold($TRIGGER,${HOLD}s): $cur"
                fi
                ;;
        esac

        # 装回来了才算「已处理」，等用户离开这个应用；装失败则退避 20 秒再试，
        # 否则会每秒重试一次，日志刷爆、system_server 也被拖住
        if pkg_visible; then
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
    restart) cmd_stop; sleep 1; cmd_start ;;
    stop) cmd_stop ;;
    run) run_loop ;;
    status) cmd_status ;;
    uninstall) cfg; do_uninstall ;;
    once)
        cfg
        if pkg_visible; then
            # 已经看得见，就不用重装，只验证能不能拉起来
            echo "VISIBLE=1"
            pm enable "$PKG" >/dev/null 2>&1
            pm enable "$MAIN" >/dev/null 2>&1
            launch_chen >/dev/null 2>&1
            echo "LAUNCHED=$?"
        else
            echo "VISIBLE=0"
            restore "manual-test"
            pkg_visible && echo "INSTALLED=1" || echo "INSTALLED=0"
        fi
        tail -n 6 "$LOG" 2>/dev/null
        ;;
    *)
        echo "usage: guard.sh start|run|stop|status|once|uninstall"
        ;;
esac
