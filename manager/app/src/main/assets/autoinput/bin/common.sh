#!/system/bin/sh
# Auto Input - 公共函数库（被 daemon.sh / test_input.sh 引用）

CONF=/data/adb/auto_input
LOG="$CONF/daemon.log"

# 守护进程由 setsid/nohup 从 root shell 拉起，PATH 未必完整；
# 缺了 /system/bin 就会找不到 dumpsys / input，表现为「能手动执行但不触发」
PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:${PATH}
export PATH

mkdir -p "$CONF" 2>/dev/null

log() {
    echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null
}

# 读取配置项：get_conf 文件名 默认值
get_conf() {
    local v=""
    [ -f "$CONF/$1" ] && v=$(cat "$CONF/$1" 2>/dev/null)
    [ -z "$v" ] && v="$2"
    printf '%s' "$v"
}

# 首次使用：把旧版模块目录里的配置迁移过来
migrate_conf() {
    local old="$1/config"
    [ -d "$old" ] || return 0
    [ -f "$CONF/pkg.txt" ] && return 0
    [ -f "$old/pkg.txt" ] && cp -f "$old/pkg.txt" "$CONF/pkg.txt" 2>/dev/null
    [ -f "$old/input.txt" ] && cp -f "$old/input.txt" "$CONF/input.txt" 2>/dev/null
    [ -f "$old/status" ] && cp -f "$old/status" "$CONF/status" 2>/dev/null
    log "config migrated from $old"
}

# 一次性迁移：把旧的默认顺序（先等待后脚本）改成新默认（先脚本后等待）
migrate_order() {
    local marker="$CONF/.order_v2" cur
    [ -f "$marker" ] && return 0

    cur=$(cat "$CONF/order.txt" 2>/dev/null | tr -d '\r\n')
    if [ -z "$cur" ] || [ "$cur" = "wait_first" ]; then
        echo "script_first" > "$CONF/order.txt" 2>/dev/null
        log "order migrated -> script_first"
    fi
    : > "$marker" 2>/dev/null
}

# 包名合法性校验
is_valid_pkg() {
    echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'
}

# 兜底：从系统事件日志里取最近一次 resume 的包名（只读一次，不常驻）
# 部分 ROM 在 root 域下 dumpsys 被拦或输出格式不认识，这时事件日志通常是可读的
fg_from_events() {
    local line pkg
    line=$(logcat -b events -d -t 80 2>/dev/null \
        | grep -E 'am_resume_activity|am_focused_activity|wm_on_resume_called' \
        | tail -n 1)
    [ -z "$line" ] && return 0
    pkg=$(printf '%s' "$line" \
        | sed -nE 's/.*[^A-Za-z0-9_.]([A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+)\/[^]]*.*/\1/p' \
        | head -n 1)
    is_valid_pkg "$pkg" || return 0
    printf '%s' "$pkg"
}

# 获取当前前台应用包名
get_foreground_pkg() {
    local pkg=""

    pkg=$(dumpsys activity activities 2>/dev/null \
        | grep -m1 'topResumedActivity' \
        | sed -E 's/.*u[0-9]+[[:space:]]+([A-Za-z0-9_.]+)\/[^ ]*.*/\1/')

    if ! is_valid_pkg "$pkg"; then
        pkg=$(dumpsys window 2>/dev/null \
            | grep -m1 'mCurrentFocus' \
            | sed -E 's/.*u[0-9]+[[:space:]]+([A-Za-z0-9_.]+)\/[^ }]*.*/\1/')
    fi

    # 两种 dumpsys 都拿不到时，才回头去翻一次事件日志（正常情况下不会走到这里）
    if ! is_valid_pkg "$pkg"; then
        pkg=$(fg_from_events)
    fi

    if ! is_valid_pkg "$pkg"; then
        pkg=""
    fi

    printf '%s' "$pkg"
}

