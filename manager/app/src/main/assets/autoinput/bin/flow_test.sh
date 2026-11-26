#!/system/bin/sh
# Auto Input - 手动执行一次完整流程（不需要等触发，也不看前台是不是目标）
# 目的：把「脚本执行 + 内容喂入 / 屏幕输入 + 回车」整条链路单独跑一遍，
# 用来区分「触发没成功」还是「触发成功但输入/回车没生效」。
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

pkg="${1:-$(get_foreground_pkg)}"
mode=$(get_conf input_mode.txt script | tr -d '\r\n')
[ -z "$mode" ] && mode="script"

log "manual flow: pkg=${pkg:-unknown} mode=$mode"
echo "FLOW pkg=${pkg:-unknown} mode=$mode"

# 模式一：脚本喂入（互动式脚本，把内容逐行写进 stdin，每行自带换行）
if [ "$mode" != "screen" ]; then
    run_script_feed
    echo "FLOW done(script)"
    exit 0
fi

# 模式二：屏幕输入（先跑脚本，再逐行注入 + 回车）
delay=$(get_conf delay.txt 5 | tr -dc '0-9')
[ -z "$delay" ] && delay=5
order=$(get_conf order.txt script_first | tr -d '\r\n')
[ -z "$order" ] && order="script_first"
keep=$(get_conf keep_script.txt 1 | tr -d '\r\n')
[ -z "$keep" ] && keep=1

if [ "$order" = "wait_first" ]; then
    log "-- 手动流程：先等待 ${delay}s"
    sleep "$delay"
    if [ "$keep" = "1" ]; then run_user_script_hold; else run_user_script; fi
else
    if [ "$keep" = "1" ]; then run_user_script_hold; else run_user_script; fi
    if [ "$delay" -gt 0 ]; then
        log "-- 手动流程：脚本已启动，等待 ${delay}s 后开始输入"
        sleep "$delay"
    fi
fi

do_input
release_user_script
echo "FLOW done(screen)"