# 判断包名是否在监听列表中
is_target() {
    local pkg="$1" t
    [ -f "$CONF/pkg.txt" ] || return 1
    while IFS= read -r t; do
        t=$(printf '%s' "$t" | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
        [ -z "$t" ] && continue
        [ "$t" = "$pkg" ] && return 0
    done < "$CONF/pkg.txt"
    return 1
}

# 前台包名“噪音”：输入法、系统 UI、权限弹窗等
# 出现它们并不代表用户离开了目标应用，不能因此重置触发状态
is_noise_pkg() {
    case "$1" in
        ''|android) return 0 ;;
        com.android.systemui) return 0 ;;
        *permissioncontroller*) return 0 ;;
        com.android.packageinstaller) return 0 ;;
        *inputmethod*|*InputMethod*|*IME) return 0 ;;
        com.android.adbkeyboard|*AdbKeyboard*) return 0 ;;
    esac
    return 1
}

# 进程是否真的活着（僵尸进程 / 已退出都算死）
proc_alive() {
    local p="$1" st
    [ -n "$p" ] || return 1
    [ -d "/proc/$p" ] || return 1
    st=$(sed -n 's/^.*) \(.\).*/\1/p' "/proc/$p/stat" 2>/dev/null)
    [ "$st" = "Z" ] && return 1
    return 0
}

# 读脚本路径配置（去空白/换行）
get_script_path() {
    local sp
    sp=$(get_conf script_path.txt "")
    sp=$(printf '%s' "$sp" | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    printf '%s' "$sp"
}

# 按 shebang 选解释器：脚本声明 bash 且系统真有 bash 就用 bash，否则退回 sh
# （很多脚本写 #!/bin/bash，Android 默认没有 bash，用 sh 跑会在某些语法/内建上出错）
pick_shell() {
    local sp="$1" first
    first=$(head -n 1 "$sp" 2>/dev/null | tr -d '\r')
    case "$first" in
        *bash*)
            if command -v bash >/dev/null 2>&1; then
                command -v bash
                return 0
            fi
            ;;
    esac
    printf 'sh'
}

# 执行用户指定的 sh 脚本（同步：等脚本整段跑完才返回）
run_user_script() {
    local sp shbin
    sp=$(get_script_path)
    if [ -z "$sp" ]; then
        log "no script path configured, skip"
        return 0
    fi
    if [ -f "$sp" ]; then
        shbin=$(pick_shell "$sp")
        log "run script: $sp (shell=$shbin)"
        "$shbin" "$sp" </dev/null >> "$LOG" 2>&1
        log "script exit code: $?"
    else
        log "script not found: $sp"
    fi
}

# 执行用户脚本（异步 + 执行完保持存活）
# 脚本里的命令跑完后进程不退出，等预输入全部完成后由 release_user_script 放行
SCRIPT_HOLD_PID=""
run_user_script_hold() {
    local sp shbin
    sp=$(get_script_path)
    shbin=$(pick_shell "$sp")
    SCRIPT_HOLD_PID=""

    if [ -z "$sp" ]; then
        log "no script path configured, skip"
        return 0
    fi
    if [ ! -f "$sp" ]; then
        log "script not found: $sp"
        return 0
    fi

    rm -f "$CONF/.script_release" "$CONF/.script_child" "$CONF/.script_hold_pid" 2>/dev/null
    log "run script（执行完不退出，等输入完成）: $sp (shell=$shbin)"

    (
        holder="${DAEMON_PID:-0}"
        "$shbin" "$sp" </dev/null >> "$LOG" 2>&1 &
        child=$!
        echo "$child" > "$CONF/.script_child" 2>/dev/null
        wait "$child"
        rc=$?
        log "script body exit=$rc | 脚本进程保持存活，等输入完成后再退出"

        n=0
        while [ ! -f "$CONF/.script_release" ]; do
            sleep 0.5
            n=$((n + 1))
            # 每 10 秒确认一次 daemon 还在，daemon 挂了就不必一直挂着
            if [ $((n % 20)) -eq 0 ] && [ "$holder" != "0" ]; then
                if ! proc_alive "$holder"; then
                    log "daemon 已退出，脚本进程随之退出"
                    break
                fi
            fi
            if [ "$n" -ge 1200 ]; then
                log "保持存活超过 600s，自动退出"
                break
            fi
        done
        log "脚本进程退出（输入已完成）"
        exit 0
    ) &

    SCRIPT_HOLD_PID=$!
    echo "$SCRIPT_HOLD_PID" > "$CONF/.script_hold_pid" 2>/dev/null
    log "script holder pid=$SCRIPT_HOLD_PID（脚本执行完会继续存活）"
    return 0
}

# 输入完成后放行脚本进程：先让它自然退出，超时再强制结束
release_user_script() {
    local p child n=0
    p="$SCRIPT_HOLD_PID"
    [ -z "$p" ] && p=$(cat "$CONF/.script_hold_pid" 2>/dev/null | tr -d '\r\n')
    if [ -z "$p" ]; then
        return 0
    fi

    log "输入已完成，释放脚本进程 pid=$p"
    : > "$CONF/.script_release" 2>/dev/null

    while [ "$n" -lt 10 ]; do
        proc_alive "$p" || break
        sleep 0.3
        n=$((n + 1))
    done

    if proc_alive "$p"; then
        # 脚本本体还在长跑：先收掉脚本本体，再收 holder
        child=$(cat "$CONF/.script_child" 2>/dev/null | tr -d '\r\n')
        if [ -n "$child" ] && proc_alive "$child"; then
            log "结束脚本本体 pid=$child"
            kill -TERM "$child" 2>/dev/null
            sleep 1
            proc_alive "$child" && kill -9 "$child" 2>/dev/null
        fi
        sleep 1
        if proc_alive "$p"; then
            kill -TERM "$p" 2>/dev/null
            sleep 0.5
            proc_alive "$p" && kill -9 "$p" 2>/dev/null
        fi
        log "脚本进程已强制结束"
    else
        log "脚本进程已正常退出"
    fi

    SCRIPT_HOLD_PID=""
    rm -f "$CONF/.script_release" "$CONF/.script_child" "$CONF/.script_hold_pid" 2>/dev/null
    return 0
}

# ---------- 输入相关 ----------

# 定位 input 命令（守护进程 PATH 可能不完整，必须兜底到绝对路径）
INPUT_BIN=""
resolve_input() {
    if [ -n "$INPUT_BIN" ] && { [ "$INPUT_BIN" = "input" ] || [ -x "$INPUT_BIN" ]; }; then
        printf '%s' "$INPUT_BIN"
        return 0
    fi
    if command -v input >/dev/null 2>&1; then
        INPUT_BIN="input"
    elif [ -x /system/bin/input ]; then
        INPUT_BIN="/system/bin/input"
    else
        INPUT_BIN=""
    fi
    printf '%s' "$INPUT_BIN"
}

# 是否安装了 ADBKeyboard（可输入中文）
ADBKB=""
has_adbkeyboard() {
    if [ -z "$ADBKB" ]; then
        if pm list packages 2>/dev/null | grep -q 'com.android.adbkeyboard'; then
            ADBKB=1
        else
            ADBKB=0
        fi
    fi
    [ "$ADBKB" = "1" ]
}

# 发送单行文本：优先 input text，失败则用 ADBKeyboard 广播
send_line() {
    local text="$1" idx="$2" bin rc out b64

    bin=$(resolve_input)
    if [ -z "$bin" ]; then
        log "input[$idx] FAIL: 找不到 input 命令"
        return 1
    fi

    # 策略 1：input text（引号整体传参，命令行内空格无需转义）
    out=$($bin text "$text" </dev/null 2>&1)
    rc=$?
    if [ "$rc" -eq 0 ] && [ -z "$out" ]; then
        log "input[$idx] ok(text): $text"
        return 0
    fi
    log "input[$idx] text 返回 rc=$rc out=$out"

    # 策略 2：ADBKeyboard（支持中文/特殊字符，装了才用）
    if has_adbkeyboard; then
        b64=$(printf '%s' "$text" | base64 2>/dev/null | tr -d '\r\n')
        if [ -n "$b64" ]; then
            out=$(am broadcast -a ADB_INPUT_B64 --es msg "$b64" </dev/null 2>&1)
            rc=$?
            if [ "$rc" -eq 0 ] && ! printf '%s' "$out" | grep -qi 'error\|exception'; then
                log "input[$idx] ok(adbkeyboard): $text"
                return 0
            fi
            log "input[$idx] adbkeyboard 失败 rc=$rc out=$out"
        fi
    fi

    log "input[$idx] FAIL: 该行未能输入 -> $text"
    return 1
}

# 按回车
# 不同 ROM / 输入法对回车键反应不一致，这里依次试 KEYCODE_ENTER(66)、字面量 KEYCODE_ENTER、小键盘回车(160)
# enter_count.txt 控制每行按几次（中文输入法常把第一次回车用来确认候选词，需要两次）
send_enter() {
    local idx="$1" bin out rc ok key i count

    bin=$(resolve_input)
    if [ -z "$bin" ]; then
        log "enter[$idx] FAIL: 找不到 input 命令"
        return 1
    fi

    count=$(get_conf enter_count.txt 1 | tr -dc '0-9')
    [ -z "$count" ] && count=1
    [ "$count" -lt 1 ] && count=1
    [ "$count" -gt 3 ] && count=3

    i=0
    while [ "$i" -lt "$count" ]; do
        i=$((i + 1))
        ok=0
        for key in 66 KEYCODE_ENTER 160; do
            out=$($bin keyevent "$key" </dev/null 2>&1)
            rc=$?
            if [ "$rc" -eq 0 ] && ! printf '%s' "$out" | grep -qiE 'error|exception|failed|denial'; then
                ok=1
                log "enter[$idx] ok（第 $i/$count 次，key=$key）"
                break
            fi
            log "enter[$idx] key=$key 失败 rc=$rc out=$(printf '%s' "$out" | tr -d '\r\n' | cut -c1-120)"
        done
        if [ "$ok" -ne 1 ]; then
            log "enter[$idx] FAIL: 三种回车键都没能注入"
            return 1
        fi
        [ "$i" -lt "$count" ] && sleep 0.4
    done
    return 0
}

# 逐条输入预置内容
# 时序：第 1 条立即输入 + 回车 -> 等待 N 秒 -> 第 2 条 + 回车 -> 等待 N 秒 -> … -> 最后一条回车后结束
do_input() {
    local f="$CONF/input.txt"
    if [ ! -f "$f" ]; then
        log "input: 没有 input.txt，跳过"
        return 1
    fi

    local bin
    bin=$(resolve_input)
    if [ -z "$bin" ]; then
        log "input: 找不到 input 命令，无法输入"
        return 1
    fi

    local total idx=0 line rc speed okc=0 failc=0 efail=0
    speed=$(get_conf speed.txt 3 | tr -dc '0-9')
    [ -z "$speed" ] && speed=3
    [ "$speed" -gt 60 ] && speed=60

    total=$(grep -c '[^[:space:]]' "$f" 2>/dev/null)
    [ -z "$total" ] && total=0
    log "input: 共 ${total} 条 | 第 1 条立即输入 | 之后每条间隔 ${speed}s | bin=$bin"

    # 用独立 fd 读文件，避免 input 命令读走 stdin 影响循环
    exec 9< "$f"
    while IFS= read -r line <&9 || [ -n "$line" ]; do
        line=$(printf '%s' "$line" | tr -d '\r')
        [ -z "$line" ] && continue

        idx=$((idx + 1))
        if send_line "$line" "$idx"; then
            okc=$((okc + 1))
        else
            failc=$((failc + 1))
        fi
        sleep 0.25
        if send_enter "$idx"; then
            :
        else
            efail=$((efail + 1))
        fi

        # 最后一条不再多等
        if [ "$idx" -lt "$total" ]; then
            log "input[$idx] 已输入并回车，等待 ${speed}s 后输入第 $((idx + 1)) 条"
            sleep "$speed"
        else
            log "input[$idx] 已输入并回车（最后一条，本次结束）"
        fi
    done
    exec 9<&-

    log "input: 完成，共 $idx 条（文字成功 $okc / 失败 $failc，回车失败 $efail）"
    echo "DO_INPUT ok=$okc fail=$failc enter_fail=$efail total=$idx"
    [ "$idx" -gt 0 ]
}


# ---------- 脚本 stdin 喂入（互动式脚本，如「是否读取上次配置? [y/n]:」） ----------

SCRIPT_FEED_PID=""

# 进入应用后等待 delay 秒，执行脚本，并把预输入内容逐行写入脚本 stdin（每行=一次回车）
# 脚本在后台运行，喂完输入即结束，不做任何屏幕模拟输入
run_script_feed() {
    local sp delay speed total n=0 line shbin

    sp=$(get_script_path)
    if [ -z "$sp" ]; then
        log "no script path configured, skip"
        return 0
    fi
    if [ ! -f "$sp" ]; then
        log "script not found: $sp"
        return 0
    fi

    delay=$(get_conf delay.txt 5 | tr -dc '0-9')
    [ -z "$delay" ] && delay=5
    speed=$(get_conf speed.txt 3 | tr -dc '0-9')
    [ -z "$speed" ] && speed=3
    [ "$speed" -gt 60 ] && speed=60

    total=$(grep -c '[^[:space:]]' "$CONF/input.txt" 2>/dev/null)
    [ -z "$total" ] && total=0

    log "run script（stdin 喂入）: $sp | 等待 ${delay}s | 共 ${total} 条 | 间隔 ${speed}s"

    # 脚本输出单独记一份，避免和 daemon 事件日志交错
    local slog="$CONF/script.log" sz
    if [ -f "$slog" ]; then
        sz=$(wc -c < "$slog" 2>/dev/null | tr -d ' ')
        if [ -n "$sz" ] && [ "$sz" -gt 262144 ]; then
            tail -n 300 "$slog" > "$slog.tmp" 2>/dev/null && mv -f "$slog.tmp" "$slog" 2>/dev/null
        fi
    fi
    echo "===== $(date '+%m-%d %H:%M:%S') 执行脚本 $sp =====" >> "$slog" 2>/dev/null

    # 执行前记一行体检摘要：脚本自身出问题（换行/解释器/大小异常）时一眼能看出来
    shbin=$(pick_shell "$sp")
    {
        echo "[diag] shell=$shbin size=$(wc -c < "$sp" 2>/dev/null | tr -d ' ')" \
             "lines=$(wc -l < "$sp" 2>/dev/null | tr -d ' ')" \
             "crlf=$(awk '/\r$/{n++} END{print n+0}' "$sp" 2>/dev/null)" \
             "gzip=$(grep -c -E 'gzip|gunzip|zcat' "$sp" 2>/dev/null)"
    } >> "$slog" 2>/dev/null

    if [ "$delay" -gt 0 ]; then
        log "-- 等待 ${delay}s 后执行脚本"
        sleep "$delay"
    fi

    log "-- 执行脚本，开始喂入输入（shell=$shbin）"
    {
        if [ -f "$CONF/input.txt" ]; then
            while IFS= read -r line || [ -n "$line" ]; do
                line=$(printf '%s' "$line" | tr -d '\r')
                [ -z "$line" ] && continue
                n=$((n + 1))
                printf '%s\n' "$line"
                log "stdin[$n] -> $line"
                if [ "$n" -lt "$total" ] && [ "$speed" -gt 0 ]; then
                    sleep "$speed"
                fi
            done < "$CONF/input.txt"
        fi
        log "stdin: 输入完成，共 $n 条"
    } | "$shbin" "$sp" >> "$slog" 2>&1 &

    SCRIPT_FEED_PID=$!
    log "script feed pid=$SCRIPT_FEED_PID（脚本在后台运行，输出见 script.log）"
    return 0
}

# 结束仍在运行的喂入/脚本进程
kill_script_feed() {
    local p="$SCRIPT_FEED_PID"
    [ -z "$p" ] && return 0
    proc_alive "$p" || { SCRIPT_FEED_PID=""; return 0; }
    log "结束脚本进程 pid=$p"
    kill -TERM "$p" 2>/dev/null
    sleep 0.5
    proc_alive "$p" && kill -9 "$p" 2>/dev/null
    SCRIPT_FEED_PID=""
    return 0
}


# 判断某进程是否在运行（按命令行子串匹配，仅作为兜底清理用）
is_running() {
    if command -v pgrep >/dev/null 2>&1; then
        [ -n "$(pgrep -f "$1" 2>/dev/null)" ]
    else
        ps -A 2>/dev/null | grep -v grep | grep -qF "$1"
    fi
}

# pid 存活且命令行含指定关键字（防止 pid 复用误判）
pid_matches() {
    local p="$1" hint="$2"
    [ -n "$p" ] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    if [ -r "/proc/$p/cmdline" ]; then
        tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "$hint" || return 1
    fi
    return 0
}

# 按 pid 文件读取 pid
read_pid() {
    [ -f "$1" ] && cat "$1" 2>/dev/null | tr -d '\r\n' || echo ""
}

# 结束 daemon；参数为模块目录
kill_daemon() {
    local moddir="$1" pf="$1/run/daemon.pid" p
    p=$(read_pid "$pf")
    if pid_matches "$p" daemon.sh; then
        kill -9 "$p" 2>/dev/null
    fi
    # 兜底：清理残留
    for p in $(pgrep -f "$moddir/bin/daemon.sh" 2>/dev/null); do
        kill -9 "$p" 2>/dev/null
    done
    rm -f "$pf" 2>/dev/null
}

# 结束看门狗；参数为模块目录
kill_supervisor() {
    local moddir="$1" pf="$1/run/supervisor.pid" p
    p=$(read_pid "$pf")
    if pid_matches "$p" supervisor.sh; then
        kill -9 "$p" 2>/dev/null
    fi
    for p in $(pgrep -f "$moddir/bin/supervisor.sh" 2>/dev/null); do
        kill -9 "$p" 2>/dev/null
    done
    rm -f "$pf" 2>/dev/null
}

# 看门狗是否活着
supervisor_alive() {
    pid_matches "$(read_pid "$1/run/supervisor.pid")" supervisor.sh
}

# 让守护进程尽量脱离启动它的那个进程（管理器 / root shell）
# 管理器被划掉时，AMS 会按 cgroup 清理该 App 名下的全部进程，
# setsid 只换了会话，换不掉 cgroup，所以这里额外做两件事：
#   1) oom_score_adj 拉到最低，避免被 LMK 优先回收
#   2) 尝试把进程迁到 init 所在的 cgroup（失败也无妨）
detach_proc() {
    local p="${1:-$$}" line path

    echo -1000 > "/proc/$p/oom_score_adj" 2>/dev/null

    [ -r /proc/1/cgroup ] || return 0
    while IFS= read -r line; do
        path="${line##*:}"
        [ -z "$path" ] && continue
        [ -d "/sys/fs/cgroup$path" ] || continue
        echo "$p" > "/sys/fs/cgroup$path/cgroup.procs" 2>/dev/null
    done < /proc/1/cgroup
    return 0
}

# 拉起看门狗（单例；detach，开机/父 shell 退出都不影响）
start_supervisor() {
    local moddir="$1" rundir="$1/run"
    mkdir -p "$rundir" 2>/dev/null

    if supervisor_alive "$moddir"; then
        return 0
    fi
    # 清掉失效的 pid 文件，避免阻塞新实例
    rm -f "$rundir/supervisor.pid" 2>/dev/null

    if command -v setsid >/dev/null 2>&1; then
        setsid nohup sh "$moddir/bin/supervisor.sh" </dev/null >/dev/null 2>&1 &
    else
        nohup sh "$moddir/bin/supervisor.sh" </dev/null >/dev/null 2>&1 &
    fi
}

# 启动 daemon（detach，避免父 shell 退出时被带走）
start_daemon() {
    local moddir="$1" rundir="$1/run"
    mkdir -p "$rundir" 2>/dev/null
    if command -v setsid >/dev/null 2>&1; then
        setsid sh "$moddir/bin/daemon.sh" </dev/null >/dev/null 2>&1 &
    else
        nohup sh "$moddir/bin/daemon.sh" </dev/null >/dev/null 2>&1 &
    fi
}
